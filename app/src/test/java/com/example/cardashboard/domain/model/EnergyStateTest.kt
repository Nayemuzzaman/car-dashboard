package com.example.cardashboard.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyStateTest {

    @Test
    fun `fuel is low at or below its threshold`() {
        val atThreshold = EnergyState.Fuel(
            levelPercent = EnergyState.Fuel.DEFAULT_LOW_FUEL_PERCENT,
            estimatedRangeKm = 60f
        )
        val below = atThreshold.copy(levelPercent = 4f)
        val above = atThreshold.copy(levelPercent = 30f)

        assertTrue(atThreshold.isLow)
        assertTrue(below.isLow)
        assertFalse(above.isLow)
    }

    @Test
    fun `battery uses its own low threshold`() {
        val battery = EnergyState.Battery(levelPercent = 14f, estimatedRangeKm = 40f)

        assertTrue(battery.isLow)
        assertFalse(battery.copy(levelPercent = 16f).isLow)
    }

    @Test
    fun `powertrain family follows the energy type`() {
        assertEquals(
            VehicleType.COMBUSTION,
            EnergyState.Fuel(levelPercent = 50f, estimatedRangeKm = null).vehicleType
        )
        assertEquals(
            VehicleType.ELECTRIC,
            EnergyState.Battery(levelPercent = 50f, estimatedRangeKm = null).vehicleType
        )
    }

    @Test
    fun `only the charging state counts as charging`() {
        val base = EnergyState.Battery(levelPercent = 50f, estimatedRangeKm = 190f)

        assertTrue(base.copy(chargingState = ChargingState.CHARGING).isCharging)
        assertFalse(base.copy(chargingState = ChargingState.PLUGGED_IN_IDLE).isCharging)
        assertFalse(base.copy(chargingState = ChargingState.CHARGE_COMPLETE).isCharging)
        assertFalse(base.copy(chargingState = ChargingState.NOT_CHARGING).isCharging)
    }

    @Test
    fun `regenerative braking is off unless the source reports it`() {
        val battery = EnergyState.Battery(levelPercent = 50f, estimatedRangeKm = 190f)

        assertFalse(battery.regenerativeBrakingActive)
    }
}
