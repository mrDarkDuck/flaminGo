package com.example.mtchecker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

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

    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация UI элементов
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
        
        // Установка текста версии
        versionText.text = getString(R.string.version_label, BuildConfig.VERSION_NAME, BuildConfig.BUILD_TIME)
        networkText.text = getString(R.string.source_github) + " | ${getNetworkType()}"

        // Настройка таблицы
        adapter = ProxyAdapter(mutableListOf())
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Загружаем прошлый результат
        loadSavedResult()

        // Слушатели кнопок
        radioGithub.setOnClickListener { 
            networkText.text = getString(R.string.source_github) + " | ${getNetworkType()}" 
        }
        radioCustom.setOnClickListener { 
            getContent.launch("text/plain") 
        }

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

        // Новая кнопка Выхода (крестик)
        findViewById<Button>(R.id.exitButton).setOnClickListener {
            checkJob?.cancel() // Останавливаем проверку, если идет
            saveResult(adapter.items) // СОХРАНЯЕМ ПЕРЕД ВЫХОДОМ
            finishAffinity()
        }

        // Кнопка "Что нового"
        findViewById<Button>(R.id.whatsNewButton).setOnClickListener {
            // Ссылка на сырой файл CHANGELOG.md в вашем репозитории
            val changelogUrl = "https://raw.githubusercontent.com/mrDarkDuck/flaminGo/main/CHANGELOG.md"
            showChangelogDialog(changelogUrl)
        }

    // Сохраняем список, если приложение свернули или убили системой
    override fun onStop() {
        super.onStop()
        saveResult(adapter.items)
    }

    private fun stopChecking() {
        checkJob?.cancel()
        chronometer.stop()
        statusText.text = "Stopped"
        resetButtons()
        saveResult(adapter.items)
    }

    private fun resetButtons() {
        startButton.isEnabled = true
        stopButton.isEnabled = false
        chronometer.visibility = android.view.View.GONE
        radioGithub.isEnabled = true
        radioCustom.isEnabled = true
    }

    private fun getNetworkType(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "No Net"
        val capabilities = cm.getNetworkCapabilities(network) ?: return "Unknown"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> getString(R.string.network_wifi)
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getString(R.string.network_cell)
            else -> "Other"
        }
    }

    private fun processFile(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream))
            customText = reader.readText()
            reader.close()
            inputStream?.close()
            networkText.text = getString(R.string.source_custom) + " | ${getNetworkType()}"
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
                    saveResult(adapter.items)
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

    private fun saveResult(items: List<ProxyItem>) {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val savedString = items.joinToString("|") { "${it.link};;${it.displayName};;${it.ping}" }
        prefs.edit().putString("last_result", savedString).apply()
    }

    private fun loadSavedResult() {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val saved = prefs.getString("last_result", "") ?: ""
        if (saved.isNotEmpty()) {
            val items = saved.split("|").mapNotNull { 
                val parts = it.split(";;")
                if (parts.size == 3) ProxyItem(parts[0], parts[1], parts[2].toLongOrNull() ?: 0L) else null
            }
            adapter.items.addAll(items)
            adapter.notifyDataSetChanged()
        }
    }
    // Функция для скачивания и показа окна с изменениями
    private fun showChangelogDialog(url: String) {
        // Показываем временное сообщение, что идет загрузка
        val loadingToast = Toast.makeText(this, "Загрузка...", Toast.LENGTH_SHORT)
        loadingToast.show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val text = URL(url).readText()
                withContext(Dispatchers.Main) {
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Что нового?")
                        .setMessage(text)
                        .setPositiveButton("ОК", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Что нового?")
                        .setMessage("Не удалось загрузить список изменений.\nПроверьте интернет.")
                        .setPositiveButton("ОК", null)
                        .show()
                }
            }
        }
    }
    
}
