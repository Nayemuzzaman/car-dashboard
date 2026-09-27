package com.csjotlab.cardashboard.nav.engine

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.GpsQuality
import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.domain.NavigationPhase
import com.csjotlab.cardashboard.nav.domain.RerouteState
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.domain.RouteStep
import com.csjotlab.cardashboard.vehicle.domain.Clock
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine behaviours a real drive depends on, one fix at a time with real fix timestamps. */
class NavigationEngineDriveTest {

    private val m = 1.0 / 111_194.9

    // North 500 m on High St, right onto Route 3 for 300 m east, arrive.
    private val start = GeoPoint(0.0, 0.0)
    private val corner = GeoPoint(500 * m, 0.0)
    private val end = GeoPoint(500 * m, 300 * m)
    private val route = Route(
        origin = start,
        destination = end,
        steps = listOf(
            RouteStep(Maneuver(ManeuverType.Depart, null), 500f, 50L, listOf(start, corner), roadName = "High St"),
            RouteStep(Maneuver(ManeuverType.TurnRight, null), 300f, 30L, listOf(corner, end), roadName = "Route 3"),
            RouteStep(Maneuver(ManeuverType.Arrive, null), 0f, 0L, listOf(end)),
        ),
        geometry = listOf(start, corner, end),
        totalDistanceMeters = 800f,
        totalDurationSeconds = 80L,
    )

    private var now = 0L
    private val engine = NavigationEngine(Clock { now })

    private fun fix(
        north: Double,
        east: Double = 0.0,
        speed: Float? = 10f,
        course: Float? = 0f,
        compass: Float? = null,
        guiding: Boolean = true,
        route: Route? = this.route,
        rerouteState: RerouteState = RerouteState.Idle,
    ): NavigationEngineResult {
        now += 1_000L
        return engine.update(
            NavigationUpdate(
                route = route,
                location = GeoPoint(north * m, east * m),
                speedMps = speed,
                gpsCourseDegrees = course,
                compassDegrees = compass,
                rerouteState = rerouteState,
                accuracyMeters = 5f,
                fixTimestampMs = now,
                guidanceActive = guiding,
                gpsQuality = GpsQuality.Good,
            ),
        )
    }

    private fun compassOnly(degrees: Float, north: Double, east: Double = 0.0): NavigationEngineResult = engine.update(
        NavigationUpdate(
            route = route, location = GeoPoint(north * m, east * m), speedMps = 10f, gpsCourseDegrees = 0f,
            compassDegrees = degrees, rerouteState = RerouteState.Idle, accuracyMeters = 5f,
            fixTimestampMs = now, // the same fix as before: this is a compass tick, not a new fix
            guidanceActive = true, gpsQuality = GpsQuality.Good,
        ),
    )

    private fun gpsLost(): NavigationEngineResult {
        now += 1_000L
        return engine.update(
            NavigationUpdate(
                route = route, location = null, speedMps = null, gpsCourseDegrees = null, compassDegrees = null,
                rerouteState = RerouteState.Idle, guidanceActive = true, gpsQuality = GpsQuality.Lost,
            ),
        )
    }

    @Test
    fun `the banner has distance, road-named instruction and current road`() {
        val s = fix(200.0).state

        assertEquals(300f, s.distanceToManeuverMeters!!, 1f)
        assertEquals("Turn right onto Route 3", s.maneuverInstruction)
        assertEquals("High St", s.currentRoadName)
        assertEquals(200f, s.distanceAlongRouteMeters!!, 1f)
        assertEquals(NavigationPhase.Navigating, s.phase)
    }

    @Test
    fun `instructions advance after the turn and the remaining distance keeps falling`() {
        fix(200.0)
        val beforeTurn = fix(480.0).state
        assertEquals(ManeuverType.TurnRight, beforeTurn.nextManeuver?.type)

        val afterTurn = fix(500.0, 100.0, course = 90f).state
        assertEquals(ManeuverType.Arrive, afterTurn.nextManeuver?.type)
        assertEquals("Route 3", afterTurn.currentRoadName)
        assertTrue(afterTurn.remainingDistanceMeters!!.valueOrNull()!! < beforeTurn.remainingDistanceMeters!!.valueOrNull()!!)
    }

    @Test
    fun `a stopped car keeps its last driving direction, not the compass`() {
        fix(100.0, course = 0f)
        fix(200.0, course = 0f)
        val stopped = fix(200.0, speed = 0f, course = 170f, compass = 250f).state

        assertEquals(0f, stopped.headingDegrees.valueOrNull()!!, 0.5f)
    }

    @Test
    fun `compass ticks between fixes never count as off-route evidence`() {
        fix(100.0)
        fix(200.0, 100.0) // one bad fix, 100 m off the road
        repeat(20) { assertFalse(compassOnly(10f, 200.0, 100.0).requestReroute) }
        assertFalse(compassOnly(10f, 200.0, 100.0).state.offRoute)
    }

    @Test
    fun `leaving the road for several seconds requests one reroute`() {
        fix(100.0)
        val results = (1..6).map { fix(150.0 + it * 10, 120.0) }
        assertEquals(1, results.count { it.requestReroute })
        assertTrue(results.last().state.offRoute)
    }

    @Test
    fun `a lost gps signal holds progress instead of restarting from the route origin`() {
        fix(100.0)
        val before = fix(300.0).state

        val lost = gpsLost().state

        assertEquals(NavigationPhase.Navigating, lost.phase)
        assertEquals(GpsQuality.Lost, lost.gpsQuality)
        assertEquals(before.remainingDistanceMeters!!.valueOrNull()!!, lost.remainingDistanceMeters!!.valueOrNull()!!, 0.01f)
        assertEquals(before.nextManeuver, lost.nextManeuver)
        assertNull(lost.location.valueOrNull())
        assertNotNull("the marker stays where it was last seen", lost.displayLocation)
        assertFalse(gpsLost().requestReroute)
    }

    @Test
    fun `the vehicle is drawn on the road when the fix is a few metres off it`() {
        val s = fix(200.0, 6.0).state
        assertEquals(0.0, s.displayLocation!!.longitude, 1e-9)
        assertEquals(6 * m, s.location.valueOrNull()!!.longitude, 1e-9) // the raw fix is still reported
    }

    @Test
    fun `without guidance a route is a preview from its origin and never reroutes or arrives`() {
        val atEnd = fix(500.0, 300.0, guiding = false)
        assertEquals(NavigationPhase.Previewing, atEnd.state.phase)
        assertEquals(800f, atEnd.state.remainingDistanceMeters!!.valueOrNull()!!, 1f)

        repeat(6) { assertFalse(fix(250.0, 400.0, guiding = false).requestReroute) }
    }

    @Test
    fun `arrival is reached at the end of the route`() {
        fix(100.0)
        fix(500.0, 100.0, course = 90f)
        assertEquals(NavigationPhase.Arrived, fix(500.0, 295.0, course = 90f).state.phase)
    }

    @Test
    fun `vehicle speed decides stationary when the gps reports no speed`() {
        fix(100.0, course = 0f)
        now += 1_000L
        val parked = engine.update(
            NavigationUpdate(
                route = route, location = GeoPoint(100 * m, 0.0), speedMps = null, gpsCourseDegrees = 200f,
                compassDegrees = 300f, rerouteState = RerouteState.Idle, fixTimestampMs = now,
                vehicleSpeedMps = 0f,
            ),
        ).state
        assertEquals(0f, parked.headingDegrees.valueOrNull()!!, 0.5f)
    }
}
