package ru.servertronix.i2pmessenger.presence

data class PresencePacket(
    val type: String = "PRESENCE",
    val senderId: String,          // Base32 (без суффикса)
    val senderBase64: String? = null, // <-- НОВОЕ ПОЛЕ (Base64 отправителя)
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        return "$senderId|$timestamp"
    }

    companion object {
        fun fromJson(json: String): PresencePacket? {
            return try {
                val parts = json.split("|")
                if (parts.size == 2) {
                    PresencePacket(
                        senderId = parts[0],
                        timestamp = parts[1].toLongOrNull() ?: System.currentTimeMillis()
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}