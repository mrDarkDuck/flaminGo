package com.example.mtchecker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var checkButton: Button
    
    private val url = "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        resultText = findViewById(R.id.resultText)
        checkButton = findViewById(R.id.checkButton)
        val copyButton = findViewById<Button>(R.id.copyButton)

        checkButton.setOnClickListener {
            startChecking()
        }

        copyButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Proxies", resultText.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Скопировано!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startChecking() {
        checkButton.isEnabled = false
        resultText.text = ""
        statusText.text = "Скачиваю список..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val text = URL(url).readText()
                val regex = Regex("""server=([^&]+).*?port=(\d+)""")
                val proxies = regex.findAll(text).map { match ->
                    val server = match.groupValues[1].trimEnd('.')
                    val port = match.groupValues[2].toInt()
                    Pair(server, port)
                }.toList().distinct()

                withContext(Dispatchers.Main) {
                    statusText.text = "Проверяю ${proxies.size} серверов..."
                }

                val working = mutableListOf<String>()
                var checkedCount = 0

                for ((server, port) in proxies) {
                    if (isServerAlive(server, port)) {
                        // Берем оригинальную ссылку из файла
                        val originalLine = text.lines().find { it.contains("server=$server") && it.contains("port=$port") }
                        if (originalLine != null) working.add(originalLine)
                    }
                    checkedCount++
                    if (checkedCount % 10 == 0) {
                        val progress = checkedCount
                        withContext(Dispatchers.Main) {
                            statusText.text = "Проверено $progress из ${proxies.size}..."
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    statusText.text = "Готово! Найдено: ${working.size}"
                    resultText.text = working.joinToString("\n\n")
                    checkButton.isEnabled = true
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Ошибка: ${e.message}"
                    checkButton.isEnabled = true
                }
            }
        }
    }

    private fun isServerAlive(host: String, port: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 3000) // таймаут 3 секунды
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }
}
