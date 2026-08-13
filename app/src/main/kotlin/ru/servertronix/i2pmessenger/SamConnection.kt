package ru.servertronix.i2pmessenger

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

class SamConnection(private val host: String = "127.0.0.1", private val port: Int = 7656) {

    private val TAG = "SamConnection"
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null

    // --- БАЗОВЫЕ МЕТОДЫ ---

    fun connect(): Boolean {
        return try {
            socket = Socket(host, port)
            writer = PrintWriter(socket!!.getOutputStream(), true)
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
            Log.d(TAG, "Соединение с SAM установлено")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка подключения: ${e.message}")
            false
        }
    }

    fun disconnect() {
        try {
            reader?.close()
            writer?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при закрытии: ${e.message}")
        } finally {
            reader = null
            writer = null
            socket = null
        }
    }

    fun sendCommand(command: String): String? {
        if (writer == null || reader == null) {
            Log.e(TAG, "Нет соединения")
            return null
        }
        return try {
            writer!!.println(command)
            writer!!.flush()
            val response = reader!!.readLine()
            Log.d(TAG, "Отправлено: $command")
            Log.d(TAG, "Получено: $response")
            response
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отправки: ${e.message}")
            null
        }
    }

    fun isConnected(): Boolean = socket?.isConnected == true && !socket!!.isClosed

    // --- ПРОВЕРКА ДОСТУПНОСТИ SAM ---

    fun checkSamAvailable(): Boolean {
        var testSocket: Socket? = null
        var testWriter: PrintWriter? = null
        var testReader: BufferedReader? = null
        return try {
            Log.d(TAG, "Проверяем SAM...")
            testSocket = Socket()
            testSocket.connect(InetSocketAddress(host, port), 2000)
            testSocket.soTimeout = 2000

            testWriter = PrintWriter(testSocket.getOutputStream(), true)
            testReader = BufferedReader(InputStreamReader(testSocket.getInputStream()))

            testWriter.println("HELLO VERSION MIN=3.1 MAX=3.1")
            testWriter.flush()

            val response = testReader.readLine()
            Log.d(TAG, "SAM health check: $response")

            response != null && response.contains("RESULT=OK")
        } catch (e: Exception) {
            Log.w(TAG, "SAM недоступен: ${e.message}")
            false
        } finally {
            try { testReader?.close() } catch (_: Exception) {}
            try { testWriter?.close() } catch (_: Exception) {}
            try { testSocket?.close() } catch (_: Exception) {}
        }
    }

    // --- ОТПРАВКА СООБЩЕНИЯ ЧЕРЕЗ STREAM ---

    fun sendStreamMessage(sessionId: String, destination: String, message: String, logCallback: (String) -> Unit): Boolean {
        var tempSocket: Socket? = null
        var tempWriter: PrintWriter? = null
        var tempReader: BufferedReader? = null
        return try {
            logCallback("📡 Открываем сокет для отправки...")
            tempSocket = Socket(host, port)
            tempWriter = PrintWriter(tempSocket!!.getOutputStream(), true)
            tempReader = BufferedReader(InputStreamReader(tempSocket!!.getInputStream()))
            logCallback("✅ Сокет открыт")

            logCallback("🔄 Отправляем HELLO...")
            tempWriter.println("HELLO VERSION MIN=3.1 MAX=3.1")
            tempWriter.flush()
            val helloResp = tempReader.readLine()
            logCallback("📩 Ответ HELLO: $helloResp")
            if (helloResp == null || !helloResp.contains("RESULT=OK")) {
                logCallback("❌ HELLO не удался")
                return false
            }

            logCallback("🔄 Отправляем STREAM CONNECT с ID: $sessionId...")
            val connectCmd = "STREAM CONNECT ID=$sessionId DESTINATION=$destination"
            logCallback("📤 Команда CONNECT: $connectCmd")
            tempWriter.println(connectCmd)
            tempWriter.flush()
            val connectResp = tempReader.readLine()
            logCallback("📩 Ответ CONNECT: $connectResp")
            if (connectResp == null || !connectResp.contains("RESULT=OK")) {
                logCallback("❌ CONNECT не удался")
                return false
            }

            logCallback("📤 Отправляем сообщение: $message")
            tempWriter.println(message)
            tempWriter.flush()
            logCallback("✅ Сообщение отправлено")

            tempSocket.close()
            true
        } catch (e: Exception) {
            logCallback("❌ Исключение: ${e.message}")
            false
        } finally {
            tempSocket?.close()
        }
    }

    // --- ПРИЁМ СООБЩЕНИЯ ЧЕРЕЗ STREAM ---

    fun acceptStreamMessage(sessionId: String): Pair<String, String>? {
        var tempSocket: Socket? = null
        var tempWriter: PrintWriter? = null
        var tempReader: BufferedReader? = null
        return try {
            tempSocket = Socket(host, port)
            tempWriter = PrintWriter(tempSocket!!.getOutputStream(), true)
            tempReader = BufferedReader(InputStreamReader(tempSocket!!.getInputStream()))

            tempWriter.println("HELLO VERSION MIN=3.1 MAX=3.1")
            tempWriter.flush()
            val helloResp = tempReader.readLine()
            if (helloResp == null || !helloResp.contains("RESULT=OK")) {
                Log.e(TAG, "HELLO failed: $helloResp")
                return null
            }

            val acceptCmd = "STREAM ACCEPT ID=$sessionId"
            Log.d(TAG, "ACCEPT команда: $acceptCmd")
            tempWriter.println(acceptCmd)
            tempWriter.flush()
            val acceptResp = tempReader.readLine()
            if (acceptResp == null || !acceptResp.contains("RESULT=OK")) {
                Log.e(TAG, "ACCEPT failed: $acceptResp")
                return null
            }
            Log.d(TAG, "ACCEPT успешен, ждём входящее соединение...")

            val senderLine = tempReader.readLine()
            if (senderLine == null || senderLine.isEmpty()) {
                Log.e(TAG, "Не получен адрес отправителя")
                return null
            }
            val sender = senderLine

            val message = tempReader.readLine()
            if (message == null) {
                Log.e(TAG, "Не получено сообщение")
                return null
            }

            Log.d(TAG, "Получено сообщение от $sender: $message")
            Pair(sender, message)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка приёма: ${e.message}")
            null
        } finally {
            tempSocket?.close()
        }
    }
}