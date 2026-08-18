package ru.servertronix.i2pmessenger.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val chatId: String,          // адрес контакта
    val senderId: String,        // адрес отправителя
    val text: String,
    val timestamp: Long,
    val isMine: Boolean,
    val status: String = "SENT"  // SENT, DELIVERED, READ, FAILED
)