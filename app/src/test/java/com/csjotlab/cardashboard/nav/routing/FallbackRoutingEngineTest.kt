package com.csjotlab.cardashboard.nav.routing

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.domain.RouteStep
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackRoutingEngineTest {

    private val request = RouteRequest(GeoPoint(0.0, 0.0), GeoPoint(0.01, 0.0))

    private fun route(tag: Float) = Route(
        origin = request.origin,
        destination = request.destination,
        steps = listOf(
            RouteStep(Maneuver(ManeuverType.Depart, null), tag, 1L, emptyList()),
            RouteStep(Maneuver(ManeuverType.Arrive, null), 0f, 0L, emptyList()),
        ),
        geometry = listOf(request.origin, request.destination),
        totalDistanceMeters = tag,
        totalDurationSeconds = 1L,
    )

    @Test
    fun `the first successful engine wins and later engines are not asked`() = runTest {
        val first = FakeRoutingEngine { RouteResult.Success(route(1f)) }
        val second = FakeRoutingEngine { RouteResult.Success(route(2f)) }

        val result = FallbackRoutingEngine(listOf(first, second)).route(request)

        assertEquals(1f, (result as RouteResult.Success).route.totalDistanceMeters)
        assertEquals(1, first.requests.size)
        assertEquals(0, second.requests.size)
    }

    @Test
    fun `a failing engine hands the same request to the next one`() = runTest {
        val first = FakeRoutingEngine { RouteResult.Failure("down") }
        val second = FakeRoutingEngine { RouteResult.Failure("also down") }
        val third = FakeRoutingEngine { RouteResult.Success(route(3f)) }

        val result = FallbackRoutingEngine(listOf(first, second, third)).route(request)

        assertEquals(3f, (result as RouteResult.Success).route.totalDistanceMeters)
        assertEquals(listOf(request), third.requests)
    }

    @Test
    fun `when every engine fails the last failure is returned`() = runTest {
        val engine = FallbackRoutingEngine(
            listOf(FakeRoutingEngine { RouteResult.Failure("first") }, FakeRoutingEngine { RouteResult.Failure("last") }),
        )

        val result = engine.route(request)

        assertTrue(result is RouteResult.Failure)
        assertEquals("last", (result as RouteResult.Failure).reason)
    }

    @Test
    fun `an empty chain is a configuration error`() {
        val thrown = runCatching { FallbackRoutingEngine(emptyList()) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }
}
