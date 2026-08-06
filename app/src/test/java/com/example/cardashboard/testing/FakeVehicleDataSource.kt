package com.example.cardashboard.testing

import com.example.cardashboard.data.vehicle.VehicleDataSource
import com.example.cardashboard.domain.model.DriveMode
import com.example.cardashboard.domain.model.VehicleDataState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A data source the test drives by hand.
 *
 * [emissions] is a shared flow rather than a fixed list so a test can push a snapshot, assert, then
 * push another — including a failure.
 */
class FakeVehicleDataSource(
    private val emissions: MutableSharedFlow<VehicleDataState> = MutableSharedFlow(replay = 1)
) : VehicleDataSource {

    var resetTripCount: Int = 0
        private set
    var resetCount: Int = 0
        private set
    var lastDriveMode: DriveMode? = null
        private set

    suspend fun emit(state: VehicleDataState) = emissions.emit(state)

    override fun observe(): Flow<VehicleDataState> = emissions

    override suspend fun resetTrip() {
        resetTripCount++
    }

    override suspend fun selectDriveMode(mode: DriveMode) {
        lastDriveMode = mode
    }

    override suspend fun reset() {
        resetCount++
    }
}
