package com.csjotlab.cardashboard.nav.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.vehicle.domain.Clock
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Platform GPS source. Thin by design: it adapts `LocationManager` callbacks into
 * [LocationReading]s and does no navigation logic.
 *
 * On API < 26 there is no `hasSpeed()`/`hasBearing()`/`hasAccuracy()`, so those fields are emitted
 * as null (`Signal.Unknown` downstream) rather than guessed as zero.
 */
class GpsLocationProvider(
    private val context: Context,
    private val clock: Clock,
) : LocationProvider {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _readings = MutableSharedFlow<LocationReading>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val readings: Flow<LocationReading> = _readings.asSharedFlow()

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _readings.tryEmit(location.toReading(clock.nowMs()))
        }
    }

    override suspend fun start() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        // start() is called from the application scope (no Looper); the four-argument overload
        // would throw "Can't create handler inside thread that has not called Looper.prepare()",
        // so the main looper is named explicitly. A failure is logged, never silently swallowed.
        try {
            // A real fix the phone took moments ago lets the map start where the car is instead of
            // waiting for a fresh one; it keeps its true age, and anything older is not used.
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { last ->
                val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos) / 1_000_000
                if (ageMs in 0..MAX_LAST_KNOWN_AGE_MS) _readings.tryEmit(last.toReading(clock.nowMs() - ageMs))
            }
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_INTERVAL_MS,
                MIN_DISTANCE_METERS,
                listener,
                Looper.getMainLooper(),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "GPS updates unavailable: ${t.message}")
        }
    }

    override suspend fun stop() {
        locationManager.removeUpdates(listener)
    }

    private fun Location.toReading(timestampMs: Long): LocationReading {
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        return LocationReading(
            point = GeoPoint(latitude, longitude),
            speedMps = if (modern && hasSpeed()) speed else null,
            courseDegrees = if (modern && hasBearing()) bearing else null,
            accuracyMeters = if (modern && hasAccuracy()) accuracy else null,
            timestampMs = timestampMs,
            courseAccuracyDegrees = if (modern && hasBearingAccuracy()) bearingAccuracyDegrees else null,
        )
    }

    private companion object {
        const val TAG = "CarDash/Nav"
        const val MIN_INTERVAL_MS = 1_000L
        const val MIN_DISTANCE_METERS = 0f
        const val MAX_LAST_KNOWN_AGE_MS = 60_000L
    }
}
