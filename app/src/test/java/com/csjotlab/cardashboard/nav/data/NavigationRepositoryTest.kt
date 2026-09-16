package com.csjotlab.cardashboard.nav.data

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.domain.NavigationPhase
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.domain.RouteStep
import com.csjotlab.cardashboard.nav.fakes.ControllableHeadingProvider
import com.csjotlab.cardashboard.nav.fakes.ControllableLocationProvider
import com.csjotlab.cardashboard.nav.location.LocationReading
import com.csjotlab.cardashboard.nav.routing.FakeRoutingEngine
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationRepositoryTest {

    private val route = Route(
        origin = GeoPoint(0.0, 0.0),
        destination = GeoPoint(0.0063, 0.0),
        steps = listOf(
            RouteStep(Maneuver(ManeuverType.Depart, null), 500f, 60L, emptyList()),
            RouteStep(Maneuver(ManeuverType.TurnLeft, null), 200f, 30L, emptyList()),
            RouteStep(Maneuver(ManeuverType.Arrive, null), 0f, 0L, emptyList()),
        ),
        geometry = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0063, 0.0)),
        totalDistanceMeters = 700f,
        totalDurationSeconds = 90L,
    )

    private fun fix(point: GeoPoint = GeoPoint(0.0, 0.0)) = LocationReading(
        point = point,
        speedMps = 3f,
        courseDegrees = 55f,
        accuracyMeters = null,
        timestampMs = 1_000L,
    )

    private fun TestScope.repository(
        location: ControllableLocationProvider,
        heading: ControllableHeadingProvider,
        routing: FakeRoutingEngine,
    ) = NavigationRepository(
        routingEngine = routing,
        locationProvider = location,
        headingProvider = heading,
        clock = Clock { currentTime },
        scope = backgroundScope,
    )

    @Test
    fun `destination plus a fix requests and publishes a route`() = runTest {
        val location = ControllableLocationProvider()
        val heading = ControllableHeadingProvider()
        val routing = FakeRoutingEngine { RouteResult.Success(route) }
        val repository = repository(location, heading, routing)
        runCurrent()

        location.emit(fix())
        repository.setDestination(route.destination)
        runCurrent()

        assertEquals(1, routing.requests.size)
        assertEquals(NavigationPhase.Navigating, repository.snapshot.value.state.phase)
        assertEquals(route, repository.snapshot.value.state.route)
    }

    @Test
    fun `a destination selected before a fix waits for the fix to route`() = runTest {
        val location = ControllableLocationProvider()
        val heading = ControllableHeadingProvider()
        val routing = FakeRoutingEngine { RouteResult.Success(route) }
        val repository = repository(location, heading, routing)
        runCurrent()

        repository.setDestination(route.destination)
        runCurrent()

        assertEquals(0, routing.requests.size)
        assertEquals(route.destination, repository.snapshot.value.destination)
        assertNull(repository.snapshot.value.state.route)

        location.emit(fix())
        runCurrent()

        assertEquals(1, routing.requests.size)
        assertEquals(NavigationPhase.Navigating, repository.snapshot.value.state.phase)
    }

    @Test
    fun `leaving the route triggers exactly one reroute request`() = runTest {
        val location = ControllableLocationProvider()
        val heading = ControllableHeadingProvider()
        val routing = FakeRoutingEngine { RouteResult.Success(route) }
        val repository = repository(location, heading, routing)
        runCurrent()

        location.emit(fix(GeoPoint(0.0, 0.0)))
        repository.setDestination(route.destination)
        runCurrent()
        assertEquals(1, routing.requests.size)

        val offRoute = GeoPoint(0.003, 0.001)
        repeat(3) {
            location.emit(fix(offRoute))
            runCurrent()
        }

        assertEquals(2, routing.requests.size)
        assertTrue(repository.snapshot.value.state.offRoute)
    }

    @Test
    fun `stop clears the route and destination`() = runTest {
        val location = ControllableLocationProvider()
        val heading = ControllableHeadingProvider()
        val routing = FakeRoutingEngine { RouteResult.Success(route) }
        val repository = repository(location, heading, routing)
        runCurrent()

        location.emit(fix())
        repository.setDestination(route.destination)
        runCurrent()
        assertEquals(NavigationPhase.Navigating, repository.snapshot.value.state.phase)

        repository.stop()
        runCurrent()

        assertEquals(NavigationPhase.Idle, repository.snapshot.value.state.phase)
        assertNull(repository.snapshot.value.state.route)
        assertNull(repository.snapshot.value.destination)
    }

    @Test
    fun `location goes unknown after silence`() = runTest {
        val location = ControllableLocationProvider()
        val heading = ControllableHeadingProvider()
        val routing = FakeRoutingEngine { RouteResult.Success(route) }
        val repository = repository(location, heading, routing)
        runCurrent()

        location.emit(fix())
        runCurrent()
        assertTrue(repository.snapshot.value.state.location.valueOrNull() != null)

        advanceTimeBy(6_000L)
        runCurrent()

        assertEquals(Signal.Unknown, repository.snapshot.value.state.location)
    }

    @Test
    fun `changing the toll preference re-requests the route with the new flag`() = runTest {
        val location = ControllableLocationProvider()
        val heading = ControllableHeadingProvider()
        val routing = FakeRoutingEngine { RouteResult.Success(route) }
        val repository = repository(location, heading, routing)
        runCurrent()
        location.emit(fix())
        repository.setDestination(route.destination)
        runCurrent()
        assertEquals(listOf(false), routing.requests.map { it.avoidTolls })
        assertFalse(repository.snapshot.value.avoidTolls)

        repository.setAvoidTolls(true)
        runCurrent()

        assertEquals(listOf(false, true), routing.requests.map { it.avoidTolls })
        assertTrue(repository.snapshot.value.avoidTolls)
        assertEquals(route, repository.snapshot.value.state.route)

        // Setting the same value again is not a new request.
        repository.setAvoidTolls(true)
        runCurrent()
        assertEquals(2, routing.requests.size)
    }

    @Test
    fun `the toll preference is remembered for later routes and reroutes`() = runTest {
        val location = ControllableLocationProvider()
        val heading = ControllableHeadingProvider()
        val routing = FakeRoutingEngine { RouteResult.Success(route) }
        val repository = repository(location, heading, routing)
        runCurrent()
        repository.setAvoidTolls(true)
        runCurrent()
        assertTrue("no destination yet, nothing to route", routing.requests.isEmpty())

        location.emit(fix())
        repository.setDestination(route.destination)
        runCurrent()

        assertEquals(listOf(true), routing.requests.map { it.avoidTolls })
    }
}
