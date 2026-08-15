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
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class I2PManager(
    private val context: Context
) {

    companion object {
        private const val TAG = "I2PManager"
        private const val PREFS = "i2p_identity"
        private const val PREF_PUBLIC = "public_destination"
        private const val PREF_PRIVATE = "private_destination"
        private const val MAX_MESSAGE_SIZE = SamConnection.MAX_MESSAGE_SIZE
        private const val INITIAL_RECONNECT_DELAY_MS = 2_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val ACCEPT_FAILURE_THRESHOLD = 3

        @Volatile
        private var singleton: I2PManager? = null

        private val _state = MutableStateFlow<I2PConnectionState>(
            I2PConnectionState.Disconnected
        )

        val state: StateFlow<I2PConnectionState> get() = _state

        @Synchronized
        fun init(context: Context) {
            if (singleton != null) {
                Log.d(TAG, "I2PManager already initialized")
                return
            }
            val manager = I2PManager(context.applicationContext)
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
    // FIELDS
    // =====================================================================

    private val managerJob = SupervisorJob()
    internal val scope = CoroutineScope(Dispatchers.IO + managerJob)

    @Volatile
    private var samConnection = SamConnection()

    private val connected = AtomicBoolean(false)
    private val reconnectInProgress = AtomicBoolean(false)
    private val sessionCreated = AtomicBoolean(false)
    @Volatile private var sessionId: String = ""

    private val acceptLoopRunning = AtomicBoolean(false)
    private var acceptJob: Job? = null
    private var reconnectJob: Job? = null

    private val sessionMutex = Mutex()
    private val started = AtomicBoolean(false)
    private val reconnectRequested = AtomicBoolean(false)

    @Volatile private var privateDestination: String? = null
    @Volatile private var publicDestination: String? = null

    private val messageListeners = CopyOnWriteArrayList<(String, String) -> Unit>()
    @Volatile private var presenceManager: PresenceManager? = null

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
    // LIFECYCLE
    // =====================================================================

    @Synchronized
    fun start() {
        if (!started.compareAndSet(false, true)) {
            Log.d(TAG, "start(): already started")
            return
        }

        Log.d(TAG, "🚀 I2PManager lifecycle started")

        if (privateDestination.isNullOrBlank()) {
            loadSavedDestination()
        }

        requestConnection()
    }

    private fun requestConnection() {
        if (!scope.isActive || !started.get()) return

        setState(I2PConnectionState.Connecting)

        if (reconnectJob?.isActive == true) return

        reconnectJob = scope.launch {
            var delayMs = 0L

            while (isActive && started.get()) {
                if (delayMs > 0L) {
                    delay(delayMs)
                }

                try {
                    if (establishSession()) {
                        // establishSession() is authoritative. Once the new
                        // control socket + STREAM session + ACCEPT loop are
                        // ready, keep them. Do not immediately tear them down
                        // just because a previous failure requested reconnect.
                        reconnectRequested.set(false)
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
                delayMs = if (delayMs == 0L) INITIAL_RECONNECT_DELAY_MS
                           else minOf(MAX_RECONNECT_DELAY_MS, delayMs * 2)
                Log.w(TAG, "🔄 reconnect in ${delayMs}ms")
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!started.compareAndSet(true, false)) {
            return
        }

        Log.d(TAG, "🛑 I2PManager stop()")

        reconnectJob?.cancel()
        reconnectJob = null
        reconnectRequested.set(false)

        acceptLoopRunning.set(false)
        samConnection.closeActiveAcceptSocket()
        acceptJob?.cancel()
        acceptJob = null

        sessionCreated.set(false)
        setConnected(false)
        setState(I2PConnectionState.Disconnected)

        val oldSam = samConnection
        scope.launch {
            try {
                oldSam.disconnect()
            } catch (t: Throwable) {
                Log.w(TAG, "SAM disconnect failed: ${t.message}")
            } finally {
                managerJob.cancel()
            }
        }
    }

    fun destroy() {
        stop()
        managerJob.cancel()
    }

    // =====================================================================
    // CONNECTION / RECONNECT
    // =====================================================================

    private suspend fun establishSession(): Boolean {
        return sessionMutex.withLock {
            if (!started.get()) return@withLock false
            if (connected.get() && sessionCreated.get()) return@withLock true

            acceptLoopRunning.set(false)
            samConnection.closeActiveAcceptSocket()
            acceptJob?.cancel()
            acceptJob = null

            val oldSam = samConnection
            try { oldSam.disconnect() } catch (_: Throwable) {}

            val newSam = SamConnection()
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
                    return@withLock false
                }

                sessionId = newId
                sessionCreated.set(true)
                setConnected(true)
                startAcceptLoop()
                true
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.e(TAG, "establishSession failed", t)
                false
            } finally {
                if (!sessionCreated.get()) {
                    try { newSam.disconnect() } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun requestReconnect(reason: String) {
        if (!started.get() || !scope.isActive) return

        Log.w(TAG, "🔄 reconnect requested: $reason")
        reconnectRequested.set(true)
        connected.set(false)
        sessionCreated.set(false)
        setState(I2PConnectionState.Connecting)
        requestConnection()
    }

    // =====================================================================
    // ENSURE DESTINATION
    // =====================================================================

    private suspend fun ensureDestinationWithSam(sam: SamConnection): Boolean {
        if (!privateDestination.isNullOrBlank() && !publicDestination.isNullOrBlank()) {
            return true
        }

        if (loadSavedDestination()) {
            return true
        }

        Log.d(TAG, "🔍 [IM] генерируем новый Destination...")
        val generated = sam.generateDestination() ?: return false

        publicDestination = generated.publicDestination
        privateDestination = generated.privateKey

        saveDestinationToPreferences(generated.publicDestination, generated.privateKey)

        try {
            onOwnDestinationChanged?.invoke(generated.publicDestination)
        } catch (_: Exception) {}

        Log.d(TAG, "🔍 [IM] ✅ Destination сгенерирован")
        return true
    }

    // =====================================================================
    // CREATE SESSION
    // =====================================================================

    private suspend fun createFullSession(): Boolean {
        Log.d(TAG, "🔍 [IM] ===== createFullSession() START =====")

        if (connected.get() && sessionCreated.get()) {
            Log.d(TAG, "🔍 [IM] Already created")
            return true
        }

        sessionMutex.withLock {
            Log.d(TAG, "🔍 [IM] Starting full session creation...")

            val sam = samConnection

            Log.d(TAG, "🔍 [IM] [STEP1] Connecting to SAM...")
            if (!sam.connect()) {
                Log.e(TAG, "🔍 [IM] ❌ SAM connect failed")
                return false
            }
            Log.d(TAG, "🔍 [IM] [STEP1] ✅ connected")

            Log.d(TAG, "🔍 [IM] [STEP2] HELLO...")
            val helloResponse = sam.hello()
            if (!isOk(helloResponse)) {
                Log.e(TAG, "🔍 [IM] ❌ HELLO failed")
                return false
            }
            Log.d(TAG, "🔍 [IM] [STEP2] ✅ HELLO OK")

            Log.d(TAG, "🔍 [IM] [STEP3] Ensuring destination...")
            if (!ensureDestinationWithSam(sam)) {
                Log.e(TAG, "🔍 [IM] ❌ Destination failed")
                return false
            }
            Log.d(TAG, "🔍 [IM] [STEP3] ✅ destination OK")

            sessionId = newSessionId()
            Log.d(TAG, "🔍 [IM] [STEP4] Creating STREAM session: $sessionId")

            val sessionOk = sam.createStreamSession(
                sessionId,
                privateDestination ?: return false
            )

            if (!sessionOk) {
                Log.e(TAG, "🔍 [IM] ❌ SESSION CREATE failed")
                return false
            }

            Log.d(TAG, "🔍 [IM] [STEP4] ✅ SESSION CREATE OK")

            sessionCreated.set(true)
            setConnected(true)
            startAcceptLoop()

            Log.d(TAG, "🔍 [IM] ✅ Full session created")
            return true
        }
    }

    // =====================================================================
    // DESTINATION (ОБЩИЙ)
    // =====================================================================

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

    fun restoreIdentity(): Boolean = loadSavedDestination()

    // =====================================================================
    // ACCEPT LOOP
    // =====================================================================

    @Synchronized
    private fun startAcceptLoop() {
        if (acceptLoopRunning.get()) {
            return
        }

        acceptLoopRunning.set(true)

        acceptJob = scope.launch {
            delay(500L)
            acceptLoop()
        }

        Log.d(TAG, "🔍 [IM] [ACCEPT] loop started")
    }

    private suspend fun acceptLoop() {
        var failures = 0

        while (scope.isActive && acceptLoopRunning.get()) {
            if (!connected.get() || !sessionCreated.get()) {
                delay(1_000L)
                continue
            }

            var accepted: SamConnection.AcceptedStream? = null
            val sam = samConnection

            try {
                accepted = sam.acceptStream(sessionId)

                if (accepted == null) {
                    failures++

                    // A STREAM ACCEPT socket can legitimately time out while
                    // waiting for an incoming peer. That does NOT mean the
                    // SAM session is dead. Never send "SESSION STATUS ID=..."
                    // here: SESSION STATUS is a server response, not a SAM
                    // 3.1 client command.
                    if (!sam.isControlConnectionAlive()) {
                        requestReconnect("SAM control connection lost during ACCEPT")
                        break
                    }

                    delay(minOf(5_000L, 500L * failures))
                    continue
                }

                failures = 0
                handleAcceptedStream(accepted)

            } catch (e: Exception) {
                if (scope.isActive && acceptLoopRunning.get()) {
                    failures++
                    Log.e(TAG, "🔍 [IM] acceptStream ошибка: ${e.message}")
                    if (!sam.isControlConnectionAlive() || failures >= ACCEPT_FAILURE_THRESHOLD) {
                        requestReconnect("ACCEPT loop failure: ${e.message}")
                        break
                    }
                    delay(minOf(5_000L, 500L * failures))
                }
            } finally {
                accepted?.close()
            }
        }

        Log.d(TAG, "🔍 [IM] acceptLoop() FINISH")
    }

    // =====================================================================
    // HANDLE INCOMING STREAM
    // =====================================================================

    private suspend fun handleAcceptedStream(stream: SamConnection.AcceptedStream) {
        val sender = stream.senderDestination
        Log.d(TAG, "🔍 [IM] ===== handleAcceptedStream() =====")
        Log.d(TAG, "🔍 [IM] sender: ${sender.take(32)}...")

        try {
            onDestinationDiscovered?.invoke(sender)
        } catch (e: Exception) {
            Log.e(TAG, "🔍 [IM] onDestinationDiscovered ошибка", e)
        }

        val data = samConnection.readFramedMessage(stream.input)
        if (data == null) {
            Log.w(TAG, "🔍 [IM] invalid frame")
            return
        }

        val message = String(data, StandardCharsets.UTF_8)
        Log.d(TAG, "🔍 [IM] получено сообщение: ${data.size} bytes")

        var consumed = false

        try {
            consumed = onMessageReceived?.invoke(sender, message) == true
            Log.d(TAG, "🔍 [IM] onMessageReceived вернул $consumed")
        } catch (e: Exception) {
            Log.e(TAG, "🔍 [IM] onMessageReceived ошибка", e)
        }

        if (consumed) {
            Log.d(TAG, "🔍 [IM] сообщение обработано системой")
            return
        }

        for (listener in messageListeners) {
            try {
                listener(sender, message)
            } catch (e: Exception) {
                Log.e(TAG, "🔍 [IM] ошибка в слушателе", e)
            }
        }
    }

    // =====================================================================
    // SEND MESSAGE
    // =====================================================================

    suspend fun sendMessage(destination: String, message: String): Boolean {
        Log.d(TAG, "🔍 [IM] ===== sendMessage() START =====")

        if (message.isEmpty()) {
            Log.w(TAG, "🔍 [IM] ❌ message пустое")
            return false
        }

        val payload = message.toByteArray(StandardCharsets.UTF_8)
        if (payload.size > MAX_MESSAGE_SIZE) {
            Log.w(TAG, "🔍 [IM] ❌ сообщение слишком большое")
            return false
        }

        if (!connected.get() || !sessionCreated.get()) {
            Log.w(TAG, "🔍 [IM] ❌ not connected")
            return false
        }

        val resolved = resolveDestination(destination)
        if (resolved == null) {
            Log.w(TAG, "🔍 [IM] ❌ не удалось разрешить destination")
            return false
        }

        Log.d(TAG, "🔍 [IM] отправка на ${resolved.take(32)}...")

        val sam = samConnection
        val result = sam.sendStreamMessage(
            sessionId,
            resolved,
            message
        ) { log ->
            Log.d(TAG, "🔍 [IM] callback: $log")
        }

        if (!result) {
            // STREAM CONNECT failure belongs to this short-lived stream
            // socket. It is not proof that the long-lived SAM session died.
            // The accept loop is the component that observes a real session/
            // control-socket failure and requests a full rebuild.
            Log.w(TAG, "🔍 [IM] outbound STREAM failed; keeping SAM session alive")
        }

        Log.d(TAG, "🔍 [IM] sendMessage вернул $result")
        return result
    }

    // =====================================================================
    // DESTINATION RESOLUTION
    // =====================================================================

    private suspend fun resolveDestination(destination: String): String? {
        val value = destination.trim()
        if (value.isEmpty()) {
            return null
        }

        if (value.contains("=") || value.length >= 100) {
            return value
        }

        val sam = samConnection
        val result = sam.lookupDestination(value)
        Log.d(TAG, "🔍 [IM] lookupDestination вернул ${if (result != null) "ключ" else "null"}")
        return result
    }

    // =====================================================================
    // STATE
    // =====================================================================

    private fun setConnected(value: Boolean) {
        if (connected.getAndSet(value) == value) {
            return
        }

        val state = if (value) I2PConnectionState.Connected else I2PConnectionState.Disconnected
        setState(state)

        Log.d(TAG, "🔍 [IM] setConnected: ${if (value) "🟢 ONLINE" else "🔴 OFFLINE"}")

        try {
            onConnectionStateChanged?.invoke(value)
        } catch (_: Exception) {}
    }

    private fun setState(newState: I2PConnectionState) {
        _state.value = newState
    }

    fun isConnected(): Boolean = connected.get() && sessionCreated.get()
    fun getPublicDestination(): String? = publicDestination
    fun getPrivateDestination(): String? = privateDestination
    fun getSessionId(): String = sessionId

    // =====================================================================
    // BASE32
    // =====================================================================

    private fun getPublicDestinationBase32(): String {
        val public = publicDestination ?: return ""

        return try {
            val clean = public.trim().replace("-", "+").replace("~", "/")
            val padded = when (clean.length % 4) {
                2 -> clean + "=="
                3 -> clean + "="
                else -> clean
            }
            val destinationBytes = Base64.decode(padded, Base64.DEFAULT)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(destinationBytes)
            encodeBase32(digest)
        } catch (e: Exception) {
            Log.e(TAG, "🔍 [IM] Base32 error", e)
            ""
        }
    }

    private fun encodeBase32(bytes: ByteArray): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val result = StringBuilder()

        var buffer = 0
        var bitsLeft = 0

        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bitsLeft += 8

            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1f
                result.append(alphabet[index])
                bitsLeft -= 5
            }
        }

        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1f
            result.append(alphabet[index])
        }

        return result.toString()
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    private fun isOk(response: String?): Boolean = response?.contains("RESULT=OK") == true

    private fun newSessionId(): String = "i2pmessenger-${UUID.randomUUID()}"

    private fun log(message: String) {
        Log.d(TAG, message)
        try {
            onLog?.invoke(message)
        } catch (_: Exception) {}
    }
}