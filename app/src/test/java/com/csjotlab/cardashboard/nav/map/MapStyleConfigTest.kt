package com.csjotlab.cardashboard.nav.map

import com.csjotlab.cardashboard.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStyleConfigTest {

    @Test
    fun `day and night styles are distinct https urls and not the blank demo tiles`() {
        assertTrue(BuildConfig.MAP_DAY_STYLE_URL.startsWith("https://"))
        assertTrue(BuildConfig.MAP_NIGHT_STYLE_URL.startsWith("https://"))
        assertNotEquals(BuildConfig.MAP_DAY_STYLE_URL, BuildConfig.MAP_NIGHT_STYLE_URL)
        assertFalse(BuildConfig.MAP_DAY_STYLE_URL.contains("demotiles"))
        assertFalse(BuildConfig.MAP_NIGHT_STYLE_URL.contains("demotiles"))
    }

    @Test
    fun `graphhopper is opt-in and the public routers and geocoder are configured`() {
        assertEquals("", BuildConfig.ROUTING_BASE_URL)
        assertTrue(BuildConfig.VALHALLA_BASE_URL.startsWith("https://"))
        assertTrue(BuildConfig.OSRM_BASE_URL.startsWith("https://"))
        assertTrue(BuildConfig.GEOCODING_BASE_URL.startsWith("https://"))
    }

    @Test
    fun `provider returns the configured url per style`() {
        val provider = ConfigurableMapStyleProvider(dayStyleUrl = "https://day", nightStyleUrl = "https://night")

        assertEquals("https://day", provider.style(MapStyle.Day))
        assertEquals("https://night", provider.style(MapStyle.Night))
    }
}
