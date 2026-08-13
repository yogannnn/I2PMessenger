package ru.servertronix.i2pmessenger

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

object I2PManager {

    private const val TAG = "I2PManager"
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    private val sam = SamConnection()
    private var privKey: String? = null
    private var pubKey: String? = null
    private var myBase32: String = ""
    private var sessionId: String = ""
    private var isInitialized = false

    // --- СОСТОЯНИЕ (StateFlow) ---
    private val _state = MutableStateFlow<I2PConnectionState>(I2PConnectionState.Disconnected)
    val state: StateFlow<I2PConnectionState> = _state.asStateFlow()

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statusCheckerJob: Job? = null
    private var isReconnecting = false

    private val listeners = mutableListOf<(sender: String, message: String) -> Unit>()

    // --- ИНИЦИАЛИЗАЦИЯ ---

    fun init(context: Context) {
        this.context = context.applicationContext
        prefs = context.getSharedPreferences("i2p_prefs", Context.MODE_PRIVATE)

        if (isInitialized) return
        isInitialized = true

        mainScope.launch {
            _state.value = I2PConnectionState.Connecting
            val success = withContext(Dispatchers.IO) {
                try {
                    initializeI2P()
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка инициализации", e)
                    false
                }
            }
            if (success) {
                _state.value = I2PConnectionState.Connected
                startReceiver()
                startStatusChecker()
            } else {
                _state.value = I2PConnectionState.Error("Не удалось подключиться к I2P")
            }
        }
    }

    private fun initializeI2P(): Boolean {
        try {
            if (!sam.connect()) {
                Log.e(TAG, "Не удалось подключиться к SAM")
                return false
            }
            Log.d(TAG, "Подключено к SAM")

            val hello = sam.sendCommand("HELLO VERSION MIN=3.1 MAX=3.1")
            if (hello == null || !hello.contains("RESULT=OK")) {
                Log.e(TAG, "Ошибка HELLO")
                return false
            }
            Log.d(TAG, "Рукопожатие успешно!")

            privKey = prefs.getString("privKey", null)
            pubKey = prefs.getString("pubKey", null)
            if (privKey == null || pubKey == null) {
                val genResp = sam.sendCommand("DEST GENERATE SIGNATURE_TYPE=7")
                if (genResp == null || !genResp.startsWith("DEST REPLY")) {
                    Log.e(TAG, "Ошибка генерации ключей")
                    return false
                }
                val pub = extractPubKey(genResp)
                val priv = extractPrivKey(genResp)
                if (pub == null || priv == null) {
                    Log.e(TAG, "Не удалось извлечь ключи")
                    return false
                }
                privKey = priv
                pubKey = pub
                prefs.edit().putString("privKey", priv).putString("pubKey", pub).apply()
                Log.d(TAG, "Ключи сохранены")
            }

            myBase32 = try {
                base64ToBase32(pubKey!!)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка Base32: ${e.message}")
                ""
            }

            sessionId = "i2pSession_${System.currentTimeMillis()}"
            val sessionCmd = "SESSION CREATE STYLE=STREAM ID=$sessionId DESTINATION=${privKey!!} i2cp.leaseSetEncType=6,4"
            val sessionResp = sam.sendCommand(sessionCmd)
            if (sessionResp == null || !sessionResp.contains("RESULT=OK")) {
                Log.e(TAG, "Ошибка создания сессии: $sessionResp")
                return false
            }
            Log.d(TAG, "Сессия создана: $sessionId")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации I2P: ${e.message}")
            return false
        }
    }

    // --- ПРИЁМНИК ---

