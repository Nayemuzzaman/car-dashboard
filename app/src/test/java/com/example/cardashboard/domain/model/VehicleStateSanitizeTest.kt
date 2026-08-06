package com.example.cardashboard.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A misbehaving data source must degrade the display, never crash it. */
class VehicleStateSanitizeTest {

    @Test
    fun `negative speed is clamped to zero`() {
        val state = VehicleState(speedKmh = -42f).sanitized()

        assertEquals(0f, state.speedKmh, 0f)
    }

    @Test
    fun `speed above the display maximum is clamped`() {
        val state = VehicleState(speedKmh = 5_000f).sanitized()

        assertEquals(VehicleState.MAX_SPEED_KMH, state.speedKmh, 0f)
    }

    @Test
    fun `non finite speed falls back to zero instead of propagating NaN`() {
        assertEquals(0f, VehicleState(speedKmh = Float.NaN).sanitized().speedKmh, 0f)
        assertEquals(
            0f,
            VehicleState(speedKmh = Float.POSITIVE_INFINITY).sanitized().speedKmh,
            0f
        )
    }

    @Test
    fun `negative rpm is clamped to zero and excessive rpm to the maximum`() {
        assertEquals(0, VehicleState(rpm = -100).sanitized().rpm)
        assertEquals(VehicleState.MAX_RPM, VehicleState(rpm = 99_000).sanitized().rpm)
    }

    @Test
    fun `fuel percentage is clamped into zero to one hundred`() {
        val tooLow = VehicleState(
            energy = EnergyState.Fuel(levelPercent = -12f, estimatedRangeKm = 10f)
        ).sanitized()
        val tooHigh = VehicleState(
            energy = EnergyState.Fuel(levelPercent = 180f, estimatedRangeKm = 10f)
        ).sanitized()

        assertEquals(0f, tooLow.energy.levelPercent, 0f)
        assertEquals(100f, tooHigh.energy.levelPercent, 0f)
    }

    @Test
    fun `battery percentage is clamped and the electric extras survive`() {
        val state = VehicleState(
            energy = EnergyState.Battery(
                levelPercent = 140f,
                estimatedRangeKm = 300f,
                chargingState = ChargingState.CHARGING,
                regenerativeBrakingActive = true
            )
        ).sanitized()

        val battery = state.energy as EnergyState.Battery
        assertEquals(100f, battery.levelPercent, 0f)
        assertTrue(battery.isCharging)
        assertTrue(battery.regenerativeBrakingActive)
    }

    @Test
    fun `negative range is discarded rather than shown`() {
        val state = VehicleState(
            energy = EnergyState.Fuel(levelPercent = 50f, estimatedRangeKm = -5f)
        ).sanitized()

        assertNull(state.energy.estimatedRangeKm)
    }

    @Test
    fun `implausible temperatures are treated as a missing sensor`() {
        val state = VehicleState(
            temperatures = Temperatures(outsideCelsius = 900f, powertrainCelsius = Float.NaN)
        ).sanitized()

        assertNull(state.temperatures.outsideCelsius)
        assertNull(state.temperatures.powertrainCelsius)
    }

    @Test
    fun `plausible temperatures are kept unchanged`() {
        val state = VehicleState(
            temperatures = Temperatures(outsideCelsius = -18f, powertrainCelsius = 88f)
        ).sanitized()

        assertEquals(-18f, state.temperatures.outsideCelsius!!, 0f)
        assertEquals(88f, state.temperatures.powertrainCelsius!!, 0f)
    }

    @Test
    fun `low energy telltale is derived from the level`() {
        val low = VehicleState(
            energy = EnergyState.Fuel(levelPercent = 5f, estimatedRangeKm = 20f)
        ).sanitized()
        val fine = VehicleState(
            energy = EnergyState.Fuel(levelPercent = 55f, estimatedRangeKm = 300f)
        ).sanitized()

        assertTrue(low.indicators.lowEnergy)
        assertFalse(fine.indicators.lowEnergy)
    }

    @Test
    fun `overheating telltale is derived from the powertrain temperature`() {
        val hot = VehicleState(
            temperatures = Temperatures(powertrainCelsius = 120f)
        ).sanitized()
        val normal = VehicleState(
            temperatures = Temperatures(powertrainCelsius = 90f)
        ).sanitized()

        assertTrue(hot.indicators.temperatureWarning)
        assertFalse(normal.indicators.temperatureWarning)
    }

    @Test
    fun `a source reported telltale is never turned off by sanitizing`() {
        val state = VehicleState(
            indicators = Indicators(engineWarning = true, absWarning = true)
        ).sanitized()

        assertTrue(state.indicators.engineWarning)
        assertTrue(state.indicators.absWarning)
    }

    @Test
    fun `a healthy snapshot lights no telltales at all`() {
        val state = VehicleState(
            energy = EnergyState.Fuel(levelPercent = 70f, estimatedRangeKm = 430f),
            temperatures = Temperatures(outsideCelsius = 18f, powertrainCelsius = 90f)
        ).sanitized()

        assertEquals(0, state.indicators.activeFaultCount)
        assertFalse(state.indicators.leftTurnSignal)
        assertFalse(state.indicators.highBeam)
        assertFalse(state.indicators.lowEnergy)
    }

    @Test
    fun `an empty tank is a real low fuel warning, not a fake one`() {
        // The placeholder state reports 0% because nothing has been read yet. The UI never draws
        // telltales until a snapshot exists, but if it did, an empty tank is genuinely low.
        val state = VehicleState().sanitized()

        assertTrue(state.indicators.lowEnergy)
        assertEquals(1, state.indicators.activeFaultCount)
    }

    @Test
    fun `negative odometer and trip values are clamped`() {
        val state = VehicleState(
            odometerKm = -10.0,
            trip = TripData(distanceKm = -3.0, drivingTimeMillis = -500L, energyUsed = -1.0)
        ).sanitized()

        assertEquals(0.0, state.odometerKm, 0.0)
        assertEquals(0.0, state.trip.distanceKm, 0.0)
        assertEquals(0L, state.trip.drivingTimeMillis)
        assertNull(state.trip.energyUsed)
    }

    @Test
    fun `sanitizing twice changes nothing further`() {
        val once = VehicleState(
            speedKmh = -5f,
            rpm = 99_999,
            energy = EnergyState.Fuel(levelPercent = 3f, estimatedRangeKm = 12f),
            temperatures = Temperatures(outsideCelsius = 17f, powertrainCelsius = 130f)
        ).sanitized()

        assertEquals(once, once.sanitized())
    }
}
