package com.example.cardashboard.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitConversionTest {

    @Test
    fun `one hundred kilometres per hour is about sixty two miles per hour`() {
        assertEquals(62.14f, 100f.kmhToDisplay(SpeedUnit.MILES_PER_HOUR), 0.01f)
    }

    @Test
    fun `metric display leaves the value untouched`() {
        assertEquals(137f, 137f.kmhToDisplay(SpeedUnit.KILOMETERS_PER_HOUR), 0f)
    }

    @Test
    fun `the mile factor is the internationally defined one`() {
        assertEquals(1.609344, SpeedUnit.KM_PER_MILE, 0.0)
    }

    @Test
    fun `distances use the same factor as speeds`() {
        assertEquals(62.137, 100.0.kmToDisplay(SpeedUnit.MILES_PER_HOUR), 0.001)
        assertEquals(100.0, 100.0.kmToDisplay(SpeedUnit.KILOMETERS_PER_HOUR), 0.0)
    }

    @Test
    fun `zero converts to zero in both units`() {
        assertEquals(0f, 0f.kmhToDisplay(SpeedUnit.MILES_PER_HOUR), 0f)
        assertEquals(0.0, 0.0.kmToDisplay(SpeedUnit.MILES_PER_HOUR), 0.0)
    }

    @Test
    fun `celsius converts to fahrenheit at the known reference points`() {
        assertEquals(32f, 0f.celsiusToDisplay(TemperatureUnit.FAHRENHEIT), 0.001f)
        assertEquals(212f, 100f.celsiusToDisplay(TemperatureUnit.FAHRENHEIT), 0.001f)
        assertEquals(-40f, (-40f).celsiusToDisplay(TemperatureUnit.FAHRENHEIT), 0.001f)
    }

    @Test
    fun `celsius display leaves the value untouched`() {
        assertEquals(21.5f, 21.5f.celsiusToDisplay(TemperatureUnit.CELSIUS), 0f)
    }
}
