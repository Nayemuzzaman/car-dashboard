package com.csjotlab.cardashboard.nav.engine

import com.csjotlab.cardashboard.nav.domain.GpsQuality
import com.csjotlab.cardashboard.nav.location.LocationReading

data class FilteredLocation(
    /** The fix to use, or null when this reading was rejected. */
    val fix: LocationReading?,
    val quality: GpsQuality,
)

/**
 * First line of defence against real-world GPS: coarse fixes, multipath jumps, parked-car jitter and
 * chips that report no course. It only ever passes on, holds back, or annotates a position the
 * platform actually reported — it never invents one.
 *
 * - A fix coarser than [maxAccuracyMeters] is dropped (quality [GpsQuality.Degraded]).
 * - A fix implying more than [maxSpeedMps] from the last accepted one is dropped, unless
 *   [jumpConfirmations] such fixes arrive in a row (then the earlier position was the wrong one) or
 *   the gap is longer than [longGapMs] (tunnel exit, GPS re-acquired).
 * - While stationary, a fix inside the accuracy circle of the held position is reported *at* the held
 *   position, so the marker and the heading do not wander.
 * - With no reported course, a course and speed are derived from the last anchor once the car has
 *   moved [minCourseMeters]; closer than that, GPS noise dominates and no course is claimed.
 */
class LocationFilter(
    private val maxAccuracyMeters: Float = 100f,
    private val goodAccuracyMeters: Float = 25f,
    private val maxSpeedMps: Double = 70.0,
    private val jumpConfirmations: Int = 3,
    private val longGapMs: Long = 30_000L,
    private val stationarySpeedMps: Float = 0.5f,
    private val minCourseMeters: Double = 8.0,
) {
    private var accepted: LocationReading? = null
    private var courseAnchor: LocationReading? = null
    private var rejectedJumps = 0

    fun reset() {
        accepted = null
        courseAnchor = null
        rejectedJumps = 0
    }

    fun process(reading: LocationReading): FilteredLocation {
        val accuracy = reading.accuracyMeters
        if (accuracy != null && accuracy > maxAccuracyMeters) {
            return FilteredLocation(null, GpsQuality.Degraded)
        }
        val quality = if (accuracy == null || accuracy <= goodAccuracyMeters) GpsQuality.Good else GpsQuality.Degraded

        val previous = accepted
        if (previous != null && isImplausibleJump(previous, reading)) {
            rejectedJumps++
            if (rejectedJumps < jumpConfirmations) return FilteredLocation(null, GpsQuality.Degraded)
            courseAnchor = null // the old anchor belonged to the wrong position
        }
        rejectedJumps = 0

        var fix = withDerivedMotion(reading)
        if (previous != null && isStationaryJitter(previous, fix)) {
            fix = fix.copy(point = previous.point)
        }
        accepted = fix
        return FilteredLocation(fix, quality)
    }

    private fun isImplausibleJump(previous: LocationReading, next: LocationReading): Boolean {
        val dtMs = next.timestampMs - previous.timestampMs
        if (dtMs > longGapMs) return false
        val meters = GeoMath.distanceMeters(previous.point, next.point)
        val slack = (previous.accuracyMeters ?: 0f) + (next.accuracyMeters ?: 0f)
        val seconds = dtMs.coerceAtLeast(MIN_DT_MS) / 1_000.0
        return (meters - slack) / seconds > maxSpeedMps
    }

    private fun isStationaryJitter(previous: LocationReading, next: LocationReading): Boolean {
        val speed = next.speedMps ?: return false
        if (speed >= stationarySpeedMps) return false
        val radius = maxOf(next.accuracyMeters ?: 0f, MIN_JITTER_RADIUS_METERS).toDouble()
        return GeoMath.distanceMeters(previous.point, next.point) <= radius
    }

    private fun withDerivedMotion(reading: LocationReading): LocationReading {
        val anchor = courseAnchor
        if (anchor == null) {
            courseAnchor = reading
            return reading
        }
        val meters = GeoMath.distanceMeters(anchor.point, reading.point)
        if (meters < minCourseMeters) return reading
        courseAnchor = reading
        if (reading.courseDegrees != null && reading.speedMps != null) return reading
        val seconds = (reading.timestampMs - anchor.timestampMs) / 1_000.0
        return reading.copy(
            courseDegrees = reading.courseDegrees ?: GeoMath.bearingDegrees(anchor.point, reading.point),
            speedMps = reading.speedMps ?: if (seconds > 0) (meters / seconds).toFloat() else null,
        )
    }

    private companion object {
        const val MIN_DT_MS = 200L
        const val MIN_JITTER_RADIUS_METERS = 10f
    }
}

