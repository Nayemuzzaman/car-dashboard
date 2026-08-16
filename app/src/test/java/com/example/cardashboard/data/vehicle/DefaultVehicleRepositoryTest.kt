package com.example.cardashboard.data.vehicle

import com.example.cardashboard.domain.model.DriveMode
import com.example.cardashboard.domain.model.EnergyState
import com.example.cardashboard.domain.model.VehicleDataSourceKind
import com.example.cardashboard.domain.model.VehicleDataState
import com.example.cardashboard.domain.model.VehicleDataUnavailableReason
import com.example.cardashboard.domain.model.VehicleState
import com.example.cardashboard.domain.model.VehicleType
import com.example.cardashboard.testing.FakeSettingsRepository
import com.example.cardashboard.testing.FakeVehicleDataSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultVehicleRepositoryTest {

    private val settings = FakeSettingsRepository()
    private val demoSource = FakeVehicleDataSource()
    private val liveSource = FakeVehicleDataSource()
    private val demoSourcesCreated = mutableListOf<VehicleType>()

    private fun repository(
        demo: VehicleDataSource = demoSource,
        live: VehicleDataSource = liveSource
    ) = DefaultVehicleRepository(
        settingsRepository = settings,
        demoSourceFactory = { type ->
            demoSourcesCreated += type
            demo
        },
        liveSource = live
    )

    /** Collects into [into] for the duration of the test, then cancels cleanly. */
    private fun TestScope.collectStates(
        flow: Flow<VehicleDataState>,
        into: MutableList<VehicleDataState>
    ) = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(into) }

    @Test
    fun `demo mode selects the demo source and tags its snapshots`() = runTest {
        val repository = repository()
        val collected = mutableListOf<VehicleDataState>()
        collectStates(repository.vehicleData, collected)

        demoSource.emit(
            VehicleDataState.Available(VehicleState(speedKmh = 50f), VehicleDataSourceKind.DEMO)
        )

        val available = collected.filterIsInstance<VehicleDataState.Available>().last()
        assertEquals(VehicleDataSourceKind.DEMO, available.source)
        assertEquals(listOf(VehicleType.COMBUSTION), demoSourcesCreated)
    }

    @Test
    fun `turning demo mode off switches to the vehicle source`() = runTest {
        val repository = repository()
        val collected = mutableListOf<VehicleDataState>()
        collectStates(repository.vehicleData, collected)

        demoSource.emit(
            VehicleDataState.Available(VehicleState(speedKmh = 50f), VehicleDataSourceKind.DEMO)
        )
        settings.setDemoModeEnabled(false)
        liveSource.emit(
            VehicleDataState.Unavailable(VehicleDataUnavailableReason.NO_SUPPORTED_INTERFACE)
        )

        assertTrue(collected.last() is VehicleDataState.Unavailable)
    }

    @Test
    fun `demo readings are not kept on screen after switching to the vehicle source`() = runTest {
        val repository = repository()
        val collected = mutableListOf<VehicleDataState>()
        collectStates(repository.vehicleData, collected)

        demoSource.emit(
            VehicleDataState.Available(VehicleState(speedKmh = 120f), VehicleDataSourceKind.DEMO)
        )
        settings.setDemoModeEnabled(false)
        liveSource.emit(
            VehicleDataState.Unavailable(VehicleDataUnavailableReason.NO_SUPPORTED_INTERFACE)
        )

        val unavailable = collected.last() as VehicleDataState.Unavailable
        assertNull(
            "simulated values must not survive as the vehicle source's last known state",
            unavailable.lastKnownState
        )
    }

    @Test
    fun `changing the demo vehicle type builds a source for that powertrain`() = runTest {
        val repository = repository()
        collectStates(repository.vehicleData, mutableListOf())

        settings.setDemoVehicleType(VehicleType.ELECTRIC)

        assertEquals(
            listOf(VehicleType.COMBUSTION, VehicleType.ELECTRIC),
            demoSourcesCreated
        )
    }

    @Test
    fun `impossible values from a source are clamped before they are emitted`() = runTest {
        val repository = repository()
        val collected = mutableListOf<VehicleDataState>()
        collectStates(repository.vehicleData, collected)

        demoSource.emit(
            VehicleDataState.Available(
                vehicleState = VehicleState(
                    speedKmh = -80f,
                    rpm = 99_999,
                    energy = EnergyState.Fuel(levelPercent = 250f, estimatedRangeKm = -4f)
                ),
                source = VehicleDataSourceKind.DEMO
            )
        )

        val state = collected.filterIsInstance<VehicleDataState.Available>().last().vehicleState
        assertEquals(0f, state.speedKmh, 0f)
        assertEquals(VehicleState.MAX_RPM, state.rpm)
        assertEquals(100f, state.energy.levelPercent, 0f)
        assertNull(state.energy.estimatedRangeKm)
    }

    @Test
    fun `a source failure becomes a read error that keeps the last good reading`() = runTest {
        val good = VehicleState(speedKmh = 72f)
        val failing = object : VehicleDataSource {
            override fun observe(): Flow<VehicleDataState> = flow {
                emit(VehicleDataState.Available(good, VehicleDataSourceKind.DEMO))
                throw IllegalStateException("source went away")
            }
        }
        val repository = repository(demo = failing)
        val collected = mutableListOf<VehicleDataState>()
        collectStates(repository.vehicleData, collected)

        val unavailable = collected.filterIsInstance<VehicleDataState.Unavailable>().last()
        assertEquals(VehicleDataUnavailableReason.READ_FAILED, unavailable.reason)
        assertEquals(72f, unavailable.lastKnownState!!.speedKmh, 0f)
    }

    @Test
    fun `a failure does not end the stream for the next source selection`() = runTest {
        val failing = object : VehicleDataSource {
            override fun observe(): Flow<VehicleDataState> = flow {
                throw IllegalStateException("source went away")
            }
        }
        val repository = repository(demo = failing)
        val collected = mutableListOf<VehicleDataState>()
        collectStates(repository.vehicleData, collected)

        settings.setDemoModeEnabled(false)
        liveSource.emit(
            VehicleDataState.Available(VehicleState(speedKmh = 10f), VehicleDataSourceKind.VEHICLE)
        )

        assertTrue(collected.last() is VehicleDataState.Available)
    }

    @Test
    fun `resetting the trip reaches the active source`() = runTest {
        val repository = repository()
        collectStates(repository.vehicleData, mutableListOf())

        repository.resetTrip()

        assertEquals(1, demoSource.resetTripCount)
        assertEquals(0, liveSource.resetTripCount)
    }

    @Test
    fun `resetting demo data reaches the active source`() = runTest {
        val repository = repository()
        collectStates(repository.vehicleData, mutableListOf())

        repository.resetDemoData()

        assertEquals(1, demoSource.resetCount)
    }

    @Test
    fun `a drive mode chosen before a source switch is applied to the new source`() = runTest {
        val secondDemoSource = FakeVehicleDataSource()
        val sources = ArrayDeque(listOf<VehicleDataSource>(demoSource, secondDemoSource))
        val repository = DefaultVehicleRepository(
            settingsRepository = settings,
            demoSourceFactory = { sources.removeFirst() },
            liveSource = liveSource
        )
        collectStates(repository.vehicleData, mutableListOf())

        repository.selectDriveMode(DriveMode.SPORT)
        settings.setDemoVehicleType(VehicleType.ELECTRIC)

        assertEquals(DriveMode.SPORT, demoSource.lastDriveMode)
        assertEquals(DriveMode.SPORT, secondDemoSource.lastDriveMode)
    }

    @Test
    fun `commands are ignored rather than crashing when no source is active yet`() = runTest {
        val repository = repository()

        repository.resetTrip()
        repository.resetDemoData()
        repository.selectDriveMode(DriveMode.ECO)

        assertEquals(0, demoSource.resetTripCount)
        assertEquals(0, demoSource.resetCount)
        assertNull(demoSource.lastDriveMode)
    }
}
