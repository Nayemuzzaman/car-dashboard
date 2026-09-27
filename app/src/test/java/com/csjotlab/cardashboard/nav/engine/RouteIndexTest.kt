package com.csjotlab.cardashboard.nav.engine

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.domain.RouteStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteIndexTest {

    private val m = 1.0 / 111_194.9 // one metre of latitude, in degrees

    /** North 500 m, then east 200 m. */
    private val corner = GeoPoint(500 * m, 0.0)
    private val end = GeoPoint(500 * m, 200 * m)
    private val geometry = listOf(GeoPoint(0.0, 0.0), corner, end)

    private fun route(steps: List<RouteStep>) = Route(
        origin = geometry.first(),
        destination = geometry.last(),
        steps = steps,
        geometry = geometry,
        totalDistanceMeters = 700f,
        totalDurationSeconds = 90L,
    )

    private val stepsWithGeometry = listOf(
        RouteStep(Maneuver(ManeuverType.Depart, null), 480f, 60L, listOf(geometry[0], corner)),
        RouteStep(Maneuver(ManeuverType.TurnRight, null), 190f, 30L, listOf(corner, end), roadName = "Route 3"),
        RouteStep(Maneuver(ManeuverType.Arrive, null), 0f, 0L, listOf(end)),
    )

    @Test
    fun `cumulative length follows the geometry`() {
        val index = RouteIndex(route(stepsWithGeometry))
        assertEquals(700.0, index.lengthMeters, 1.0)
    }

    @Test
    fun `step starts come from the step geometry, not the router's rounded distances`() {
        val index = RouteIndex(route(stepsWithGeometry))
        assertEquals(0.0, index.stepStartMeters[0], 0.01)
        assertEquals(500.0, index.stepStartMeters[1], 1.0) // router said 480; the geometry says 500
        assertEquals(700.0, index.stepStartMeters[2], 1.0)
    }

    @Test
    fun `without step geometry the router distances are scaled onto the geometry`() {
        val index = RouteIndex(route(stepsWithGeometry.map { it.copy(geometry = emptyList()) }))
        // 480 / 670 of 700 m.
        assertEquals(480.0 * 700.0 / 670.0, index.stepStartMeters[1], 1.0)
        assertEquals(700.0, index.stepStartMeters[2], 1.0)
    }

    @Test
    fun `projection gives distance along, lateral offset, snapped point and segment bearing`() {
        val index = RouteIndex(route(stepsWithGeometry))
        val beside = GeoPoint(200 * m, 10 * m) // 200 m up the first leg, 10 m east of it

        val p = index.project(beside)

        assertEquals(200.0, p.alongMeters, 1.0)
        assertEquals(10.0, p.lateralMeters, 0.5)
        assertEquals(200 * m, p.point.latitude, 1e-7)
        assertEquals(0.0, p.point.longitude, 1e-7)
        assertEquals(0f, p.bearingDegrees, 0.5f) // heading north
    }

    @Test
    fun `a window restricts the projection to part of the route`() {
        // A route that goes north 500 m and comes straight back south: every point is on it twice.
        val back = listOf(GeoPoint(0.0, 0.0), GeoPoint(500 * m, 0.0), GeoPoint(0.0, 1 * m))
        val index = RouteIndex(route(stepsWithGeometry).copy(geometry = back, steps = emptyList()))
        val point = GeoPoint(100 * m, 0.5 * m)

        assertTrue(index.project(point, fromMeters = 0.0, toMeters = 450.0).alongMeters < 150.0)
        assertTrue(index.project(point, fromMeters = 600.0, toMeters = 1_000.0).alongMeters > 850.0)
    }

    @Test
    fun `split at a distance puts the cut point in both halves`() {
        val index = RouteIndex(route(stepsWithGeometry))

        val (traveled, remaining) = index.split(600.0)

        assertEquals(geometry.first(), traveled.first())
        assertEquals(traveled.last(), remaining.first())
        assertEquals(end, remaining.last())
        assertEquals(500 * m, remaining.first().latitude, 1e-7)
        assertEquals(100 * m, remaining.first().longitude, 1e-6)
        assertEquals(3, traveled.size) // origin, corner, cut
        assertEquals(2, remaining.size) // cut, end
    }

    @Test
    fun `split clamps outside the route`() {
        val index = RouteIndex(route(stepsWithGeometry))
        assertEquals(1, index.split(-5.0).first.size)
        assertEquals(geometry, index.split(-5.0).second)
        assertEquals(1, index.split(10_000.0).second.size)
    }
}
