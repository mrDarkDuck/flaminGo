package com.example.mtchecker

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.coroutines.coroutineContext // <-- ДОБАВЛЕН ИМПОРТ

class CheckService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val CHANNEL_ID = "ProxyCheckChannel"
    private val NOTIFICATION_ID = 1

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("url") ?: ""
        val customText = intent?.getStringExtra("customText")
        val isCustom = !customText.isNullOrEmpty()

        // Запускаем сервис как Foreground (появляется в шторке)
        startForeground(NOTIFICATION_ID, buildNotification("Подготовка к проверке..."))

        serviceScope.launch {
            try {
                val text = if (isCustom) customText!! else {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connect()
                    conn.inputStream.bufferedReader().use { it.readText() }
                }
                
                performChecks(text)
                
            } catch (e: Exception) {
                sendBroadcast(Intent("CHECK_ERROR").putExtra("error", e.message))
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun performChecks(text: String) {
        val regex = Regex("""server=([^&]+).*?port=(\d+)""")
        val proxies = regex.findAll(text).map { match ->
            val server = match.groupValues[1].trimEnd('.')
            val port = match.groupValues[2].toInt()
            Pair(server, port)
        }.toList().distinct()

        if (proxies.isEmpty()) {
            sendBroadcast(Intent("CHECK_ERROR").putExtra("error", "No proxies found"))
            stopSelf()
            return
        }

        var checkedCount = 0
        
        for ((server, port) in proxies) {
            // ИСПРАВЛЕНИЕ ЗДЕСЬ: используем coroutineContext.isActive
            if (!coroutineContext.isActive) break

            val startTime = System.currentTimeMillis()
            if (isServerAlive(server, port)) {
                val ping = System.currentTimeMillis() - startTime
                val originalLine = text.lines().find { it.contains("server=$server") && it.contains("port=$port") }
                
                if (originalLine != null) {
                    // Отправляем найденный прокси в MainActivity
                    val resultIntent = Intent("CHECK_RESULT")
                    resultIntent.putExtra("link", originalLine)
                    resultIntent.putExtra("displayName", "$server:$port")
                    resultIntent.putExtra("ping", ping)
                    sendBroadcast(resultIntent)
                }
            }
            checkedCount++
            
            if (checkedCount % 5 == 0 || checkedCount == proxies.size) {
                // Обновляем прогресс в MainActivity и в шторке
                val progressIntent = Intent("CHECK_PROGRESS")
                progressIntent.putExtra("checked", checkedCount)
                progressIntent.putExtra("total", proxies.size)
                sendBroadcast(progressIntent)
                
                updateNotification("Проверено $checkedCount из ${proxies.size}...")
            }
        }

        // ИСПРАВЛЕНИЕ ЗДЕСЬ: используем coroutineContext.isActive
        if (coroutineContext.isActive) {
            sendBroadcast(Intent("CHECK_FINISHED"))
        }
        stopSelf()
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

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- ЛОГИКА УВЕДОМЛЕНИЙ ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Проверка прокси",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FlaminGo")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
