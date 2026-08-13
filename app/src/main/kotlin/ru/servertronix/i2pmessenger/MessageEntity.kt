package ru.servertronix.i2pmessenger.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val chatId: String, // address контакта
    val senderId: String, // наш адрес или адрес собеседника
    val text: String,
    val timestamp: Long,
    val status: String // "SENDING", "SENT", "DELIVERED", "READ", "FAILED"
)