package com.example.mtchecker

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

class CheckService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var checkJob: Job? = null
    private val CHANNEL_ID = "ProxyCheckChannel"
    private val NOTIFICATION_ID = 1

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
        val limit = intent?.getIntExtra("limit", 0) ?: 0
        val onlyFast = intent?.getBooleanExtra("onlyFast", false) ?: false
        val isReCheck = intent?.getBooleanExtra("isReCheck", false) ?: false

        isRunning = true
        currentChecked = 0
        currentTotal = 0
        foundProxies.clear()

        startForeground(NOTIFICATION_ID, buildNotification("Подготовка к проверке..."))

        checkJob?.cancel()
        checkJob = serviceScope.launch {
            try {
                val text = if (isCustom || isReCheck) {
                    customText ?: ""
                } else {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.requestMethod = "GET"
                    conn.connect()
                    conn.inputStream.bufferedReader().use { it.readText() }
                }
                
                if (text.isEmpty()) {
                    broadcastError("Список пуст или не удалось загрузить.")
                    stopSelf()
                    return@launch
                }
                
                performChecks(text, limit, onlyFast)
            } catch (e: Exception) {
                broadcastError(e.message ?: "Ошибка загрузки списка")
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun performChecks(text: String, limit: Int, onlyFast: Boolean) {
        val regex = Regex("""server=([^&]+).*?port=(\d+)""")
        val allProxies = regex.findAll(text).map { match ->
            val server = match.groupValues[1].trimEnd('.')
            val port = match.groupValues[2].toInt()
            Pair(server, port)
        }.toList().distinct()

        if (allProxies.isEmpty()) {
            broadcastError("Прокси не найдены.")
            stopSelf()
            return
        }

        currentTotal = allProxies.size
        broadcastProgress(0, currentTotal)

        val semaphore = Semaphore(15)
        val foundCount = AtomicInteger(0)
        // Используем потокобезопасную очередь
        val retryList = ConcurrentLinkedQueue<Pair<String, Int>>()
        val checkedCount = AtomicInteger(0)

        // --- ПРОХОД 1: БЫСТРОЕ СИТО (1 секунда) ---
        updateNotification("Быстрая проверка (1 сек)...")
        coroutineScope {
            for (proxy in allProxies) {
                if (limit > 0 && foundCount.get() >= limit) break
                if (!coroutineContext.isActive) break

                launch {
                    semaphore.withPermit {
                        if (!coroutineContext.isActive) return@withPermit

                        val startTime = System.currentTimeMillis()
                        if (isServerAlive(proxy.first, proxy.second, timeoutMs = 1000)) {
                            val ping = System.currentTimeMillis() - startTime
                            if (!(onlyFast && ping >= 150)) {
                                addResult(proxy, ping, text, foundCount)
                            }
                        } else {
                            retryList.add(proxy) // Без synchronized
                        }
                        
                        val current = checkedCount.incrementAndGet()
                        currentChecked = current
                        if (current % 5 == 0 || current == allProxies.size) {
                            broadcastProgress(current, allProxies.size)
                        }
                    }
                }
            }
        }

        // --- ПРОХОД 2: ГЛУБОКОЕ СИТО (3 секунды) ---
        if ((limit == 0 || foundCount.get() < limit) && coroutineContext.isActive && !onlyFast) {
            updateNotification("Глубокая проверка (${retryList.size} шт)...")
            coroutineScope {
                for (proxy in retryList) {
                    if (limit > 0 && foundCount.get() >= limit) break
                    if (!coroutineContext.isActive) break

                    launch {
                        semaphore.withPermit {
                            if (!coroutineContext.isActive) return@withPermit

                            val startTime = System.currentTimeMillis()
                            if (isServerAlive(proxy.first, proxy.second, timeoutMs = 3000)) {
                                val ping = System.currentTimeMillis() - startTime
                                addResult(proxy, ping, text, foundCount)
                            }
                        }
                    }
                }
            }
        }

        // ИСПРАВЛЕНО: isRunning сбрасывается всегда, даже если нажали "Стоп"
        isRunning = false
        if (coroutineContext.isActive) {
            broadcastFinished()
        }
        stopSelf()
    }

    private fun addResult(proxy: Pair<String, Int>, ping: Long, text: String, foundCount: AtomicInteger) {
        val originalLine = text.lines().find { it.contains("server=${proxy.first}") && it.contains("port=${proxy.second}") }
        if (originalLine != null) {
            val item = ProxyItem(originalLine, "${proxy.first}:${proxy.second}", ping)
            synchronized(foundProxies) { foundProxies.add(item) }
            foundCount.incrementAndGet()
            broadcastResult(item)
        }
    }

    // ИСПРАВЛЕНО: Сокет теперь всегда закрывается, даже при ошибке
    private fun isServerAlive(host: String, port: Int, timeoutMs: Int): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        } catch (e: Exception) {
            false
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        checkJob?.cancel()
        serviceScope.cancel()
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun broadcastProgress(checked: Int, total: Int) {
        val intent = Intent("CHECK_PROGRESS").apply {
            putExtra("checked", checked)
            putExtra("total", total)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastResult(item: ProxyItem) {
        val intent = Intent("CHECK_RESULT").apply {
            putExtra("link", item.link)
            putExtra("displayName", item.displayName)
            putExtra("ping", item.ping)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastFinished() {
        val intent = Intent("CHECK_FINISHED").apply { setPackage(packageName) }
        sendBroadcast(intent)
    }

    private fun broadcastError(error: String) {
        val intent = Intent("CHECK_ERROR").apply {
            putExtra("error", error)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

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
