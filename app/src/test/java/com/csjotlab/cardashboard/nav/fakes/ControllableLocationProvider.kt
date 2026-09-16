package com.csjotlab.cardashboard.nav.fakes

import com.csjotlab.cardashboard.nav.location.HeadingProvider
import com.csjotlab.cardashboard.nav.location.LocationProvider
import com.csjotlab.cardashboard.nav.location.LocationReading
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class ControllableLocationProvider : LocationProvider {
    private val _readings = MutableSharedFlow<LocationReading>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val readings: Flow<LocationReading> = _readings.asSharedFlow()

    var started = false
        private set

    override suspend fun start() {
        started = true
    }

    override suspend fun stop() {
        started = false
    }

    fun emit(reading: LocationReading) {
        _readings.tryEmit(reading)
    }
}

class ControllableHeadingProvider : HeadingProvider {
    private val _readings = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val readings: Flow<Float> = _readings.asSharedFlow()

    var started = false
        private set

    override suspend fun start() {
        started = true
    }

    override suspend fun stop() {
        started = false
    }

    fun emit(degrees: Float) {
        _readings.tryEmit(degrees)
    }
}
