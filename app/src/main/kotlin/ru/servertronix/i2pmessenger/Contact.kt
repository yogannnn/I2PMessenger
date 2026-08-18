package ru.servertronix.i2pmessenger

data class Contact(
    val id: Int = 0,
    val name: String,
    val address: String,
    val publicKeyBase64: String? = null, // <-- ДОБАВЛЕНО
    val isOnline: Boolean = false,
    val lastSeen: Long? = null,
    val lastMessage: String? = null,
    val lastMessageTime: Long? = null
) {
    fun getStatus(): String {
        return if (isOnline) {
            "🟢 Онлайн"
        } else {
            lastSeen?.let {
                val minutesAgo = (System.currentTimeMillis() - it) / 60_000
                when {
                    minutesAgo < 5 -> "🟡 Был недавно"
                    minutesAgo < 60 -> "🔴 Был $minutesAgo мин назад"
                    else -> "🔴 Был давно"
                }
            } ?: "⚪ Неизвестно"
        }
    }

    fun getStatusColor(): Int {
        return when {
            isOnline -> android.graphics.Color.rgb(76, 175, 80)
            lastSeen != null && (System.currentTimeMillis() - lastSeen!!) / 60_000 < 5 -> {
                android.graphics.Color.rgb(200, 160, 0)
            }
            else -> android.graphics.Color.GRAY
        }
    }
}