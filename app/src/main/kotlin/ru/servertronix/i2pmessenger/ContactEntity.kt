package ru.servertronix.i2pmessenger.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    val address: String,
    val isOnline: Boolean = false,
    val lastMessage: String? = null,
    val lastMessageTime: Long? = null
)