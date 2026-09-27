package com.csjotlab.cardashboard.nav.engine

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.domain.RouteStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManeuverInstructionTest {

    @Test
    fun `turns name the road they lead onto`() {
        assertEquals("Turn right onto Route 3", ManeuverPhrasing.instruction(ManeuverType.TurnRight, "Route 3"))
        assertEquals("Turn left", ManeuverPhrasing.instruction(ManeuverType.TurnLeft, null))
        assertEquals("Continue on Main St", ManeuverPhrasing.instruction(ManeuverType.Continue, "Main St"))
        assertEquals("Make a U-turn", ManeuverPhrasing.instruction(ManeuverType.UTurn, null))
    }

    @Test
    fun `highway entrances, exits, roundabouts and arrival have their own wording`() {
        assertEquals("Take the ramp on the right onto E3", ManeuverPhrasing.instruction(ManeuverType.RampRight, "E3"))
        assertEquals("Take the ramp on the left", ManeuverPhrasing.instruction(ManeuverType.RampLeft, null))
        assertEquals("Take exit 12", ManeuverPhrasing.instruction(ManeuverType.Exit("12"), null))
        assertEquals("Take the exit onto Ring Rd", ManeuverPhrasing.instruction(ManeuverType.Exit(null), "Ring Rd"))
        assertEquals("At the roundabout, take the 2nd exit onto Park Ave", ManeuverPhrasing.instruction(ManeuverType.Roundabout(2), "Park Ave"))
        assertEquals("Slight right onto A1", ManeuverPhrasing.instruction(ManeuverType.SlightRight, "A1"))
        assertEquals("Arrive at your destination", ManeuverPhrasing.instruction(ManeuverType.Arrive, "Anything"))
    }

    @Test
    fun `an unknown maneuver has no instruction rather than an invented one`() {
        assertNull(ManeuverPhrasing.instruction(ManeuverType.Unknown, "Somewhere"))
    }

    @Test
    fun `a ramp keeps its phrase with distance`() {
        assertEquals(
            "Take the ramp on the right in 300 m",
            ManeuverPhrasing.phrase(ManeuverProgress(Maneuver(ManeuverType.RampRight, null), 300f)),
        )
    }
}

class ManeuverProgressDetailTest {

    private fun step(type: ManeuverType, distance: Float, road: String? = null) =
        RouteStep(Maneuver(type, null), distance, 10L, emptyList(), roadName = road)

    private val route = Route(
        origin = GeoPoint(0.0, 0.0),
        destination = GeoPoint(0.0, 0.0),
        steps = listOf(
            step(ManeuverType.Depart, 500f, "High St"),
            step(ManeuverType.TurnRight, 100f, "Route 3"),
            step(ManeuverType.TurnLeft, 900f, "Mill Ln"),
            step(ManeuverType.Arrive, 0f),
        ),
        geometry = emptyList(),
        totalDistanceMeters = 1_500f,
        totalDurationSeconds = 30L,
    )

    @Test
    fun `the next maneuver carries its index and the road it leads onto`() {
        val p = ManeuverProgressTracker.progressAt(route, 200f)!!
        assertEquals(1, p.stepIndex)
        assertEquals("Route 3", p.roadName)
        assertEquals("High St", p.currentRoadName)
    }

    @Test
    fun `a maneuver closely followed by another announces it`() {
        // The left turn comes 100 m after the right turn.
        assertEquals(ManeuverType.TurnLeft, ManeuverProgressTracker.progressAt(route, 200f)!!.then?.type)
        // After the right turn, the next maneuver (left) is 900 m before arrival: nothing to announce.
        assertNull(ManeuverProgressTracker.progressAt(route, 650f)!!.then)
    }
}
