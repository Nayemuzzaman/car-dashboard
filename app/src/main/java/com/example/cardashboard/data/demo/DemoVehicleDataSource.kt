package com.example.cardashboard.data.demo

import com.example.cardashboard.data.vehicle.VehicleDataSource
import com.example.cardashboard.domain.model.DriveMode
import com.example.cardashboard.domain.model.VehicleDataSourceKind
import com.example.cardashboard.domain.model.VehicleDataState
import com.example.cardashboard.domain.model.VehicleType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns [DemoVehicleSimulator] into a timed stream of snapshots.
 *
 * The flow is cold and driven entirely by `delay`, so it starts when a collector appears and stops
 * the moment the collecting scope is cancelled — no background thread, no `GlobalScope`, nothing
 * left running when the ViewModel goes away.
 */
class DemoVehicleDataSource(
    vehicleType: VehicleType,
    private val tickMillis: Long = DemoVehicleSimulator.DEFAULT_TICK_MILLIS,
    private val simulator: DemoVehicleSimulator = DemoVehicleSimulator(vehicleType, tickMillis)
) : VehicleDataSource {

    /** The simulator holds mutable state; commands can arrive while the loop is stepping it. */
    private val simulatorLock = Mutex()

    override fun observe(): Flow<VehicleDataState> = flow {
        emit(VehicleDataState.Loading)
        while (currentCoroutineContext().isActive) {
            val snapshot = simulatorLock.withLock { simulator.step() }
            emit(VehicleDataState.Available(snapshot, VehicleDataSourceKind.DEMO))
            delay(tickMillis)
        }
    }

    override suspend fun resetTrip() = simulatorLock.withLock { simulator.resetTrip() }

    override suspend fun selectDriveMode(mode: DriveMode) =
        simulatorLock.withLock { simulator.setDriveMode(mode) }

    override suspend fun reset() = simulatorLock.withLock { simulator.reset() }
}
