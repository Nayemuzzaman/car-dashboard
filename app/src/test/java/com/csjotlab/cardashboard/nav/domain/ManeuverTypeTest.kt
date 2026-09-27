package com.csjotlab.cardashboard.nav.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ManeuverTypeTest {

    /**
     * The maneuver vocabulary is structural, not a convention: widening it requires deliberately
     * editing this test, which is the point.
     */
    @Test
    fun `the maneuver vocabulary contains exactly the intended types`() {
        val subclasses = ManeuverType::class.sealedSubclasses.map { it.simpleName }.toSet()

        assertEquals(
            setOf(
                "Depart",
                "Arrive",
                "Continue",
                "TurnLeft",
                "TurnRight",
                "SlightLeft",
                "SlightRight",
                "SharpLeft",
                "SharpRight",
                "UTurn",
                "Roundabout",
                "Exit",
                "KeepLeft",
                "KeepRight",
                "Merge",
                "RampLeft",
                "RampRight",
                "Unknown",
            ),
            subclasses,
        )
    }

    @Test
    fun `roundabout and exit carry their structured detail`() {
        assertEquals(2, ManeuverType.Roundabout(exitNumber = 2).exitNumber)
        assertEquals("12", ManeuverType.Exit(number = "12").number)
    }
}
