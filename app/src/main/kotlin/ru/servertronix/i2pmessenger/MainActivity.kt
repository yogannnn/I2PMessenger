package ru.servertronix.i2pmessenger

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ru.servertronix.i2pmessenger.data.local.AppDatabase
import ru.servertronix.i2pmessenger.data.repository.ContactRepository
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var rvContacts: RecyclerView
    private lateinit var contactAdapter: ContactAdapter
    private val contactsList = mutableListOf<Contact>()
    private lateinit var contactRepository: ContactRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // --- ИНИЦИАЛИЗАЦИЯ ROOM ---
        val db = AppDatabase.getInstance(this)
        contactRepository = ContactRepository(db)

        // --- ИНИЦИАЛИЗАЦИЯ I2P ---
        I2PManager.init(this)

        // --- НАСТРОЙКА ТУЛБАРА ---
        drawerLayout = findViewById(R.id.drawerLayout)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_menu)

        // --- БОКОВОЕ МЕНЮ ---
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
                    Toast.makeText(this, "Выход", Toast.LENGTH_SHORT).show()
                    drawerLayout.closeDrawers()
                    true
                }
                else -> false
            }
        }

        // --- ПОДПИСКА НА СОСТОЯНИЕ I2P ---
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
            }
        }

        // --- СПИСОК КОНТАКТОВ (RecyclerView) ---
        rvContacts = findViewById(R.id.rvContacts)
        rvContacts.layoutManager = LinearLayoutManager(this)

        contactAdapter = ContactAdapter(
            contactsList,
            onItemClick = { contact ->
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

        // --- ЗАГРУЗКА КОНТАКТОВ ИЗ ROOM ---
        loadContacts()

        // --- ПЛАВАЮЩАЯ КНОПКА ---
        val fab = findViewById<FloatingActionButton>(R.id.fabAddContact)
        fab.setOnClickListener {
            showAddContactDialog()
        }
    }

    // --- ЗАГРУЗКА КОНТАКТОВ ---
    private fun loadContacts() {
        lifecycleScope.launch {
            contactRepository.getAllContacts().collectLatest { contacts ->
                contactsList.clear()
                contactsList.addAll(contacts)
                contactAdapter.updateContacts(contactsList)
            }
        }
    }

    // --- ДОБАВЛЕНИЕ КОНТАКТА ---
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
            val finalAddress = if (address.endsWith(".b32.i2p")) address else "$address.b32.i2p"

            lifecycleScope.launch {
                contactRepository.addContact(name, finalAddress)
                Toast.makeText(this@MainActivity, "Контакт добавлен", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Отмена", null)
        builder.show()
    }

    // --- РЕДАКТИРОВАНИЕ / УДАЛЕНИЕ ---
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
            val finalAddress = if (address.endsWith(".b32.i2p")) address else "$address.b32.i2p"

            lifecycleScope.launch {
                contactRepository.updateContact(contact.id, name, finalAddress)
                Toast.makeText(this@MainActivity, "Контакт обновлён", Toast.LENGTH_SHORT).show()
            }
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
                    Toast.makeText(this@MainActivity, "Контакт удалён", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
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
        I2PManager.shutdown()
    }
}