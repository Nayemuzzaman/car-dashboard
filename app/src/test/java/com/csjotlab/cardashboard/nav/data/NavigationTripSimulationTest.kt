package com.csjotlab.cardashboard.nav.data

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.domain.NavigationPhase
import com.csjotlab.cardashboard.nav.domain.RerouteState
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.domain.RouteStep
import com.csjotlab.cardashboard.nav.engine.GeoMath
import com.csjotlab.cardashboard.nav.fakes.ControllableHeadingProvider
import com.csjotlab.cardashboard.nav.fakes.ControllableLocationProvider
import com.csjotlab.cardashboard.nav.location.LocationReading
import com.csjotlab.cardashboard.nav.routing.FakeRoutingEngine
import com.csjotlab.cardashboard.nav.routing.RouteRequest
import com.csjotlab.cardashboard.nav.routing.RouteResult
import com.csjotlab.cardashboard.vehicle.domain.Clock
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A whole trip, fix by fix, through the real repository and engine with a fake GPS and a fake
 * router — so the drive can be verified without a car. The router builds each route as a path of
 * right-angle legs from wherever it is asked to start, like a real one would.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationTripSimulationTest {

    private val m = 1.0 / 111_194.9
    private fun at(north: Double, east: Double) = GeoPoint(north * m, east * m)

    /** North 400 m, right, east 300 m, left, north 300 m, right, east 200 m, arrive. */
    private val corners = listOf(at(0.0, 0.0), at(400.0, 0.0), at(400.0, 300.0), at(700.0, 300.0), at(700.0, 500.0))
    private val destination = corners.last()

    private fun routeThrough(points: List<GeoPoint>, turns: List<ManeuverType>): Route {
        val steps = points.zipWithNext().mapIndexed { i, (a, b) ->
            val type = if (i == 0) ManeuverType.Depart else turns[i - 1]
            RouteStep(Maneuver(type, null), GeoMath.distanceMeters(a, b).toFloat(), 30L, listOf(a, b), roadName = "Road $i")
        } + RouteStep(Maneuver(ManeuverType.Arrive, null), 0f, 0L, listOf(points.last()))
        return Route(
            origin = points.first(),
            destination = points.last(),
            steps = steps,
            geometry = points,
            totalDistanceMeters = steps.sumOf { it.distanceMeters.toDouble() }.toFloat(),
            totalDurationSeconds = steps.sumOf { it.durationSeconds },
        )
    }

    private val plannedRoute = routeThrough(corners, listOf(ManeuverType.TurnRight, ManeuverType.TurnLeft, ManeuverType.TurnRight))

    /** A reroute: from the car, east to the destination's longitude, then north (or south) to it. */
    private fun reroute(request: RouteRequest): Route {
        val o = request.origin
        val bend = GeoPoint(o.latitude, destination.longitude)
        return routeThrough(listOf(o, bend, destination), listOf(ManeuverType.TurnLeft))
    }

    private val location = ControllableLocationProvider()

    private fun TestScope.newTrip(): Pair<NavigationRepository, FakeRoutingEngine> {
        val routing = FakeRoutingEngine { request ->
            RouteResult.Success(if (request.origin == corners.first()) plannedRoute else reroute(request))
        }
        val repository = NavigationRepository(routing, location, ControllableHeadingProvider(), Clock { currentTime }, backgroundScope)
        runCurrent()
        return repository to routing
    }

    /**
     * Drive in a straight line from [from] to [to] at 12 m/s, one fix per second, until the end or
     * until [onFix] returns true (the driver reacts, e.g. to a new route). Returns the last position.
     */
    private fun TestScope.drive(repository: NavigationRepository, from: GeoPoint, to: GeoPoint, onFix: (NavigationSnapshot) -> Boolean = { false }): GeoPoint {
        val meters = GeoMath.distanceMeters(from, to)
        val course = GeoMath.bearingDegrees(from, to)
        val steps = (meters / 12.0).toInt().coerceAtLeast(1)
        var position = from
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            advanceTimeBy(1_000L)
            position = GeoPoint(from.latitude + (to.latitude - from.latitude) * t, from.longitude + (to.longitude - from.longitude) * t)
            location.emit(LocationReading(position, speedMps = 12f, courseDegrees = course, accuracyMeters = 5f, timestampMs = currentTime))
            runCurrent()
            if (onFix(repository.snapshot.value)) break
        }
        return position
    }

    @Test
    fun `instructions advance through consecutive turns and the trip ends in arrival`() = runTest {
        val (repository, routing) = newTrip()
        location.emit(LocationReading(corners.first(), 0f, 0f, 5f, currentTime)); runCurrent()
        repository.setDestination(destination)
        repository.setGuidanceActive(true)
        runCurrent()
        assertEquals(1, routing.requests.size)

        val announced = mutableListOf<ManeuverType>()
        val remaining = mutableListOf<Float>()
        corners.zipWithNext().forEach { (a, b) ->
            drive(repository, a, b) { snapshot ->
                snapshot.state.nextManeuver?.type?.let { if (announced.lastOrNull() != it) announced += it }
                snapshot.state.remainingDistanceMeters?.valueOrNull()?.let { remaining += it }
                false
            }
        }

        assertEquals(listOf(ManeuverType.TurnRight, ManeuverType.TurnLeft, ManeuverType.TurnRight, ManeuverType.Arrive), announced)
        assertTrue("remaining distance never goes up", remaining.zipWithNext().all { (a, b) -> b <= a + 0.5f })
        assertEquals(NavigationPhase.Arrived, repository.snapshot.value.state.phase)
        assertEquals("no reroute on a clean drive", 1, routing.requests.size)
    }

    @Test
    fun `a wrong turn is detected, rerouted from the car, and guidance continues to arrival`() = runTest {
        val (repository, routing) = newTrip()
        location.emit(LocationReading(corners.first(), 0f, 0f, 5f, currentTime)); runCurrent()
        repository.setDestination(destination)
        repository.setGuidanceActive(true)
        runCurrent()

        // Drive to the first corner, then miss the right turn and keep going north until the new
        // route appears — a driver follows the new route from there.
        drive(repository, corners[0], corners[1])
        val here = drive(repository, corners[1], at(700.0, 0.0)) { routing.requests.size == 2 }

        assertEquals(2, routing.requests.size)
        val rerouteOrigin = routing.requests.last().origin
        assertTrue("rerouted from where the car was", GeoMath.distanceMeters(rerouteOrigin, corners[1]) > 30.0)
        val newRoute = repository.snapshot.value.state.route!!
        assertEquals(rerouteOrigin, newRoute.origin)
        assertFalse(repository.snapshot.value.state.offRoute)
        assertTrue(repository.snapshot.value.guidanceActive)

        // Follow the new route to the end.
        val bend = newRoute.geometry[1]
        drive(repository, here, bend)
        drive(repository, bend, destination)

        assertEquals(NavigationPhase.Arrived, repository.snapshot.value.state.phase)
        assertEquals("one reroute for one wrong turn", 2, routing.requests.size)
        assertEquals(RerouteState.Idle, repository.snapshot.value.state.rerouteState)
    }

    @Test
    fun `ending the trip mid-drive clears guidance and stops rerouting`() = runTest {
        val (repository, routing) = newTrip()
        location.emit(LocationReading(corners.first(), 0f, 0f, 5f, currentTime)); runCurrent()
        repository.setDestination(destination)
        repository.setGuidanceActive(true)
        runCurrent()
        drive(repository, corners[0], at(200.0, 0.0))

        repository.setGuidanceActive(false)
        repository.setDestination(null)
        runCurrent()
        drive(repository, at(200.0, 0.0), at(200.0, 200.0)) // far off the old route

        assertEquals(NavigationPhase.Idle, repository.snapshot.value.state.phase)
        assertEquals(1, routing.requests.size)
    }
}
