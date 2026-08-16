package com.example.cardashboard.domain.repository

import com.example.cardashboard.domain.model.DriveMode
import com.example.cardashboard.domain.model.VehicleDataState
import kotlinx.coroutines.flow.Flow

/**
 * Single entry point the UI uses to reach vehicle data.
 *
 * Implementations decide which data source is active (demo or vehicle) and are responsible for
 * sanitizing snapshots before emitting them.
 */
interface VehicleRepository {

    /** Continuous stream of dashboard snapshots. Cold: collection starts the active source. */
    val vehicleData: Flow<VehicleDataState>

    /**
     * Clears the trip computer. The odometer is deliberately untouched.
     * Only honoured by sources that own the trip values, such as the demo simulation.
     */
    suspend fun resetTrip()

    /**
     * Requests a different drive mode. Sources that cannot change vehicle behaviour simply record
     * the selection so the dashboard can reflect it — no real vehicle is commanded.
     */
    suspend fun selectDriveMode(mode: DriveMode)

    /** Restarts the demo simulation from its initial state. No effect on a real vehicle source. */
    suspend fun resetDemoData()
}
