package ru.servertronix.i2pmessenger.i2p

import kotlinx.coroutines.flow.Flow
import ru.servertronix.i2pmessenger.presence.PresencePacket

interface PresenceTransport {
    /**
     * Отправляет пакет присутствия указанному получателю
     */
    suspend fun sendPresence(destination: String, packet: PresencePacket)

    /**
     * Поток входящих пакетов присутствия
     */
    fun listenForPresence(): Flow<PresencePacket>
}