    private fun startReceiver() {
        mainScope.launch {
            while (_state.value is I2PConnectionState.Connected) {
                try {
                    val result = withContext(Dispatchers.IO) {
                        sam.acceptStreamMessage(sessionId)
                    }
                    if (result != null) {
                        val (sender, msg) = result
                        Log.d(TAG, "Получено сообщение от $sender: $msg")
                        listeners.forEach { it(sender, msg) }
                    } else {
                        delay(1000)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка приёмника: ${e.message}")
                    delay(5000)
                }
            }
        }
    }

    // --- ПРОВЕРКА СТАТУСА ---

    private fun startStatusChecker() {
        if (statusCheckerJob?.isActive == true) return

        statusCheckerJob = mainScope.launch {
            var consecutiveFailures = 0

            while (isActive) {
                val samAvailable = withContext(Dispatchers.IO) {
                    try {
                        sam.checkSamAvailable()
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка проверки SAM", e)
                        false
                    }
                }

                if (samAvailable) {
                    consecutiveFailures = 0
                    if (_state.value !is I2PConnectionState.Connected) {
                        if (!isReconnecting) {
                            isReconnecting = true
                            _state.value = I2PConnectionState.Connecting
                            val restored = withContext(Dispatchers.IO) {
                                try {
                                    sam.disconnect()
                                    initializeI2P()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Ошибка восстановления I2P", e)
                                    false
                                }
                            }
                            isReconnecting = false
                            if (restored) {
                                Log.d(TAG, "I2P-сессия восстановлена")
                                _state.value = I2PConnectionState.Connected
                                startReceiver()
                            } else {
                                _state.value = I2PConnectionState.Error("Не удалось восстановить сессию")
                            }
                        }
                    }
                } else {
                    consecutiveFailures++
                    if (consecutiveFailures >= 2) {
                        _state.value = I2PConnectionState.Disconnected
                    }
                }

                delay(3000)
            }
        }
    }

    // --- ОТПРАВКА СООБЩЕНИЯ ---

    fun sendMessage(destination: String, message: String, callback: (Boolean) -> Unit) {
        mainScope.launch {
            if (_state.value !is I2PConnectionState.Connected) {
                Log.e(TAG, "Не в сети, отправка невозможна")
                callback(false)
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                sam.sendStreamMessage(sessionId, destination, message) { /* логи */ }
            }
            callback(result)
        }
    }

    // --- ПОЛУЧЕНИЕ ДАННЫХ ---

    fun addMessageListener(listener: (sender: String, message: String) -> Unit) {
        listeners.add(listener)
    }

    fun removeMessageListener(listener: (sender: String, message: String) -> Unit) {
        listeners.remove(listener)
    }

    fun getMyAddress(): String = myBase32
    fun getMyPublicKey(): String? = pubKey

    // --- ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ ---

    private fun extractPrivKey(response: String): String? {
        val start = response.indexOf(" PRIV=")
        if (start == -1) return null
        val end = response.indexOf(" ", start + 6)
        return if (end != -1) response.substring(start + 6, end) else response.substring(start + 6)
    }

    private fun extractPubKey(response: String): String? {
        val start = response.indexOf(" PUB=")
        if (start == -1) return null
        val end = response.indexOf(" ", start + 5)
        return if (end != -1) response.substring(start + 5, end) else response.substring(start + 5)
    }

    private fun base64ToBase32(i2pBase64: String): String {
        val clean = i2pBase64.trim()
        var standardBase64 = clean
            .replace('-', '+')
            .replace('~', '/')
        when (standardBase64.length % 4) {
            2 -> standardBase64 += "=="
            3 -> standardBase64 += "="
            0 -> { /* ok */ }
            else -> throw IllegalArgumentException("Некорректная длина I2P Base64: ${clean.length}")
        }
        val destination = android.util.Base64.decode(standardBase64, android.util.Base64.DEFAULT)
        if (destination.size < 387) {
            throw IllegalArgumentException("Destination слишком короткий: ${destination.size} байт")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(destination)
        return encodeBase32(digest)
    }

    private fun encodeBase32(bytes: ByteArray): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val result = StringBuilder()
        var i = 0
        var buffer = 0
        var bitsLeft = 0
        while (i < bytes.size) {
            buffer = (buffer shl 8) or (bytes[i].toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1F
                result.append(alphabet[index])
                bitsLeft -= 5
            }
            i++
        }
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1F
            result.append(alphabet[index])
        }
        return result.toString()
    }

    // --- ЗАВЕРШЕНИЕ ---

    fun shutdown() {
        mainScope.cancel()
        sam.disconnect()
        isInitialized = false
    }
}