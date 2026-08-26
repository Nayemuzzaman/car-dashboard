package com.csjotlab.cardashboard.vehicle.fakes

import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleDiagnosticsState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import com.csjotlab.cardashboard.vehicle.source.VehicleDataSource
import com.csjotlab.cardashboard.vehicle.source.VehicleSourceActivation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A source whose emissions the test drives directly.
 *
 * Deliberately backed by `MutableSharedFlow(replay = 1)` and **not** `MutableStateFlow`:
 * a `StateFlow` silently swallows an assignment equal to its current value, which would make any
 * test of the repository's own de-duplication vacuous — it would be measuring `MutableStateFlow`.
 * Here `emitState(x); emitState(x)` really does put two emissions on the wire.
 */
class ControllableVehicleDataSource(
    override val id: VehicleSourceId,
    /** Set to make [stop] throw the way a real USB `close()` can. */
    private val stopFailure: Throwable? = null,
) : VehicleDataSource {

    private val states = MutableSharedFlow<VehicleState>(replay = 1, extraBufferCapacity = 64)
    private val diags = MutableSharedFlow<VehicleDiagnosticsState>(replay = 1, extraBufferCapacity = 64)
    private val connections = MutableSharedFlow<VehicleConnectionState>(replay = 1, extraBufferCapacity = 64)
    private val beats = MutableSharedFlow<Long>(extraBufferCapacity = 64)

    init {
        // Seed the replay slot so `combine` has a value for each flow from the first collection,
        // exactly as the old StateFlow initial values did.
        states.tryEmit(VehicleState.unavailable(id))
        diags.tryEmit(VehicleDiagnosticsState.empty())
        connections.tryEmit(VehicleConnectionState.Disconnected)
    }

    override val vehicleState: Flow<VehicleState> = states.asSharedFlow()
    override val diagnostics: Flow<VehicleDiagnosticsState> = diags.asSharedFlow()
    override val connectionState: Flow<VehicleConnectionState> = connections.asSharedFlow()
    override val liveness: Flow<Long> = beats.asSharedFlow()

    var started = false
        private set
    private var preparedActivation: VehicleSourceActivation? = null
    private val lifecycleLock = Mutex()

    override suspend fun start(): VehicleSourceActivation = lifecycleLock.withLock {
        preparedActivation?.let { return@withLock it }
        lateinit var handle: VehicleSourceActivation
        handle = VehicleSourceActivation {
            lifecycleLock.withLock {
                if (preparedActivation === handle) started = true
            }
        }
        preparedActivation = handle
        handle
    }

    override suspend fun stop() = lifecycleLock.withLock {
        started = false
        preparedActivation = null
        stopFailure?.let { throw it }
        Unit
    }

    fun emitState(state: VehicleState) { check(states.tryEmit(state)) }
    fun emitDiagnostics(state: VehicleDiagnosticsState) { check(diags.tryEmit(state)) }
    fun emitConnection(state: VehicleConnectionState) { check(connections.tryEmit(state)) }

    /** Proof of contact with no change in any value — an idling car answering every poll. */
    fun emitLiveness(atMs: Long) { check(beats.tryEmit(atMs)) }
}
