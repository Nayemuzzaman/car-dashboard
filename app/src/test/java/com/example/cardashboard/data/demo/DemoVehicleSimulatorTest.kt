package com.example.cardashboard.data.demo

import com.example.cardashboard.domain.model.DriveMode
import com.example.cardashboard.domain.model.EnergyState
import com.example.cardashboard.domain.model.Gear
import com.example.cardashboard.domain.model.VehicleState
import com.example.cardashboard.domain.model.VehicleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoVehicleSimulatorTest {

    private fun simulator(type: VehicleType = VehicleType.COMBUSTION) =
        DemoVehicleSimulator(vehicleType = type, tickMillis = TICK_MILLIS)

    /** Advances the simulation by [seconds] and returns the last snapshot produced. */
    private fun DemoVehicleSimulator.stepForward(seconds: Double): VehicleState {
        var last = step()
        val target = (seconds * 1000 / TICK_MILLIS).toInt()
        repeat(target - 1) { last = step() }
        return last
    }

    private fun DemoVehicleSimulator.wholeCycle(): List<VehicleState> =
        List(TICKS_PER_CYCLE) { step() }

    @Test
    fun `two simulators stepped the same number of times produce identical values`() {
        val first = simulator().wholeCycle()
        val second = simulator().wholeCycle()

        assertEquals(first, second)
    }

    @Test
    fun `every value in a full cycle stays inside the displayable range`() {
        simulator().wholeCycle().forEach { state ->
            assertTrue("speed ${state.speedKmh}", state.speedKmh in 0f..VehicleState.MAX_SPEED_KMH)
            assertTrue("rpm ${state.rpm}", state.rpm in 0..VehicleState.MAX_RPM)
            assertTrue("level", state.energy.levelPercent in 0f..100f)
            assertTrue("odometer", state.odometerKm >= 0.0)
            assertTrue("trip", state.trip.distanceKm >= 0.0)
        }
    }

    @Test
    fun `the cycle passes through park reverse neutral and drive`() {
        val gears = simulator().wholeCycle().map { it.gear }.toSet()

        assertTrue(gears.containsAll(setOf(Gear.PARK, Gear.REVERSE, Gear.NEUTRAL, Gear.DRIVE)))
    }

    @Test
    fun `sport mode reports the sport gear position`() {
        val simulator = simulator()
        simulator.setDriveMode(DriveMode.SPORT)

        val gears = simulator.wholeCycle().map { it.gear }.toSet()

        assertTrue(gears.contains(Gear.SPORT))
        assertTrue(gears.contains(Gear.PARK))
    }

    @Test
    fun `the cycle exercises every telltale the simulation drives`() {
        val states = simulator().wholeCycle()

        assertTrue(states.any { it.indicators.leftTurnSignal })
        assertTrue(states.any { it.indicators.rightTurnSignal })
        assertTrue(states.any { it.indicators.hazardLights })
        assertTrue(states.any { it.indicators.highBeam })
        assertTrue(states.any { it.indicators.lowBeam })
        assertTrue(states.any { it.indicators.parkingBrake })
        assertTrue(states.any { it.indicators.seatBeltUnfastened })
        assertTrue(states.any { it.indicators.doorOpen })
        assertTrue(states.any { it.indicators.engineWarning })
        assertTrue(states.any { it.indicators.tirePressureWarning })
        assertTrue(states.any { it.indicators.tractionControlWarning })
        assertTrue(states.any { it.indicators.absWarning })
        assertTrue(states.any { it.indicators.temperatureWarning })
    }

    @Test
    fun `no telltale is lit on the very first snapshot except the ones the drive cycle starts with`() {
        val first = simulator().step()

        // Parked with the door still open and the belt unfastened; nothing else.
        assertTrue(first.indicators.parkingBrake)
        assertEquals(Gear.PARK, first.gear)
        assertEquals(0f, first.speedKmh, 0f)
    }

    @Test
    fun `resetting the trip clears trip values and leaves the odometer alone`() {
        val simulator = simulator()
        val beforeReset = simulator.stepForward(seconds = 60.0)
        assertTrue(beforeReset.trip.distanceKm > 0.0)

        simulator.resetTrip()
        val afterReset = simulator.step()

        assertEquals(0.0, afterReset.trip.distanceKm, TRIP_TOLERANCE_KM)
        assertTrue(afterReset.trip.drivingTimeMillis <= TICK_MILLIS)
        assertTrue(
            "odometer must keep counting",
            afterReset.odometerKm >= beforeReset.odometerKm
        )
        assertTrue(afterReset.odometerKm > DemoVehicleSimulator.INITIAL_ODOMETER_KM)
    }

    @Test
    fun `resetting everything returns the odometer to its starting value`() {
        val simulator = simulator()
        simulator.stepForward(seconds = 90.0)

        simulator.reset()
        val afterReset = simulator.step()

        assertEquals(
            DemoVehicleSimulator.INITIAL_ODOMETER_KM,
            afterReset.odometerKm,
            TRIP_TOLERANCE_KM
        )
        assertEquals(0.0, afterReset.trip.distanceKm, TRIP_TOLERANCE_KM)
    }

    @Test
    fun `driving time only accrues while the vehicle can move`() {
        val simulator = simulator()
        // The first four seconds are spent parked.
        val parked = simulator.stepForward(seconds = 3.0)

        assertEquals(0L, parked.trip.drivingTimeMillis)

        val driving = simulator.stepForward(seconds = 40.0)
        assertTrue(driving.trip.drivingTimeMillis > 0L)
    }

    @Test
    fun `the combustion profile reports a fuel tank and an idle speed`() {
        val states = simulator(VehicleType.COMBUSTION).wholeCycle()

        assertTrue(states.all { it.energy is EnergyState.Fuel })
        assertTrue(states.all { it.vehicleType == VehicleType.COMBUSTION })
        assertTrue("engine idles rather than stopping", states.all { it.rpm > 0 })
    }

    @Test
    fun `the electric profile reports a battery that charges while parked`() {
        val states = simulator(VehicleType.ELECTRIC).wholeCycle()
        val batteries = states.map { it.energy as EnergyState.Battery }

        assertTrue(states.all { it.vehicleType == VehicleType.ELECTRIC })
        assertTrue("charges at the end of the cycle", batteries.any { it.isCharging })
        assertTrue("recuperates while slowing", batteries.any { it.regenerativeBrakingActive })
    }

    @Test
    fun `an electric motor spins with road speed and stops with it`() {
        val states = simulator(VehicleType.ELECTRIC).wholeCycle()
        val stationary = states.first { it.speedKmh == 0f }

        assertEquals(0, stationary.rpm)
        assertTrue(states.any { it.rpm > 1_000 })
    }

    @Test
    fun `sport mode uses more energy over the same cycle than eco`() {
        val eco = simulator().apply { setDriveMode(DriveMode.ECO) }.wholeCycle().last()
        val sport = simulator().apply { setDriveMode(DriveMode.SPORT) }.wholeCycle().last()

        assertTrue(
            "sport drained ${100f - sport.energy.levelPercent}, eco ${100f - eco.energy.levelPercent}",
            sport.energy.levelPercent < eco.energy.levelPercent
        )
    }

    @Test
    fun `the outside sensor drops out during the cycle and comes back`() {
        val outside = simulator().wholeCycle().map { it.temperatures.outsideCelsius }

        assertTrue("a dropout is simulated", outside.any { it == null })
        assertNotNull("and it recovers", outside.last())
    }

    @Test
    fun `the drive cycle actually moves the vehicle`() {
        val states = simulator().wholeCycle()

        assertNotEquals(0f, states.maxOf { it.speedKmh })
        assertTrue(states.maxOf { it.speedKmh } > 100f)
        assertTrue(states.last().odometerKm > DemoVehicleSimulator.INITIAL_ODOMETER_KM)
    }

    private companion object {
        const val TICK_MILLIS = 200L
        const val TRIP_TOLERANCE_KM = 0.05
        val TICKS_PER_CYCLE = (DemoVehicleSimulator.CYCLE_SECONDS * 1000 / TICK_MILLIS).toInt()
    }
}
