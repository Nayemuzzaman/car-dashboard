package com.csjotlab.cardashboard.nav.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SolarDayNightTest {

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `the sun is high at local noon on the equator at the equinox`() {
        assertTrue(SolarDayNight.elevationDegrees(0.0, 0.0, at("2026-03-20T12:00:00Z")) > 80.0)
        assertTrue(SolarDayNight.elevationDegrees(0.0, 0.0, at("2026-03-20T00:00:00Z")) < -80.0)
    }

    @Test
    fun `dhaka is day at local noon and night at local midnight`() {
        // Dhaka is UTC+6.
        assertTrue(SolarDayNight.isDaylight(23.81, 90.41, at("2026-09-27T06:00:00Z")))
        assertFalse(SolarDayNight.isDaylight(23.81, 90.41, at("2026-09-27T18:00:00Z")))
    }

    @Test
    fun `fukuoka sunset is around six in the evening in late september`() {
        // Sunset in Fukuoka (UTC+9) on 27 September is about 18:05 local.
        assertTrue(SolarDayNight.isDaylight(33.59, 130.40, at("2026-09-27T08:45:00Z"))) // 17:45
        assertFalse(SolarDayNight.isDaylight(33.59, 130.40, at("2026-09-27T09:30:00Z"))) // 18:30
    }
}
