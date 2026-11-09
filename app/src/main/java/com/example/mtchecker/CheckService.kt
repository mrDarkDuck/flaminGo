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
                val text = if (isCustom || isReCheck) customText!! else {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.requestMethod = "GET"
                    conn.connect()
                    conn.inputStream.bufferedReader().use { it.readText() }
                }
                performChecks(text, limit, onlyFast)
            } catch (e: Exception) {
                broadcastError(e.message ?: "Ошибка загрузки списка (возможно, заблокирован провайдером)")
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun performChecks(text: String, limit: Int, onlyFast: Boolean) {
        val regex = Regex("""server=([^&]+).*?port=(\d+)""")
        val proxies = regex.findAll(text).map { match ->
            val server = match.groupValues[1].trimEnd('.')
            val port = match.groupValues[2].toInt()
            Pair(server, port)
        }.toList().distinct()

        if (proxies.isEmpty()) {
            broadcastError("Прокси не найдены. Возможно, список пуст или заблокирован.")
            stopSelf()
            return
        }

        currentTotal = proxies.size
        broadcastProgress(0, currentTotal)

        // Ограничиваем количество одновременных проверок (15 потоков)
        val semaphore = Semaphore(15)
        
        // Потокобезопасные счетчики
        val checkedCount = AtomicInteger(0)
        val foundCount = AtomicInteger(0)

        coroutineScope {
            for ((server, port) in proxies) {
                // Если достигли лимита или нажали Стоп - выходим из цикла
                if (limit > 0 && foundCount.get() >= limit) break
                if (!coroutineContext.isActive) break

                launch {
                    semaphore.withPermit {
                        // Проверяем еще раз, не нажали ли Стоп, пока стояли в очереди
                        if (!coroutineContext.isActive) return@withPermit

                        val startTime = System.currentTimeMillis()
                        if (isServerAlive(server, port)) {
                            val ping = System.currentTimeMillis() - startTime
                            
                            // Фильтр "только быстрые"
                            if (!(onlyFast && ping >= 150)) {
                                val originalLine = text.lines().find { it.contains("server=$server") && it.contains("port=$port") }
                                if (originalLine != null) {
                                    val item = ProxyItem(originalLine, "$server:$port", ping)
                                    
                                    // Синхронизируем добавление в общий список
                                    synchronized(foundProxies) {
                                        foundProxies.add(item)
                                    }
                                    foundCount.incrementAndGet()
                                    broadcastResult(item)
                                }
                            }
                        }
                        
                        val current = checkedCount.incrementAndGet()
                        currentChecked = current // Обновляем статическую переменную для MainActivity
                        
                        // Обновляем прогресс-бар каждые 5 проверок
                        if (current % 5 == 0 || current == proxies.size) {
                            broadcastProgress(current, proxies.size)
                            updateNotification("Проверено $current из ${proxies.size}...")
                        }
                    }
                }
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
