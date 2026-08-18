package ru.servertronix.i2pmessenger.i2p

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.servertronix.i2pmessenger.I2PConnectionState
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class I2PManager(
    private val context: Context,
    private val samHost: String = DEFAULT_SAM_HOST,
    private val samPort: Int = DEFAULT_SAM_PORT
) {

    companion object {
        private const val TAG = "I2PManager"
        private const val PREFS = "i2p_identity"
        private const val PREF_PUBLIC = "public_destination"
        private const val PREF_PRIVATE = "private_destination"
        private const val MAX_MESSAGE_SIZE = SamConnection.MAX_MESSAGE_SIZE
        private const val INITIAL_RECONNECT_DELAY_MS = 5_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L

        const val DEFAULT_SAM_HOST = SamConnection.DEFAULT_HOST
        const val DEFAULT_SAM_PORT = SamConnection.DEFAULT_PORT

        // BASE32 encoding
        private const val BASE32_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

        @Volatile
        private var singleton: I2PManager? = null

        private val _state = MutableStateFlow<I2PConnectionState>(
            I2PConnectionState.Disconnected
        )

        val state: StateFlow<I2PConnectionState> get() = _state

        @Synchronized
        fun init(context: Context) {
            init(context, DEFAULT_SAM_HOST, DEFAULT_SAM_PORT)
        }

        @Synchronized
        fun init(context: Context, host: String, port: Int) {
            if (singleton != null) {
                Log.d(TAG, "I2PManager already initialized")
                return
            }
            val manager = I2PManager(context.applicationContext, host, port)
            singleton = manager
            manager.start()
        }

        @Synchronized
        fun shutdown() {
            singleton?.stop()
            singleton = null
            _state.value = I2PConnectionState.Disconnected
        }

        fun getMyAddress(): String = singleton?.getPublicDestinationBase32().orEmpty()
        fun getMyPublicKey(): String? = singleton?.getPublicDestination()
        fun getInstance(): I2PManager? = singleton
        fun getSamConnection(): SamConnection? = singleton?.getSamConnectionSafe()
        fun getSessionId(): String = singleton?.getSessionId() ?: ""

        /** Конвертирует base64 (SAM format) в base32 адрес без суффикса .b32.i2p */
        fun base64ToBase32(base64: String): String {
            val clean = base64.trim()
            var standardBase64 = clean
                .replace('-', '+')
                .replace('~', '/')
            when (standardBase64.length % 4) {
                2 -> standardBase64 += "=="
                3 -> standardBase64 += "="
                0 -> { /* ok */ }
            }
            val destination = Base64.decode(standardBase64, Base64.DEFAULT)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(destination)
            return encodeBase32Internal(digest)
        }

        private fun encodeBase32Internal(bytes: ByteArray): String {
            val result = StringBuilder()
            var buffer = 0
            var bitsLeft = 0
            for (byte in bytes) {
                buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
                bitsLeft += 8
                while (bitsLeft >= 5) {
                    val index = (buffer shr (bitsLeft - 5)) and 0x1F
                    result.append(BASE32_ALPHABET[index])
                    bitsLeft -= 5
                }
            }
            if (bitsLeft > 0) {
                val index = (buffer shl (5 - bitsLeft)) and 0x1F
                result.append(BASE32_ALPHABET[index])
            }
            return result.toString()
        }

        fun addMessageListener(listener: (String, String) -> Unit) {
            singleton?.addMessageListenerInternal(listener)
        }

        fun removeMessageListener(listener: (String, String) -> Unit) {
            singleton?.removeMessageListenerInternal(listener)
        }

        fun sendMessage(destination: String, message: String, callback: (Boolean) -> Unit) {
            val manager = singleton ?: run { callback(false); return }
            manager.scope.launch {
                val result = manager.sendMessage(destination, message)
                callback(result)
            }
        }

        fun setPresenceManager(pm: PresenceManager) {
            singleton?.presenceManager = pm
        }
    }

    // =====================================================================
    // FIELDS — ПОЛЯ КЛАССА
    // =====================================================================

    // Корневая корутина-джоба всего менеджера. SupervisorJob — падение одной дочерней корутины не убивает остальные
    private val managerJob = SupervisorJob()
    // Scope для всех фоновых задач (reconnect, acceptLoop, отправка). Выполняется на IO диспетчере
    internal val scope = CoroutineScope(Dispatchers.IO + managerJob)

    // Низкоуровневое SAM-соединение (control-сокет + хелперы для STREAM CONNECT/ACCEPT)
    @Volatile
    private var samConnection = SamConnection(samHost, samPort)

    // Флаги состояния (AtomicBoolean — потокобезопасны без локов)
    private val connected = AtomicBoolean(false)       // TCP control-сокет открыт и HELLO прошёл
    private val sessionCreated = AtomicBoolean(false)  // STREAM-сессия (SESSION CREATE) успешно создана
    @Volatile private var sessionId: String = ""       // ID текущей STREAM-сессии (уникален для каждого reconnect)

    // Поколение control-сокета на момент создания ТЕКУЩЕЙ STREAM-сессии.
    // Увеличивается внутри SamConnection при каждом пересоздании control-сокета.
    // Если controlGenerationAtSession != samConnection.getControlGeneration() —
    // значит control-сокет был пересоздан "тихо" (например, после таймаута в ensureHelloLocked),
    // и текущий sessionId уже невалиден на стороне SAM — нужен полный реконнект.
    @Volatile
    private var controlGenerationAtSession = 0

    /**
     * Защита от параллельных реконнектов.
     * true = прямо сейчас выполняется establishSession() (под sessionMutex).
     * requestReconnect() делает compareAndSet(false, true) — если уже true, второй вызов игнорируется.
     * Сбрасывается в finally блоке establishSession().
     */
    private val rebuilding = AtomicBoolean(false)

    // Accept loop — постоянный цикл приёма входящих STREAM соединений
    private val acceptLoopRunning = AtomicBoolean(false)
    private var acceptJob: Job? = null
    private var reconnectJob: Job? = null

    // Мьютекс для критической секции establishSession — только один реконнект одновременно
    private val sessionMutex = Mutex()
    // Флаг того, что менеджер запущен (start() вызван, stop() — нет)
    private val started = AtomicBoolean(false)

    // Identity (приватный/публичный ключи дестинации), загружаются из SharedPreferences или генерируются
    @Volatile private var privateDestination: String? = null
    @Volatile private var publicDestination: String? = null

    // Слушатели входящих сообщений (UI, PresenceManager и т.д.)
    private val messageListeners = CopyOnWriteArrayList<(String, String) -> Unit>()
    @Volatile private var presenceManager: PresenceManager? = null

    // Колбэки для UI / логирования
    @Volatile private var onOwnDestinationChanged: ((String) -> Unit)? = null
    @Volatile private var onMessageReceived: ((String, String) -> Boolean)? = null
    @Volatile private var onDestinationDiscovered: ((String) -> Unit)? = null
    @Volatile private var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    @Volatile private var onLog: ((String) -> Unit)? = null

    // =====================================================================
    // CALLBACKS
    // =====================================================================

    fun setOnMessageReceived(callback: (String, String) -> Boolean) { onMessageReceived = callback }
    fun setOnDestinationDiscovered(callback: (String) -> Unit) { onDestinationDiscovered = callback }
    fun setOnOwnDestinationChanged(callback: (String) -> Unit) { onOwnDestinationChanged = callback }
    fun setOnConnectionStateChanged(callback: (Boolean) -> Unit) { onConnectionStateChanged = callback }
    fun setOnLog(callback: (String) -> Unit) { onLog = callback }

    private fun addMessageListenerInternal(listener: (String, String) -> Unit) {
        if (!messageListeners.contains(listener)) messageListeners.add(listener)
    }

    private fun removeMessageListenerInternal(listener: (String, String) -> Unit) {
        messageListeners.remove(listener)
    }

    // =====================================================================
    // GET SAM CONNECTION
    // =====================================================================

    private fun getSamConnectionSafe(): SamConnection {
        return samConnection
    }

    // =====================================================================
    // LIFECYCLE — УПРАВЛЕНИЕ ЖИЗНЕННЫМ ЦИКЛОМ
    // =====================================================================

    /**
     * Запускает менеджер. Вызывается из I2PService.onCreate().
     * Если уже запущен — делает вид, что ничего не делал.
     * Загружает identity (ключи) из SharedPreferences и запускает connect-reconnect цикл.
     */
    @Synchronized
    fun start() {
        // compareAndSet — атомарная проверка "запущен ли уже"
        if (!started.compareAndSet(false, true)) {
            Log.d(TAG, "start(): already started")
            return
        }

        Log.d(TAG, "🚀 I2PManager lifecycle started")

        // Если identity не загружена — пытаемся прочитать из SharedPreferences
        if (privateDestination.isNullOrBlank()) {
            loadSavedDestination()
        }

        // Запускаем цикл реконнектов
        requestConnection()
    }

    /**
     * Останавливает менеджер и освобождает все ресурсы.
     * Вызывается из I2PService.onDestroy().
     * Отправляет SESSION REMOVE SAM-мосту, затем закрывает control-сокет.
     */
    @Synchronized
    fun stop() {
        if (!started.compareAndSet(true, false)) {
            return
        }

        Log.d(TAG, "🛑 I2PManager stop()")

        // Останавливаем reconnect и accept loop
        reconnectJob?.cancel()
        reconnectJob = null
        acceptLoopRunning.set(false)
        acceptJob?.cancel()
        acceptJob = null

        // Уведомляем SAM-мост о закрытии сессии (хороший тон)
        if (sessionId.isNotEmpty()) {
            scope.launch {
                try {
                    samConnection.removeSession(sessionId)
                    Log.d(TAG, "✅ SESSION REMOVE отправлен для $sessionId")
                } catch (t: Throwable) {
                    Log.w(TAG, "SESSION REMOVE failed", t)
                }
            }
        }

        sessionCreated.set(false)
        setConnected(false)
        setState(I2PConnectionState.Disconnected)

        // Асинхронно закрываем control-сокет, затем убиваем все корутины
        val oldSam = samConnection
        scope.launch {
            try {
                oldSam.disconnect()
            } catch (t: Throwable) {
                Log.w(TAG, "SAM disconnect failed: ${t.message}")
            } finally {
                managerJob.cancel() // убиваем все дочерние корутины
            }
        }
    }

    /** Альтернативный stop с немедленным убийством корутин (не используется в текущем коде) */
    fun destroy() {
        stop()
        managerJob.cancel()
    }

    // =====================================================================
    // REQUEST CONNECTION — ЦИКЛ РЕКОННЕКТОВ
    // =====================================================================

    /**
     * Запускает фоновую корутину, которая пытается установить сессию с SAM-мостом.
     * При неудаче — экспоненциально увеличивает задержку (5с → 10с → 20с → ... → 60с).
     * Цикл крутится, пока не удастся установить соединение или пока менеджер не остановлен.
     */
    private fun requestConnection() {
        if (!scope.isActive || !started.get()) return

        setState(I2PConnectionState.Connecting)

        // Если реконнект уже идёт — не запускаем второй
        if (reconnectJob?.isActive == true) return

        reconnectJob = scope.launch {
            var delayMs = 0L

            while (isActive && started.get()) {
                if (delayMs > 0L) {
                    delay(delayMs)
                }

                try {
                    if (establishSession()) {
                        setState(I2PConnectionState.Connected)
                        Log.d(TAG, "🟢 SAM/I2P session established")
                        break
                    }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    Log.e(TAG, "Connection attempt failed", t)
                }

                setConnected(false)
                setState(I2PConnectionState.Error("SAM/I2P connection lost"))
                // Экспоненциальный backoff: 5с → 10с → 20с → ... → 60с макс
                delayMs = if (delayMs == 0L) INITIAL_RECONNECT_DELAY_MS
                           else minOf(MAX_RECONNECT_DELAY_MS, delayMs * 2)
                Log.w(TAG, "🔄 reconnect in ${delayMs}ms")
            }
        }
    }

    // =====================================================================
    // ESTABLISH SESSION — УСТАНОВКА СЕССИИ
    // =====================================================================

    /**
     * Полностью пересоздаёт SAM-соединение: control-сокет + STREAM-сессия.
     * Выполняется под sessionMutex — только один раз за раз.
     *
     * Порядок:
     * 1. Останавливаем старый acceptLoop
     * 2. Закрываем старый control-сокет, удаляем старую STREAM-сессию из SAM
     * 3. Создаём новый SamConnection (новый control-сокет)
     * 4. connect() — TCP к SAM-мосту
     * 5. hello() — SAM протокол HELLO
     * 6. ensureDestinationWithSam() — загружаем или генерируем identity
     * 7. createStreamSession() — создаём STREAM-сессию
     * 8. Запоминаем controlGenerationAtSession — поколение control-сокета
     * 9. Запускаем acceptLoop()
     *
     * Если любой шаг провалился — сессия не создаётся, возвращаем false → backoff.
     */
    private suspend fun establishSession(): Boolean {
        return sessionMutex.withLock {
            // Уже подключены — ничего делать не нужно
            if (!started.get()) return@withLock false
            if (connected.get() && sessionCreated.get()) return@withLock true

            // Останавливаем старый acceptLoop перед пересозданием
            acceptLoopRunning.set(false)
            acceptJob?.cancel()
            acceptJob = null

            val oldSam = samConnection

            // Удаляем старую STREAM-сессию из SAM-моста (хороший тон)
            if (sessionId.isNotEmpty()) {
                try {
                    oldSam.removeSession(sessionId)
                    Log.d(TAG, "Removed old session $sessionId")
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to remove old session", t)
                }
            }

            // Закрываем старый control-сокет
            try { oldSam.disconnect() } catch (_: Throwable) {}

            // Создаём НОВЫЙ SamConnection — это новый control-сокет
            val newSam = SamConnection(samHost, samPort)
            samConnection = newSam
            sessionCreated.set(false)
            sessionId = ""

            try {
                Log.d(TAG, "🔌 Connecting to SAM...")
                if (!newSam.connect()) return@withLock false

                val hello = newSam.hello()
                if (!isOk(hello)) return@withLock false

                if (!ensureDestinationWithSam(newSam)) return@withLock false

                val newId = newSessionId()
                val privateKey = privateDestination ?: return@withLock false

                if (!newSam.createStreamSession(newId, privateKey)) {
                    Log.e(TAG, "❌ SESSION CREATE failed")
                    return@withLock false
                }

                sessionId = newId
                // Запоминаем поколение control-сокета на момент создания сессии
                controlGenerationAtSession = newSam.getControlGeneration()
                sessionCreated.set(true)
                setConnected(true)

                // Запускаем ACCEPT луп для приёма входящих
                startAcceptLoop()

                true
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.e(TAG, "establishSession failed", t)
                false
            } finally {
                // Если сессия не создалась — закрываем временный SamConnection
                if (!sessionCreated.get()) {
                    try { newSam.disconnect() } catch (_: Throwable) {}
                }
                rebuilding.set(false) // сбрасываем флаг реконнекта
            }
        }
    }

    /**
     * Запрашивает полный реконнект (пересоздание control-сокета + STREAM-сессии).
     *
     * Вызывается из:
     * - acceptLoop при обнаружении мёртвого control-сокета или смены поколения
     * - sendMessage при получении SESSION_INVALID от SAM
     * - health check при недоступности SAM-моста
     *
     * protect от параллельных вызовов через rebuilding atomic flag.
     */
    private fun requestReconnect(reason: String) {
        if (!started.get() || !scope.isActive) return

        // Только один реконнект одновременно — если уже идём в establishSession(), пропускаем
        if (!rebuilding.compareAndSet(false, true)) {
            Log.d(TAG, "🔄 reconnect уже в процессе, пропускаем: $reason")
            return
        }

        Log.w(TAG, "🔄 reconnect requested: $reason")

        // СНАЧАЛА синхронно останавливаем acceptLoop, чтобы он не мешал establishSession()
        acceptLoopRunning.set(false)
        acceptJob?.cancel()
        acceptJob = null
        samConnection.closeActiveAcceptSocket()

        // Асинхронно закрываем control-сокет (не ждём)
        scope.launch {
            try {
                samConnection.disconnect()
            } catch (t: Throwable) {
                Log.w(TAG, "disconnect during reconnect failed", t)
            }
        }
        // Сбрасываем флаги и запускаем цикл реконнектов с backoff
        connected.set(false)
        sessionCreated.set(false)
        setState(I2PConnectionState.Connecting)
        requestConnection()
    }

    // =====================================================================
    // ENSURE DESTINATION — ИДЕНТИЧНОСТЬ (КЛЮЧИ)
    // =====================================================================

    /**
     * Гарантирует, что у нас есть identity (приватный и публичный ключи).
     * Порядок:
     * 1. Если уже есть — возвращаем true
     * 2. Если сохранены в SharedPreferences — загружаем
     * 3. Если нет — генерируем новые через SAM (DEST GENERATE) и сохраняем
     *
     * Identity используется при создании STREAM-сессии (SESSION CREATE DESTINATION=...)
     */
    private suspend fun ensureDestinationWithSam(sam: SamConnection): Boolean {
        // Уже есть identity в памяти — ничего делать не нужно
        if (!privateDestination.isNullOrBlank() && !publicDestination.isNullOrBlank()) {
            return true
        }

        // Пробуем загрузить из SharedPreferences
        if (loadSavedDestination()) {
            return true
        }

        // Генерируем новый identity через SAM
        Log.d(TAG, "🔍 [IM] генерируем новый Destination...")
        val generated = sam.generateDestination() ?: return false

        publicDestination = generated.publicDestination
        privateDestination = generated.privateKey

        saveDestinationToPreferences(generated.publicDestination, generated.privateKey)

        // Уведомляем UI (обновляем навигацию)
        try {
            onOwnDestinationChanged?.invoke(generated.publicDestination)
        } catch (_: Exception) {}

        Log.d(TAG, "🔍 [IM] ✅ Destination сгенерирован")
        return true
    }

    // =====================================================================
    // DESTINATION — СОХРАНЕНИЕ И ЗАГРУЗКА IDENTITY
    // =====================================================================

    /**
     * Загружает identity из SharedPreferences.
     * Если ключи не найдены — возвращает false (будем генерировать новые).
     */
    private fun loadSavedDestination(): Boolean {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val pub = prefs.getString(PREF_PUBLIC, null)
            val priv = prefs.getString(PREF_PRIVATE, null)

            if (pub.isNullOrBlank() || priv.isNullOrBlank()) {
                return false
            }

            publicDestination = pub
            privateDestination = priv

            try {
                onOwnDestinationChanged?.invoke(pub)
            } catch (_: Exception) {}

            Log.d(TAG, "🔍 [IM] ✅ загружен из preferences")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "🔍 [IM] ❌ ошибка загрузки", e)
            return false
        }
    }

    /**
     * Сохраняет identity в SharedPreferences.
     * Вызывается после генерации нового ключа (DEST GENERATE).
     */
    private fun saveDestinationToPreferences(publicValue: String, privateValue: String) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_PUBLIC, publicValue)
                .putString(PREF_PRIVATE, privateValue)
                .apply()
            Log.d(TAG, "🔍 [IM] ✅ сохранён в preferences")
        } catch (e: Exception) {
            Log.e(TAG, "🔍 [IM] ❌ ошибка сохранения", e)
        }
    }

    /** Публичный метод для ручного восстановления identity (не используется в текущем коде) */
    fun restoreIdentity(): Boolean = loadSavedDestination()

    // =====================================================================
    // ACCEPT LOOP — ПРИЁМ ВХОДЯЩИХ СООБЩЕНИЙ
    // =====================================================================

    /**
     * Запускает acceptLoop в фоне (с задержкой 500мс).
     * Accept loop — это вечный цикл, который ждёт входящих STREAM соединений от других I2P-узлов.
     *
     * ВАЖНО: acceptLoop работает на отдельном сокетном соединении с SAM-мостом
     * (не на control-сокете). Каждый входящий peer создаёт новый TCP сокет.
     */
    @Synchronized
    private fun startAcceptLoop() {
        if (acceptLoopRunning.get()) {
            return
        }

        acceptLoopRunning.set(true)

        acceptJob = scope.launch {
            delay(500L) // небольшая пауза перед стартом, чтобы не мешать установке сессии
            acceptLoop()
        }

        Log.d(TAG, "🔍 [IM] [ACCEPT] loop started")
    }

    /**
     * Главный цикл приёма входящих сообщений.
     *
     * Механика:
     * 1. Каждый оборот открывает НОВЫЙ TCP-сокет к SAM-мосту
     * 2. Делает HELLO на этом сокете
     * 3. Отправляет STREAM ACCEPT ID=... (ожидает входящее соединение)
     * 4. Если кто-то подключился — читает фрейм (4 байта длина + payload)
     * 5. Передаёт сообщение в onMessageReceived (PresenceManager) и messageListeners
     *
     * Обработка результатов:
     * - Accepted — сообщение обработано, сбрасываем failures
     * - IdleTimeout — никто не пришёл (нормально), НЕ считаем ошибкой
     * - Rejected (INVALID_ID и т.д.) — сессия мертва, запрашиваем реконнект
     * - Failed — реальная ошибка, инкрементируем failures, при 5 ошибках — реконнект
     *
     * Health check каждые 30 секунд: проверяет, что SAM-мост вообще отвечает (отдельный сокет).
     */
    private suspend fun acceptLoop() {
        var failures = 0
        val ACCEPT_FAILURE_THRESHOLD = 5 // Через сколько ошибок запрашиваем реконнект
        var lastHealthCheck = 0L
        val HEALTH_CHECK_INTERVAL_MS = 30_000L // Проверка доступности SAM-моста каждые 30 сек

        while (scope.isActive && acceptLoopRunning.get()) {
            // Если сессия не создана или disconnected — ждём
            if (!connected.get() || !sessionCreated.get()) {
                delay(1_000L)
                continue
            }

            // Проактивная проверка: SAM-мост жив? (независимый сокет, не трогает control)
            val now = System.currentTimeMillis()
            if (now - lastHealthCheck >= HEALTH_CHECK_INTERVAL_MS) {
                lastHealthCheck = now
                if (!samConnection.checkSamAvailable()) {
                    Log.w(TAG, "🔍 [IM] SAM bridge unavailable (health check failed), requesting reconnect")
                    requestReconnect("SAM bridge health check failed")
                    break
                }
            }

            val sam = samConnection

            try {
                when (val result = sam.acceptStream(sessionId)) {
                    is SamConnection.AcceptResult.Accepted -> {
                        // Кто-то подключился — обрабатываем
                        failures = 0
                        try {
                            handleAcceptedStream(result.stream)
                        } finally {
                            result.stream.close() // Закрываем сокет после обработки
                        }
                    }

                    SamConnection.AcceptResult.IdleTimeout -> {
                        // Никто не подключился за 5 минут — это НОРМАЛЬНО, не считаем ошибкой
                        // Idle timeout — это нормально (нет входящих соединений),
                        // НЕ инкрементим failures. failures считаем только для реальных ошибок.
                        if (!sam.isControlConnectionAlive() ||
                            sam.getControlGeneration() != controlGenerationAtSession) {
                            requestReconnect("SAM control connection lost during ACCEPT")
                            break
                        }
                        delay(1_000L) // небольшая пауза перед следующим ACCEPT
                    }

                    is SamConnection.AcceptResult.Rejected -> {
                        // SAM вернул ошибку (INVALID_ID, CANT_REACH_PEER и т.д.)
                        // Это значит, что сессия мертва — запрашиваем реконнект
                        Log.w(TAG, "🔍 [IM] STREAM ACCEPT rejected: ${result.samResult}")
                        requestReconnect("STREAM ACCEPT rejected: ${result.samResult}")
                        break
                    }

                    is SamConnection.AcceptResult.Failed -> {
                        failures++
                        Log.e(TAG, "🔍 [IM] acceptStream ошибка: ${result.message}")
                        if (!sam.isControlConnectionAlive() ||
                            sam.getControlGeneration() != controlGenerationAtSession ||
                            failures >= ACCEPT_FAILURE_THRESHOLD) {
                            requestReconnect("ACCEPT loop failure: ${result.message}")
                            break
                        }
                        delay(minOf(5_000L, 500L * failures)) // Backoff при ошибках
                    }
                }
            } catch (e: Exception) {
                if (scope.isActive && acceptLoopRunning.get()) {
                    failures++
                    Log.e(TAG, "🔍 [IM] acceptStream ошибка: ${e.message}")
                    if (!sam.isControlConnectionAlive() ||
                        sam.getControlGeneration() != controlGenerationAtSession ||
                        failures >= ACCEPT_FAILURE_THRESHOLD) {
                        requestReconnect("ACCEPT loop failure: ${e.message}")
                        break
                    }
                    delay(minOf(5_000L, 500L * failures))
                }
            }
        }

        Log.d(TAG, "🔍 [IM] acceptLoop() FINISH")
    }

    // =====================================================================
    // HANDLE INCOMING STREAM — ОБРАБОТКА ВХОДЯЩЕГО СООБЩЕНИЯ
    // =====================================================================

    /**
     * Обрабатывает входящее STREAM соединение от другого I2P-узла.
     *
     * Этапы:
     * 1. Уведомляем onDestinationDiscovered (обновляем список контактов)
     * 2. Читаем framed message (4 байта длина + payload)
     * 3. Передаём в onMessageReceived (PresenceManager обрабатывает PRESENCE-пакеты)
     * 4. Если не потреблено — разсылаем messageListeners (ChatActivity и др.)
     *
     * @param stream — AcceptedStream объект, содержащий senderDestination, input stream и socket
     */
    private suspend fun handleAcceptedStream(stream: SamConnection.AcceptedStream) {
        val sender = stream.senderDestination
        Log.d(TAG, "🔍 [IM] ===== handleAcceptedStream() =====")
        Log.d(TAG, "🔍 [IM] sender: ${sender.take(32)}...")

        // Уведомляем, что нашли новый peer (добавим в контакты если нужно)
        try {
            onDestinationDiscovered?.invoke(sender)
        } catch (e: Exception) {
            Log.e(TAG, "🔍 [IM] onDestinationDiscovered ошибка", e)
        }

        // Читаем framed message (4 байта big-endian int длина + payload)
        val data = samConnection.readFramedMessage(stream.input)
        if (data == null) {
            Log.w(TAG, "🔍 [IM] invalid frame")
            return
        }

        val message = String(data, StandardCharsets.UTF_8)
        Log.d(TAG, "🔍 [IM] получено сообщение: ${data.size} bytes")

        // Сначала даём шанс системным обработчикам (PresenceManager для PRESENCE-пакетов)
        var consumed = false

        try {
            consumed = onMessageReceived?.invoke(sender, message) == true
            Log.d(TAG, "🔍 [IM] onMessageReceived вернул $consumed")
        } catch (e: Exception) {
            Log.e(TAG, "🔍 [IM] onMessageReceived ошибка", e)
        }

        // Если системный обработчик потребил — больше ничего не шлём
        if (consumed) {
            Log.d(TAG, "🔍 [IM] сообщение обработано системой")
            return
        }

        // Разсылаем всем UI-слушателям (ChatActivity подписывается через addMessageListener)
        for (listener in messageListeners) {
            try {
                listener(sender, message)
            } catch (e: Exception) {
                Log.e(TAG, "🔍 [IM] ошибка в слушателе", e)
            }
        }
    }

    // =====================================================================
    // SEND MESSAGE — ОТПРАВКА СООБЩЕНИЙ
    // =====================================================================

    /**
     * Отправляет сообщение на указанный destination.
     *
     * Механика:
     * 1. Проверяем, что подключены и сессия создана
     * 2. resolveDestination() — конвертируем base32 адрес в base64 destination (с кешем)
     * 3. Проверяем поколение control-сокета (если изменилось — реконнект)
     * 4. createStreamSocket() — открываем НОВЫЙ TCP-сокет к SAM-мосту
     * 5. STREAM CONNECT ID=... DEST=... → если INVALID_ID — реконнект
     * 6. writeFrame(payload) → отправляем фрейм (4 байта длина + данные)
     * 7. Закрываем сокет (короткоживущий, не переиспользуем)
     *
     * Результаты:
     * - SENT → true
     * - SESSION_INVALID → requestReconnect(), false
     * - PEER_UNREACHABLE → false (сессия жива, пир оффлайн)
     * - FAILED → проверка control-сокета → реконнект или false
     */
    suspend fun sendMessage(destination: String, message: String): Boolean {
        Log.d(TAG, "🔍 [IM] ===== sendMessage() START =====")

        // Валидация
        if (message.isEmpty()) {
            Log.w(TAG, "🔍 [IM] ❌ message пустое")
            return false
        }

        val payload = message.toByteArray(StandardCharsets.UTF_8)
        if (payload.size > MAX_MESSAGE_SIZE) {
            Log.w(TAG, "🔍 [IM] ❌ сообщение слишком большое")
            return false
        }

        // Проверяем, что подключены
        if (!connected.get() || !sessionCreated.get()) {
            Log.w(TAG, "🔍 [IM] ❌ not connected")
            return false
        }

        // Разрешаем base32 адрес в base64 destination
        val resolved = resolveDestination(destination)
        if (resolved == null) {
            Log.w(TAG, "🔍 [IM] ❌ не удалось разрешить destination")
            return false
        }

        // Проверяем поколение control-сокета ПЕРЕД отправкой — если изменилось,
        // значит control-сокет был пересоздан, а sessionId уже невалиден
        val sam = samConnection
        if (sam.getControlGeneration() != controlGenerationAtSession) {
            Log.w(TAG, "🔍 [IM] control generation changed (${sam.getControlGeneration()} != $controlGenerationAtSession), requesting reconnect")
            requestReconnect("control generation changed before send")
            return false
        }

        Log.d(TAG, "🔍 [IM] отправка на ${resolved.take(32)}...")

        // Отправляем через sendStreamMessage (внутри создаётся и закрывается STREAM CONNECT сокет)
        val sendResult = sam.sendStreamMessage(
            sessionId,
            resolved,
            message
        ) { log ->
            Log.d(TAG, "🔍 [IM] callback: $log")
        }

        // Обрабатываем результат
        val ok = when (sendResult) {
            SamConnection.StreamSendResult.SENT -> true

            SamConnection.StreamSendResult.SESSION_INVALID -> {
                // SAM вернул INVALID_ID — сессия мертва, запрашиваем реконнект
                Log.w(TAG, "🔍 [IM] STREAM session is dead (INVALID_ID); requesting reconnect")
                requestReconnect("STREAM session invalid during send")
                false
            }

            SamConnection.StreamSendResult.PEER_UNREACHABLE -> {
                // Пир оффлайн/недоступен, но сессия жива — просто возвращаем false
                Log.w(TAG, "🔍 [IM] peer unreachable; keeping SAM session alive")
                false
            }

            SamConnection.StreamSendResult.FAILED -> {
                // Общая ошибка — проверяем, жив ли control-сокет
                // НЕ шлём SESSION STATUS на control-сокет — это ломает соединение.
                // Если control-сокет мёртв или поколение изменилось — реконнект.
                if (!sam.isControlConnectionAlive() || sam.getControlGeneration() != controlGenerationAtSession) {
                    requestReconnect("send failed and SAM control connection unusable")
                } else {
                    Log.w(TAG, "🔍 [IM] outbound STREAM failed; keeping SAM session alive")
                }
                false
            }
        }

        Log.d(TAG, "🔍 [IM] sendMessage вернул $ok")
        return ok
    }

    // =====================================================================
    // DESTINATION RESOLUTION — РАЗРЕШЕНИЕ АДРЕСОВ
    // =====================================================================

    /**
     * Разрешает base32 адрес (khdy7cxq...) в base64 destination.
     *
     * SAM-протокол: NAMING LOOKUP NAME=<base32>
     * Возвращает raw base64 destination (VEgoBVRVYwfTwyyvoU62gRZ2FA9CmRQ8YzPvH4XdrqZUSCgFVFVjB9PDLK-hTra...)
     *
     * Важно:
     * - Если адрес уже содержит '=' или длиннее 100 символов — это уже base64 destination, возвращаем как есть
     * - Кеш делается в ContactRepository.resolveDestination(), здесь только SAM-запрос
     */
    private suspend fun resolveDestination(destination: String): String? {
        val value = destination.trim()
        if (value.isEmpty()) {
            return null
        }

        // Уже base64 destination (содержит '=' или длиннее 100) — возвращаем как есть
        if (value.contains("=") || value.length >= 100) {
            return value
        }

        val sam = samConnection
        val result = sam.lookupDestination(value)
        Log.d(TAG, "🔍 [IM] lookupDestination вернул ${if (result != null) "ключ" else "null"}")
        return result
    }

    // =====================================================================
    // STATE — УПРАВЛЕНИЕ СОСТОЯНИЕМ
    // =====================================================================

    /**
     * Обновляет статус подключения и запускает state flow.
     * called из establishSession (connected=true) и requestReconnect (connected=false).
     */
    private fun setConnected(value: Boolean) {
        // getAndSet — атомарно, если значение не изменилось — выходим
        if (connected.getAndSet(value) == value) {
            return
        }

        val state = if (value) I2PConnectionState.Connected else I2PConnectionState.Disconnected
        setState(state)

        Log.d(TAG, "🔍 [IM] setConnected: ${if (value) "🟢 ONLINE" else "🔴 OFFLINE"}")

        // Уведомляем UI (обновляем статус в drawer)
        try {
            onConnectionStateChanged?.invoke(value)
        } catch (_: Exception) {}
    }

    /** Обновляет state flow — подписчики (MainActivity) узнают об изменении */
    private fun setState(newState: I2PConnectionState) {
        _state.value = newState
    }

    // Публичные методы доступа к состоянию
    fun isConnected(): Boolean = connected.get() && sessionCreated.get()
    fun getPublicDestination(): String? = publicDestination
    fun getPrivateDestination(): String? = privateDestination
    fun getSessionId(): String = sessionId

    // =====================================================================
    // BASE32 — КОНВЕРТАЦИЯ АДРЕСОВ
    // =====================================================================

    /**
     * Конвертирует public destination (base64) в base32 адрес для отображения.
     *
     * Это обратная операция: publicDestination (base64) → SHA-256 → base32
     * base32 адрес — это то, что показывают пользователю (khdy7cxq...)
     *
     * Пример: VEgoBVRVYwfTwyyvoU62gRZ2FA9CmRQ8YzPvH4XdrqZUSCgFVFVjB9PDLK... → khdy7cxqh2tonqcsyhmcybklvz262ajmbqnbxkcw5c6zcgwhv3eq
     */
    private fun getPublicDestinationBase32(): String {
        val public = publicDestination ?: return ""

        return try {
            // SAM использует URL-safe base64 (- и ~ вместо + и /)
            val clean = public.trim().replace("-", "+").replace("~", "/")
            // Добавляем padding
            val padded = when (clean.length % 4) {
                2 -> clean + "=="
                3 -> clean + "="
                else -> clean
            }
            // Декодируем base64
            val destinationBytes = Base64.decode(padded, Base64.DEFAULT)
            // Вычисляем SHA-256 хеш
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(destinationBytes)
            // Кодируем в base32
            encodeBase32Internal(digest)
        } catch (e: Exception) {
            Log.e(TAG, "🔍 [IM] Base32 error", e)
            ""
        }
    }

    // =====================================================================
    // HELPERS — ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // =====================================================================

    /** Проверяет, что SAM-ответ содержит "RESULT=OK" */
    private fun isOk(response: String?): Boolean = response?.contains("RESULT=OK") == true

    /** Генерирует уникальный ID для STREAM-сессии */
    private fun newSessionId(): String = "i2pmessenger-${UUID.randomUUID()}"

    /** Логирование с вызовом onLog callback (для UI) */
    private fun log(message: String) {
        Log.d(TAG, message)
        try {
            onLog?.invoke(message)
        } catch (_: Exception) {}
    }
}