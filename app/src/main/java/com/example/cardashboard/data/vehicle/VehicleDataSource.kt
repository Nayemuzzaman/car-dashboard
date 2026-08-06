package com.example.cardashboard.data.vehicle

import com.example.cardashboard.domain.model.DriveMode
import com.example.cardashboard.domain.model.VehicleDataState
import kotlinx.coroutines.flow.Flow

/**
 * One place vehicle data can come from.
 *
 * Two implementations exist and they are deliberately kept apart so demo values can never be
 * mistaken for measurements: [com.example.cardashboard.data.demo.DemoVehicleDataSource] simulates a
 * drive cycle, and [NoVehicleInterfaceDataSource] reports that this build has no way to read a real
 * vehicle.
 */
interface VehicleDataSource {

    /** Cold stream of snapshots. Collection starts the source; cancellation stops it. */
    fun observe(): Flow<VehicleDataState>

    /** Clears trip-computer values. Odometer is never affected. */
    suspend fun resetTrip() = Unit

    /** Records the driver's drive-mode selection. */
    suspend fun selectDriveMode(mode: DriveMode) = Unit

    /** Returns the source to its initial state, where that is meaningful. */
    suspend fun reset() = Unit
}
