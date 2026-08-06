package com.example.cardashboard.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class IndicatorsTest {

    @Test
    fun `nothing is lit by default`() {
        val indicators = Indicators.NONE

        assertFalse(indicators.leftTurnSignal)
        assertFalse(indicators.rightTurnSignal)
        assertFalse(indicators.hazardLights)
        assertFalse(indicators.highBeam)
        assertFalse(indicators.lowBeam)
        assertFalse(indicators.parkingBrake)
        assertFalse(indicators.seatBeltUnfastened)
        assertFalse(indicators.doorOpen)
        assertFalse(indicators.engineWarning)
        assertFalse(indicators.lowEnergy)
        assertFalse(indicators.tirePressureWarning)
        assertFalse(indicators.temperatureWarning)
        assertFalse(indicators.absWarning)
        assertFalse(indicators.tractionControlWarning)
        assertEquals(0, indicators.activeFaultCount)
    }

    @Test
    fun `fault count covers only telltales that mean something is wrong`() {
        val indicators = Indicators(
            seatBeltUnfastened = true,
            doorOpen = true,
            engineWarning = true,
            // Informational lamps must not inflate the count.
            leftTurnSignal = true,
            lowBeam = true,
            highBeam = true
        )

        assertEquals(3, indicators.activeFaultCount)
    }

    @Test
    fun `every fault telltale contributes exactly one to the count`() {
        val allFaults = Indicators(
            seatBeltUnfastened = true,
            doorOpen = true,
            engineWarning = true,
            lowEnergy = true,
            tirePressureWarning = true,
            temperatureWarning = true,
            absWarning = true,
            tractionControlWarning = true
        )

        assertEquals(8, allFaults.activeFaultCount)
    }
}
