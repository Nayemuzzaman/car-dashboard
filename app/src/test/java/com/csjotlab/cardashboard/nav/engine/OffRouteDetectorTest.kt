package com.csjotlab.cardashboard.nav.engine

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.domain.RerouteState
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.domain.RouteStep
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OffRouteDetectorTest {

    // A north-south route along longitude 0.
    private val route = Route(
        origin = GeoPoint(0.0, 0.0),
        destination = GeoPoint(0.01, 0.0),
        steps = listOf(
            RouteStep(Maneuver(ManeuverType.Depart, null), 0f, 0L, emptyList()),
            RouteStep(Maneuver(ManeuverType.Arrive, null), 0f, 0L, emptyList()),
        ),
        geometry = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.01, 0.0)),
        totalDistanceMeters = 1_112f,
        totalDurationSeconds = 0L,
    )

    private val onRoute = GeoPoint(0.005, 0.0)
    private val offRoute = GeoPoint(0.005, 0.001) // ~111 m east of the line

    @Test
    fun `an on-route fix never triggers`() {
        val detector = OffRouteDetector(thresholdMeters = 30f, consecutiveFixes = 3)
        repeat(5) { assertFalse(detector.check(onRoute, route)) }
    }

    @Test
    fun `a single out-of-route fix does not trigger`() {
        val detector = OffRouteDetector(thresholdMeters = 30f, consecutiveFixes = 3)
        assertFalse(detector.check(offRoute, route))
    }

    @Test
    fun `triggering needs the configured number of consecutive out-of-route fixes`() {
        val detector = OffRouteDetector(thresholdMeters = 30f, consecutiveFixes = 3)

        assertFalse(detector.check(offRoute, route))
        assertFalse(detector.check(offRoute, route))
        assertTrue(detector.check(offRoute, route))
    }

    @Test
    fun `an on-route fix resets the streak`() {
        val detector = OffRouteDetector(thresholdMeters = 30f, consecutiveFixes = 3)

        assertFalse(detector.check(offRoute, route))
        assertFalse(detector.check(offRoute, route))
        assertFalse(detector.check(onRoute, route)) // resets the two-fix streak

        assertFalse(detector.check(offRoute, route))
        assertFalse(detector.check(offRoute, route))
        assertTrue(detector.check(offRoute, route))
    }
}

class ReroutePolicyTest {

    @Test
    fun `a request is legitimate only while off-route and idle`() {
        assertTrue(ReroutePolicy.shouldRequest(offRoute = true, RerouteState.Idle))
    }

    @Test
    fun `an in-flight request is not duplicated`() {
        assertFalse(ReroutePolicy.shouldRequest(offRoute = true, RerouteState.InProgress))
    }

    @Test
    fun `a failed request does not loop`() {
        assertFalse(ReroutePolicy.shouldRequest(offRoute = true, RerouteState.Failed))
    }

    @Test
    fun `on-route never requests a reroute`() {
        assertFalse(ReroutePolicy.shouldRequest(offRoute = false, RerouteState.Idle))
    }
}
