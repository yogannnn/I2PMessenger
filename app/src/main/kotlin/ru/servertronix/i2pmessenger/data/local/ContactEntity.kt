package ru.servertronix.i2pmessenger.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    val address: String,               // Base32 без суффикса, lowercase
    val publicKeyBase64: String? = null, // <-- НОВОЕ ПОЛЕ
    val isOnline: Boolean = false,
    val lastSeen: Long? = null,
    val lastMessage: String? = null,
    val lastMessageTime: Long? = null
)