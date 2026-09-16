package com.csjotlab.cardashboard.nav.engine

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.domain.NavigationPhase
import com.csjotlab.cardashboard.nav.domain.RerouteState
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.domain.RouteStep
import com.csjotlab.cardashboard.vehicle.domain.Clock
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationEngineTest {

    private val metersPerDegree = 111_194.9
    private val geometry = listOf(
        GeoPoint(0.0, 0.0),
        GeoPoint(500.0 / metersPerDegree, 0.0),
        GeoPoint(700.0 / metersPerDegree, 0.0),
    )

    private val route = Route(
        origin = geometry.first(),
        destination = geometry.last(),
        steps = listOf(
            RouteStep(Maneuver(ManeuverType.Depart, null), 500f, 60L, emptyList()),
            RouteStep(Maneuver(ManeuverType.TurnLeft, null), 200f, 30L, emptyList()),
            RouteStep(Maneuver(ManeuverType.Arrive, null), 0f, 0L, emptyList()),
        ),
        geometry = geometry,
        totalDistanceMeters = 700f,
        totalDurationSeconds = 90L,
    )

    private var now = 1_000L
    private val clock = Clock { now }

    private fun update(
        engine: NavigationEngine,
        route: Route? = this.route,
        location: GeoPoint? = geometry.first(),
        speedMps: Float? = 3f,
        gpsCourseDegrees: Float? = 55f,
        compassDegrees: Float? = 200f,
        rerouteState: RerouteState = RerouteState.Idle,
    ): NavigationEngineResult = engine.update(
        NavigationUpdate(route, location, speedMps, gpsCourseDegrees, compassDegrees, rerouteState),
    )

    @Test
    fun `setting a route with no location fix enters previewing`() {
        val engine = NavigationEngine(clock)
        val result = update(engine, location = null)

        assertEquals(NavigationPhase.Previewing, result.state.phase)
        assertEquals(Signal.Unknown, result.state.location)
        assertFalse(result.requestReroute)
    }

    @Test
    fun `clearing the route and fix returns to idle with nothing known`() {
        val engine = NavigationEngine(clock)
        update(engine)
        val result = update(engine, route = null, location = null)

        assertEquals(NavigationPhase.Idle, result.state.phase)
        assertEquals(Signal.Unknown, result.state.location)
        assertNull(result.state.route)
    }

    @Test
    fun `moving uses the gps course as the heading`() {
        val engine = NavigationEngine(clock)
        val result = update(engine, speedMps = 3f, gpsCourseDegrees = 55f, compassDegrees = 200f)

        assertEquals(55f, result.state.headingDegrees.valueOrNull()!!, 0.001f)
    }

    @Test
    fun `stationary uses the compass azimuth as the heading`() {
        val engine = NavigationEngine(clock)
        val result = update(engine, speedMps = 1f, gpsCourseDegrees = 55f, compassDegrees = 200f)

        assertEquals(200f, result.state.headingDegrees.valueOrNull()!!, 0.001f)
    }

    @Test
    fun `a moving fix without a course falls back to compass`() {
        val engine = NavigationEngine(clock)
        val result = update(engine, speedMps = 3f, gpsCourseDegrees = null, compassDegrees = 200f)

        assertEquals(200f, result.state.headingDegrees.valueOrNull()!!, 0.001f)
    }

    @Test
    fun `a route plus a live fix enters navigating with a maneuver phrase`() {
        val engine = NavigationEngine(clock)
        val result = update(engine, location = geometry.first())

        assertEquals(NavigationPhase.Navigating, result.state.phase)
        assertEquals("Turn left in 500 m", result.state.maneuverPhrase)
        assertEquals(700f, result.state.remainingDistanceMeters!!.valueOrNull()!!, 1f)
        assertFalse(result.requestReroute)
    }

    @Test
    fun `off-route triggers exactly one reroute request on the rising edge`() {
        val engine = NavigationEngine(clock)
        update(engine, location = geometry.first())

        val offRoute = GeoPoint(0.005, 0.001) // ~111 m east of the route

        assertFalse(update(engine, location = offRoute).requestReroute)
        assertFalse(update(engine, location = offRoute).requestReroute)

        val triggered = update(engine, location = offRoute)
        assertTrue(triggered.state.offRoute)
        assertTrue(triggered.requestReroute)

        val sustained = update(engine, location = offRoute)
        assertTrue(sustained.state.offRoute)
        assertFalse(sustained.requestReroute)
    }

    @Test
    fun `a new route that still leaves the stationary fix off-route does not re-request until the car moves`() {
        val engine = NavigationEngine(clock)
        update(engine, location = geometry.first())
        val parked = GeoPoint(0.005, 0.001) // ~111 m east of the road; the car is not moving
        repeat(2) { update(engine, location = parked) }
        assertTrue(update(engine, location = parked).requestReroute)

        // The reroute comes back snapped to the same road, so the fix is still ~111 m off it.
        val rerouted = route.copy(totalDurationSeconds = 91L)
        update(engine, route = rerouted, location = parked)
        repeat(3) { assertFalse("stationary: must not loop", update(engine, route = rerouted, location = parked).requestReroute) }

        // Once the driver has moved a meaningful distance (still off-route), one fresh reroute is
        // allowed — and then gated again until they move further.
        val moved = GeoPoint(0.005, 0.0015) // ~55 m further east
        assertTrue(update(engine, route = rerouted, location = moved).requestReroute)
        assertFalse(update(engine, route = rerouted, location = moved).requestReroute)
    }

    @Test
    fun `a new destination resets the reroute movement gate`() {
        val engine = NavigationEngine(clock)
        update(engine, location = geometry.first())
        val parked = GeoPoint(0.005, 0.001)
        repeat(3) { update(engine, location = parked) }

        update(engine, route = null, location = parked)
        val other = route.copy(destination = GeoPoint(0.01, 0.0))
        // Three consecutive off-route fixes on the new route; the third one may request.
        update(engine, route = other, location = parked)
        update(engine, route = other, location = parked)

        assertTrue(update(engine, route = other, location = parked).requestReroute)
    }

    @Test
    fun `a failed reroute is a state and does not loop`() {
        val engine = NavigationEngine(clock)
        update(engine, location = geometry.first())

        val offRoute = GeoPoint(0.005, 0.001)
        repeat(3) { update(engine, location = offRoute) }

        val failed = update(engine, location = offRoute, rerouteState = RerouteState.Failed)
        assertEquals(RerouteState.Failed, failed.state.rerouteState)
        assertFalse(failed.requestReroute)
    }

    @Test
    fun `reaching the destination enters arrived`() {
        val engine = NavigationEngine(clock)
        val result = update(engine, location = geometry.last())

        assertEquals(NavigationPhase.Arrived, result.state.phase)
    }
}
