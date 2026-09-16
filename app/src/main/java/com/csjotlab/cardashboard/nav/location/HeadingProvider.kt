package com.csjotlab.cardashboard.nav.location

import kotlinx.coroutines.flow.Flow

/** Supplies a compass azimuth in degrees, 0..360, used for heading-up when the car is stationary. */
interface HeadingProvider {
    val readings: Flow<Float>
    suspend fun start()
    suspend fun stop()
}
