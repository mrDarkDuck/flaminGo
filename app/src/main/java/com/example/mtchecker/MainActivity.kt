package com.example.mtchecker

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var networkText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var chronometer: Chronometer
    private lateinit var radioGithub: RadioButton
    private lateinit var radioCustom: RadioButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var versionText: TextView
    private lateinit var adapter: ProxyAdapter

    private val url = "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt"
    private val prefsName = "MtCheckerPrefs"
    
    private var customText: String? = null
    private var currentCheckNetwork = ""
    private var isServiceRunning = false

    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processFile(it) }
    }

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { 
        updateNetworkText()
        if (!isServiceRunning) loadSavedResult()
    }

    private val connectivityManager by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                updateNetworkText()
                if (!isServiceRunning) loadSavedResult()
            }
        }
    }

    // Слушаем сигналы от Сервиса
    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "CHECK_PROGRESS" -> {
                    val checked = intent.getIntExtra("checked", 0)
                    val total = intent.getIntExtra("total", 0)
                    progressBar.max = total
                    progressBar.progress = checked
                    statusText.text = getString(R.string.status_checking, checked, total)
                }
                "CHECK_RESULT" -> {
                    val link = intent.getStringExtra("link") ?: return
                    val name = intent.getStringExtra("displayName") ?: return
                    val ping = intent.getLongExtra("ping", 0L)
                    adapter.addItem(ProxyItem(link, name, ping))
                }
                "CHECK_FINISHED" -> {
                    isServiceRunning = false
                    statusText.text = getString(R.string.status_done, adapter.itemCount)
                    chronometer.stop()
                    resetButtons()
                    saveResult(adapter.items, currentCheckNetwork)
                }
                "CHECK_ERROR" -> {
                    isServiceRunning = false
                    val error = intent.getStringExtra("error") ?: "Unknown error"
                    statusText.text = "Error: $error"
                    resetButtons()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        networkText = findViewById(R.id.networkText)
        progressBar = findViewById(R.id.progressBar)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        chronometer = findViewById(R.id.chronometer)
        radioGithub = findViewById(R.id.radioGithub)
        radioCustom = findViewById(R.id.radioCustom)
        recyclerView = findViewById(R.id.recyclerView)
        versionText = findViewById(R.id.versionText)
        
        versionText.text = getString(R.string.version_label, BuildConfig.VERSION_NAME, BuildConfig.BUILD_TIME)

        adapter = ProxyAdapter(mutableListOf())
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        checkAndRequestPermissions()

        radioGithub.setOnClickListener { updateNetworkText() }
        radioCustom.setOnClickListener { getContent.launch("text/plain") }

        startButton.setOnClickListener { startChecking() }
        stopButton.setOnClickListener { stopChecking() }
        
        findViewById<Button>(R.id.copyAllButton).setOnClickListener {
            val allLinks = adapter.items.joinToString("\n\n") { it.link }
            if (allLinks.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Proxies", allLinks))
                Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.exitButton).setOnClickListener {
            stopService(Intent(this, CheckService::class.java))
            saveResult(adapter.items, currentCheckNetwork.ifEmpty { getNetworkIdentifier() })
            finishAffinity()
        }

        findViewById<Button>(R.id.whatsNewButton).setOnClickListener {
            val changelogUrl = "https://raw.githubusercontent.com/mrDarkDuck/flaminGo/main/CHANGELOG.md"
            showChangelogDialog(changelogUrl)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION, 
            Manifest.permission.READ_PHONE_STATE
        )
        // Для Android 13+ нужно спрашивать разрешение на уведомления
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val allGranted = permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        
        if (allGranted) {
            updateNetworkText()
            loadSavedResult()
        } else {
            requestPermissions.launch(permissions.toTypedArray())
        }
    }

    override fun onStart() {
        super.onStart()
        // Регистрируем приемник сигналов от сервиса
        val filter = IntentFilter().apply {
            addAction("CHECK_PROGRESS")
            addAction("CHECK_RESULT")
            addAction("CHECK_FINISHED")
            addAction("CHECK_ERROR")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(serviceReceiver, filter)
        }
        
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        if (::adapter.isInitialized) {
            updateNetworkText()
            if (!isServiceRunning) loadSavedResult()
        }
    }

    override fun onStop() {
        super.onStop()
        // Отписываемся от сигналов, чтобы не течь памяти
        try { unregisterReceiver(serviceReceiver) } catch (e: Exception) {}
        connectivityManager.unregisterNetworkCallback(networkCallback)
        if (::adapter.isInitialized) {
            saveResult(adapter.items, currentCheckNetwork.ifEmpty { getNetworkIdentifier() })
        }
    }

    private fun getNetworkName(): String {
        val network = connectivityManager.activeNetwork ?: return "No Net"
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return "Unknown"
        
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    @Suppress("DEPRECATION")
                    val ssid = wifiManager.connectionInfo.ssid.replace("\"", "")
                    if (ssid != "<unknown ssid>" && ssid.isNotEmpty()) return "Wi-Fi: $ssid"
                } catch (e: Exception) {}
            }
            return "Wi-Fi"
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    val carrier = tm.networkOperatorName
                    if (!carrier.isNullOrEmpty()) return "Mobile: $carrier"
                } catch (e: Exception) {}
            }
            return "Mobile"
        }
        return "Other"
    }

    private fun getNetworkIdentifier(): String {
        return getNetworkName().replace("[^a-zA-Z0-9а-яА-Я]".toRegex(), "_")
    }

    private fun updateNetworkText() {
        val source = if (radioCustom.isChecked) getString(R.string.source_custom) else getString(R.string.source_github)
        networkText.text = "$source | ${getNetworkName()}"
    }

    private fun stopChecking() {
        stopService(Intent(this, CheckService::class.java))
        isServiceRunning = false
        chronometer.stop()
        statusText.text = "Stopped"
        resetButtons()
        saveResult(adapter.items, currentCheckNetwork)
    }

    private fun resetButtons() {
        startButton.isEnabled = true
        stopButton.isEnabled = false
        chronometer.visibility = android.view.View.GONE
        radioGithub.isEnabled = true
        radioCustom.isEnabled = true
    }

    private fun processFile(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream))
            customText = reader.readText()
            reader.close()
            inputStream?.close()
            updateNetworkText()
            radioCustom.isChecked = true
        } catch (e: Exception) {
            Toast.makeText(this, "File error: ${e.message}", Toast.LENGTH_LONG).show()
            radioGithub.isChecked = true
        }
    }

    private fun startChecking() {
        val isCustom = radioCustom.isChecked
        if (isCustom && customText == null) {
            getContent.launch("text/plain")
            return
        }

        isServiceRunning = true
        currentCheckNetwork = getNetworkIdentifier()

        startButton.isEnabled = false
        stopButton.isEnabled = true
        radioGithub.isEnabled = false
        radioCustom.isEnabled = false
        progressBar.progress = 0
        adapter.clear()
        statusText.text = "Starting service..."
        
        chronometer.base = SystemClock.elapsedRealtime()
        chronometer.start()
        chronometer.visibility = android.view.View.VISIBLE

        // Запускаем Сервис
        val serviceIntent = Intent(this, CheckService::class.java).apply {
            putExtra("url", url)
            if (isCustom) putExtra("customText", customText)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun saveResult(items: List<ProxyItem>, netId: String) {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val savedString = items.joinToString("|") { "${it.link};;${it.displayName};;${it.ping}" }
        val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
        
        prefs.edit()
            .putString("last_result_$netId", savedString)
            .putString("last_check_date_$netId", dateStr)
            .apply()
    }

    private fun loadSavedResult() {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val netId = getNetworkIdentifier()
        val netName = getNetworkName()
        
        val saved = prefs.getString("last_result_$netId", "") ?: ""
        val lastDate = prefs.getString("last_check_date_$netId", "") ?: ""
        
        adapter.clear()
        
        if (saved.isNotEmpty()) {
            val items = saved.split("|").mapNotNull { 
                val parts = it.split(";;")
                if (parts.size == 3) ProxyItem(parts[0], parts[1], parts[2].toLongOrNull() ?: 0L) else null
            }
            adapter.items.addAll(items)
            adapter.notifyDataSetChanged()
            
            if (lastDate.isNotEmpty()) {
                statusText.text = "Проверка ($netName): $lastDate"
            }
        } else {
            statusText.text = getString(R.string.status_ready)
        }
    }

    private fun showChangelogDialog(url: String) {
        val loadingToast = Toast.makeText(this, "Загрузка...", Toast.LENGTH_SHORT)
        loadingToast.show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val text = URL(url).readText()
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Что нового?")
                        .setMessage(text)
                        .setPositiveButton("ОК", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Что нового?")
                        .setMessage("Не удалось загрузить список изменений.\nПроверьте интернет.")
                        .setPositiveButton("ОК", null)
                        .show()
                }
            }
        }
    }
}
