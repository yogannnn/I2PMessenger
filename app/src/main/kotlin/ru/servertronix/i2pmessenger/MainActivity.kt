package ru.servertronix.i2pmessenger

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.servertronix.i2pmessenger.data.local.AppDatabase
import ru.servertronix.i2pmessenger.data.repository.ContactRepository
import ru.servertronix.i2pmessenger.i2p.I2PManager

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var rvContacts: RecyclerView
    private lateinit var contactAdapter: ContactAdapter
    private val contactsList = mutableListOf<Contact>()
    private lateinit var contactRepository: ContactRepository

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ---- ЗАПРАШИВАЕМ РАЗРЕШЕНИЕ НА УВЕДОМЛЕНИЯ (Android 13+) ----
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST
                )
            }
        }

        // ---- ЗАПУСКАЕМ СЕРВИС ----
        Log.d("MainActivity", "🔍 [UI] запускаем I2PService")
        val intent = Intent(this, I2PService::class.java)
        ContextCompat.startForegroundService(this, intent)

        val db = AppDatabase.getInstance(this)
        contactRepository = ContactRepository(db)

        // ---- ОБНОВЛЯЕМ КЛЮЧИ ДЛЯ ВСЕХ КОНТАКТОВ ----
        lifecycleScope.launch {
            Log.d("MainActivity", "🔍 [UI] обновляем ключи для всех контактов...")
            val count = contactRepository.resolveMissingDestinations()
            Log.d("MainActivity", "🔍 [UI] обновлено $count контактов")
        }

        drawerLayout = findViewById(R.id.drawerLayout)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_menu)

        val navigationView = findViewById<NavigationView>(R.id.navigationView)
        navigationView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_profile -> {
                    startActivity(Intent(this, ProfileActivity::class.java))
                    drawerLayout.closeDrawers()
                    true
                }
                R.id.nav_settings -> {
                    Toast.makeText(this, "Настройки", Toast.LENGTH_SHORT).show()
                    drawerLayout.closeDrawers()
                    true
                }
                R.id.nav_logout -> {
                    stopService(Intent(this, I2PService::class.java))
                    Toast.makeText(this, "Выход", Toast.LENGTH_SHORT).show()
                    drawerLayout.closeDrawers()
                    true
                }
                else -> false
            }
        }

        lifecycleScope.launch {
            I2PManager.state.collectLatest { state ->
                val headerView = navigationView.getHeaderView(0)
                val tvStatus = headerView.findViewById<TextView>(R.id.nav_user_status)
                val address = I2PManager.getMyAddress()
                val statusText = when (state) {
                    is I2PConnectionState.Connected -> "🟢 Онлайн"
                    is I2PConnectionState.Disconnected -> "🔴 Офлайн"
                    is I2PConnectionState.Connecting -> "🔄 Подключение..."
                    is I2PConnectionState.Error -> "⚠️ Ошибка: ${state.message}"
                }
                tvStatus.text = if (address.isNotEmpty()) "$statusText\n$address" else statusText

                // Обновляем уведомление сервиса
                I2PService.getInstance()?.updateStatus(state is I2PConnectionState.Connected)
            }
        }

        rvContacts = findViewById(R.id.rvContacts)
        rvContacts.layoutManager = LinearLayoutManager(this)

        contactAdapter = ContactAdapter(
            contactsList,
            onItemClick = { contact ->
                Log.d("MainActivity", "🔍 [UI] клик по контакту: ${contact.name}")
                val intent = Intent(this, ChatActivity::class.java)
                intent.putExtra("contact_name", contact.name)
                intent.putExtra("contact_address", contact.address)
                startActivity(intent)
            },
            onItemLongClick = { contact, position ->
                showContextMenu(contact, position)
            }
        )
        rvContacts.adapter = contactAdapter

        loadContacts()

        val fab = findViewById<FloatingActionButton>(R.id.fabAddContact)
        fab.setOnClickListener {
            showAddContactDialog()
        }
    }

    // ---- ОБРАБОТКА РЕЗУЛЬТАТА ЗАПРОСА РАЗРЕШЕНИЯ ----
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("MainActivity", "✅ Разрешение на уведомления получено")
                Log.d("MainActivity", "Foreground service continues; notification permission is now granted")
            } else {
                Log.w("MainActivity", "⚠️ Разрешение на уведомления отклонено")
                Toast.makeText(this, "Для работы в фоне нужно разрешение на уведомления", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadContacts() {
        lifecycleScope.launch {
            contactRepository.getAllContacts().collectLatest { contacts ->
                contactsList.clear()
                contactsList.addAll(contacts)
                contactAdapter.updateContacts(contactsList)
            }
        }
    }

    private fun showAddContactDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Добавить контакт")

        val view = layoutInflater.inflate(R.layout.dialog_add_contact, null)
        val etName = view.findViewById<EditText>(R.id.etContactName)
        val etAddress = view.findViewById<EditText>(R.id.etContactAddress)
        builder.setView(view)

        builder.setPositiveButton("Добавить") { _, _ ->
            val name = etName.text.toString().trim()
            val address = etAddress.text.toString().trim()
            if (name.isEmpty() || address.isEmpty()) {
                Toast.makeText(this, "Заполните все поля", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }

            val normalizedAddress = address.removeSuffix(".b32.i2p").lowercase()

            if (contactsList.any { it.address == normalizedAddress }) {
                Toast.makeText(this, "Контакт с таким адресом уже существует", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }

            val newContact = Contact(
                id = contactsList.size + 1,
                name = name,
                address = normalizedAddress
            )
            contactsList.add(newContact)
            contactAdapter.updateContacts(contactsList)

            lifecycleScope.launch {
                try {
                    val destination = contactRepository.addContactAndResolve(name, normalizedAddress)
                    Log.d("MainActivity", "🔍 addContactAndResolve вернул: $destination")
                    if (destination != null) {
                        Toast.makeText(
                            this@MainActivity,
                            "Контакт добавлен, ключ получен",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Контакт добавлен, но ключ пока не получен",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "❌ Ошибка добавления контакта", e)
                    Toast.makeText(
                        this@MainActivity,
                        "Ошибка: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                loadContacts()
            }
        }

        builder.setNegativeButton("Отмена", null)
        builder.show()
    }

    private fun showEditContactDialog(contact: Contact, position: Int) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Редактировать контакт")

        val view = layoutInflater.inflate(R.layout.dialog_add_contact, null)
        val etName = view.findViewById<EditText>(R.id.etContactName)
        val etAddress = view.findViewById<EditText>(R.id.etContactAddress)
        etName.setText(contact.name)
        etAddress.setText(contact.address)
        builder.setView(view)

        builder.setPositiveButton("Сохранить") { _, _ ->
            val name = etName.text.toString().trim()
            val address = etAddress.text.toString().trim()
            if (name.isEmpty() || address.isEmpty()) {
                Toast.makeText(this, "Заполните все поля", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            val normalizedAddress = address.removeSuffix(".b32.i2p").lowercase()
            if (contactsList.any { it.address == normalizedAddress && it.id != contact.id }) {
                Toast.makeText(this, "Контакт с таким адресом уже существует", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            lifecycleScope.launch {
                contactRepository.updateContact(contact.id, name, normalizedAddress)
                contactRepository.updatePublicKey(normalizedAddress, "")
                contactRepository.resolveAndSaveDestination(normalizedAddress)
            }
            contactsList[position] = contact.copy(name = name, address = normalizedAddress)
            contactAdapter.updateContacts(contactsList)
            Toast.makeText(this, "Контакт обновлён", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Отмена", null)
        builder.show()
    }

    private fun showDeleteConfirmDialog(contact: Contact, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("Удалить контакт")
            .setMessage("Вы уверены, что хотите удалить ${contact.name}?")
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch {
                    contactRepository.deleteContact(contact.id)
                }
                contactsList.removeAt(position)
                contactAdapter.updateContacts(contactsList)
                Toast.makeText(this, "Контакт удалён", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showContextMenu(contact: Contact, position: Int) {
        val options = arrayOf("Редактировать", "Удалить")
        AlertDialog.Builder(this)
            .setTitle(contact.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditContactDialog(contact, position)
                    1 -> showDeleteConfirmDialog(contact, position)
                }
            }
            .show()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Не останавливаем сервис, чтобы он работал в фоне
    }
}