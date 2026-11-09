package com.example.mtchecker

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
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
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
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
    
    private var checkJob: Job? = null
    private var customText: String? = null
    private var currentCheckNetwork = ""

    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processFile(it) }
    }

    // Обработчик разрешений (неважно, разрешил пользователь или нет, мы просто продолжаем работу)
    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { 
        updateNetworkText()
        if (checkJob?.isActive != true) loadSavedResult()
    }

    private val connectivityManager by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                updateNetworkText()
                if (checkJob?.isActive != true) loadSavedResult()
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
            checkJob?.cancel()
            saveResult(adapter.items, currentCheckNetwork.ifEmpty { getNetworkIdentifier() })
            finishAffinity()
        }

        findViewById<Button>(R.id.whatsNewButton).setOnClickListener {
            val changelogUrl = "https://raw.githubusercontent.com/mrDarkDuck/flaminGo/main/CHANGELOG.md"
            showChangelogDialog(changelogUrl)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE)
        val allGranted = permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        
        if (allGranted) {
            updateNetworkText()
            loadSavedResult()
        } else {
            requestPermissions.launch(permissions)
        }
    }

    override fun onResume() {
        super.onResume()
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        // Обновляем только если приложение уже инициализировано
        if (::adapter.isInitialized) {
            updateNetworkText()
            if (checkJob?.isActive != true) loadSavedResult()
        }
    }

    override fun onPause() {
        super.onPause()
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }

    override fun onStop() {
        super.onStop()
        if (::adapter.isInitialized) {
            saveResult(adapter.items, currentCheckNetwork.ifEmpty { getNetworkIdentifier() })
        }
    }

    // Получаем имя сети БЕЗОПАСНО (чтобы не вылетало)
    private fun getNetworkName(): String {
        val network = connectivityManager.activeNetwork ?: return "No Net"
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return "Unknown"
        
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            // Проверяем, дал ли пользователь разрешение на геолокацию
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    @Suppress("DEPRECATION")
                    val ssid = wifiManager.connectionInfo.ssid.replace("\"", "")
                    if (ssid != "<unknown ssid>" && ssid.isNotEmpty()) {
                        return "Wi-Fi: $ssid"
                    }
                } catch (e: Exception) { /* Игнорируем ошибки */ }
            }
            return "Wi-Fi" // Если нет разрешения или ошибка
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            // Проверяем разрешение на чтение данных телефона
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    val carrier = tm.networkOperatorName
                    if (!carrier.isNullOrEmpty()) {
                        return "Mobile: $carrier"
                    }
                } catch (e: Exception) { /* Игнорируем ошибки */ }
            }
            return "Mobile" // Если нет разрешения или ошибка
        }
        return "Other"
    }

    // Идентификатор сети для сохранения (заменяет недопустимые символы)
    private fun getNetworkIdentifier(): String {
        return getNetworkName().replace("[^a-zA-Z0-9а-яА-Я]".toRegex(), "_")
    }

    private fun updateNetworkText() {
        val source = if (radioCustom.isChecked) getString(R.string.source_custom) else getString(R.string.source_github)
        networkText.text = "$source | ${getNetworkName()}"
    }

    private fun stopChecking() {
        checkJob?.cancel()
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

        currentCheckNetwork = getNetworkIdentifier()

        startButton.isEnabled = false
        stopButton.isEnabled = true
        radioGithub.isEnabled = false
        radioCustom.isEnabled = false
        progressBar.progress = 0
        adapter.clear()
        statusText.text = "Downloading..."
        
        chronometer.base = SystemClock.elapsedRealtime()
        chronometer.start()
        chronometer.visibility = android.view.View.VISIBLE

        checkJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val text = if (isCustom) customText!! else {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connect()
                    conn.inputStream.bufferedReader().use { it.readText() }
                }
                
                withContext(Dispatchers.Main) {
                    startCheckFromText(text)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Error: ${e.message}"
                    resetButtons()
                }
            }
        }
    }

    private fun startCheckFromText(text: String) {
        val regex = Regex("""server=([^&]+).*?port=(\d+)""")
        val proxies = regex.findAll(text).map { match ->
            val server = match.groupValues[1].trimEnd('.')
            val port = match.groupValues[2].toInt()
            Pair(server, port)
        }.toList().distinct()

        if (proxies.isEmpty()) {
            statusText.text = "No proxies found"
            resetButtons()
            return
        }

        progressBar.max = proxies.size

        CoroutineScope(Dispatchers.IO).launch {
            var checkedCount = 0
            for ((server, port) in proxies) {
                if (!isActive) break

                val startTime = System.currentTimeMillis()
                if (isServerAlive(server, port)) {
                    val ping = System.currentTimeMillis() - startTime
                    val originalLine = text.lines().find { it.contains("server=$server") && it.contains("port=$port") }
                    
                    if (originalLine != null) {
                        withContext(Dispatchers.Main) {
                            adapter.addItem(ProxyItem(originalLine, "$server:$port", ping))
                        }
                    }
                }
                checkedCount++
                
                if (checkedCount % 5 == 0 || checkedCount == proxies.size) {
                    val progress = checkedCount
                    val total = proxies.size
                    withContext(Dispatchers.Main) {
                        progressBar.progress = progress
                        statusText.text = getString(R.string.status_checking, progress, total)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (isActive) {
                    statusText.text = getString(R.string.status_done, adapter.itemCount)
                    chronometer.stop()
                    resetButtons()
                    saveResult(adapter.items, currentCheckNetwork)
                }
            }
        }
    }

    private fun isServerAlive(host: String, port: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 3000)
            socket.close()
            true
        } catch (e: Exception) {
            false
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
