package ru.servertronix.i2pmessenger

sealed class I2PConnectionState {
    object Disconnected : I2PConnectionState()
    object Connecting : I2PConnectionState()
    object Connected : I2PConnectionState()
    data class Error(val message: String) : I2PConnectionState()
}