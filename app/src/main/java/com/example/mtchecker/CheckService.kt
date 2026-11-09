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
import kotlin.coroutines.coroutineContext

class CheckService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var checkJob: Job? = null
    private val CHANNEL_ID = "ProxyCheckChannel"
    private val NOTIFICATION_ID = 1

    // СОХРАНЯЕМ СОСТОЯНИЕ ЗДЕСЬ, чтобы MainActivity могла его прочитать
    companion object {
        var isRunning = false
        var currentChecked = 0
        var currentTotal = 0
        var currentNetworkId = ""
        val foundProxies = mutableListOf<ProxyItem>()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("url") ?: ""
        val customText = intent?.getStringExtra("customText")
        val isCustom = !customText.isNullOrEmpty()

        isRunning = true
        currentChecked = 0
        currentTotal = 0
        foundProxies.clear()

        startForeground(NOTIFICATION_ID, buildNotification("Подготовка к проверке..."))

        checkJob?.cancel()
        checkJob = serviceScope.launch {
            try {
                val text = if (isCustom) customText!! else {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connect()
                    conn.inputStream.bufferedReader().use { it.readText() }
                }
                performChecks(text)
            } catch (e: Exception) {
                broadcastError(e.message ?: "Unknown error")
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
            broadcastError("No proxies found")
            stopSelf()
            return
        }

        currentTotal = proxies.size

        for ((server, port) in proxies) {
            if (!coroutineContext.isActive) break

            val startTime = System.currentTimeMillis()
            if (isServerAlive(server, port)) {
                val ping = System.currentTimeMillis() - startTime
                val originalLine = text.lines().find { it.contains("server=$server") && it.contains("port=$port") }
                
                if (originalLine != null) {
                    val item = ProxyItem(originalLine, "$server:$port", ping)
                    foundProxies.add(item)
                    broadcastResult(item)
                }
            }
            currentChecked++
            
            if (currentChecked % 5 == 0 || currentChecked == proxies.size) {
                broadcastProgress(currentChecked, proxies.size)
                updateNotification("Проверено $currentChecked из ${proxies.size}...")
            }
        }

        if (coroutineContext.isActive) {
            isRunning = false
            broadcastFinished()
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
        checkJob?.cancel()
        serviceScope.cancel()
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- ОТПРАВКА СИГНАЛОВ (С УКАЗАНИЕМ ПАКЕТА) ---

    private fun broadcastProgress(checked: Int, total: Int) {
        val intent = Intent("CHECK_PROGRESS")
        intent.putExtra("checked", checked)
        intent.putExtra("total", total)
        intent.setPackage(packageName) // <-- Обязательно для Android 13+
        sendBroadcast(intent)
    }

    private fun broadcastResult(item: ProxyItem) {
        val intent = Intent("CHECK_RESULT")
        intent.putExtra("link", item.link)
        intent.putExtra("displayName", item.displayName)
        intent.putExtra("ping", item.ping)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun broadcastFinished() {
        val intent = Intent("CHECK_FINISHED")
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun broadcastError(error: String) {
        val intent = Intent("CHECK_ERROR")
        intent.putExtra("error", error)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    // --- УВЕДОМЛЕНИЯ ---

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
