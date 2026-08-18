package ru.servertronix.i2pmessenger.i2p

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class PresenceManager(
    private val i2pManager: I2PManager,
    private val destinationProvider: suspend () -> List<String>
) {

    companion object {
        private const val TAG = "PresenceManager"
        private const val DEFAULT_INTERVAL_MS = 15_000L
        private const val DEFAULT_TIMEOUT_MS = 45_000L
        private const val INITIAL_DELAY_MS = 2_000L
        private const val MAX_PRESENCE_SIZE = 512
        private const val PRESENCE_PREFIX = "PRESENCE|"
        private const val ONLINE = "online"
        private const val OFFLINE = "offline"
    }

    // =====================================================================
    // COROUTINES
    // =====================================================================

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var heartbeatJob: Job? = null
    private var cleanupJob: Job? = null

    // =====================================================================
    // STATE
    // =====================================================================

    @Volatile
    private var running = false

    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val onlineState = ConcurrentHashMap<String, Boolean>()

    // =====================================================================
    // CALLBACKS
    // =====================================================================

    private var onPresenceChanged: ((destinationBase64: String, online: Boolean) -> Unit)? = null
    private var onDestinationDiscovered: ((destinationBase64: String) -> Unit)? = null
    private var onLog: ((String) -> Unit)? = null

    // =====================================================================
    // CALLBACK SETTERS
    // =====================================================================

    fun setOnPresenceChanged(callback: ((destinationBase64: String, online: Boolean) -> Unit)?) {
        onPresenceChanged = callback
    }

    fun setOnDestinationDiscovered(callback: ((destinationBase64: String) -> Unit)?) {
        onDestinationDiscovered = callback
    }

    fun setOnLog(callback: ((String) -> Unit)?) {
        onLog = callback
    }

    // =====================================================================
    // LOG
    // =====================================================================

    private fun log(message: String) {
        Log.d(TAG, message)
        onLog?.invoke(message)
    }

    private fun logWarning(message: String) {
        Log.w(TAG, message)
        onLog?.invoke("⚠️ $message")
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
        onLog?.invoke("❌ $message")
    }

    // =====================================================================
    // START
    // =====================================================================

    @Synchronized
    fun start(
        onPresenceChanged: ((destinationBase64: String, online: Boolean) -> Unit)? = null,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ) {
        log("🔍 [PM] ===== START ===== running=$running")

        if (onPresenceChanged != null) {
            this.onPresenceChanged = onPresenceChanged
            log("🔍 [PM] onPresenceChanged установлен")
        }

        if (running) {
            log("🔍 [PM] уже запущен")
            return
        }

        if (intervalMs <= 0L || timeoutMs <= 0L) {
            logError("🔍 [PM] некорректные параметры: interval=$intervalMs, timeout=$timeoutMs")
            return
        }

        running = true
        log("🔍 [PM] 🚀 запускаем, heartbeat=${intervalMs}ms, timeout=${timeoutMs}ms")

        startHeartbeatLoop(intervalMs)
        startCleanupLoop(timeoutMs)

        log("🔍 [PM] ===== START FINISH =====")
    }

    // =====================================================================
    // HEARTBEAT LOOP
    // =====================================================================

    private fun startHeartbeatLoop(intervalMs: Long) {
        log("🔍 [PM] startHeartbeatLoop() interval=$intervalMs")

        if (heartbeatJob?.isActive == true) {
            log("🔍 [PM] heartbeatJob уже активен")
            return
        }

        heartbeatJob = scope.launch {
            log("🔍 [PM] heartbeatJob запущен")
            delay(INITIAL_DELAY_MS)
            log("🔍 [PM] initial delay прошёл ($INITIAL_DELAY_MS ms)")

            while (isActive && running) {
                log("🔍 [PM] ===== HEARTBEAT ITERATION START =====")
                try {
                    sendPresenceToContacts()
                } catch (e: CancellationException) {
                    log("🔍 [PM] heartbeatJob отменён")
                    throw e
                } catch (e: Exception) {
                    logError("🔍 [PM] Ошибка heartbeat: ${e.message}", e)
                }
                log("🔍 [PM] ===== HEARTBEAT ITERATION FINISH, задержка ${intervalMs}ms =====")
                delay(intervalMs)
            }
            log("🔍 [PM] heartbeatJob завершён (running=$running)")
        }
    }

    // =====================================================================
    // SEND PRESENCE (МАКСИМАЛЬНО ПОДРОБНЫЙ)
    // =====================================================================

    private suspend fun sendPresenceToContacts() {
        log("🔍 [PM] ===== sendPresenceToContacts() START =====")

        // --- ПРОВЕРКА I2P ---
        log("🔍 [PM] checking I2PManager.isConnected()...")
        if (!i2pManager.isConnected()) {
            log("🔍 [PM] ❌ I2PManager НЕ готов (isConnected=false), heartbeat пропущен")
            return
        }
        log("🔍 [PM] ✅ I2PManager готов (isConnected=true)")

        // --- ПОЛУЧЕНИЕ СПИСКА КОНТАКТОВ ---
        log("🔍 [PM] вызываем destinationProvider()...")
        val destinations = try {
            val result = destinationProvider()
            log("🔍 [PM] ✅ destinationProvider вернул ${result.size} адресов")
            if (result.isNotEmpty()) {
                log("🔍 [PM]   адреса: $result")
            }
            result
        } catch (e: Exception) {
            logError("🔍 [PM] ❌ destinationProvider ошибка: ${e.message}", e)
            return
        }

        if (destinations.isEmpty()) {
            logWarning("🔍 [PM] ⚠️ Нет контактов для presence")
            return
        }

        // --- СОЗДАНИЕ PAYLOAD ---
        val payload = createPresencePayload()
        log("🔍 [PM] payload: \"$payload\" (${payload.length} символов)")

        // --- ОТПРАВКА КАЖДОМУ КОНТАКТУ ---
        log("🔍 [PM] отправляем presence ${destinations.size} контактам")

        for ((index, destination) in destinations.withIndex()) {
            if (!running) {
                log("🔍 [PM] остановлено (running=false)")
                return
            }

            val normalized = destination.trim()
            log("🔍 [PM] [$index/${destinations.size}] отправка на '$normalized'...")

            try {
                log("🔍 [PM] [$index] вызываем i2pManager.sendMessage(destination='$normalized', message='$payload')")
                val sent = i2pManager.sendMessage(normalized, payload)
                log("🔍 [PM] [$index] i2pManager.sendMessage вернул $sent")
                if (sent) {
                    log("🔍 [PM] [$index] ✅ presence отправлен успешно")
                } else {
                    logWarning("🔍 [PM] [$index] ❌ i2pManager.sendMessage вернул false")
                }
            } catch (e: CancellationException) {
                log("🔍 [PM] [$index] отправка прервана")
                throw e
            } catch (e: Exception) {
                logError("🔍 [PM] [$index] ❌ ошибка отправки: ${e.message}", e)
            }
        }

        log("🔍 [PM] ===== sendPresenceToContacts() FINISH =====")
    }

    // =====================================================================
    // PAYLOAD
    // =====================================================================

    private fun createPresencePayload(): String {
        val payload = "$PRESENCE_PREFIX$ONLINE|${System.currentTimeMillis()}"
        log("🔍 [PM] createPresencePayload: \"$payload\"")
        return payload
    }

    // =====================================================================
    // INCOMING MESSAGE (МАКСИМАЛЬНО ПОДРОБНЫЙ)
    // =====================================================================

    fun handleIncomingMessage(senderDestinationBase64: String, message: String): Boolean {
        log("🔍 [PM] ===== handleIncomingMessage() START =====")
        log("🔍 [PM] sender (Base64): ${senderDestinationBase64.take(80)}...")
        log("🔍 [PM] message: \"${message.take(100)}\" (${message.length} символов)")

        if (!running) {
            log("🔍 [PM] ❌ PresenceManager не запущен (running=false)")
            return false
        }

        val sender = senderDestinationBase64.trim()
        if (!isValidDestination(sender)) {
            logWarning("🔍 [PM] ❌ невалидный sender: '$sender'")
            return false
        }

        if (message.length > MAX_PRESENCE_SIZE) {
            logWarning("🔍 [PM] ❌ сообщение слишком большое: ${message.length} > $MAX_PRESENCE_SIZE")
            return false
        }

        val presence = parsePresence(message)
        if (presence == null) {
            log("🔍 [PM] сообщение НЕ является presence, пропускаем")
            return false
        }

        log("🔍 [PM] ✅ распознан presence: state=${presence.state}, timestamp=${presence.timestamp}")

        when (presence.state) {
            ONLINE -> {
                log("🔍 [PM] обрабатываем ONLINE для ${sender.take(32)}...")
                handleOnline(sender)
                return true
            }
            OFFLINE -> {
                log("🔍 [PM] обрабатываем OFFLINE для ${sender.take(32)}...")
                handleOffline(sender)
                return true
            }
            else -> {
                logWarning("🔍 [PM] неизвестный state: ${presence.state}")
                return false
            }
        }
    }

    // =====================================================================
    // ONLINE
    // =====================================================================

    private fun handleOnline(senderDestinationBase64: String) {
        log("🔍 [PM] handleOnline() START: ${senderDestinationBase64.take(40)}...")

        val now = System.currentTimeMillis()
        log("🔍 [PM] now=$now")

        // Сообщаем о discovery
        try {
            log("🔍 [PM] вызываем onDestinationDiscovered(sender)...")
            onDestinationDiscovered?.invoke(senderDestinationBase64)
            log("🔍 [PM] ✅ onDestinationDiscovered вызван")
        } catch (e: Exception) {
            logError("🔍 [PM] ❌ onDestinationDiscovered ошибка: ${e.message}", e)
        }

        lastSeen[senderDestinationBase64] = now
        log("🔍 [PM] lastSeen обновлён: $now")

        val wasOnline = onlineState[senderDestinationBase64] ?: false
        log("🔍 [PM] wasOnline=$wasOnline")

        if (!wasOnline) {
            onlineState[senderDestinationBase64] = true
            log("🔍 [PM] onlineState обновлён: true")
            log("🔍 [PM] 🟢 ONLINE: ${shortDestination(senderDestinationBase64)}")
            notifyPresenceChanged(senderDestinationBase64, true)
        } else {
            log("🔍 [PM] контакт уже был ONLINE")
        }
        log("🔍 [PM] handleOnline() FINISH")
    }

    // =====================================================================
    // OFFLINE
    // =====================================================================

    private fun handleOffline(senderDestinationBase64: String) {
        log("🔍 [PM] handleOffline() START: ${senderDestinationBase64.take(40)}...")

        val wasOnline = onlineState[senderDestinationBase64] ?: false
        log("🔍 [PM] wasOnline=$wasOnline")

        onlineState[senderDestinationBase64] = false
        log("🔍 [PM] onlineState обновлён: false")

        if (wasOnline) {
            log("🔍 [PM] 🔴 OFFLINE: ${shortDestination(senderDestinationBase64)}")
            notifyPresenceChanged(senderDestinationBase64, false)
        }
        log("🔍 [PM] handleOffline() FINISH")
    }

    // =====================================================================
    // PARSER
    // =====================================================================

    private data class PresenceData(val state: String, val timestamp: Long?)

    private fun parsePresence(message: String): PresenceData? {
        val value = message.trim()
        log("🔍 [PM] parsePresence: value=\"$value\"")

        if (!value.startsWith(PRESENCE_PREFIX)) {
            log("🔍 [PM] не начинается с $PRESENCE_PREFIX")
            return null
        }

        val parts = value.split("|")
        log("🔍 [PM] parts.size=${parts.size}, parts=$parts")

        return try {
            when {
                parts.size >= 3 -> {
                    val state = parts[1].trim().lowercase()
                    val timestamp = parts[2].trim().toLongOrNull()
                    log("🔍 [PM] state='$state', timestamp=$timestamp")
                    if (state != ONLINE && state != OFFLINE) {
                        log("🔍 [PM] неизвестный state: $state")
                        null
                    } else {
                        PresenceData(state, timestamp)
                    }
                }
                parts.size == 2 -> {
                    val timestamp = parts[1].trim().toLongOrNull()
                    if (timestamp == null) {
                        log("🔍 [PM] невалидный timestamp: ${parts[1]}")
                        null
                    } else {
                        log("🔍 [PM] старый формат: timestamp=$timestamp")
                        PresenceData(ONLINE, timestamp)
                    }
                }
                else -> {
                    log("🔍 [PM] неправильный формат: parts.size=${parts.size}")
                    null
                }
            }
        } catch (e: Exception) {
            logWarning("🔍 [PM] ошибка парсинга: ${e.message}")
            null
        }
    }

    // =====================================================================
    // OFFLINE CLEANUP
    // =====================================================================

    private fun startCleanupLoop(timeoutMs: Long) {
        log("🔍 [PM] startCleanupLoop() timeout=$timeoutMs")

        if (cleanupJob?.isActive == true) {
            log("🔍 [PM] cleanupJob уже активен")
            return
        }

        cleanupJob = scope.launch {
            log("🔍 [PM] cleanupJob запущен")
            while (isActive && running) {
                try {
                    checkOfflineContacts(timeoutMs)
                } catch (e: CancellationException) {
                    log("🔍 [PM] cleanupJob отменён")
                    throw e
                } catch (e: Exception) {
                    logError("🔍 [PM] cleanupJob ошибка: ${e.message}", e)
                }
                delay(minOf(timeoutMs / 2, 15_000L))
            }
            log("🔍 [PM] cleanupJob завершён")
        }
    }

    private fun checkOfflineContacts(timeoutMs: Long) {
        log("🔍 [PM] checkOfflineContacts() START, timeout=$timeoutMs")
        val now = System.currentTimeMillis()

        for (entry in lastSeen.entries) {
            val destination = entry.key
            val lastTimestamp = entry.value
            val diff = now - lastTimestamp
            log("🔍 [PM] проверка ${shortDestination(destination)}: last=$lastTimestamp, diff=${diff}ms, timeout=${timeoutMs}ms")

            if (diff > timeoutMs) {
                val wasOnline = onlineState[destination] ?: false
                if (wasOnline) {
                    onlineState[destination] = false
                    log("🔍 [PM] 🔴 OFFLINE: ${shortDestination(destination)} (таймаут, diff=${diff}ms)")
                    notifyPresenceChanged(destination, false)
                }
            }
        }
        log("🔍 [PM] checkOfflineContacts() FINISH")
    }

    // =====================================================================
    // QUERY
    // =====================================================================

    fun isOnline(destinationBase64: String): Boolean {
        return onlineState[destinationBase64] ?: false
    }

    fun getLastSeen(destinationBase64: String): Long? {
        return lastSeen[destinationBase64]
    }

    fun getOnlineStates(): Map<String, Boolean> {
        return HashMap(onlineState)
    }

    // =====================================================================
    // MANUAL OFFLINE
    // =====================================================================

    fun markOffline(destinationBase64: String) {
        val destination = destinationBase64.trim()
        if (destination.isEmpty()) return

        val wasOnline = onlineState[destination] ?: false
        onlineState[destination] = false
        if (wasOnline) {
            log("🔍 [PM] 🔴 OFFLINE: ${shortDestination(destination)} (ручной)")
            notifyPresenceChanged(destination, false)
        }
    }

    // =====================================================================
    // CALLBACK
    // =====================================================================

    private fun notifyPresenceChanged(destinationBase64: String, online: Boolean) {
        log("🔍 [PM] notifyPresenceChanged: ${shortDestination(destinationBase64)} -> ${if (online) "ONLINE" else "OFFLINE"}")
        try {
            onPresenceChanged?.invoke(destinationBase64, online)
            log("🔍 [PM] ✅ onPresenceChanged вызван")
        } catch (e: Exception) {
            logError("🔍 [PM] ❌ onPresenceChanged ошибка: ${e.message}", e)
        }
    }

    // =====================================================================
    // VALIDATION
    // =====================================================================

    private fun isValidDestination(destination: String): Boolean {
        val value = destination.trim()
        if (value.isEmpty()) return false

        if (value.lowercase().endsWith(".b32.i2p")) {
            return value.length > ".b32.i2p".length
        }

        return value.length >= 32
    }

    // =====================================================================
    // UTILS
    // =====================================================================

    private fun shortDestination(destination: String): String {
        return if (destination.length > 24) {
            destination.take(24) + "..."
        } else {
            destination
        }
    }

    // =====================================================================
    // STATE
    // =====================================================================

    fun isRunning(): Boolean = running

    // =====================================================================
    // STOP
    // =====================================================================

    @Synchronized
    fun stop() {
        log("🔍 [PM] stop() called")
        if (!running) {
            log("🔍 [PM] уже остановлен")
            return
        }

        running = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        cleanupJob?.cancel()
        cleanupJob = null

        lastSeen.clear()
        onlineState.clear()

        log("🔍 [PM] остановлен")
    }

    // =====================================================================
    // SHUTDOWN
    // =====================================================================

    fun shutdown() {
        log("🔍 [PM] shutdown() called")
        stop()
        job.cancel()
        onPresenceChanged = null
        onDestinationDiscovered = null
        onLog = null
        log("🔍 [PM] shutdown завершён")
    }
}