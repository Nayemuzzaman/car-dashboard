package com.csjotlab.cardashboard.nav.engine

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.domain.RouteStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManeuverProgressTrackerTest {

    private fun step(type: ManeuverType, distance: Float) = RouteStep(
        maneuver = Maneuver(type, null),
        distanceMeters = distance,
        durationSeconds = 0L,
        geometry = emptyList(),
    )

    private fun routeOf(vararg steps: RouteStep) = Route(
        origin = GeoPoint(0.0, 0.0),
        destination = GeoPoint(1.0, 1.0),
        steps = steps.toList(),
        geometry = emptyList(),
        totalDistanceMeters = steps.sumOf { it.distanceMeters.toDouble() }.toFloat(),
        totalDurationSeconds = 0L,
    )

    private val demoRoute = routeOf(
        step(ManeuverType.Depart, 500f),
        step(ManeuverType.TurnLeft, 200f),
        step(ManeuverType.Arrive, 0f),
    )

    @Test
    fun `an empty route has no next maneuver`() {
        assertNull(ManeuverProgressTracker.progressAt(routeOf(), 0f))
    }

    @Test
    fun `the departure maneuver is never reported as upcoming`() {
        val progress = ManeuverProgressTracker.progressAt(demoRoute, 0f)!!

        assertEquals(ManeuverType.TurnLeft, progress.maneuver.type)
        assertEquals(500f, progress.distanceToManeuverMeters)
    }

    @Test
    fun `distance to the next maneuver shrinks as the driver advances`() {
        val progress = ManeuverProgressTracker.progressAt(demoRoute, 200f)!!

        assertEquals(ManeuverType.TurnLeft, progress.maneuver.type)
        assertEquals(300f, progress.distanceToManeuverMeters)
    }

    @Test
    fun `a maneuver reached exactly is now not already passed`() {
        val progress = ManeuverProgressTracker.progressAt(demoRoute, 500f)!!

        assertEquals(ManeuverType.TurnLeft, progress.maneuver.type)
        assertEquals(0f, progress.distanceToManeuverMeters)
    }

    @Test
    fun `past the final step the arrival maneuver is now`() {
        val progress = ManeuverProgressTracker.progressAt(demoRoute, 10_000f)!!

        assertEquals(ManeuverType.Arrive, progress.maneuver.type)
        assertEquals(0f, progress.distanceToManeuverMeters)
    }

    @Test
    fun `after a turn the next maneuver becomes the arrival`() {
        val progress = ManeuverProgressTracker.progressAt(demoRoute, 550f)!!

        assertEquals(ManeuverType.Arrive, progress.maneuver.type)
        assertEquals(150f, progress.distanceToManeuverMeters)
    }
}
