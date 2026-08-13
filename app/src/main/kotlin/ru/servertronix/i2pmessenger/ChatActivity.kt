package ru.servertronix.i2pmessenger

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import java.security.MessageDigest

class ChatActivity : AppCompatActivity() {

    private lateinit var rvMessages: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var adapter: MessageAdapter
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvContactName: TextView
    private lateinit var tvOnlineStatus: TextView
    private lateinit var indicatorOnline: View

    private val messages = mutableListOf<Message>()
    private var contactName: String = ""
    private var contactAddress: String = ""
    private var myAddress: String = ""

    private val messageListener: (sender: String, message: String) -> Unit = { sender, msg ->
        runOnUiThread {
            val senderBase32 = extractBase32FromBase64(sender)
            val cleanSender = senderBase32.removeSuffix(".b32.i2p")
            val cleanMyAddress = myAddress.removeSuffix(".b32.i2p")
            val isMine = cleanSender == cleanMyAddress
            addMessage(msg, isMine)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        contactName = intent.getStringExtra("contact_name") ?: "Чат"
        contactAddress = intent.getStringExtra("contact_address") ?: ""
        myAddress = I2PManager.getMyAddress()

        toolbar = findViewById(R.id.toolbar)
        tvContactName = findViewById(R.id.tvContactName)
        tvOnlineStatus = findViewById(R.id.tvOnlineStatus)
        indicatorOnline = findViewById(R.id.indicatorOnline)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        tvContactName.text = contactName
        updateOnlineStatus(true)

        rvMessages = findViewById(R.id.rvMessages)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)

        adapter = MessageAdapter(messages)
        rvMessages.layoutManager = LinearLayoutManager(this)
        rvMessages.adapter = adapter

        I2PManager.addMessageListener(messageListener)

        btnSend.setOnClickListener {
            val msg = etMessage.text.toString().trim()
            if (msg.isNotEmpty()) {
                addMessage(msg, true)
                etMessage.text.clear()
                I2PManager.sendMessage(contactAddress, msg) { success ->
                    if (!success) {
                        runOnUiThread {
                            // можно пометить как неотправленное
                        }
                    }
                }
            }
        }
    }

    private fun updateOnlineStatus(isOnline: Boolean) {
        tvOnlineStatus.text = if (isOnline) "Онлайн" else "Офлайн"
        indicatorOnline.setBackgroundResource(
            if (isOnline) R.drawable.indicator_online else R.drawable.indicator_offline
        )
    }

    private fun addMessage(text: String, isMine: Boolean) {
        val message = Message(
            id = System.currentTimeMillis().toString(),
            text = text,
            timestamp = System.currentTimeMillis(),
            isMine = isMine
        )
        messages.add(message)
        adapter.updateMessages(messages)
        rvMessages.scrollToPosition(messages.size - 1)
    }

    // ========== МЕНЮ ==========

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_chat, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_clear_chat -> {
                messages.clear()
                adapter.updateMessages(messages)
                true
            }
            R.id.action_block -> {
                // TODO: блокировка контакта
                true
            }
            R.id.action_delete_contact -> {
                // TODO: удаление контакта
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ========== ПРЕОБРАЗОВАНИЯ ==========

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