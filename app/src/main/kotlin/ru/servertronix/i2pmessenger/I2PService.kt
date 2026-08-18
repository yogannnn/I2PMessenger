package ru.servertronix.i2pmessenger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.servertronix.i2pmessenger.data.local.AppDatabase
import ru.servertronix.i2pmessenger.data.repository.ContactRepository
import ru.servertronix.i2pmessenger.i2p.I2PManager
import ru.servertronix.i2pmessenger.i2p.PresenceManager

/**
 * Single owner of the I2P/SAM network lifecycle.
 *
 * Activities can be created/destroyed freely. The network stack is not tied
 * to an Activity lifecycle.
 */
class I2PService : Service() {

    companion object {
        private const val TAG = "I2PService"
        private const val CHANNEL_ID = "i2p_messenger_channel"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        private var instance: I2PService? = null

        fun isRunning(): Boolean = instance != null
        fun getInstance(): I2PService? = instance
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var presenceManager: PresenceManager? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Запуск I2P…"))

        // ONLY the service owns the I2P lifecycle.
        I2PManager.init(applicationContext)
        setupPresenceManager()

        serviceScope.launch {
            I2PManager.state.collectLatest { state ->
                val text = when (state) {
                    is I2PConnectionState.Connected -> "🟢 Онлайн"
                    is I2PConnectionState.Disconnected -> "🔴 Офлайн"
                    is I2PConnectionState.Connecting -> "🔄 Подключение…"
                    is I2PConnectionState.Error -> "⚠️ ${state.message}"
                }
                updateNotification(text)
            }
        }

        Log.d(TAG, "🟢 I2PService created; network lifecycle is service-owned")
    }

    private fun setupPresenceManager() {
        val db = AppDatabase.getInstance(this)
        // Dynamic SAM lookup: never keep a stale SamConnection across reconnects.
        val contactRepo = ContactRepository(db)
        val i2pManager = I2PManager.getInstance()

        if (i2pManager == null) {
            Log.e(TAG, "I2PManager is not initialized")
            updateNotification("⚠️ Ошибка запуска")
            return
        }

        val pm = PresenceManager(
            i2pManager = i2pManager,
            destinationProvider = suspend {
                contactRepo.getAllContactsSync().map { it.address }
            }
        )
        presenceManager = pm

        i2pManager.setOnMessageReceived { senderDestination, message ->
            try {
                pm.handleIncomingMessage(senderDestination, message)
            } catch (t: Throwable) {
                Log.e(TAG, "Presence message handler failed", t)
                false
            }
        }

        i2pManager.setOnDestinationDiscovered { destinationBase64 ->
            serviceScope.launch {
                try {
                    val contact = contactRepo.findContactByDestinationSync(destinationBase64)
                    if (contact != null && contact.publicKeyBase64 == null) {
                        contactRepo.updatePublicKey(contact.address, destinationBase64)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to persist discovered destination", t)
                }
            }
        }

        i2pManager.setOnLog { message -> Log.d("I2P", message) }

        I2PManager.setPresenceManager(pm)

        // Start exactly once. Do not call start() again from onCreate.
        pm.start(
            onPresenceChanged = { destinationBase64, online ->
                serviceScope.launch {
                    try {
                        contactRepo.updateOnlineStatusByDestination(
                            destinationBase64,
                            online,
                            System.currentTimeMillis()
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to update presence", t)
                    }
                }
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If Android recreates the service, onCreate() restores the complete stack.
        // Repeated start commands do not create another manager or presence loop.
        Log.d(TAG, "onStartCommand(startId=$startId)")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Do NOT stop the service when the user swipes the UI task away.
        // The foreground service is intentionally independent of the Activity task.
        Log.d(TAG, "Task removed; keeping I2P foreground service alive")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.w(TAG, "🔴 I2PService onDestroy(): stopping network stack")
        instance = null

        try {
            presenceManager?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "PresenceManager stop failed", t)
        }
        presenceManager = null

        // This is the ONLY normal path that shuts down I2PManager.
        I2PManager.shutdown()

        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "I2P Messenger",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Соединение I2P Messenger"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("I2P Messenger")
            .setContentText(text)
            .setSmallIcon(R.drawable.twotone_message)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, createNotification(text))
        } catch (t: Throwable) {
            Log.w(TAG, "Notification update failed: ${t.message}")
        }
    }

    fun updateStatus(isOnline: Boolean) {
        updateNotification(if (isOnline) "🟢 Онлайн" else "🔴 Офлайн")
    }
}
