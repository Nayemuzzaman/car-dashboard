package com.csjotlab.cardashboard.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceGlyphTest {

    @Test
    fun `well-known osm categories get a recognisable glyph`() {
        assertEquals("✈", placeGlyph("aerodrome"))
        assertEquals("🚉", placeGlyph("station"))
        assertEquals("🚉", placeGlyph("train_station"))
        assertEquals("🚏", placeGlyph("bus_stop"))
        assertEquals("🍴", placeGlyph("restaurant"))
        assertEquals("⛽", placeGlyph("fuel"))
        assertEquals("🅿", placeGlyph("parking"))
        assertEquals("🏥", placeGlyph("hospital"))
        assertEquals("🏨", placeGlyph("hotel"))
        assertEquals("🛒", placeGlyph("supermarket"))
        assertEquals("🏙", placeGlyph("city"))
        assertEquals("🛣", placeGlyph("residential"))
        assertEquals("🛣", placeGlyph("trunk"))
    }

    @Test
    fun `unknown and missing categories fall back to a pin, recents get a clock`() {
        assertEquals("📍", placeGlyph("something_odd"))
        assertEquals("📍", placeGlyph(null))
        assertTrue(RECENT_GLYPH.isNotBlank())
        assertNotEquals(RECENT_GLYPH, placeGlyph(null))
    }

    @Test
    fun `convenience stores get their own glyph and a readable label`() {
        assertEquals("🏪", placeGlyph("convenience"))
        assertEquals("🛒", placeGlyph("supermarket"))
        assertEquals("Convenience store", placeKindLabel("convenience"))
        assertEquals("Bus stop", placeKindLabel("bus_stop"))
        assertNull(placeKindLabel(null))
    }
}
