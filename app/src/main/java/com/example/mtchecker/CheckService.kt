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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
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
                val text = if (isCustom || isReCheck) customText ?: "" else {
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
        val regex = Regex("""server=([^&]+).*?port=(\d+).*?secret=([^&]+)""")
        val allProxies = regex.findAll(text).mapNotNull { match ->
            val server = match.groupValues[1].trimEnd('.')
            val port = match.groupValues[2].toIntOrNull()
            val secret = match.groupValues[3]
            if (port != null) Triple(server, port, secret) else null
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
        val retryList = ConcurrentLinkedQueue<Triple<String, Int, String>>()
        val checkedCount = AtomicInteger(0)

        // --- ПРОХОД 1: БЫСТРОЕ СИТО (1 секунда, TCP) ---
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
                            // ИСПРАВЛЕНИЕ: Теперь вызываем ГЛУБОКУЮ проверку для прошедших TCP
                            if (checkMTProtoDeep(proxy.first, proxy.second, proxy.third, 3000)) {
                                val ping = System.currentTimeMillis() - startTime
                                if (!(onlyFast && ping >= 150)) {
                                    addResult(proxy, ping, text, foundCount)
                                }
                            }
                        } else {
                            retryList.add(proxy)
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
                                // ИСПРАВЛЕНИЕ: И здесь тоже вызываем ГЛУБОКУЮ проверку
                                if (checkMTProtoDeep(proxy.first, proxy.second, proxy.third, 3000)) {
                                    val ping = System.currentTimeMillis() - startTime
                                    addResult(proxy, ping, text, foundCount)
                                }
                            }
                        }
                    }
                }
            }
        }

        isRunning = false
        if (coroutineContext.isActive) {
            broadcastFinished()
        }
        stopSelf()
    }

    private fun addResult(proxy: Triple<String, Int, String>, ping: Long, text: String, foundCount: AtomicInteger) {
        val originalLine = text.lines().find { it.contains("server=${proxy.first}") && it.contains("port=${proxy.second}") }
        if (originalLine != null) {
            val item = ProxyItem(originalLine, "${proxy.first}:${proxy.second}", ping)
            synchronized(foundProxies) { foundProxies.add(item) }
            foundCount.incrementAndGet()
            broadcastResult(item)
        }
    }

    private fun isServerAlive(host: String, port: Int, timeoutMs: Int): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        } catch (e: Exception) {
            false
        } finally {
            try { socket?.close() } catch (e: Exception) {}
        }
    }

    // --- ГЛУБОКАЯ MTProto ПРОВЕРКА (Obfuscated2) ---
    private fun checkMTProtoDeep(host: String, port: Int, secretHex: String, timeoutMs: Int): Boolean {
        // Пока делаем только для dd-секретов (самые популярные)
        if (!secretHex.startsWith("dd", ignoreCase = true)) {
            return true 
        }

        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs

            // 1. Генерируем 64 случайных байта
            val random = ByteArray(64)
            java.security.SecureRandom().nextBytes(random)
            
            // Защита от HTTP (первые байты не должны быть GET, POST, HEAD)
            if (random[0] == 0x48.toByte() && random[1] == 0x45.toByte()) random[0] = 0x01 // HE
            if (random[0] == 0x50.toByte() && random[1] == 0x4F.toByte()) random[0] = 0x01 // PO
            if (random[0] == 0x47.toByte() && random[1] == 0x45.toByte()) random[0] = 0x01 // GE

            // Маркер протокола MTProto
            random[56] = 0xEE.toByte()
            random[57] = 0xEE.toByte()
            random[58] = 0xEE.toByte()
            random[59] = 0xEE.toByte()

            // 2. Извлекаем ключ из секрета (пропускаем "dd")
            val secretBytes = ByteArray((secretHex.length - 2) / 2)
            for (i in secretBytes.indices) {
                val index = i * 2 + 2
                secretBytes[i] = secretHex.substring(index, index + 2).toInt(16).toByte()
            }

            // 3. Вычисляем SHA256 от секрета для IV
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(secretBytes)
            val hash = digest.digest()
            
            val key = SecretKeySpec(hash.copyOfRange(0, 16), "AES")
            val iv = IvParameterSpec(random.copyOfRange(32, 48)) // Используем кусок рандома

            // 4. Шифруем (AES-CTR)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, iv)
            
            val encrypted = cipher.doFinal(random)
            
            // 5. Отправляем
            socket.getOutputStream().write(encrypted)
            socket.getOutputStream().flush()

            // 6. Ждем ответ. Если сервер прислал данные или не закрыл соединение (не EOF) - значит работает.
            val buffer = ByteArray(1024)
            val bytesRead = socket.getInputStream().read(buffer)
            
            return bytesRead > 0
        } catch (e: Exception) {
            return false
        } finally {
            try { socket?.close() } catch (e: Exception) {}
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
