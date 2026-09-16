package com.csjotlab.cardashboard.nav.engine

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.RerouteState
import com.csjotlab.cardashboard.nav.domain.Route

/**
 * Detects that the driver has left the route.
 *
 * A single out-of-route fix is not enough: GPS noise would produce spurious reroutes. The position
 * must be farther than [thresholdMeters] from the route geometry for [consecutiveFixes] fixes in a
 * row; any in-route fix resets the streak.
 */
class OffRouteDetector(
    private val thresholdMeters: Float = 30f,
    private val consecutiveFixes: Int = 3,
) {
    private var offRouteStreak = 0

    fun check(position: GeoPoint, route: Route): Boolean {
        val distance = GeoMath.distanceToPolylineMeters(position, route.geometry)
        if (distance > thresholdMeters) {
            offRouteStreak++
            return offRouteStreak >= consecutiveFixes
        }
        offRouteStreak = 0
        return false
    }

    /** Forget the streak. Called when the route changes, so a new route starts fresh. */
    fun reset() {
        offRouteStreak = 0
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
