package com.csjotlab.cardashboard.ui.navigation

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.map.MapStyle
import com.csjotlab.cardashboard.nav.map.MapThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class NavThemeTest {

    private val dhaka = GeoPoint(23.81, 90.41)
    private val noon = Instant.parse("2026-09-27T06:00:00Z").toEpochMilli()
    private val midnight = Instant.parse("2026-09-27T18:00:00Z").toEpochMilli()

    @Test
    fun `auto follows the sun at the car's position`() {
        assertEquals(MapStyle.Day, resolveMapStyle(MapThemeMode.Auto, systemDark = true, position = dhaka, nowMs = noon))
        assertEquals(MapStyle.Night, resolveMapStyle(MapThemeMode.Auto, systemDark = false, position = dhaka, nowMs = midnight))
    }

    @Test
    fun `auto without a position follows the system setting`() {
        assertEquals(MapStyle.Night, resolveMapStyle(MapThemeMode.Auto, systemDark = true, position = null, nowMs = noon))
        assertEquals(MapStyle.Day, resolveMapStyle(MapThemeMode.Auto, systemDark = false, position = null, nowMs = midnight))
    }

    @Test
    fun `an explicit choice wins`() {
        assertEquals(MapStyle.Night, resolveMapStyle(MapThemeMode.Night, systemDark = false, position = dhaka, nowMs = noon))
        assertEquals(MapStyle.Day, resolveMapStyle(MapThemeMode.Day, systemDark = true, position = dhaka, nowMs = midnight))
    }

    @Test
    fun `banner distances are rounded the way drivers read them`() {
        assertEquals(NOW, NavigationFormatter.formatManeuverDistance(15f))
        assertEquals("70 m", NavigationFormatter.formatManeuverDistance(68f))
        assertEquals("300 m", NavigationFormatter.formatManeuverDistance(312f))
        assertEquals("950 m", NavigationFormatter.formatManeuverDistance(960f))
        assertEquals("1.0 km", NavigationFormatter.formatManeuverDistance(990f))
        assertEquals("1.2 km", NavigationFormatter.formatManeuverDistance(1_234f))
        assertEquals("25 km", NavigationFormatter.formatManeuverDistance(24_600f))
    }
}
