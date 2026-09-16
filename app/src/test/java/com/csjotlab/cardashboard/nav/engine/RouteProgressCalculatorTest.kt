package com.csjotlab.cardashboard.nav.engine

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.domain.RouteStep
import com.csjotlab.cardashboard.vehicle.domain.Clock
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteProgressCalculatorTest {

    private val metersPerDegree = 111_194.9

    // A straight north-south route: 0 -> 500 m (depart) -> 700 m (turn) -> 700 m (arrive).
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

    @Test
    fun `at the origin everything remains`() {
        val progress = RouteProgressCalculator.progress(route, geometry[0])

        assertEquals(700f, progress.remainingDistanceMeters, 1f)
        assertEquals(90L, progress.remainingTimeSeconds)
    }

    @Test
    fun `at the mid-turn vertex the remaining step is the full turn`() {
        val progress = RouteProgressCalculator.progress(route, geometry[1])

        assertEquals(200f, progress.remainingDistanceMeters, 1f)
        assertEquals(30L, progress.remainingTimeSeconds)
    }

    @Test
    fun `at the destination nothing remains`() {
        val progress = RouteProgressCalculator.progress(route, geometry[2])

        assertEquals(0f, progress.remainingDistanceMeters, 1f)
        assertEquals(0L, progress.remainingTimeSeconds)
    }

    @Test
    fun `eta is the clock now plus the remaining seconds`() {
        val clock = Clock { 1_000_000L }
        assertEquals(1_030_000L, RouteProgressCalculator.etaMs(clock, remainingTimeSeconds = 30L))
    }
}
