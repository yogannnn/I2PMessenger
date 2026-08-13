package ru.servertronix.i2pmessenger

data class Message(
    val id: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isMine: Boolean
)