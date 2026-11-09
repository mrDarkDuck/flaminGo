package com.example.mtchecker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var networkText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var checkButton: Button
    private lateinit var stopButton: Button
    private lateinit var loadFileButton: Button

    private val url = "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt"
    private val prefsName = "MtCheckerPrefs"
    
    private var checkJob: Job? = null
    private val workingLinks = mutableListOf<String>()

    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        resultText = findViewById(R.id.resultText)
        networkText = findViewById(R.id.networkText)
        progressBar = findViewById(R.id.progressBar)
        checkButton = findViewById(R.id.checkButton)
        stopButton = findViewById(R.id.stopButton)
        loadFileButton = findViewById(R.id.loadFileButton)
        val copyButton = findViewById<Button>(R.id.copyButton)

        networkText.text = "Сеть: ${getNetworkType()}"
        loadSavedResult()

        checkButton.setOnClickListener {
            startChecking(url)
        }

        stopButton.setOnClickListener {
            stopChecking()
        }

        loadFileButton.setOnClickListener {
            getContent.launch("text/plain")
        }

        copyButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Proxies", resultText.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Скопировано!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopChecking() {
        checkJob?.cancel()
        statusText.text = "Процесс остановлен пользователем"
        resetButtons()
        if (resultText.text.isNotEmpty()) {
            saveResult(resultText.text.toString())
        }
    }

    private fun resetButtons() {
        checkButton.isEnabled = true
        loadFileButton.isEnabled = true
        stopButton.isEnabled = false
    }

    private fun getNetworkType(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "Нет сети"
        val capabilities = cm.getNetworkCapabilities(network) ?: return "Неизвестно"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная"
            else -> "Другая"
        }
    }

    private fun processFile(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val text = reader.readText()
            reader.close()
            inputStream?.close()
            startCheckFromText(text, "Локальный файл")
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка чтения файла: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startChecking(targetUrl: String) {
        checkButton.isEnabled = false
        loadFileButton.isEnabled = false
        stopButton.isEnabled = true
        resultText.text = ""
        progressBar.progress = 0
        statusText.text = "Скачиваю список..."

        checkJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val connection = URL(targetUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connect()
                
                val lastModified = connection.getHeaderField("Last-Modified") ?: "Дата неизвестна"
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                
                withContext(Dispatchers.Main) {
                    startCheckFromText(text, "GitHub ($lastModified)")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Ошибка сети: ${e.message}"
                    resetButtons()
                }
            }
        }
    }

    private fun startCheckFromText(text: String, sourceName: String) {
        val regex = Regex("""server=([^&]+).*?port=(\d+)""")
        val proxies = regex.findAll(text).map { match ->
            val server = match.groupValues[1].trimEnd('.')
            val port = match.groupValues[2].toInt()
            Pair(server, port)
        }.toList().distinct()

        if (proxies.isEmpty()) {
            statusText.text = "Прокси не найдены в источнике"
            resetButtons()
            return
        }

        statusText.text = "Проверяю ${proxies.size} серверов..."
        progressBar.max = proxies.size
        workingLinks.clear()
        resultText.text = ""

        val networkType = getNetworkType()
        val dateStr = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val header = "<b>Источник:</b> $sourceName<br><b>Сеть:</b> $networkType<br><b>Дата опроса:</b> $dateStr<br><b>Найдено:</b> <span id='count'>0</span> из ${proxies.size}<br><br>"

        // Перезапускаем Job для процесса проверки
        checkJob = CoroutineScope(Dispatchers.IO).launch {
            var checkedCount = 0
            for ((server, port) in proxies) {
                if (!isActive) break // Прерываем цикл, если нажали Стоп

                if (isServerAlive(server, port)) {
                    val originalLine = text.lines().find { it.contains("server=$server") && it.contains("port=$port") }
                    if (originalLine != null) {
                        workingLinks.add(originalLine)
                        
                        // Мгновенно выводим найденную ссылку на экран
                        val headerWithCount = header.replace("<span id='count'>0</span>", "<span style='color:#D81B60;'><b>${workingLinks.size}</b></span>")
                        val linksHtml = workingLinks.joinToString("<br><br>") { link -> "<a href=\"$link\">$link</a>" }
                        
                        withContext(Dispatchers.Main) {
                            resultText.text = HtmlCompat.fromHtml(headerWithCount + linksHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                        }
                    }
                }
                checkedCount++
                
                if (checkedCount % 5 == 0 || checkedCount == proxies.size) {
                    val progress = checkedCount
                    val total = proxies.size
                    withContext(Dispatchers.Main) {
                        progressBar.progress = progress
                        statusText.text = "Проверено $progress из $total..."
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (isActive) {
                    statusText.text = "Готово! Найдено: ${workingLinks.size}"
                    resetButtons()
                    saveResult(resultText.text.toString())
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

    private fun saveResult(text: String) {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().putString("last_result", text).apply()
    }

    private fun loadSavedResult() {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val saved = prefs.getString("last_result", "")
        if (!saved.isNullOrEmpty()) {
            resultText.text = HtmlCompat.fromHtml(saved, HtmlCompat.FROM_HTML_MODE_LEGACY)
            statusText.text = "Загружен прошлый результат"
        }
    }
}
