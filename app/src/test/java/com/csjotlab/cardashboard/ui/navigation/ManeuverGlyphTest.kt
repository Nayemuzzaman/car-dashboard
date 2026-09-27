package com.csjotlab.cardashboard.ui.navigation

import com.csjotlab.cardashboard.nav.domain.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManeuverGlyphTest {

    private val everyType = listOf(
        ManeuverType.Depart, ManeuverType.Arrive, ManeuverType.Continue,
        ManeuverType.TurnLeft, ManeuverType.TurnRight, ManeuverType.SlightLeft, ManeuverType.SlightRight,
        ManeuverType.SharpLeft, ManeuverType.SharpRight, ManeuverType.UTurn,
        ManeuverType.Roundabout(2), ManeuverType.Exit("12"), ManeuverType.KeepLeft, ManeuverType.KeepRight,
        ManeuverType.Merge, ManeuverType.RampLeft, ManeuverType.RampRight, ManeuverType.Unknown,
    )

    @Test
    fun `every maneuver type has a non-blank glyph`() {
        everyType.forEach { type -> assertTrue("$type", type.glyph().isNotBlank()) }
        // Pin the vocabulary size so a new ManeuverType forces a glyph decision here too.
        assertEquals(ManeuverType::class.sealedSubclasses.size, everyType.size)
    }

    @Test
    fun `left and right are visibly different`() {
        assertNotEquals(ManeuverType.TurnLeft.glyph(), ManeuverType.TurnRight.glyph())
        assertNotEquals(ManeuverType.SlightLeft.glyph(), ManeuverType.SlightRight.glyph())
        assertNotEquals(ManeuverType.KeepLeft.glyph(), ManeuverType.KeepRight.glyph())
    }
}
