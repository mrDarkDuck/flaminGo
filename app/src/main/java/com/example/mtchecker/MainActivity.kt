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
    private lateinit var recheckButton: Button
    private lateinit var chronometer: Chronometer
    private lateinit var radioGithub: RadioButton
    private lateinit var radioCustom: RadioButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var versionText: TextView
    private lateinit var adapter: ProxyAdapter

    private val defaultUrl = "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt"
    private val prefsName = "MtCheckerPrefs"
    
    private var customText: String? = null

    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processFile(it) }
    }

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { 
        updateNetworkText()
        if (!CheckService.isRunning) loadSavedResult()
    }

    private val connectivityManager by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                updateNetworkText()
                if (!CheckService.isRunning) loadSavedResult()
            }
        }
    }

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
                    CheckService.isRunning = false
                    statusText.text = getString(R.string.status_done, adapter.itemCount)
                    chronometer.stop()
                    resetButtons()
                    saveResult(adapter.items, CheckService.currentNetworkId.ifEmpty { getNetworkIdentifier() })
                }
                "CHECK_ERROR" -> {
                    CheckService.isRunning = false
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
        recheckButton = findViewById(R.id.recheckButton)
        chronometer = findViewById(R.id.chronometer)
        radioGithub = findViewById(R.id.radioGithub)
        radioCustom = findViewById(R.id.radioCustom)
        recyclerView = findViewById(R.id.recyclerView)
        versionText = findViewById(R.id.versionText)
        
        // Показываем только версию, без времени
        versionText.text = "Версия: ${BuildConfig.VERSION_NAME}"

        adapter = ProxyAdapter(mutableListOf())
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        checkAndRequestPermissions()

        radioGithub.setOnClickListener { updateNetworkText() }
        radioCustom.setOnClickListener { getContent.launch("text/plain") }

        startButton.setOnClickListener { startChecking() }
        stopButton.setOnClickListener { stopChecking() }
        recheckButton.setOnClickListener { startRecheck() }
        
        findViewById<Button>(R.id.copyAllButton).setOnClickListener {
            val allLinks = adapter.items.joinToString("\n\n") { it.link }
            if (allLinks.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Proxies", allLinks))
                Toast.makeText(this, "Скопировано!", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            showSettingsDialog()
        }

        findViewById<Button>(R.id.exitButton).setOnClickListener {
            stopService(Intent(this, CheckService::class.java))
            saveResult(adapter.items, CheckService.currentNetworkId.ifEmpty { getNetworkIdentifier() })
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
            
            if (CheckService.isRunning || CheckService.currentTotal > 0) {
                adapter.clear()
                adapter.items.addAll(CheckService.foundProxies)
                adapter.notifyDataSetChanged()
                
                progressBar.max = CheckService.currentTotal
                progressBar.progress = CheckService.currentChecked
                
                if (CheckService.isRunning) {
                    statusText.text = getString(R.string.status_checking, CheckService.currentChecked, CheckService.currentTotal)
                    startButton.isEnabled = false
                    stopButton.isEnabled = true
                    recheckButton.isEnabled = false
                    radioGithub.isEnabled = false
                    radioCustom.isEnabled = false
                    chronometer.visibility = android.view.View.VISIBLE
                } else {
                    statusText.text = getString(R.string.status_done, adapter.itemCount)
                    resetButtons()
                    saveResult(adapter.items, CheckService.currentNetworkId.ifEmpty { getNetworkIdentifier() })
                }
            } else {
                loadSavedResult()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(serviceReceiver) } catch (e: Exception) {}
        connectivityManager.unregisterNetworkCallback(networkCallback)
        if (::adapter.isInitialized) {
            saveResult(adapter.items, CheckService.currentNetworkId.ifEmpty { getNetworkIdentifier() })
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
        CheckService.isRunning = false
        chronometer.stop()
        statusText.text = "Остановлено"
        resetButtons()
        saveResult(adapter.items, CheckService.currentNetworkId.ifEmpty { getNetworkIdentifier() })
    }

    private fun resetButtons() {
        startButton.isEnabled = true
        stopButton.isEnabled = false
        recheckButton.isEnabled = adapter.items.isNotEmpty() 
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
            Toast.makeText(this, "Ошибка файла: ${e.message}", Toast.LENGTH_LONG).show()
            radioGithub.isChecked = true
        }
    }

    private fun startChecking() {
        val isCustom = radioCustom.isChecked
        if (isCustom && customText == null) {
            getContent.launch("text/plain")
            return
        }
        
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val targetUrl = prefs.getString("list_url", defaultUrl) ?: defaultUrl
        val limit = prefs.getInt("check_limit", 0)
        val onlyFast = prefs.getBoolean("only_fast", false)

        prepareUiForCheck()

        val serviceIntent = Intent(this, CheckService::class.java).apply {
            putExtra("url", targetUrl)
            putExtra("limit", limit)
            putExtra("onlyFast", onlyFast)
            if (isCustom) putExtra("customText", customText)
        }
        startServiceCompat(serviceIntent)
    }

    private fun startRecheck() {
        if (adapter.items.isEmpty()) return
        
        val linksToRecheck = adapter.items.joinToString("\n") { it.link }
        
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val onlyFast = prefs.getBoolean("only_fast", false)

        prepareUiForCheck()

        val serviceIntent = Intent(this, CheckService::class.java).apply {
            putExtra("customText", linksToRecheck)
            putExtra("isReCheck", true)
            putExtra("onlyFast", onlyFast)
        }
        startServiceCompat(serviceIntent)
    }

    private fun prepareUiForCheck() {
        startButton.isEnabled = false
        stopButton.isEnabled = true
        recheckButton.isEnabled = false
        radioGithub.isEnabled = false
        radioCustom.isEnabled = false
        progressBar.progress = 0
        adapter.clear()
        statusText.text = "Запуск сервиса..."
        
        chronometer.base = SystemClock.elapsedRealtime()
        chronometer.start()
        chronometer.visibility = android.view.View.VISIBLE
    }

    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        
        val urlInput = dialogView.findViewById<EditText>(R.id.settingsUrl)
        val depthGroup = dialogView.findViewById<RadioGroup>(R.id.depthGroup)
        val onlyFastCheck = dialogView.findViewById<CheckBox>(R.id.onlyFastCheck)

        urlInput.setText(prefs.getString("list_url", defaultUrl) ?: defaultUrl)
        
        when (prefs.getInt("check_limit", 0)) {
            1 -> depthGroup.check(R.id.depth1)
            3 -> depthGroup.check(R.id.depth3)
            5 -> depthGroup.check(R.id.depth5)
            else -> depthGroup.check(R.id.depthAll)
        }
        onlyFastCheck.isChecked = prefs.getBoolean("only_fast", false)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(dialogView)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                var newUrl = urlInput.text.toString().trim()
                if (newUrl.isEmpty()) newUrl = defaultUrl

                val limit = when (depthGroup.checkedRadioButtonId) {
                    R.id.depth1 -> 1
                    R.id.depth3 -> 3
                    R.id.depth5 -> 5
                    else -> 0
                }

                prefs.edit()
                    .putString("list_url", newUrl)
                    .putInt("check_limit", limit)
                    .putBoolean("only_fast", onlyFastCheck.isChecked)
                    .apply()
            }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
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
        recheckButton.isEnabled = adapter.items.isNotEmpty()
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
