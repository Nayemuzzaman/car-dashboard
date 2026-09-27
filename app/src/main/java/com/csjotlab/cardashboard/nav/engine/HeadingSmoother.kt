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
 * Chooses which raw bearing is authoritative for the heading-up camera and the vehicle arrow.
 *
 * While moving, GPS course-over-ground is the truth about which way the car is going. When the car
 * stops, GPS course turns into noise — and the phone's compass says which way the *phone* faces,
 * which in a car mount is unrelated to the car and is disturbed by the car's metal and electrics. So
 * a stopped car keeps its **last reliable course**; the compass is only used before any course has
 * been reliable (parked at the start of a trip), so the map is not arbitrarily north-up.
 */
object HeadingSourcePolicy {
    const val GPS_COURSE_MIN_SPEED_MPS = 2.0f
    const val MAX_COURSE_ACCURACY_DEGREES = 35f

    fun isCourseReliable(speedMps: Float?, courseDegrees: Float?, courseAccuracyDegrees: Float?): Boolean =
        speedMps != null && speedMps >= GPS_COURSE_MIN_SPEED_MPS && courseDegrees != null &&
            (courseAccuracyDegrees == null || courseAccuracyDegrees <= MAX_COURSE_ACCURACY_DEGREES)

    fun select(
        speedMps: Float?,
        gpsCourseDegrees: Float?,
        courseAccuracyDegrees: Float?,
        lastReliableCourseDegrees: Float?,
        compassDegrees: Float?,
    ): Float? = when {
        isCourseReliable(speedMps, gpsCourseDegrees, courseAccuracyDegrees) -> gpsCourseDegrees
        lastReliableCourseDegrees != null -> lastReliableCourseDegrees
        else -> compassDegrees
    }

    /** The choice with no history: course while moving, otherwise the compass. */
    fun select(speedMps: Float?, gpsCourseDegrees: Float?, compassDegrees: Float?): Float? =
        select(speedMps, gpsCourseDegrees, null, null, compassDegrees)
}
