package com.example.cardashboard.data.demo

import com.example.cardashboard.domain.model.DriveMode
import com.example.cardashboard.domain.model.VehicleDataSourceKind
import com.example.cardashboard.domain.model.VehicleDataState
import com.example.cardashboard.domain.model.VehicleType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The timing wrapper around the simulation: does it start, tick and stop when it should? */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoVehicleDataSourceTest {

    private val tickMillis = 200L

    private fun source(type: VehicleType = VehicleType.COMBUSTION) =
        DemoVehicleDataSource(vehicleType = type, tickMillis = tickMillis)

    @Test
    fun `the first emission says loading, then snapshots follow`() = runTest {
        val states = source().observe().take(3).toList()

        assertEquals(VehicleDataState.Loading, states.first())
        assertTrue(states.drop(1).all { it is VehicleDataState.Available })
    }

    @Test
    fun `every snapshot is tagged as demo data`() = runTest {
        val states = source().observe().take(6).toList()

        assertTrue(
            states.filterIsInstance<VehicleDataState.Available>()
                .all { it.source == VehicleDataSourceKind.DEMO }
        )
    }

    @Test
    fun `one snapshot is produced per tick`() = runTest {
        val collected = mutableListOf<VehicleDataState>()
        val job = launch { source().observe().toList(collected) }

        runCurrent()
        val afterFirstTick = collected.size

        advanceTimeBy(tickMillis * 5)
        runCurrent()

        assertEquals(afterFirstTick + 5, collected.size)
        job.cancel()
    }

    @Test
    fun `cancelling the collector stops the simulation`() = runTest {
        val collected = mutableListOf<VehicleDataState>()
        val job = launch { source().observe().toList(collected) }
        advanceTimeBy(tickMillis * 3)
        runCurrent()

        job.cancel()
        val sizeAtCancel = collected.size
        advanceTimeBy(tickMillis * 20)
        runCurrent()

        assertEquals(sizeAtCancel, collected.size)
    }

    @Test
    fun `commands issued while the flow runs are honoured`() = runTest {
        val source = source()
        val collected = mutableListOf<VehicleDataState>()
        val job = launch { source.observe().toList(collected) }
        advanceTimeBy(tickMillis * 10)
        runCurrent()

        source.selectDriveMode(DriveMode.SPORT)
        source.resetTrip()
        advanceTimeBy(tickMillis)
        runCurrent()

        val latest = collected.filterIsInstance<VehicleDataState.Available>().last().vehicleState
        assertEquals(DriveMode.SPORT, latest.driveMode)
        assertTrue(latest.trip.distanceKm < 0.05)
        job.cancel()
    }

    @Test
    fun `resetting restores the starting odometer`() = runTest {
        val source = source()
        val collected = mutableListOf<VehicleDataState>()
        val job = launch { source.observe().toList(collected) }
        advanceTimeBy(tickMillis * 200)
        runCurrent()

        source.reset()
        advanceTimeBy(tickMillis)
        runCurrent()

        val latest = collected.filterIsInstance<VehicleDataState.Available>().last().vehicleState
        assertEquals(DemoVehicleSimulator.INITIAL_ODOMETER_KM, latest.odometerKm, 0.05)
        job.cancel()
    }
}
