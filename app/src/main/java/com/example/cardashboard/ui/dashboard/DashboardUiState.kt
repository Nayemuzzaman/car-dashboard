package com.example.cardashboard.ui.dashboard

import androidx.compose.runtime.Immutable
import com.example.cardashboard.domain.model.DashboardSettings
import com.example.cardashboard.domain.model.VehicleDataSourceKind
import com.example.cardashboard.domain.model.VehicleDataState
import com.example.cardashboard.domain.model.VehicleState
import com.example.cardashboard.domain.model.VehicleType
import java.time.LocalDateTime

/**
 * Everything the dashboard screen renders, as one immutable value.
 *
 * The state deliberately holds domain values rather than pre-formatted strings: unit conversion and
 * number formatting are presentation concerns and live in the composables that need them, which
 * keeps this class free of `Context` and easy to assert on in tests.
 */
@Immutable
data class DashboardUiState(
    val dataState: VehicleDataState = VehicleDataState.Loading,
    val settings: DashboardSettings = DashboardSettings.DEFAULT,
    val now: LocalDateTime? = null,
    val tripResetConfirmationVisible: Boolean = false
) {
    /** The snapshot to draw: live values, or the last good ones during a dropout. */
    val vehicleState: VehicleState?
        get() = when (dataState) {
            is VehicleDataState.Available -> dataState.vehicleState
            is VehicleDataState.Unavailable -> dataState.lastKnownState
            VehicleDataState.Loading -> null
        }

    /**
     * Powertrain the cluster is drawn for. Taken from live data when there is any, otherwise from
     * the demo profile, so panels do not flip between fuel and battery while the first snapshot is
     * on its way.
     */
    val vehicleType: VehicleType
        get() = vehicleState?.vehicleType ?: settings.demoVehicleType

    val isLoading: Boolean
        get() = dataState is VehicleDataState.Loading

    /** True when values on screen are simulated. Drives the "DEMO DATA" badge. */
    val isDemoData: Boolean
        get() = dataState is VehicleDataState.Available &&
            dataState.source == VehicleDataSourceKind.DEMO

    /** True when the readings on screen are no longer being updated. */
    val isStale: Boolean
        get() = dataState is VehicleDataState.Unavailable && dataState.lastKnownState != null
}
