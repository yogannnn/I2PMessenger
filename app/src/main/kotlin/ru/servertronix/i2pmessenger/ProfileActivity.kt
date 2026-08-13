package ru.servertronix.i2pmessenger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ProfileActivity : AppCompatActivity() {

    private lateinit var tvMyAddress: TextView
    private lateinit var tvMyBase64: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnCopyAddress: Button
    private lateinit var btnShareAddress: Button
    private lateinit var btnBack: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        tvMyAddress = findViewById(R.id.tvMyAddress)
        tvMyBase64 = findViewById(R.id.tvMyBase64)
        tvStatus = findViewById(R.id.tvStatus)
        btnCopyAddress = findViewById(R.id.btnCopyAddress)
        btnShareAddress = findViewById(R.id.btnShareAddress)
        btnBack = findViewById(R.id.btnBack)

        // Показываем адрес (один раз)
        val base32 = I2PManager.getMyAddress()
        tvMyAddress.text = if (base32.isNotEmpty()) base32 else "Не удалось получить адрес"
        val base64 = I2PManager.getMyPublicKey()
        tvMyBase64.text = base64 ?: "Ключ пока недоступен"

        // --- ПОДПИСКА НА СОСТОЯНИЕ I2P ---
        lifecycleScope.launch {
            I2PManager.state.collectLatest { state ->
                when (state) {
                    is I2PConnectionState.Connected -> {
                        tvStatus.text = "🟢 Онлайн"
                    }
                    is I2PConnectionState.Disconnected -> {
                        tvStatus.text = "🔴 Офлайн"
                    }
                    is I2PConnectionState.Connecting -> {
                        tvStatus.text = "🔄 Подключение..."
                    }
                    is I2PConnectionState.Error -> {
                        tvStatus.text = "⚠️ Ошибка: ${state.message}"
                    }
                }
            }
        }

        // --- КНОПКИ ---
        btnCopyAddress.setOnClickListener {
            copyToClipboard(tvMyAddress.text.toString())
            Toast.makeText(this, "Адрес скопирован!", Toast.LENGTH_SHORT).show()
        }

        btnShareAddress.setOnClickListener {
            val address = tvMyAddress.text.toString()
            if (address.isNotEmpty() && address != "Не удалось получить адрес") {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Мой I2P адрес: $address")
                }
                startActivity(Intent.createChooser(shareIntent, "Поделиться адресом через"))
            } else {
                Toast.makeText(this, "Нет адреса для отправки", Toast.LENGTH_SHORT).show()
            }
        }

        btnBack.setOnClickListener {
            finish()
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("I2P Address", text)
        clipboard.setPrimaryClip(clip)
    }
}