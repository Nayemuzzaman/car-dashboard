package com.csjotlab.cardashboard.nav.location

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import kotlinx.coroutines.flow.Flow

data class LocationReading(
    val point: GeoPoint,
    val speedMps: Float?,
    val courseDegrees: Float?,
    val accuracyMeters: Float?,
    val timestampMs: Long,
)

/** Supplies GPS fixes. One implementation is the platform `LocationManager`; AAOS `CarSensors` can
 *  replace it behind this same interface later. */
interface LocationProvider {
    val readings: Flow<LocationReading>
    suspend fun start()
    suspend fun stop()
}
