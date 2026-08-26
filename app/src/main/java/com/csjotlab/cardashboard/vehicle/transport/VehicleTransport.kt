package com.csjotlab.cardashboard.vehicle.transport

import kotlinx.coroutines.flow.Flow

sealed interface TransportEvent {
    data object Opened : TransportEvent
    data object Detached : TransportEvent
    data class Failed(val reason: String) : TransportEvent
}

/**
 * A bidirectional byte pipe to a diagnostic adapter. It knows nothing about OBD-II; the protocol
 * layer knows nothing about USB. That split is what lets the whole protocol be tested without
 * hardware. Spec section 5.
 */
interface VehicleTransport {
    val events: Flow<TransportEvent>
    suspend fun open()
    suspend fun write(bytes: ByteArray)
    fun incoming(): Flow<ByteArray>
    suspend fun close()
}
