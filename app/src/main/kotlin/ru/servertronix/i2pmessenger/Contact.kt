package ru.servertronix.i2pmessenger

data class Contact(
    val id: Int = 0,
    val name: String,
    val address: String,
    val isOnline: Boolean = false,
    val lastMessage: String? = null,
    val lastMessageTime: Long? = null
)