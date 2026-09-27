package com.csjotlab.cardashboard.nav.engine

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.RerouteState
import com.csjotlab.cardashboard.nav.domain.Route

/**
 * Detects that the driver has left the route.
 *
 * A single out-of-route fix is not enough: GPS noise would produce spurious reroutes. The position
 * must be farther than the tolerance from the route for [consecutiveFixes] fixes in a row **and**
 * for at least [minDurationMs] (when fix times are known); any in-route fix resets the streak.
 *
 * The tolerance is `max(thresholdMeters, 1.5 × accuracy)`, capped at [maxThresholdMeters], so a
 * coarse fix in a street canyon is not taken as proof of leaving the road. A fix coarser than
 * [ignoreAccuracyMeters] is no evidence either way: it neither extends nor resets the streak.
 */
class OffRouteDetector(
    private val thresholdMeters: Float = 30f,
    private val consecutiveFixes: Int = 3,
    private val minDurationMs: Long = 4_000L,
    private val maxThresholdMeters: Float = 80f,
    private val ignoreAccuracyMeters: Float = 50f,
) {
    private var offRouteStreak = 0
    private var streakStartMs: Long? = null

    fun check(position: GeoPoint, route: Route): Boolean =
        checkLateral(GeoMath.distanceToPolylineMeters(position, route.geometry), accuracyMeters = null, timestampMs = null)

    /** [lateralMeters] is the fix's distance from the route; [timestampMs] null skips the time rule. */
    fun checkLateral(lateralMeters: Double, accuracyMeters: Float?, timestampMs: Long?): Boolean {
        if (accuracyMeters != null && accuracyMeters > ignoreAccuracyMeters) return isOffRoute(timestampMs)
        val tolerance = maxOf(thresholdMeters, (accuracyMeters ?: 0f) * ACCURACY_FACTOR).coerceAtMost(maxThresholdMeters)
        if (lateralMeters > tolerance) {
            if (offRouteStreak == 0) streakStartMs = timestampMs
            offRouteStreak++
            return isOffRoute(timestampMs)
        }
        reset()
        return false
    }

    private fun isOffRoute(timestampMs: Long?): Boolean {
        if (offRouteStreak < consecutiveFixes) return false
        val start = streakStartMs ?: return true
        val now = timestampMs ?: return true
        return now - start >= minDurationMs
    }

    /** Forget the streak. Called when the route changes, so a new route starts fresh. */
    fun reset() {
        offRouteStreak = 0
        streakStartMs = null
    }

    private companion object {
        const val ACCURACY_FACTOR = 1.5f
    }
}

/**
 * Whether an off-route condition should trigger a new routing request right now.
 *
 * The rising-edge ("exactly one request per off-route event") is the caller's concern; this policy
 * only says that a request is legitimate while off-route and no request is already in flight or
 * failed. A failed reroute therefore never loops.
 */
object ReroutePolicy {
    fun shouldRequest(offRoute: Boolean, rerouteState: RerouteState): Boolean =
        offRoute && rerouteState == RerouteState.Idle
}
