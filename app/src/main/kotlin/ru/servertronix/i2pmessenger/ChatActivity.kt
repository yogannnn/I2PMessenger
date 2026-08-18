package ru.servertronix.i2pmessenger

import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.servertronix.i2pmessenger.data.local.AppDatabase
import ru.servertronix.i2pmessenger.data.repository.ContactRepository
import ru.servertronix.i2pmessenger.data.repository.MessageRepository
import ru.servertronix.i2pmessenger.i2p.I2PManager

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

    private lateinit var contactRepository: ContactRepository
    private lateinit var messageRepository: MessageRepository

    private val messageListener: (sender: String, message: String) -> Unit = { sender, msg ->
        runOnUiThread {
            // Пропускаем presence-пакеты (они уже перехвачены системным обработчиком)
            if (msg.startsWith("PRESENCE|")) {
                Log.d("ChatActivity", "🔍 [UI] пропускаем presence: $msg")
                return@runOnUiThread
            }
            val senderBase32 = I2PManager.base64ToBase32(sender)
            val cleanSender = senderBase32.removeSuffix(".b32.i2p")
            val cleanMyAddress = myAddress.removeSuffix(".b32.i2p")
            val isMine = cleanSender == cleanMyAddress
            Log.d("ChatActivity", "🔍 [UI] получено сообщение от $cleanSender, isMine=$isMine")
            addMessage(msg, isMine, saveToDb = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        contactName = intent.getStringExtra("contact_name") ?: "Чат"
        contactAddress = intent.getStringExtra("contact_address") ?: ""
        myAddress = I2PManager.getMyAddress()

        Log.d("ChatActivity", "🔍 [UI] onCreate: contactName=$contactName, contactAddress=$contactAddress")

        val db = AppDatabase.getInstance(this)
        contactRepository = ContactRepository(db)
        messageRepository = MessageRepository(db)

        toolbar = findViewById(R.id.toolbar)
        tvContactName = findViewById(R.id.tvContactName)
        tvOnlineStatus = findViewById(R.id.tvOnlineStatus)
        indicatorOnline = findViewById(R.id.indicatorOnline)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        tvContactName.text = contactName

        loadContactStatus()

        rvMessages = findViewById(R.id.rvMessages)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)

        adapter = MessageAdapter(messages)
        rvMessages.layoutManager = LinearLayoutManager(this)
        rvMessages.adapter = adapter

        lifecycleScope.launch {
            messageRepository.getMessagesForChat(contactAddress).collectLatest { history ->
                Log.d("ChatActivity", "🔍 [UI] обновление истории чата: ${history.size} сообщений")
                messages.clear()
                messages.addAll(history)
                adapter.updateMessages(messages)
                rvMessages.scrollToPosition(messages.size - 1)
            }
        }

        I2PManager.addMessageListener(messageListener)

        btnSend.setOnClickListener {
            val msg = etMessage.text.toString().trim()
            if (msg.isNotEmpty()) {
                Log.d("ChatActivity", "🔍 [UI] отправка сообщения: $msg")
                val message = Message(
                    id = System.currentTimeMillis().toString(),
                    text = msg,
                    timestamp = System.currentTimeMillis(),
                    isMine = true,
                    status = "SENT"
                )
                addMessage(msg, true, saveToDb = true)
                etMessage.text.clear()
                I2PManager.sendMessage(contactAddress, msg) { success ->
                    Log.d("ChatActivity", "🔍 [UI] результат отправки: $success")
                    if (!success) {
                        runOnUiThread {
                            // можно пометить как FAILED
                        }
                    }
                }
            }
        }
    }

    private fun loadContactStatus() {
        lifecycleScope.launch {
            contactRepository.getContactByAddressFlow(contactAddress).collect { contact ->
                Log.d("ChatActivity", "🔍 [UI] обновление статуса контакта: ${contact?.name}, isOnline=${contact?.isOnline}, hasKey=${contact?.publicKeyBase64 != null}")
                if (contact != null) {
                    updateOnlineStatus(contact.isOnline)
                }
            }
        }
    }

    private fun updateOnlineStatus(isOnline: Boolean) {
        Log.d("ChatActivity", "🔍 [UI] updateOnlineStatus: $isOnline")
        tvOnlineStatus.text = if (isOnline) "Онлайн" else "Офлайн"
        indicatorOnline.setBackgroundResource(
            if (isOnline) R.drawable.indicator_online else R.drawable.indicator_offline
        )
    }

    private fun addMessage(text: String, isMine: Boolean, saveToDb: Boolean = false) {
        val message = Message(
            id = System.currentTimeMillis().toString(),
            text = text,
            timestamp = System.currentTimeMillis(),
            isMine = isMine,
            status = "SENT"
        )
        messages.add(message)
        adapter.updateMessages(messages)
        rvMessages.scrollToPosition(messages.size - 1)

        if (saveToDb) {
            lifecycleScope.launch {
                messageRepository.saveMessage(message, contactAddress, myAddress)
            }
        }
    }

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
                Log.d("ChatActivity", "🔍 [UI] очистка чата")
                messages.clear()
                adapter.updateMessages(messages)
                lifecycleScope.launch {
                    messageRepository.deleteMessagesForChat(contactAddress)
                }
                true
            }
            R.id.action_block -> {
                // TODO
                true
            }
            R.id.action_delete_contact -> {
                // TODO
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        I2PManager.removeMessageListener(messageListener)
    }
}