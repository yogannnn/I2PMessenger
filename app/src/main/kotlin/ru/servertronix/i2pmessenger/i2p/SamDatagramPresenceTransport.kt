package ru.servertronix.i2pmessenger.i2p

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import ru.servertronix.i2pmessenger.presence.PresencePacket

/**
 * Legacy compatibility class.
 *
 * The messenger prototype uses SAM STREAM for all application traffic,
 * including presence. SAM DATAGRAM is intentionally not used.
 *
 * Kept only so older code that references this class still compiles.
 */
class SamDatagramPresenceTransport(
    @Suppress("UNUSED_PARAMETER")
    private val samConnection: SamConnection
) : PresenceTransport {

    override suspend fun sendPresence(
        destination: String,
        packet: PresencePacket
    ) {
        Log.w(
            "DatagramPresence",
            "DATAGRAM disabled; presence must be sent through STREAM"
        )
    }

    override fun listenForPresence(): Flow<PresencePacket> {
        return emptyFlow()
    }

    fun stop() {
        Log.d(
            "DatagramPresence",
            "DATAGRAM transport stopped (disabled)"
        )
    }
}
