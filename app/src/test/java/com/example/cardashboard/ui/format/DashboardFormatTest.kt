package com.example.cardashboard.ui.format

import com.example.cardashboard.domain.model.SpeedUnit
import com.example.cardashboard.domain.model.TemperatureUnit
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** Formatting is pinned to a fixed locale so the assertions do not depend on the test machine. */
class DashboardFormatTest {

    private val locale = Locale.UK

    @Test
    fun `speed is rounded to a whole number in the selected unit`() {
        assertEquals("87", DashboardFormat.speed(86.6f, SpeedUnit.KILOMETERS_PER_HOUR))
        assertEquals("54", DashboardFormat.speed(86.6f, SpeedUnit.MILES_PER_HOUR))
    }

    @Test
    fun `trip distance keeps one decimal place`() {
        assertEquals("142.8", DashboardFormat.distance(142.84, SpeedUnit.KILOMETERS_PER_HOUR, locale))
        assertEquals("88.8", DashboardFormat.distance(142.84, SpeedUnit.MILES_PER_HOUR, locale))
    }

    @Test
    fun `odometer is whole units with grouping`() {
        assertEquals("38,421", DashboardFormat.odometer(38_421.4, SpeedUnit.KILOMETERS_PER_HOUR, locale))
        assertEquals("23,874", DashboardFormat.odometer(38_421.4, SpeedUnit.MILES_PER_HOUR, locale))
    }

    @Test
    fun `range is rounded to whole units`() {
        assertEquals("420", DashboardFormat.range(420.3f, SpeedUnit.KILOMETERS_PER_HOUR))
        assertEquals("261", DashboardFormat.range(420.3f, SpeedUnit.MILES_PER_HOUR))
    }

    @Test
    fun `revs are shown in thousands`() {
        assertEquals("2.4", DashboardFormat.rpmThousands(2_350, locale))
        assertEquals("0.8", DashboardFormat.rpmThousands(780, locale))
    }

    @Test
    fun `temperature follows the selected unit`() {
        assertEquals("91", DashboardFormat.temperature(91f, TemperatureUnit.CELSIUS, locale))
        assertEquals("196", DashboardFormat.temperature(91f, TemperatureUnit.FAHRENHEIT, locale))
    }

    @Test
    fun `duration drops the hour field until an hour has passed`() {
        assertEquals("0:45", DashboardFormat.duration(45_000, locale))
        assertEquals("23:20", DashboardFormat.duration(1_400_000, locale))
        assertEquals("1:30:00", DashboardFormat.duration(5_400_000, locale))
    }

    @Test
    fun `negative durations read as zero rather than a negative clock`() {
        assertEquals("0:00", DashboardFormat.duration(-5_000, locale))
    }

    @Test
    fun `consumption keeps one decimal place`() {
        assertEquals("7.2", DashboardFormat.consumption(7.24, locale))
    }

    @Test
    fun `percentage is rounded to a whole number`() {
        assertEquals("68", DashboardFormat.percent(67.6f))
        assertEquals("0", DashboardFormat.percent(0f))
    }
}
