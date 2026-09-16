package com.csjotlab.cardashboard.nav.engine

/** Pure heading arithmetic: normalisation and the signed shortest turn between two bearings. */
object HeadingMath {
    fun normalize(bearingDegrees: Float): Float = ((bearingDegrees % 360f) + 360f) % 360f

    /**
     * The signed turn from [fromDegrees] to [toDegrees], in `(-180, 180]`. Turning left of the
     * heading is positive, so `350 -> 10` is `+20`, never `-340`. A half turn normalises to `+180`.
     */
    fun shortestDelta(fromDegrees: Float, toDegrees: Float): Float {
        val from = normalize(fromDegrees)
        val to = normalize(toDegrees)
        var delta = (to - from) % 360f
        if (delta > 180f) delta -= 360f
        if (delta <= -180f) delta += 360f
        return delta
    }
}

/**
 * Smooths a heading-up bearing toward a target by turning the shortest arc, clamped to a maximum
 * turn rate. The camera eases to whatever this returns, so the map never spins the long way around
 * north and never teleports through a large bearing change.
 */
class HeadingSmoother(
    private val maxTurnRateDegPerSec: Float = 90f,
    initialBearingDegrees: Float = 0f,
) {
    private var smoothedDegrees = HeadingMath.normalize(initialBearingDegrees)

    fun next(targetDegrees: Float, elapsedMs: Long): Float {
        val maxTurnDegrees = maxTurnRateDegPerSec * elapsedMs / 1_000f
        val delta = HeadingMath.shortestDelta(smoothedDegrees, targetDegrees)
        smoothedDegrees = HeadingMath.normalize(smoothedDegrees + delta.coerceIn(-maxTurnDegrees, maxTurnDegrees))
        return smoothedDegrees
    }

    /** Snap to a bearing and forget any previous smoothing. Used for the first heading sample. */
    fun reset(initialBearingDegrees: Float) {
        smoothedDegrees = HeadingMath.normalize(initialBearingDegrees)
    }
}

/**
 * Chooses which raw bearing is authoritative for the heading-up camera.
 *
 * While moving, GPS course-over-ground is the truth about which way the car is facing. While
 * stationary, GPS course is meaningless, so the compass azimuth takes over. Either may be absent;
 * a null result means "no heading is known", which the caller surfaces as `Signal.Unknown`.
 */
object HeadingSourcePolicy {
    const val GPS_COURSE_MIN_SPEED_MPS = 2.0f

    fun select(speedMps: Float?, gpsCourseDegrees: Float?, compassDegrees: Float?): Float? = when {
        speedMps != null && speedMps >= GPS_COURSE_MIN_SPEED_MPS && gpsCourseDegrees != null -> gpsCourseDegrees
        compassDegrees != null -> compassDegrees
        else -> null
    }
}
