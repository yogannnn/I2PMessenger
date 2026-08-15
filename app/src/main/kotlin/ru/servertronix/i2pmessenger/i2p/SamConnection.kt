package ru.servertronix.i2pmessenger.i2p

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class SamConnection(
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT
) {

    companion object {
        private const val TAG = "SamConnection"

        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 7656

        private const val SAM_VERSION = "3.1"

        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val COMMAND_READ_TIMEOUT_MS = 180_000

        private const val STREAM_CONNECT_TIMEOUT_MS = 90_000
        private const val STREAM_ACCEPT_TIMEOUT_MS = 300_000
        private const val STREAM_READ_TIMEOUT_MS = 60_000

        private const val HEALTH_TIMEOUT_MS = 3_000

        const val MAX_MESSAGE_SIZE = 64 * 1024
        private const val MAX_SAM_LINE_SIZE = 512 * 1024
    }

    // =====================================================================
    // CONTROL CONNECTION
    // =====================================================================

    private var controlSocket: Socket? = null
    private var controlInput: InputStream? = null
    private var controlOutput: OutputStream? = null

    @Volatile
    private var helloCompleted = false

    @Volatile
    private var helloResponse: String? = null

    /**
     * Все команды через один control socket должны выполняться
     * последовательно.
     */
    private val commandMutex = Mutex()

    // =====================================================================
    // ACCEPT SOCKET
    // =====================================================================

    private val acceptSocketLock = Any()

    @Volatile
    private var activeAcceptSocket: Socket? = null

    // =====================================================================
    // DATA CLASSES
    // =====================================================================

    data class GeneratedDestination(
        val publicDestination: String,
        val privateKey: String
    )

    data class AcceptedStream(
        val senderDestination: String,
        val input: InputStream,
        private val socket: Socket
    ) {
        fun close() {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    // =====================================================================
    // CONTROL CONNECTION
    // =====================================================================

    suspend fun connect(): Boolean {
        Log.d(TAG, "🔍 [SC] connect() called")
        return commandMutex.withLock {
            Log.d(TAG, "🔍 [SC] connect() inside mutex")
            connectInternal()
        }
    }

    /**
     * Вызывается только когда commandMutex уже удерживается.
     */
    private fun connectInternal(): Boolean {
        Log.d(TAG, "🔍 [SC] connectInternal() START")

        if (isControlConnected()) {
            Log.d(TAG, "🔍 [SC] уже подключены")
            return true
        }

        disconnectControl()

        return try {
            Log.d(TAG, "[CTRL-01] connect: $host:$port")

            val socket = Socket()

            socket.connect(
                InetSocketAddress(host, port),
                CONNECT_TIMEOUT_MS
            )

            socket.soTimeout = COMMAND_READ_TIMEOUT_MS

            controlSocket = socket
            controlInput = socket.getInputStream()
            controlOutput = socket.getOutputStream()

            helloCompleted = false
            helloResponse = null

            Log.d(TAG, "🔍 [SC] [CTRL-01] ✅ connected")
            true

        } catch (e: Exception) {

            Log.e(TAG, "🔍 [SC] [CTRL-01] ❌ connect failed: ${e.message}", e)
            disconnectControl()
            false
        }
    }

    private suspend fun ensureHelloLocked(): String? {
        Log.d(TAG, "🔍 [SC] ensureHelloLocked() START")

        if (!connectInternal()) {
            Log.e(TAG, "🔍 [SC] connectInternal() вернул false")
            return null
        }

        if (helloCompleted) {
            Log.d(TAG, "🔍 [SC] hello уже выполнен: $helloResponse")
            return helloResponse
        }

        Log.d(TAG, "[CTRL-02] HELLO")

        val response = sendCommandInternal(
            "HELLO VERSION MIN=$SAM_VERSION MAX=$SAM_VERSION"
        )

        if (isOk(response)) {
            helloCompleted = true
            helloResponse = response
            Log.d(TAG, "🔍 [SC] [CTRL-02] ✅ HELLO OK: $response")
        } else {
            Log.e(TAG, "🔍 [SC] [CTRL-02] ❌ HELLO failed: $response")
        }

        return response
    }

    suspend fun hello(): String? {
        Log.d(TAG, "🔍 [SC] hello() called")
        return commandMutex.withLock {
            Log.d(TAG, "🔍 [SC] hello() inside mutex")
            ensureHelloLocked()
        }
    }

    // =====================================================================
    // DISCONNECT (ИСПРАВЛЕН — БЕЗ MUTEX)
    // =====================================================================

    suspend fun disconnect() {
        Log.d(TAG, "🔍 [SC] disconnect() called")
        closeActiveAcceptSocket()

        // Закрываем контрольный сокет БЕЗ захвата mutex,
        // чтобы не блокировать выполняющиеся команды.
        // Если команда выполняется — она упадёт с IOException,
        // что и требуется при отключении.
        disconnectControl()
    }

    private fun disconnectControl() {
        Log.d(TAG, "🔍 [SC] disconnectControl() START")

        try {
            controlInput?.close()
        } catch (_: Exception) {
        }

        try {
            controlOutput?.close()
        } catch (_: Exception) {
        }

        try {
            controlSocket?.close()
        } catch (_: Exception) {
        }

        controlInput = null
        controlOutput = null
        controlSocket = null

        helloCompleted = false
        helloResponse = null

        Log.d(TAG, "🔍 [SC] [CTRL-06] control socket closed")
    }

    private fun isControlConnected(): Boolean {
        val socket = controlSocket ?: return false

        return try {
            socket.isConnected &&
                    !socket.isClosed &&
                    !socket.isInputShutdown &&
                    !socket.isOutputShutdown
        } catch (_: Exception) {
            false
        }
    }

    // =====================================================================
    // SEND SAM COMMAND
    // =====================================================================

    private suspend fun sendCommandInternal(
        command: String
    ): String? {

        Log.d(TAG, "🔍 [SC] ===== sendCommandInternal() =====")
        Log.d(TAG, "🔍 [SC] command: ${redactSamSecrets(command)}")
        Log.d(TAG, "🔍 [SC] command length: ${command.length} символов")

        val output = controlOutput ?: run {
            Log.e(TAG, "🔍 [SC] ❌ no output stream")
            return null
        }

        val input = controlInput ?: run {
            Log.e(TAG, "🔍 [SC] ❌ no input stream")
            return null
        }

        return try {

            val bytes = command.toByteArray(StandardCharsets.UTF_8)
            Log.d(TAG, "🔍 [SC] [CTRL-WRITE] ${bytes.size} bytes")

            writeSamLine(output, command)
            Log.d(TAG, "🔍 [SC] [CTRL-WRITE] ✅ отправлено")

            val response = readSamLine(input, MAX_SAM_LINE_SIZE)

            if (response == null) {
                Log.e(TAG, "🔍 [SC] [CTRL-READ] ❌ EOF/null")
                null
            } else {
                Log.d(TAG, "🔍 [SC] [CTRL-READ] ${response.length} bytes")
                Log.d(TAG, "🔍 [SC] [CTRL-READ] ответ: ${redactSamSecrets(response)}")
                response
            }

        } catch (e: SocketException) {

            Log.e(TAG, "🔍 [SC] [CTRL-ERROR] SocketException: ${e.message}", e)
            helloCompleted = false
            helloResponse = null
            disconnectControl()
            null

        } catch (e: IOException) {

            Log.e(TAG, "🔍 [SC] [CTRL-ERROR] IOException: ${e.message}", e)
            helloCompleted = false
            helloResponse = null
            disconnectControl()
            null

        } catch (e: Exception) {

            Log.e(TAG, "🔍 [SC] [CTRL-ERROR] ${e.message}", e)
            helloCompleted = false
            helloResponse = null
            disconnectControl()
            null
        }
    }

    /** Returns true only while the SAM control socket is usable. */
    fun isControlConnectionAlive(): Boolean = isControlConnected()

    suspend fun sendCommand(
        command: String
    ): String? {

        Log.d(TAG, "🔍 [SC] sendCommand() called")
        Log.d(TAG, "🔍 [SC] command: $command")

        return commandMutex.withLock {

            if (!isControlConnected()) {
                Log.e(TAG, "🔍 [SC] [CTRL-ERROR] not connected")
                return@withLock null
            }

            sendCommandInternal(command)
        }
    }

    // =====================================================================
    // NAMING LOOKUP
    // =====================================================================

    suspend fun lookupDestination(
        address: String
    ): String? {

        Log.d(TAG, "🔍 [SC] ===== lookupDestination() =====")
        Log.d(TAG, "🔍 [SC] address (входной): '$address'")

        val normalized = normalizeAddress(address)
        Log.d(TAG, "🔍 [SC] normalized: '$normalized'")

        if (normalized.isBlank()) {
            Log.w(TAG, "🔍 [SC] адрес пустой")
            return null
        }

        return commandMutex.withLock {

            Log.d(TAG, "🔍 [SC] lookupDestination() inside mutex")

            if (!isOk(ensureHelloLocked())) {
                Log.e(TAG, "🔍 [SC] ensureHelloLocked вернул не OK")
                return@withLock null
            }

            val command = "NAMING LOOKUP NAME=$normalized"
            Log.d(TAG, "🔍 [SC] [CTRL-03] команда: $command")

            val response = sendCommandInternal(command)

            if (!isOk(response)) {
                Log.w(TAG, "🔍 [SC] [CTRL-03] NAMING LOOKUP failed: $response")
                return@withLock null
            }

            val destination = extractSamValue(response, "VALUE")

            if (!destination.isNullOrBlank()) {
                Log.d(TAG, "🔍 [SC] [CTRL-03] ✅ NAMING LOOKUP OK")
                Log.d(TAG, "🔍 [SC] [CTRL-03] destination (полный): $destination")
                destination
            } else {
                Log.e(TAG, "🔍 [SC] [CTRL-03] ❌ NAMING LOOKUP returned no VALUE")
                null
            }
        }
    }

    // =====================================================================
    // DEST GENERATE
    // =====================================================================

    suspend fun generateDestination(): GeneratedDestination? {

        Log.d(TAG, "🔍 [SC] generateDestination() START")

        return commandMutex.withLock {

            Log.d(TAG, "🔍 [SC] generateDestination() inside mutex")

            if (!isOk(ensureHelloLocked())) {
                Log.e(TAG, "🔍 [SC] ensureHelloLocked вернул не OK")
                return@withLock null
            }

            Log.d(TAG, "🔍 [SC] [CTRL-04] DEST GENERATE")

            val response = sendCommandInternal("DEST GENERATE SIGNATURE_TYPE=7")

            if (response == null || !response.startsWith("DEST REPLY ")) {
                Log.e(TAG, "🔍 [SC] [CTRL-04] ❌ unexpected response: ${response?.take(100)}")
                return@withLock null
            }

            val publicDestination = extractSamValue(response, "PUB")
            val privateKey = extractSamValue(response, "PRIV")

            if (publicDestination.isNullOrBlank() || privateKey.isNullOrBlank()) {
                Log.e(TAG, "🔍 [SC] [CTRL-04] ❌ missing PUB/PRIV")
                return@withLock null
            }

            Log.d(TAG, "🔍 [SC] [CTRL-04] ✅ DEST GENERATE OK")
            Log.d(TAG, "🔍 [SC] publicDestination: ${publicDestination.take(40)}...")
            GeneratedDestination(publicDestination, privateKey)
        }
    }

    // =====================================================================
    // SESSION CREATE
    // =====================================================================

    suspend fun createStreamSession(
        sessionId: String,
        privateDestination: String
    ): Boolean {

        Log.d(TAG, "🔍 [SC] ===== createStreamSession() =====")
        Log.d(TAG, "🔍 [SC] sessionId: $sessionId")
        Log.d(TAG, "🔍 [SC] privateDestination: ${privateDestination.take(40)}...")

        if (sessionId.isBlank() || privateDestination.isBlank()) {
            Log.e(TAG, "🔍 [SC] ❌ empty params")
            return false
        }

        return commandMutex.withLock {

            Log.d(TAG, "🔍 [SC] createStreamSession() inside mutex")

            if (!isOk(ensureHelloLocked())) {
                Log.e(TAG, "🔍 [SC] ❌ HELLO failed")
                return@withLock false
            }

            val command = "SESSION CREATE STYLE=STREAM ID=$sessionId DESTINATION=$privateDestination " +
                    "i2cp.leaseSetEncType=4 inbound.quantity=3 outbound.quantity=3"
            Log.d(TAG, "🔍 [SC] [CTRL-05] команда (полная): $command")
            val bytes = command.toByteArray(StandardCharsets.UTF_8)
            Log.d(TAG, "🔍 [SC] [CTRL-05] command length=${bytes.size} bytes")

            val response = sendCommandInternal(command)

            if (!isOk(response)) {
                Log.e(TAG, "🔍 [SC] [CTRL-05] ❌ SESSION CREATE failed: $response")
                return@withLock false
            }

            Log.d(TAG, "🔍 [SC] [CTRL-05] ✅ SESSION CREATE OK")
            Log.d(TAG, "🔍 [SC] [CTRL-05] ответ: $response")
            true
        }
    }

    // =====================================================================
    // SESSION REMOVE
    // =====================================================================

    suspend fun removeSession(
        sessionId: String
    ): Boolean {

        Log.d(TAG, "🔍 [SC] removeSession() START: $sessionId")

        if (sessionId.isBlank()) {
            Log.w(TAG, "🔍 [SC] sessionId пустой")
            return false
        }

        return commandMutex.withLock {

            if (!isControlConnected()) {
                Log.w(TAG, "🔍 [SC] not connected")
                return@withLock false
            }

            val command = "SESSION REMOVE ID=$sessionId"
            Log.d(TAG, "🔍 [SC] [CTRL-06] команда: $command")

            val response = sendCommandInternal(command)
            val ok = isOk(response)

            if (ok) {
                Log.d(TAG, "🔍 [SC] [CTRL-06] ✅ SESSION REMOVE OK")
            } else {
                Log.w(TAG, "🔍 [SC] [CTRL-06] ❌ SESSION REMOVE failed: $response")
            }

            ok
        }
    }

    // =====================================================================
    // STREAM CONNECT
    // =====================================================================

  suspend fun createStreamSocket(sessionId: String, destination: String): Socket? {
    if (sessionId.isBlank() || destination.isBlank()) {
        Log.e(TAG, "[STREAM-CONNECT] empty params")
        return null
    }

    val socket = Socket()

    return try {
        Log.d(TAG, "[STREAM-CONNECT] connecting...")
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        socket.soTimeout = STREAM_CONNECT_TIMEOUT_MS

        val output = socket.getOutputStream()
        val input = socket.getInputStream()

        // -------------------------------------------------------------
        // HELLO
        // -------------------------------------------------------------

        Log.d(TAG, "[STREAM-CONNECT] HELLO =>")
        writeSamLine(output, "HELLO VERSION MIN=$SAM_VERSION MAX=$SAM_VERSION")

        val helloResponse = readSamLine(input, MAX_SAM_LINE_SIZE)
        Log.d(TAG, "[STREAM-CONNECT] HELLO <= $helloResponse")

        if (!isOk(helloResponse)) {
            Log.e(TAG, "[STREAM-CONNECT] HELLO failed")
            closeQuietly(socket)
            return null
        }

        // -------------------------------------------------------------
        // STREAM CONNECT
        // -------------------------------------------------------------
        // IMPORTANT: do NOT send SESSION STATUS on this socket.
        //
        // A STREAM CONNECT socket is a separate SAM protocol connection.
        // The STREAM session itself was created on the long-lived control
        // connection.  We only need to pass its ID to STREAM CONNECT here.
        // Session liveness, when needed, is checked through the dedicated
        // control socket by isStreamSessionAlive().
        // Sending SESSION STATUS here was causing SAM to close this socket
        // and was incorrectly interpreted as "session is gone", which then
        // triggered a full reconnect and destroyed the real session.
        // -------------------------------------------------------------

        val command = "STREAM CONNECT ID=$sessionId DESTINATION=$destination SILENT=false"
        Log.d(TAG, "[STREAM-CONNECT] => $command")
        writeSamLine(output, command)

        val connectResponse = readSamLine(input, MAX_SAM_LINE_SIZE)
        Log.d(TAG, "[STREAM-CONNECT] <= $connectResponse")

        if (!isOk(connectResponse)) {
            Log.e(TAG, "[STREAM-CONNECT] STREAM CONNECT failed: $connectResponse")
            closeQuietly(socket)
            return null
        }

        socket.soTimeout = STREAM_READ_TIMEOUT_MS
        Log.d(TAG, "[STREAM-CONNECT] ✅ established")
        socket

    } catch (e: SocketTimeoutException) {
        Log.e(TAG, "[STREAM-CONNECT] timeout: ${e.message}")
        closeQuietly(socket)
        null
    } catch (e: Exception) {
        Log.e(TAG, "[STREAM-CONNECT] error: ${e.message}", e)
        closeQuietly(socket)
        null
    }
}

    // =====================================================================
    // STREAM SEND
    // =====================================================================

    suspend fun sendStreamMessage(
        sessionId: String,
        destination: String,
        message: String,
        logCallback: (String) -> Unit = {}
    ): Boolean {

        Log.d(TAG, "🔍 [SC] ===== sendStreamMessage() START =====")
        Log.d(TAG, "🔍 [SC] sessionId: $sessionId")
        Log.d(TAG, "🔍 [SC] destination: $destination")
        Log.d(TAG, "🔍 [SC] message: \"$message\"")
        Log.d(TAG, "🔍 [SC] message length: ${message.length} символов")

        val payload = message.toByteArray(StandardCharsets.UTF_8)
        Log.d(TAG, "🔍 [SC] payload size: ${payload.size} bytes")

        if (sessionId.isBlank() || destination.isBlank() || payload.isEmpty() || payload.size > MAX_MESSAGE_SIZE) {
            Log.w(TAG, "🔍 [SC] ❌ invalid params")
            logCallback("invalid params")
            return false
        }

        var socket: Socket? = null

        return try {
            logCallback("STREAM CONNECT...")
            Log.d(TAG, "🔍 [SC] вызываем createStreamSocket...")
            socket = createStreamSocket(sessionId, destination)
            Log.d(TAG, "🔍 [SC] createStreamSocket вернул ${if (socket != null) "Socket" else "null"}")

            if (socket == null) {
                logCallback("failed to connect")
                Log.w(TAG, "🔍 [SC] ❌ socket is null")
                return false
            }

            Log.d(TAG, "🔍 [SC] отправляем payload (${payload.size} bytes)...")
            writeFrame(socket.getOutputStream(), payload)
            logCallback("✅ sent")
            Log.d(TAG, "🔍 [SC] ✅ отправлено успешно")
            true

        } catch (e: Exception) {
            logCallback("send error: ${e.message}")
            Log.e(TAG, "🔍 [SC] ❌ sendStreamMessage ошибка", e)
            false
        } finally {
            closeQuietly(socket)
            Log.d(TAG, "🔍 [SC] ===== sendStreamMessage() FINISH =====")
        }
    }

    // =====================================================================
    // STREAM ACCEPT
    // =====================================================================

    suspend fun acceptStream(
        sessionId: String
    ): AcceptedStream? {

        Log.d(TAG, "🔍 [SC] acceptStream() START: sessionId=$sessionId")

        if (sessionId.isBlank()) {
            Log.w(TAG, "🔍 [SC] sessionId пустой")
            return null
        }

        val socket = Socket()
        var handedOff = false

        synchronized(acceptSocketLock) {
            activeAcceptSocket = socket
        }

        return try {

            Log.d(TAG, "🔍 [SC] [STREAM-ACCEPT] opening SAM socket...")
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = STREAM_ACCEPT_TIMEOUT_MS

            val output = socket.getOutputStream()
            val input = socket.getInputStream()

            // -------------------------------------------------------------
            // HELLO
            // -------------------------------------------------------------

            val helloCommand = "HELLO VERSION MIN=$SAM_VERSION MAX=$SAM_VERSION"
            Log.d(TAG, "🔍 [SC] [STREAM-ACCEPT] HELLO => $helloCommand")
            writeSamLine(output, helloCommand)

            val helloResponse = readSamLine(input, MAX_SAM_LINE_SIZE)
            Log.d(TAG, "🔍 [SC] [STREAM-ACCEPT] HELLO <= $helloResponse")

            if (!isOk(helloResponse)) {
                Log.e(TAG, "🔍 [SC] [STREAM-ACCEPT] ❌ HELLO failed: $helloResponse")
                return null
            }

            // -------------------------------------------------------------
            // STREAM ACCEPT
            // -------------------------------------------------------------

            val command = "STREAM ACCEPT ID=$sessionId SILENT=false"
            Log.d(TAG, "🔍 [SC] [STREAM-ACCEPT] => $command")
            writeSamLine(output, command)

            val acceptResponse = readSamLine(input, MAX_SAM_LINE_SIZE)
            Log.d(TAG, "🔍 [SC] [STREAM-ACCEPT] <= $acceptResponse")

            if (!isOk(acceptResponse)) {
                Log.e(TAG, "🔍 [SC] [STREAM-ACCEPT] ❌ ACCEPT failed: $acceptResponse")
                return null
            }

            // -------------------------------------------------------------
            // WAIT FOR INCOMING STREAM
            // -------------------------------------------------------------

            Log.d(TAG, "🔍 [SC] [STREAM-ACCEPT] waiting for incoming stream...")
            val sender = readSamLine(input, MAX_SAM_LINE_SIZE)
            Log.d(TAG, "🔍 [SC] [STREAM-ACCEPT] sender line: $sender")

            if (sender == null || sender.isBlank() || sender.startsWith("STREAM STATUS ")) {
                Log.e(TAG, "🔍 [SC] [STREAM-ACCEPT] ❌ invalid sender: $sender")
                return null
            }

            socket.soTimeout = STREAM_READ_TIMEOUT_MS
            handedOff = true

            Log.d(TAG, "🔍 [SC] [STREAM-ACCEPT] ✅ accepted from ${sender.take(48)}...")
            AcceptedStream(
                senderDestination = sender.trim(),
                input = input,
                socket = socket
            )

        } catch (e: SocketTimeoutException) {
            // No incoming stream during the accept wait is normal. This is NOT
            // evidence that the SAM control connection or session is dead.
            Log.d(TAG, "🔍 [SC] [STREAM-ACCEPT] idle timeout; reopening ACCEPT")
            null
        } catch (e: SocketException) {
            if (!socket.isClosed) {
                Log.e(TAG, "🔍 [SC] [STREAM-ACCEPT] SocketException: ${e.message}", e)
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "🔍 [SC] [STREAM-ACCEPT] error: ${e.message}", e)
            null
        } finally {

            synchronized(acceptSocketLock) {
                if (activeAcceptSocket === socket) {
                    activeAcceptSocket = null
                }
            }

            if (!handedOff) {
                closeQuietly(socket)
            }
        }
    }

    fun closeActiveAcceptSocket() {

        Log.d(TAG, "🔍 [SC] closeActiveAcceptSocket() called")

        val socket = synchronized(acceptSocketLock) {
            val current = activeAcceptSocket
            activeAcceptSocket = null
            current
        }

        closeQuietly(socket)
    }

    // =====================================================================
    // FRAMED MESSAGE
    // =====================================================================

    suspend fun readFramedMessage(
        input: InputStream
    ): ByteArray? {

        Log.d(TAG, "🔍 [SC] readFramedMessage() START")

        return try {

            val header = ByteArray(4)
            if (!readFully(input, header, 0, 4)) {
                Log.w(TAG, "🔍 [SC] не удалось прочитать header")
                return null
            }

            val length = ByteBuffer.wrap(header).int
            Log.d(TAG, "🔍 [SC] frame length=$length")

            if (length <= 0 || length > MAX_MESSAGE_SIZE) {
                Log.e(TAG, "🔍 [SC] invalid length=$length")
                return null
            }

            val payload = ByteArray(length)
            if (!readFully(input, payload, 0, length)) {
                Log.w(TAG, "🔍 [SC] не удалось прочитать payload")
                return null
            }

            Log.d(TAG, "🔍 [SC] ✅ прочитано ${payload.size} bytes")
            payload

        } catch (e: Exception) {
            Log.e(TAG, "🔍 [SC] readFramedMessage error: ${e.message}", e)
            null
        }
    }

    // =====================================================================
    // HEALTH CHECK
    // =====================================================================

    suspend fun checkSamAvailable(): Boolean {

        Log.d(TAG, "🔍 [SC] checkSamAvailable() START")

        var socket: Socket? = null

        return try {

            socket = Socket()
            socket.connect(InetSocketAddress(host, port), HEALTH_TIMEOUT_MS)
            socket.soTimeout = HEALTH_TIMEOUT_MS

            val output = socket.getOutputStream()
            val input = socket.getInputStream()

            val command = "HELLO VERSION MIN=$SAM_VERSION MAX=$SAM_VERSION"
            Log.d(TAG, "🔍 [SC] checkSamAvailable: $command")
            writeSamLine(output, command)

            val response = readSamLine(input, MAX_SAM_LINE_SIZE)
            val ok = isOk(response)

            Log.d(TAG, "🔍 [SC] checkSamAvailable: ok=$ok, response=$response")
            ok

        } catch (e: Exception) {
            Log.w(TAG, "🔍 [SC] checkSamAvailable: false, error=${e.message}")
            false
        } finally {
            closeQuietly(socket)
        }
    }

    // =====================================================================
    // LOW LEVEL
    // =====================================================================

    private fun writeFrame(
        output: OutputStream,
        payload: ByteArray
    ) {

        val header = ByteBuffer.allocate(4).putInt(payload.size).array()

        output.write(header)
        output.write(payload)
        output.flush()
    }

    private suspend fun readFully(
        input: InputStream,
        buffer: ByteArray,
        offset: Int,
        length: Int
    ): Boolean {

        var total = 0

        while (total < length) {

            val count = input.read(buffer, offset + total, length - total)

            if (count < 0) {
                return false
            }

            if (count > 0) {
                total += count
            }
        }

        return true
    }

    private suspend fun readSamLine(
        input: InputStream,
        maxSize: Int
    ): String? {

        val buffer = ByteArrayOutputStream()

        while (true) {

            val value = input.read()

            if (value < 0) {
                return null
            }

            if (value == '\n'.code) {
                break
            }

            if (value != '\r'.code) {

                buffer.write(value)

                if (buffer.size() > maxSize) {
                    throw IOException("SAM line too long")
                }
            }
        }

        if (buffer.size() == 0) {
            return null
        }

        return buffer.toString(StandardCharsets.UTF_8.name()).trim()
    }

    private fun writeSamLine(
        output: OutputStream,
        line: String
    ) {

        output.write(line.toByteArray(StandardCharsets.UTF_8))
        output.write('\n'.code)
        output.flush()
    }

    private fun normalizeAddress(
        address: String
    ): String {

        var value = address.trim()

        if (value.isEmpty()) {
            return ""
        }

        if (!value.contains('.')) {
            value += ".b32.i2p"
        }

        return value.lowercase()
    }

    private fun isOk(
        response: String?
    ): Boolean {
        return response?.contains("RESULT=OK") == true
    }

    private fun redactSamSecrets(value: String): String {
        return value
            .replace(Regex("\\bPRIV=\\S+"), "PRIV=<redacted>")
            .replace(Regex("\\bPRIVATE_KEY=\\S+"), "PRIVATE_KEY=<redacted>")
            .replace(Regex("\\bDESTINATION=\\S+"), "DESTINATION=<redacted>")
            .replace(Regex("\\bDEST=\\S+"), "DEST=<redacted>")
    }

    private fun extractSamValue(
        response: String?,
        key: String
    ): String? {

        if (response == null) {
            return null
        }

        val token = "$key="
        val start = response.indexOf(token)

        if (start < 0) {
            return null
        }

        val valueStart = start + token.length
        val valueEnd = response.indexOf(' ', valueStart)

        return if (valueEnd >= 0) {
            response.substring(valueStart, valueEnd)
        } else {
            response.substring(valueStart)
        }
    }

    private fun closeQuietly(
        socket: Socket?
    ) {

        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }
}