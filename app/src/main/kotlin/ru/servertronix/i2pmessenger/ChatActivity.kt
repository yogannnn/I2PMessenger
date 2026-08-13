package ru.servertronix.i2pmessenger

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.security.MessageDigest

class ChatActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button

    private var contactName: String = ""
    private var contactAddress: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        contactName = intent.getStringExtra("contact_name") ?: "Чат"
        contactAddress = intent.getStringExtra("contact_address") ?: ""

        supportActionBar?.title = contactName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        tvLog = findViewById(R.id.tvLog)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)

        I2PManager.addMessageListener(messageListener)

        btnSend.setOnClickListener {
            val msg = etMessage.text.toString().trim()
            if (msg.isNotEmpty()) {
                tvLog.append("Вы: $msg\n")
                etMessage.text.clear()
                I2PManager.sendMessage(contactAddress, msg) { success ->
                    if (!success) {
                        runOnUiThread {
                            tvLog.append("❌ Ошибка отправки\n")
                        }
                    }
                }
            }
        }
    }

    private val messageListener: (sender: String, message: String) -> Unit = { sender, msg ->
        runOnUiThread {
            val senderBase32 = extractBase32FromBase64(sender)
            // Убираем .b32.i2p для сравнения
            val cleanContactAddress = contactAddress.removeSuffix(".b32.i2p")
            val cleanSender = senderBase32.removeSuffix(".b32.i2p")
            val displayName = if (cleanSender == cleanContactAddress) {
                contactName
            } else {
                senderBase32.take(20) + "..."
            }
            tvLog.append("$displayName: $msg\n")
        }
    }

    private fun extractBase32FromBase64(base64: String): String {
        val clean = base64.trim()
        var standardBase64 = clean
            .replace('-', '+')
            .replace('~', '/')
        when (standardBase64.length % 4) {
            2 -> standardBase64 += "=="
            3 -> standardBase64 += "="
            0 -> { /* ok */ }
        }
        val destination = android.util.Base64.decode(standardBase64, android.util.Base64.DEFAULT)
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

    override fun onDestroy() {
        super.onDestroy()
        I2PManager.removeMessageListener(messageListener)
    }
}