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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.servertronix.i2pmessenger.data.local.AppDatabase
import ru.servertronix.i2pmessenger.data.repository.ContactRepository
import ru.servertronix.i2pmessenger.i2p.I2PManager

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var rvContacts: RecyclerView
    private lateinit var contactAdapter: ContactAdapter
    private val contactsList = mutableListOf<Contact>()
    private lateinit var contactRepository: ContactRepository

    // Защита от повторного запуска resolveMissingDestinations
    private var isResolvingDestinations = false
    private var lastResolveTime = 0L

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 1001
        private const val MIN_RESOLVE_INTERVAL_MS = 60_000L // 1 минута между запусками resolveMissingDestinations
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

        // ---- ОБНОВЛЯЕМ КЛЮЧИ ДЛЯ ВСЕХ КОНТАКТОВ С ЗАДЕРЖКОЙ ----
        scheduleResolveMissingDestinations()

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
                    showExitConfirmationDialog()
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
                // Открываем чат с контактом
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
                Log.d("MainActivity", "🔍 [UI] Flow обновил список: ${contacts.size} контактов")
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

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val destination = contactRepository.addContactAndResolve(name, normalizedAddress)
                    Log.d("MainActivity", "🔍 addContactAndResolve вернул: $destination")
                    withContext(Dispatchers.Main) {
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
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "❌ Ошибка добавления контакта", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "Ошибка: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
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
            lifecycleScope.launch(Dispatchers.IO) {
                contactRepository.updateContact(contact.id, name, normalizedAddress)
                contactRepository.updatePublicKey(normalizedAddress, "")
                contactRepository.resolveAndSaveDestination(normalizedAddress)
                withContext(Dispatchers.Main) {
                    contactsList[position] = contact.copy(name = name, address = normalizedAddress)
                    contactAdapter.updateContacts(contactsList)
                    Toast.makeText(this@MainActivity, "Контакт обновлён", Toast.LENGTH_SHORT).show()
                }
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
                lifecycleScope.launch(Dispatchers.IO) {
                    contactRepository.deleteContact(contact.id)
                    withContext(Dispatchers.Main) {
                        contactsList.removeAt(position)
                        contactAdapter.updateContacts(contactsList)
                        Toast.makeText(this@MainActivity, "Контакт удалён", Toast.LENGTH_SHORT).show()
                    }
                }
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

    /**
     * Запускает resolveMissingDestinations() с защитой от частых повторных вызовов.
     *
     * Почему защита:
     * - onCreate вызывается при каждом повороте экрана / возврате в MainActivity
     * - resolveMissingDestinations делает NAMING LOOKUP для каждого контакта без кеша
     * - БЕЗ защиты это портило бы SAM-мост лишними запросами
     *
     * Интервал: 60 секунд между запусками.
     * Вызывается из onCreate() и scheduleResolveMissingDestinations().
     */
    private fun scheduleResolveMissingDestinations() {
        val now = System.currentTimeMillis()
        if (isResolvingDestinations || now - lastResolveTime < MIN_RESOLVE_INTERVAL_MS) {
            Log.d("MainActivity", "🔍 [UI] resolveMissingDestinations пропущен (уже идёт или недавно был)")
            return
        }

        isResolvingDestinations = true
        lastResolveTime = now

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                delay(3000) // ждём, пока I2PManager подключится
                Log.d("MainActivity", "🔍 [UI] обновляем ключи для всех контактов...")
                val count = contactRepository.resolveMissingDestinations()
                Log.d("MainActivity", "🔍 [UI] обновлено $count контактов")
            } catch (e: Exception) {
                Log.e("MainActivity", "❌ Ошибка resolveMissingDestinations", e)
            } finally {
                isResolvingDestinations = false
            }
        }
    }

    /**
     * Показывает диалог подтверждения перед выходом.
     * Подтверждение нужно, чтобы случайно не убить foreground-сервис.
     * При подтверждении:
     * 1. Останавливаем I2PService (убивает I2PManager → acceptLoop → все сокеты)
     * 2. Инициируем завершение текущей Activity
     * 3. System.exit(0) —-forceKill процесс, если Activity не завершился
     */
    private fun showExitConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Выход")
            .setMessage(
                "Прервать соединение с I2P-сетью и завершить приложение?\n\n" +
                "Это закроет все чаты и остановит фоновую службу."
            )
            .setPositiveButton("Выйти") { _, _ ->
                Log.d("MainActivity", "🔍 [UI] пользователь подтвердил выход")
                performExit()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Выполняет фактический выход из приложения.
     *
     * Почему так сложно:
     * - Foreground service (ForegroundServiceType = dataSync) — Android не даст убить
     *   процесс, пока он в foreground. foregroundNotificationRequired в manifestе.
     * - finishAffinity() — закрывает Activity в task, но не kill process.
     * - Process.killProcess + System.exit — force-kill процесса, обходя все блокировки.
     *
     * Проблема с простым подходом (stopService → finishAffinity):
     * stopService — асинхронная операция. finishAffinity() закрывает Activity
     * быстрее, чем I2PService.onDestroy() успевает выполнить I2PManager.shutdown().
     * В результате сервис продолжает жить с открытыми сокетами.
     *
     * Решение: после stopService проверяем, что сервис действительно мёртв,
     * потом закрываем Activity, потом force-kill.
     */
    private fun performExit() {
        Log.d("MainActivity", "🔍 [UI] performExit():开始退出应用...")

        // 1. Обновляем UI перед выходом (нотификация)
        I2PService.getInstance()?.updateStatus(false)

        // 2. Останавливаем foreground-сервис
        // I2PService.onDestroy() → I2PManager.shutdown() → всех сокетов, acceptLoop
        Log.d("MainActivity", "🔍 [UI] stopping I2PService (foreground service)...")
        stopService(Intent(this, I2PService::class.java))

        // 3. Ждём, пока сервис действительно умрёт.
        // Foreground service не убивается мгновенно — нужно дождаться onDestroy.
        // Опрос каждые 200мс, таймаут 5 секунд.
        val serviceDead = waitForServiceDeath(5_000L)
        if (!serviceDead) {
            Log.w("MainActivity", "🔍 [UI] I2PService не умер в течение 5 секунд, force-kill")
        } else {
            Log.d("MainActivity", "🔍 [UI] I2PService успешно остановлен")
        }

        // 4. Закрываем Activity (MainActivity + дочерние)
        Log.d("MainActivity", "🔍 [UI] closing activities...")
        finishAffinity()

        // 5. Force-kill процесса как последний рубеж
        // Foreground service может блокировать finishAffinity
        // Также других Activity (ChatActivity, ProfileActivity) в back stack
        // могут помешать полному завершению
        Log.d("MainActivity", "🔍 [UI] force-killing process...")
        try {
            android.os.Process.killProcess(android.os.Process.myPid())
        } catch (e: Exception) {
            Log.w("MainActivity", "killProcess failed: ${e.message}")
        }
        try {
            System.exit(0)
        } catch (e: Exception) {
            Log.w("MainActivity", "System.exit failed: ${e.message}")
        }
    }

    /**
     * Ждёт смерти foreground-сервиса с таймаутом.
     * Foreground service не убивается мгновенно — Android требует времени на
     * выполнение onDestroy → I2PManager.stop() → shutdown().
     *
     * @param timeoutMs — сколько ждать (в мс)
     * @return true — сервис мёртв (или не был запущен)
     *         false — таймаут, сервис всё ещё жив
     */
    /**
     * Ждёт смерти foreground-сервиса с таймаутом.
     * Foreground service не убивается мгновенно — Android требует времени на
     * выполнение onDestroy → I2PManager.stop() → shutdown().
     *
     * @param timeoutMs — сколько ждать (в мс), по умолчанию 5 секунд
     * @return true — сервис мёртв (или не был запущен)
     *         false — таймаут, сервис всё ещё жив
     */
    private fun waitForServiceDeath(timeoutMs: Long = 5_000L): Boolean {
        val start = System.currentTimeMillis()
        val interval = 200L

        while (System.currentTimeMillis() - start < timeoutMs) {
            if (I2PService.getInstance() == null) {
                return true
            }
            Thread.sleep(interval)
        }

        return I2PService.getInstance() == null
    }

    override fun onDestroy() {
        super.onDestroy()
        // Очистка при уничтожении Activity (но сервис продолжает работать в фоне)
    }
}