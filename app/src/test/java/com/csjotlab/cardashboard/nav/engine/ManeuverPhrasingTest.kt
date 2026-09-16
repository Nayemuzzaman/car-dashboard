package com.csjotlab.cardashboard.nav.engine

import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManeuverPhrasingTest {

    private fun progress(type: ManeuverType, distanceMeters: Float) =
        ManeuverProgress(Maneuver(type, null), distanceMeters)

    @Test
    fun `a left turn under a kilometre is phrased in metres`() {
        assertEquals(
            "Turn left in 300 m",
            ManeuverPhrasing.phrase(progress(ManeuverType.TurnLeft, 300f)),
        )
    }

    @Test
    fun `a right turn over a kilometre is phrased in kilometres`() {
        assertEquals(
            "Turn right in 1.2 km",
            ManeuverPhrasing.phrase(progress(ManeuverType.TurnRight, 1_200f)),
        )
    }

    @Test
    fun `an imminent maneuver is phrased as now`() {
        assertEquals(
            "Turn left now",
            ManeuverPhrasing.phrase(progress(ManeuverType.TurnLeft, ManeuverPhrasing.IMMINENT_METERS)),
        )
    }

    @Test
    fun `a roundabout names its exit`() {
        assertEquals(
            "At the roundabout, take the 2nd exit",
            ManeuverPhrasing.phrase(progress(ManeuverType.Roundabout(exitNumber = 2), 400f)),
        )
    }

    @Test
    fun `a u-turn keeps its action and distance`() {
        assertEquals(
            "Make a U-turn in 300 m",
            ManeuverPhrasing.phrase(progress(ManeuverType.UTurn, 300f)),
        )
    }

    @Test
    fun `arrival is a complete sentence`() {
        assertEquals(
            "Arrive at destination",
            ManeuverPhrasing.phrase(progress(ManeuverType.Arrive, 0f)),
        )
    }

    @Test
    fun `an unknown maneuver has no phrase rather than inventing one`() {
        assertNull(ManeuverPhrasing.phrase(progress(ManeuverType.Unknown, 300f)))
        assertNull(ManeuverPhrasing.phrase(null))
    }
}
