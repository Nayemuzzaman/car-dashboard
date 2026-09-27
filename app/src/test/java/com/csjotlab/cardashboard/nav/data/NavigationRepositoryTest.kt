package com.csjotlab.cardashboard.nav.data

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.GpsQuality
import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.domain.NavigationPhase
import com.csjotlab.cardashboard.nav.domain.RerouteState
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.domain.RouteStep
import com.csjotlab.cardashboard.nav.fakes.ControllableHeadingProvider
import com.csjotlab.cardashboard.nav.fakes.ControllableLocationProvider
import com.csjotlab.cardashboard.nav.location.LocationReading
import com.csjotlab.cardashboard.nav.routing.FakeRoutingEngine
import com.csjotlab.cardashboard.nav.routing.RouteRequest
import com.csjotlab.cardashboard.nav.routing.RouteResult
import com.csjotlab.cardashboard.vehicle.domain.Clock
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationRepositoryTest {

    private val m = 1.0 / 111_194.9

    /** 700 m due north. */
    private val route = Route(
        origin = GeoPoint(0.0, 0.0),
        destination = GeoPoint(700 * m, 0.0),
        steps = listOf(
            RouteStep(Maneuver(ManeuverType.Depart, null), 500f, 60L, emptyList()),
            RouteStep(Maneuver(ManeuverType.TurnLeft, null), 200f, 30L, emptyList()),
            RouteStep(Maneuver(ManeuverType.Arrive, null), 0f, 0L, emptyList()),
        ),
        geometry = listOf(GeoPoint(0.0, 0.0), GeoPoint(700 * m, 0.0)),
        totalDistanceMeters = 700f,
        totalDurationSeconds = 90L,
    )

    private class Harness(
        val repository: NavigationRepository,
        val location: ControllableLocationProvider,
        val routing: FakeRoutingEngine,
    )

    private fun TestScope.harness(result: (RouteRequest) -> RouteResult = { RouteResult.Success(route) }): Harness {
        val location = ControllableLocationProvider()
        val routing = FakeRoutingEngine(result)
        val repository = NavigationRepository(
            routingEngine = routing,
            locationProvider = location,
            headingProvider = ControllableHeadingProvider(),
            clock = Clock { currentTime },
            scope = backgroundScope,
        )
        runCurrent()
        return Harness(repository, location, routing)
    }

    /** A fix [north]/[east] metres from the origin, stamped with the test clock. */
    private fun TestScope.fix(north: Double = 0.0, east: Double = 0.0, accuracy: Float? = 5f) = LocationReading(
        point = GeoPoint(north * m, east * m),
        speedMps = 10f,
        courseDegrees = 0f,
        accuracyMeters = accuracy,
        timestampMs = currentTime,
    )

    private fun TestScope.drive(h: Harness, north: Double, east: Double = 0.0) {
        advanceTimeBy(1_000L)
        h.location.emit(fix(north, east))
        runCurrent()
    }

    private fun TestScope.guideFromOrigin(h: Harness) {
        h.location.emit(fix())
        h.repository.setDestination(route.destination)
        h.repository.setGuidanceActive(true)
        runCurrent()
    }

    @Test
    fun `destination plus a fix requests and publishes a route as a preview`() = runTest {
        val h = harness()

        h.location.emit(fix())
        h.repository.setDestination(route.destination)
        runCurrent()

        assertEquals(1, h.routing.requests.size)
        assertEquals(NavigationPhase.Previewing, h.repository.snapshot.value.state.phase)
        assertEquals(route, h.repository.snapshot.value.state.route)
    }

    @Test
    fun `starting guidance with a fix enters navigating`() = runTest {
        val h = harness()
        guideFromOrigin(h)

        assertEquals(NavigationPhase.Navigating, h.repository.snapshot.value.state.phase)
        assertTrue(h.repository.snapshot.value.guidanceActive)
    }

    @Test
    fun `a destination selected before a fix waits for the fix to route`() = runTest {
        val h = harness()

        h.repository.setDestination(route.destination)
        runCurrent()
        assertEquals(0, h.routing.requests.size)
        assertEquals(route.destination, h.repository.snapshot.value.destination)
        assertNull(h.repository.snapshot.value.state.route)

        h.location.emit(fix())
        runCurrent()

        assertEquals(1, h.routing.requests.size)
        assertEquals(route, h.repository.snapshot.value.state.route)
    }

    @Test
    fun `leaving the route while guided triggers exactly one reroute, from the car`() = runTest {
        // Like a real router, the new route starts where it was asked to start: at the car.
        val h = harness { request -> RouteResult.Success(route.copy(origin = request.origin, geometry = listOf(request.origin, route.destination))) }
        guideFromOrigin(h)
        drive(h, 100.0)

        // Drifting away from the road, 20 m further each second.
        repeat(8) { drive(h, 110.0 + it * 10, 20.0 + it * 20) }

        assertEquals(2, h.routing.requests.size)
        assertEquals(GeoPoint(160 * m, 120 * m), h.routing.requests.last().origin)
    }

    @Test
    fun `an off-route position without guidance never reroutes`() = runTest {
        val h = harness()
        h.location.emit(fix())
        h.repository.setDestination(route.destination)
        runCurrent()

        repeat(8) { drive(h, 110.0 + it * 10, 20.0 + it * 20) }

        assertEquals(1, h.routing.requests.size)
    }

    @Test
    fun `a failed reroute keeps the current route and retries after moving on`() = runTest {
        var fail = false
        val h = harness { if (fail) RouteResult.Failure("offline") else RouteResult.Success(route) }
        guideFromOrigin(h)
        drive(h, 100.0)
        fail = true
        repeat(8) { drive(h, 110.0 + it * 10, 20.0 + it * 20) }

        val afterFailure = h.repository.snapshot.value
        assertEquals(RerouteState.Failed, afterFailure.state.rerouteState)
        assertEquals("the driver keeps the old route", route, afterFailure.state.route)
        assertEquals(NavigationPhase.Navigating, afterFailure.state.phase)
        val attempts = h.routing.requests.size

        // Still off-route and moving: after the back-off another attempt is made.
        fail = false
        advanceTimeBy(16_000L)
        repeat(3) { drive(h, 300.0 + it * 30, 160.0) }
        assertTrue(h.routing.requests.size > attempts)
        assertEquals(RerouteState.Idle, h.repository.snapshot.value.state.rerouteState)
    }

    @Test
    fun `a failed first route can be retried`() = runTest {
        var fail = true
        val h = harness { if (fail) RouteResult.Failure("offline") else RouteResult.Success(route) }
        h.location.emit(fix())
        h.repository.setDestination(route.destination)
        runCurrent()
        assertEquals(RerouteState.Failed, h.repository.snapshot.value.state.rerouteState)
        assertNull(h.repository.snapshot.value.state.route)

        fail = false
        h.repository.retryRoute()
        runCurrent()

        assertEquals(route, h.repository.snapshot.value.state.route)
        assertEquals(RerouteState.Idle, h.repository.snapshot.value.state.rerouteState)
    }

    @Test
    fun `route alternatives are published and one can be chosen`() = runTest {
        val alternative = route.copy(totalDurationSeconds = 120L)
        val h = harness { RouteResult.Success(route, alternatives = listOf(alternative)) }
        h.location.emit(fix())
        h.repository.setDestination(route.destination)
        runCurrent()
        assertEquals(listOf(route, alternative), h.repository.snapshot.value.routeOptions)
        assertEquals(0, h.repository.snapshot.value.selectedRouteIndex)

        h.repository.selectRoute(1)
        runCurrent()

        assertEquals(alternative, h.repository.snapshot.value.state.route)
        assertEquals(1, h.repository.snapshot.value.selectedRouteIndex)
    }

    @Test
    fun `stop clears the route and destination`() = runTest {
        val h = harness()
        guideFromOrigin(h)
        assertEquals(NavigationPhase.Navigating, h.repository.snapshot.value.state.phase)

        h.repository.stop()
        runCurrent()

        assertEquals(NavigationPhase.Idle, h.repository.snapshot.value.state.phase)
        assertNull(h.repository.snapshot.value.state.route)
        assertNull(h.repository.snapshot.value.destination)
        assertFalse(h.repository.snapshot.value.guidanceActive)
    }

    @Test
    fun `location goes unknown after silence and the signal is reported lost`() = runTest {
        val h = harness()
        h.location.emit(fix())
        runCurrent()
        assertTrue(h.repository.snapshot.value.state.location.valueOrNull() != null)
        assertEquals(GpsQuality.Good, h.repository.snapshot.value.state.gpsQuality)

        advanceTimeBy(6_000L)
        runCurrent()

        assertEquals(Signal.Unknown, h.repository.snapshot.value.state.location)
        assertEquals(GpsQuality.Lost, h.repository.snapshot.value.state.gpsQuality)
    }

    @Test
    fun `guidance holds its progress through a tunnel`() = runTest {
        val h = harness()
        guideFromOrigin(h)
        drive(h, 100.0)
        drive(h, 200.0)
        val before = h.repository.snapshot.value.state.remainingDistanceMeters!!.valueOrNull()!!

        advanceTimeBy(10_000L)
        runCurrent()

        val inTunnel = h.repository.snapshot.value.state
        assertEquals(GpsQuality.Lost, inTunnel.gpsQuality)
        assertEquals(NavigationPhase.Navigating, inTunnel.phase)
        assertEquals(before, inTunnel.remainingDistanceMeters!!.valueOrNull()!!, 0.01f)
        assertNotNull(inTunnel.displayLocation)
    }

    @Test
    fun `a wildly inaccurate fix is not used and marks the signal degraded`() = runTest {
        val h = harness()
        h.location.emit(fix())
        runCurrent()

        advanceTimeBy(1_000L)
        h.location.emit(fix(north = 400.0, accuracy = 300f))
        runCurrent()

        assertEquals(GeoPoint(0.0, 0.0), h.repository.snapshot.value.state.location.valueOrNull())
        assertEquals(GpsQuality.Degraded, h.repository.snapshot.value.state.gpsQuality)
    }

    @Test
    fun `changing the toll preference re-requests the route with the new flag`() = runTest {
        val h = harness()
        h.location.emit(fix())
        h.repository.setDestination(route.destination)
        runCurrent()
        assertEquals(listOf(false), h.routing.requests.map { it.avoidTolls })
        assertFalse(h.repository.snapshot.value.avoidTolls)

        h.repository.setAvoidTolls(true)
        runCurrent()

        assertEquals(listOf(false, true), h.routing.requests.map { it.avoidTolls })
        assertTrue(h.repository.snapshot.value.avoidTolls)
        assertEquals(route, h.repository.snapshot.value.state.route)

        // Setting the same value again is not a new request.
        h.repository.setAvoidTolls(true)
        runCurrent()
        assertEquals(2, h.routing.requests.size)
    }

    @Test
    fun `the toll preference is remembered for later routes and reroutes`() = runTest {
        val h = harness()
        h.repository.setAvoidTolls(true)
        runCurrent()
        assertTrue("no destination yet, nothing to route", h.routing.requests.isEmpty())

        h.location.emit(fix())
        h.repository.setDestination(route.destination)
        runCurrent()

        assertEquals(listOf(true), h.routing.requests.map { it.avoidTolls })
    }
}
