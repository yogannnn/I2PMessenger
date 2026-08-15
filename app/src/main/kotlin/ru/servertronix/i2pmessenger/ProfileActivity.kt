package ru.servertronix.i2pmessenger

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.servertronix.i2pmessenger.data.local.AppDatabase
import ru.servertronix.i2pmessenger.data.repository.ContactRepository
import ru.servertronix.i2pmessenger.i2p.I2PManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProfileActivity : AppCompatActivity() {

    private lateinit var tvMyAddress: TextView
    private lateinit var tvMyBase64: TextView
    private lateinit var tvStatus: TextView
    private lateinit var statusIndicator: View
    private lateinit var btnCopyAddress: Button
    private lateinit var btnShareAddress: Button
    private lateinit var btnExportIdentity: Button
    private lateinit var btnImportIdentity: Button
    private lateinit var btnExportContacts: Button
    private lateinit var btnImportContacts: Button
    private lateinit var btnBack: Button

    // =====================================================================
    // SAF LAUNCHERS
    // =====================================================================

    private val saveIdentityLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            doExportIdentity(uri)
        } else {
            Toast.makeText(this, "Экспорт отменён", Toast.LENGTH_SHORT).show()
        }
    }

    private val openIdentityLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            doImportIdentity(uri)
        } else {
            Toast.makeText(this, "Импорт отменён", Toast.LENGTH_SHORT).show()
        }
    }

    private val saveContactsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            doExportContacts(uri)
        } else {
            Toast.makeText(this, "Экспорт контактов отменён", Toast.LENGTH_SHORT).show()
        }
    }

    private val openContactsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            doImportContacts(uri)
        } else {
            Toast.makeText(this, "Импорт контактов отменён", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        tvMyAddress = findViewById(R.id.tvMyAddress)
        tvMyBase64 = findViewById(R.id.tvMyBase64)
        tvStatus = findViewById(R.id.tvStatus)
        statusIndicator = findViewById(R.id.statusIndicator)
        btnCopyAddress = findViewById(R.id.btnCopyAddress)
        btnShareAddress = findViewById(R.id.btnShareAddress)
        btnExportIdentity = findViewById(R.id.btnExportIdentity)
        btnImportIdentity = findViewById(R.id.btnImportIdentity)
        btnExportContacts = findViewById(R.id.btnExportContacts)
        btnImportContacts = findViewById(R.id.btnImportContacts)
        btnBack = findViewById(R.id.btnBack)

        val base32 = I2PManager.getMyAddress()
        tvMyAddress.text = if (base32.isNotEmpty()) base32 else "Не удалось получить адрес"
        val base64 = I2PManager.getMyPublicKey()
        tvMyBase64.text = base64 ?: "Ключ недоступен"

        lifecycleScope.launch {
            I2PManager.state.collectLatest { state ->
                val (text, colorRes) = when (state) {
                    is I2PConnectionState.Connected -> "Онлайн" to R.drawable.indicator_online
                    is I2PConnectionState.Disconnected -> "Офлайн" to R.drawable.indicator_offline
                    is I2PConnectionState.Connecting -> "Подключение..." to R.drawable.indicator_connecting
                    is I2PConnectionState.Error -> "Ошибка: ${state.message}" to R.drawable.indicator_offline
                }
                tvStatus.text = text
                statusIndicator.setBackgroundResource(colorRes)
            }
        }

        btnCopyAddress.setOnClickListener {
            copyToClipboard(tvMyAddress.text.toString())
            Toast.makeText(this, "Адрес скопирован", Toast.LENGTH_SHORT).show()
        }

        btnShareAddress.setOnClickListener {
            val address = tvMyAddress.text.toString()
            if (address.isNotEmpty() && address != "Не удалось получить адрес") {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Мой I2P адрес: $address")
                }
                startActivity(Intent.createChooser(shareIntent, "Поделиться адресом"))
            } else {
                Toast.makeText(this, "Нет адреса для отправки", Toast.LENGTH_SHORT).show()
            }
        }

        btnExportIdentity.setOnClickListener {
            showExportIdentityWarning()
        }

        btnImportIdentity.setOnClickListener {
            openIdentityLauncher.launch(arrayOf("text/plain", "text/*"))
        }

        btnExportContacts.setOnClickListener {
            showExportContactsWarning()
        }

        btnImportContacts.setOnClickListener {
            openContactsLauncher.launch(arrayOf("text/plain", "text/*"))
        }

        btnBack.setOnClickListener {
            finish()
        }
    }

    // =====================================================================
    // IDENTITY EXPORT / IMPORT
    // =====================================================================

    private fun showExportIdentityWarning() {
        val publicKey = I2PManager.getMyPublicKey()
        val privateKey = I2PManager.getInstance()?.getPrivateDestination()

        if (publicKey.isNullOrBlank() || privateKey.isNullOrBlank()) {
            Toast.makeText(this, "Ключи не сгенерированы", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Внимание")
            .setMessage(
                "Вы экспортируете приватный ключ.\n\n" +
                "Этот файл позволяет восстановить вашу identity на другом устройстве.\n\n" +
                "Никому не показывайте этот файл!\n" +
                "Храните его в надёжном месте."
            )
            .setPositiveButton("Экспортировать") { _, _ ->
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
                val address = I2PManager.getMyAddress()
                val filename = "i2p_identity_${address.take(12)}_$timestamp.txt"
                saveIdentityLauncher.launch(filename)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun doExportIdentity(uri: Uri) {
        try {
            val publicKey = I2PManager.getMyPublicKey()
            val privateKey = I2PManager.getInstance()?.getPrivateDestination()
            val address = I2PManager.getMyAddress()

            if (publicKey.isNullOrBlank() || privateKey.isNullOrBlank()) {
                Toast.makeText(this, "Ключи не найдены", Toast.LENGTH_SHORT).show()
                return
            }

            val content = buildString {
                appendLine("# =============================================")
                appendLine("# I2P MESSENGER IDENTITY EXPORT")
                appendLine("# =============================================")
                appendLine("#")
                appendLine("# ВНИМАНИЕ: ЭТОТ ФАЙЛ СОДЕРЖИТ ПРИВАТНЫЙ КЛЮЧ")
                appendLine("# Храните его в безопасном месте")
                appendLine("# Никому не показывайте")
                appendLine("#")
                appendLine("# Сгенерирован: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine("# Base32 адрес: $address")
                appendLine("#")
                appendLine("# =============================================")
                appendLine("# ПУБЛИЧНЫЙ КЛЮЧ")
                appendLine("# =============================================")
                appendLine(publicKey)
                appendLine("#")
                appendLine("# =============================================")
                appendLine("# ПРИВАТНЫЙ КЛЮЧ")
                appendLine("# =============================================")
                appendLine(privateKey)
            }

            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(content.toByteArray(Charsets.UTF_8))
                outputStream.flush()
            }

            Toast.makeText(this, "Identity экспортирована", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun doImportIdentity(uri: Uri) {
        try {
            val content = contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.readText()
                }
            } ?: run {
                Toast.makeText(this, "Не удалось прочитать файл", Toast.LENGTH_SHORT).show()
                return
            }

            val keys = parseIdentityFile(content)
            if (keys == null) {
                Toast.makeText(this, "Не удалось распарсить файл. Проверьте формат.", Toast.LENGTH_LONG).show()
                return
            }

            val (publicKey, privateKey) = keys
            val address = extractAddressFromFile(content) ?: "неизвестен"

            AlertDialog.Builder(this)
                .setTitle("Восстановление identity")
                .setMessage(
                    "Файл содержит identity для адреса:\n\n$address\n\n" +
                    "Восстановить эту identity на этом устройстве?\n\n" +
                    "Текущая identity будет заменена."
                )
                .setPositiveButton("Восстановить") { _, _ ->
                    doRestoreIdentity(publicKey, privateKey)
                }
                .setNegativeButton("Отмена", null)
                .show()

        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка импорта: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun parseIdentityFile(content: String): Pair<String, String>? {
        val lines = content.lines()
        var publicKey: String? = null
        var privateKey: String? = null

        var inPublicSection = false
        var inPrivateSection = false

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.contains("ПУБЛИЧНЫЙ КЛЮЧ") || trimmed.contains("PUBLIC") -> {
                    inPublicSection = true
                    inPrivateSection = false
                }
                trimmed.contains("ПРИВАТНЫЙ КЛЮЧ") || trimmed.contains("PRIVATE") -> {
                    inPublicSection = false
                    inPrivateSection = true
                }
                trimmed.startsWith("#") || trimmed.isEmpty() -> continue
                inPublicSection && publicKey == null && trimmed.length > 100 -> {
                    publicKey = trimmed
                }
                inPrivateSection && privateKey == null && trimmed.length > 100 -> {
                    privateKey = trimmed
                }
            }
        }

        return if (publicKey != null && privateKey != null) {
            Pair(publicKey, privateKey)
        } else {
            null
        }
    }

    private fun extractAddressFromFile(content: String): String? {
        val lines = content.lines()
        for (line in lines) {
            if (line.contains("Base32 адрес:")) {
                return line.substringAfter("Base32 адрес:").trim()
            }
        }
        return null
    }

    private fun doRestoreIdentity(publicKey: String, privateKey: String) {
        try {
            val prefs = getSharedPreferences("i2p_identity", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("public_destination", publicKey)
                .putString("private_destination", privateKey)
                .apply()

            Toast.makeText(this, "Identity восстановлена. Перезапустите приложение.", Toast.LENGTH_LONG).show()

            AlertDialog.Builder(this)
                .setTitle("Identity восстановлена")
                .setMessage("Для применения изменений нужно перезапустить I2P сервис. Перезапустить сейчас?")
                .setPositiveButton("Да") { _, _ ->
                    stopService(Intent(this, I2PService::class.java))
                    startService(Intent(this, I2PService::class.java))
                    finish()
                }
                .setNegativeButton("Позже") { _, _ ->
                    finish()
                }
                .show()

        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    // =====================================================================
    // CONTACTS EXPORT / IMPORT
    // =====================================================================

    private fun showExportContactsWarning() {
        AlertDialog.Builder(this)
            .setTitle("Экспорт контактов")
            .setMessage(
                "Будут экспортированы все контакты:\n" +
                "- Имя\n" +
                "- Base32 адрес\n" +
                "- Публичный ключ (если известен)\n\n" +
                "Файл не содержит приватных ключей."
            )
            .setPositiveButton("Экспортировать") { _, _ ->
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
                val filename = "i2p_contacts_$timestamp.txt"
                saveContactsLauncher.launch(filename)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun doExportContacts(uri: Uri) {
        try {
            val db = AppDatabase.getInstance(this)
            val contactRepo = ContactRepository(db)

            lifecycleScope.launch {
                val contacts = contactRepo.getAllContactsSync()

                if (contacts.isEmpty()) {
                    Toast.makeText(this@ProfileActivity, "Нет контактов для экспорта", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val content = buildString {
                    appendLine("# =============================================")
                    appendLine("# I2P MESSENGER CONTACTS EXPORT")
                    appendLine("# =============================================")
                    appendLine("#")
                    appendLine("# Экспортировано: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                    appendLine("# Всего контактов: ${contacts.size}")
                    appendLine("#")
                    appendLine("# Формат: NAME | ADDRESS | PUBLIC_KEY")
                    appendLine("# =============================================")
                    appendLine()

                    for (contact in contacts) {
                        val name = contact.name
                        val address = contact.address
                        val key = contact.publicKeyBase64 ?: ""
                        appendLine("$name | $address | $key")
                    }
                }

                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(content.toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                }

                Toast.makeText(
                    this@ProfileActivity,
                    "Экспортировано ${contacts.size} контактов",
                    Toast.LENGTH_LONG
                ).show()
            }

        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun doImportContacts(uri: Uri) {
        try {
            val content = contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.readText()
                }
            } ?: run {
                Toast.makeText(this, "Не удалось прочитать файл", Toast.LENGTH_SHORT).show()
                return
            }

            val contacts = parseContactsFile(content)
            if (contacts.isEmpty()) {
                Toast.makeText(this, "Не найдено контактов в файле", Toast.LENGTH_LONG).show()
                return
            }

            AlertDialog.Builder(this)
                .setTitle("Импорт контактов")
                .setMessage("Найдено ${contacts.size} контактов. Импортировать?")
                .setPositiveButton("Импортировать") { _, _ ->
                    doImportContactsInternal(contacts)
                }
                .setNegativeButton("Отмена", null)
                .show()

        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка импорта: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun parseContactsFile(content: String): List<Triple<String, String, String>> {
        val result = mutableListOf<Triple<String, String, String>>()
        val lines = content.lines()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val parts = trimmed.split("|").map { it.trim() }
            if (parts.size >= 2) {
                val name = parts[0]
                val address = parts[1].removeSuffix(".b32.i2p").lowercase()
                val key = if (parts.size >= 3) parts[2] else ""
                if (name.isNotEmpty() && address.isNotEmpty()) {
                    result.add(Triple(name, address, key))
                }
            }
        }

        return result
    }

    private fun doImportContactsInternal(
        contacts: List<Triple<String, String, String>>
    ) {
        val db = AppDatabase.getInstance(this)
        val contactRepo = ContactRepository(db)

        var imported = 0
        var skipped = 0

        lifecycleScope.launch {
            for ((name, address, key) in contacts) {
                try {
                    val existing = contactRepo.getContactByAddress(address)
                    if (existing == null) {
                        contactRepo.addContact(name, address)
                        if (key.isNotEmpty()) {
                            contactRepo.updatePublicKey(address, key)
                        }
                        imported++
                    } else {
                        if (key.isNotEmpty() && existing.publicKeyBase64 != key) {
                            contactRepo.updatePublicKey(address, key)
                        }
                        skipped++
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ProfileActivity", "Ошибка импорта контакта $address", e)
                }
            }

            val message = if (imported > 0) {
                "Импортировано: $imported, пропущено: $skipped"
            } else {
                "Все контакты уже существуют ($skipped)"
            }

            Toast.makeText(this@ProfileActivity, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("I2P Address", text)
        clipboard.setPrimaryClip(clip)
    }
}