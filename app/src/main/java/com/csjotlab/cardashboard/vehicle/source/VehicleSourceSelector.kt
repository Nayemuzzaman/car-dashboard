package com.csjotlab.cardashboard.vehicle.source

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Decides which source is active.
 *
 * There is deliberately no path from a failing real source to the mock: if the real source goes
 * away, the answer is "no source", and the dashboard says so. Spec section 6.
 */
class VehicleSourceSelector(
    realSourceAvailability: Flow<VehicleDataSource?>,
    mockModeEnabled: Flow<Boolean>,
    private val debugBuild: Boolean,
    private val mockSourceFactory: () -> VehicleDataSource,
) {
    private var cachedMock: VehicleDataSource? = null

    val activeSource: Flow<VehicleDataSource?> =
        combine(realSourceAvailability, mockModeEnabled) { real, mockEnabled ->
            when {
                real != null -> real
                debugBuild && mockEnabled -> cachedMock ?: mockSourceFactory().also { cachedMock = it }
                else -> null
            }
        }.distinctUntilChanged()
}
