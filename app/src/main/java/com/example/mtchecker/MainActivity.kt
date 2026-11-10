package com.example.mtchecker

import android.Manifest
import android.app.DownloadManager
import android.content.*
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.text.Html
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class 

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var networkText: TextView
    private lateinit var lastCheckText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var recheckButton: Button
    private lateinit var updateButton: Button
    private lateinit var whatsNewButton: Button
    private lateinit var chronometer: Chronometer
    private lateinit var radioGithub: RadioButton
    private lateinit var radioCustom: RadioButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var versionText: TextView
    private lateinit var adapter: ProxyAdapter

    private val defaultUrl = "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt"
    private val prefsName = "MtCheckerPrefs"
    
    private var customText: String? = null
    private var latestApkUrl: String? = null

    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processFile(it) }
    }

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { 
        try {
            updateNetworkText()
            if (!CheckService.isRunning) loadSavedResult()
        } catch (e: Exception) {
            statusText.text = "Ошибка разрешений"
        }
    }

    private val connectivityManager by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                try {
                    updateNetworkText()
                    if (!CheckService.isRunning) loadSavedResult()
                } catch (e: Exception) {}
            }
        }
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "CHECK_PROGRESS" -> {
                    val checked = intent.getIntExtra("checked", 0)
                    val total = intent.getIntExtra("total", 0)
                    if (total > 0) {
                        progressBar.max = total
                        progressBar.progress = checked
                        statusText.text = getString(R.string.status_checking, checked, total)
                    }
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
                    statusText.text = "Ошибка: $error"
                    progressBar.max = 100
                    progressBar.progress = 0
                    resetButtons()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            statusText = findViewById(R.id.statusText)
            networkText = findViewById(R.id.networkText)
            lastCheckText = findViewById(R.id.lastCheckText)
            progressBar = findViewById(R.id.progressBar)
            startButton = findViewById(R.id.startButton)
            stopButton = findViewById(R.id.stopButton)
            recheckButton = findViewById(R.id.recheckButton)
            updateButton = findViewById(R.id.updateButton)
            whatsNewButton = findViewById(R.id.whatsNewButton)
            chronometer = findViewById(R.id.chronometer)
            radioGithub = findViewById(R.id.radioGithub)
            radioCustom = findViewById(R.id.radioCustom)
            recyclerView = findViewById(R.id.recyclerView)
            versionText = findViewById(R.id.versionText)
            
            versionText.text = "V${BuildConfig.VERSION_NAME}"

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

            findViewById<Button>(R.id.settingsButton).setOnClickListener { showSettingsDialog() }

            updateButton.setOnClickListener {
                if (latestApkUrl != null) {
                    updateButton.isEnabled = false
                    updateButton.text = "⏳"
                    updateButton.alpha = 0.5f
                    downloadAndInstallApk(latestApkUrl!!)
                } else {
                    Toast.makeText(this, "Подождите, проверяю ссылку...", Toast.LENGTH_SHORT).show()
                }
            }

            whatsNewButton.setOnClickListener {
                val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                prefs.edit().putInt("last_seen_version", BuildConfig.VERSION_CODE).apply()
                whatsNewButton.visibility = android.view.View.GONE
                showChangelogDialog(isFromSettings = false)
            }

            findViewById<Button>(R.id.exitButton).setOnClickListener {
                stopService(Intent(this, CheckService::class.java))
                saveResult(adapter.items, CheckService.currentNetworkId.ifEmpty { getNetworkIdentifier() })
                finishAffinity()
            }

            checkForUpdates(silent = true)
            checkWhatsNewVisibility()
            
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка инициализации UI: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkWhatsNewVisibility() {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val lastSeen = prefs.getInt("last_seen_version", 0)
        
        if (BuildConfig.VERSION_CODE > lastSeen) {
            whatsNewButton.visibility = android.view.View.VISIBLE
        } else {
            whatsNewButton.visibility = android.view.View.GONE
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun checkForUpdates(silent: Boolean) {
        if (!isNetworkAvailable()) return

        // ИСПОЛЬЗУЕМ lifecycleScope
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://api.github.com/repos/mrDarkDuck/flaminGo/releases/latest")
                val connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.setRequestProperty("User-Agent", "FlaminGo-App")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                
                val tagRegex = Regex(""""tag_name"\s*:\s*"v1\.(\d+)"""")
                val latestVersion = tagRegex.find(response)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                
                val apkRegex = Regex(""""browser_download_url"\s*:\s*"([^"]+\.apk)"""")
                latestApkUrl = apkRegex.find(response)?.groupValues?.get(1)

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext // ЗАЩИТА
                    
                    if (latestVersion > BuildConfig.VERSION_CODE) {
                        updateButton.visibility = android.view.View.VISIBLE
                        if (!silent) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Доступно обновление!")
                                .setMessage("Установлена версия V1.${BuildConfig.VERSION_CODE}. Доступна V1.$latestVersion.\nСкачать?")
                                .setPositiveButton("Да") { _, _ -> 
                                    updateButton.isEnabled = false
                                    updateButton.text = "⏳"
                                    updateButton.alpha = 0.5f
                                    latestApkUrl?.let { downloadAndInstallApk(it) } 
                                }
                                .setNegativeButton("Позже", null)
                                .show()
                        }
                    } else {
                        updateButton.visibility = android.view.View.GONE
                        if (!silent) Toast.makeText(this@MainActivity, "У вас последняя версия!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun downloadAndInstallApk(apkUrl: String) {
        Toast.makeText(this, "Начинаю скачивание...", Toast.LENGTH_SHORT).show()
        val fileName = "update_${System.currentTimeMillis()}.apk"

        val request = DownloadManager.Request(Uri.parse(apkUrl))
        request.setTitle("FlaminGo Update")
        request.setDescription("Скачивание обновления...")
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        lifecycleScope.launch(Dispatchers.IO) {
            var isDownloading = true
            while (isDownloading) {
                val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        isDownloading = false
                        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                        withContext(Dispatchers.Main) { 
                            if (!isFinishing && !isDestroyed) installApk(file) 
                        }
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        isDownloading = false
                        withContext(Dispatchers.Main) {
                            if (!isFinishing && !isDestroyed) {
                                Toast.makeText(this@MainActivity, "Ошибка скачивания. Попробуйте позже.", Toast.LENGTH_SHORT).show()
                                updateButton.isEnabled = true
                                updateButton.text = "⬇️"
                                updateButton.alpha = 1.0f
                            }
                        }
                    }
                }
                cursor.close()
                delay(1000)
            }
        }
    }

    private fun installApk(file: File) {
        val intent = Intent(Intent.ACTION_VIEW)
        val uri = FileProvider.getUriForFile(this, "com.example.mtchecker.fileprovider", file)
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES) // <--- ДОБАВЛЕНО ДЛЯ ANDROID 13
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
            try {
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
            } catch (e: Exception) {}
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
        val network = connectivityManager.activeNetwork ?: return "Нет сети"
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return "Неизвестно"
        
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {


            // Проверяем разрешения в зависимости от версии Android

            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }

            if (hasPermission) {
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
        progressBar.max = 100
        progressBar.progress = 0
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
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "Нет подключения к интернету. Проверьте настройки сети.", Toast.LENGTH_LONG).show()
            statusText.text = "Нет подключения к интернету"
            return
        }

        val isCustom = radioCustom.isChecked
        if (isCustom && customText == null) {
            getContent.launch("text/plain")
            return
        }
        
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val targetUrl = prefs.getString("list_url", defaultUrl) ?: defaultUrl
        val limit = prefs.getInt("check_limit", 0)
        val onlyFast = prefs.getBoolean("only_fast", true)

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
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "Нет подключения к интернету. Проверьте настройки сети.", Toast.LENGTH_LONG).show()
            statusText.text = "Нет подключения к интернету"
            return
        }

        if (adapter.items.isEmpty()) return
        
        val linksToRecheck = adapter.items.joinToString("\n") { it.link }
        
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val onlyFast = prefs.getBoolean("only_fast", true)

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
        progressBar.max = 100
        progressBar.progress = 0
        adapter.clear()
        statusText.text = "Запуск сервиса..."
        lastCheckText.text = "" 
        
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
        val historyBtn = dialogView.findViewById<TextView>(R.id.historyBtn)
        val helpBtn = dialogView.findViewById<TextView>(R.id.helpBtn)

        urlInput.setText(prefs.getString("list_url", defaultUrl) ?: defaultUrl)
        
        when (prefs.getInt("check_limit", 0)) {
            1 -> depthGroup.check(R.id.depth1)
            3 -> depthGroup.check(R.id.depth3)
            5 -> depthGroup.check(R.id.depth5)
            else -> depthGroup.check(R.id.depthAll)
        }
        onlyFastCheck.isChecked = prefs.getBoolean("only_fast", true)

        val dialog = AlertDialog.Builder(this)
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
            .create()

        historyBtn.setOnClickListener {
            dialog.dismiss()
            showChangelogDialog(isFromSettings = true)
        }

        helpBtn.setOnClickListener {
            dialog.dismiss()
            showHelpDialog()
        }

        dialog.show()
    }

    private fun showHelpDialog() {
        val isRussian = Locale.getDefault().language == "ru"
        val prefKey = if (isRussian) "cached_help_ru" else "cached_help_en"
        val assetFileName = if (isRussian) "help_ru.md" else "help_en.md"
        
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        
        var helpText = prefs.getString(prefKey, null)
        if (helpText == null) {
            try {
                val inputStream = assets.open(assetFileName)
                helpText = inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                helpText = "Текст справки недоступен."
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.help_title))
            .setMessage(Html.fromHtml(helpText!!.replace("\n", "<br>").replace("* ", "• "), Html.FROM_HTML_MODE_LEGACY))
            .setPositiveButton(getString(R.string.btn_close), null)
            .create()
            
        dialog.show()

        if (isNetworkAvailable()) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val fileName = if (isRussian) "HELP_RU.md" else "HELP.md"
                    val url = URL("https://raw.githubusercontent.com/mrDarkDuck/flaminGo/main/$fileName")
                    
                    val connection = url.openConnection() as HttpURLConnection
                    connection.setRequestProperty("User-Agent", "FlaminGo-App")
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    val newText = connection.inputStream.bufferedReader().use { it.readText() }

                    if (newText != helpText) {
                        prefs.edit().putString(prefKey, newText).apply()
                        
                        withContext(Dispatchers.Main) {
                            if (!isFinishing && !isDestroyed && dialog.isShowing) {
                                dialog.setMessage(Html.fromHtml(newText.replace("\n", "<br>").replace("* ", "• "), Html.FROM_HTML_MODE_LEGACY))
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }

    private fun showChangelogDialog(isFromSettings: Boolean) {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "Нет подключения к интернету", Toast.LENGTH_SHORT).show()
            return
        }

        val loadingToast = Toast.makeText(this, getString(R.string.loading_history), Toast.LENGTH_SHORT)
        loadingToast.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val isRussian = Locale.getDefault().language == "ru"
                val fileName = if (isRussian) "CHANGELOG_RU.md" else "CHANGELOG.md"
                
                val url = URL("https://raw.githubusercontent.com/mrDarkDuck/flaminGo/main/$fileName")
                val connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "FlaminGo-App")
                val fullText = connection.inputStream.bufferedReader().use { it.readText() }
                
                val rawSections = fullText.split(Regex("(?=## )"))
                    .filter { it.isNotBlank() && it.trim().startsWith("## ") }
                
                if (rawSections.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "История пуста", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val slides = rawSections.map { section ->
                    val title = section.lines().first().replace("## ", "")
                    var body = section.lines().drop(1).joinToString("\n").trim()
                    
                    body = body.replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
                    body = body.replace(Regex("(?m)^\\* "), "• ")
                    body = body.replace("\n", "<br>")
                    
                    "<h3 style='color:#D81B60;'>$title</h3><br>$body"
                }

                var currentIndex = 0

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext

                    val builder = AlertDialog.Builder(this@MainActivity)
                    builder.setTitle(getString(R.string.history_title))
                    
                    val htmlText = Html.fromHtml(slides[currentIndex], Html.FROM_HTML_MODE_LEGACY)
                    builder.setMessage(htmlText)
                    
                    builder.setPositiveButton(getString(R.string.btn_next)) { _, _ -> }
                    builder.setNegativeButton(getString(R.string.btn_close), null)

                    val dialog = builder.show()

                    val posBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    val negBtn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)

                    if (slides.size == 1) {
                        posBtn.text = getString(R.string.btn_close)
                        negBtn.visibility = android.view.View.GONE
                    }

                    posBtn.setOnClickListener {
                        currentIndex++
                        if (currentIndex < slides.size) {
                            dialog.setMessage(Html.fromHtml(slides[currentIndex], Html.FROM_HTML_MODE_LEGACY))
                            
                            if (currentIndex == slides.size - 1) {
                                posBtn.text = getString(R.string.btn_close)
                                negBtn.visibility = android.view.View.GONE
                            }
                        } else {
                            dialog.dismiss()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this@MainActivity, getString(R.string.error_history_load), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    
    private fun saveResult(items: List<ProxyItem>, netId: String) {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val savedString = items.joinToString("|") { "${it.link};;${it.displayName};;${it.ping}" }
        val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
        val currentNetName = getNetworkName() // Запоминаем имя сети в момент сохранения

        
        prefs.edit()
            .putString("last_result_$netId", savedString)
            .putString("last_check_date_$netId", dateStr)
            .putString("last_check_net_name_$netId", currentNetName) // СОХРАНЯЕМ ИМЯ СЕТИ
            .apply()
            
        lastCheckText.text = "Проверка ($currentNetName): $dateStr"
    }

    private fun loadSavedResult() {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val netId = getNetworkIdentifier()
        val currentNetName = getNetworkName()
        
        val saved = prefs.getString("last_result_$netId", "") ?: ""
        val lastDate = prefs.getString("last_check_date_$netId", "") ?: ""

        // ЧИТАЕМ СОХРАНЕННОЕ ИМЯ СЕТИ. Если его нет (старые данные) - берем текущее
        val savedNetName = prefs.getString("last_check_net_name_$netId", currentNetName) ?: currentNetName
        
        adapter.clear()
        
        progressBar.max = 100
        progressBar.progress = 0
        
        if (saved.isNotEmpty()) {
            val items = saved.split("|").mapNotNull { 
                val parts = it.split(";;")
                if (parts.size == 3) ProxyItem(parts[0], parts[1], parts[2].toLongOrNull() ?: 0L) else null
            }
            adapter.items.addAll(items)
            adapter.notifyDataSetChanged()
            
            if (lastDate.isNotEmpty()) {
                lastCheckText.text = "Проверка ($savedNetName): $lastDate"
            } else {
                lastCheckText.text = ""
            }
        } else {
            statusText.text = getString(R.string.status_ready)
            lastCheckText.text = ""
        }
        recheckButton.isEnabled = adapter.items.isNotEmpty()
    }
}
