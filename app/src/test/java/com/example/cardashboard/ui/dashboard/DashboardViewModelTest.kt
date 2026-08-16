package com.example.cardashboard.ui.dashboard

import com.example.cardashboard.domain.model.DriveMode
import com.example.cardashboard.domain.model.EnergyState
import com.example.cardashboard.domain.model.SpeedUnit
import com.example.cardashboard.domain.model.VehicleDataSourceKind
import com.example.cardashboard.domain.model.VehicleDataState
import com.example.cardashboard.domain.model.VehicleDataUnavailableReason
import com.example.cardashboard.domain.model.VehicleState
import com.example.cardashboard.domain.model.VehicleType
import com.example.cardashboard.domain.repository.VehicleRepository
import com.example.cardashboard.testing.FakeSettingsRepository
import com.example.cardashboard.util.TimeProvider
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val settings = FakeSettingsRepository()
    private val vehicleRepository = RecordingVehicleRepository()
    private val timeProvider = FixedTimeProvider(LocalDateTime.of(2026, 8, 6, 14, 32))

    @Before
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main, which does not exist in a JVM unit test.
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = DashboardViewModel(
        vehicleRepository = vehicleRepository,
        settingsRepository = settings,
        timeProvider = timeProvider
    )

    @Test
    fun `starts in the loading state with nothing to draw`() = runTest {
        val viewModel = viewModel()

        assertTrue(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.vehicleState)
        assertFalse(viewModel.uiState.value.isDemoData)
    }

    @Test
    fun `an available snapshot reaches the ui state and is tagged as demo`() = runTest {
        val viewModel = viewModel()
        val collector = backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        vehicleRepository.emit(
            VehicleDataState.Available(
                VehicleState(speedKmh = 88f, rpm = 2_400),
                VehicleDataSourceKind.DEMO
            )
        )

        val state = viewModel.uiState.value
        assertEquals(88f, state.vehicleState!!.speedKmh, 0f)
        assertTrue(state.isDemoData)
        assertFalse(state.isLoading)
        assertEquals(timeProvider.fixed, state.now)
        collector.cancel()
    }

    @Test
    fun `a dropout keeps the last reading on screen and marks it stale`() = runTest {
        val viewModel = viewModel()
        val collector = backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        vehicleRepository.emit(
            VehicleDataState.Unavailable(
                reason = VehicleDataUnavailableReason.READ_FAILED,
                lastKnownState = VehicleState(speedKmh = 61f)
            )
        )

        val state = viewModel.uiState.value
        assertTrue(state.isStale)
        assertFalse(state.isDemoData)
        assertEquals(61f, state.vehicleState!!.speedKmh, 0f)
        collector.cancel()
    }

    @Test
    fun `with no data at all there is nothing to draw and nothing is claimed`() = runTest {
        val viewModel = viewModel()
        val collector = backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        vehicleRepository.emit(
            VehicleDataState.Unavailable(VehicleDataUnavailableReason.NO_SUPPORTED_INTERFACE)
        )

        val state = viewModel.uiState.value
        assertNull(state.vehicleState)
        assertFalse(state.isDemoData)
        assertFalse(state.isStale)
        collector.cancel()
    }

    @Test
    fun `toggling the speed unit switches to miles and back and persists each time`() = runTest {
        val viewModel = viewModel()
        val collector = backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.onToggleSpeedUnit()
        assertEquals(SpeedUnit.MILES_PER_HOUR, settings.current.speedUnit)
        assertEquals(SpeedUnit.MILES_PER_HOUR, viewModel.uiState.value.settings.speedUnit)

        viewModel.onToggleSpeedUnit()
        assertEquals(SpeedUnit.KILOMETERS_PER_HOUR, settings.current.speedUnit)
        collector.cancel()
    }

    @Test
    fun `selecting a drive mode is passed to the repository`() = runTest {
        val viewModel = viewModel()

        viewModel.onDriveModeSelected(DriveMode.SPORT)

        assertEquals(DriveMode.SPORT, vehicleRepository.lastDriveMode)
    }

    @Test
    fun `trip reset does nothing until the driver confirms`() = runTest {
        val viewModel = viewModel()
        val collector = backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.onRequestTripReset()
        assertTrue(viewModel.uiState.value.tripResetConfirmationVisible)
        assertEquals(0, vehicleRepository.resetTripCount)

        viewModel.onConfirmTripReset()
        assertFalse(viewModel.uiState.value.tripResetConfirmationVisible)
        assertEquals(1, vehicleRepository.resetTripCount)
        collector.cancel()
    }

    @Test
    fun `dismissing the trip reset leaves the trip alone`() = runTest {
        val viewModel = viewModel()
        val collector = backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.onRequestTripReset()
        viewModel.onDismissTripReset()

        assertFalse(viewModel.uiState.value.tripResetConfirmationVisible)
        assertEquals(0, vehicleRepository.resetTripCount)
        collector.cancel()
    }

    @Test
    fun `the powertrain comes from live data once a snapshot has arrived`() = runTest {
        val viewModel = viewModel()
        val collector = backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        // Before any data, the demo profile decides which panels are drawn.
        settings.setDemoVehicleType(VehicleType.ELECTRIC)
        assertEquals(VehicleType.ELECTRIC, viewModel.uiState.value.vehicleType)

        vehicleRepository.emit(
            VehicleDataState.Available(
                VehicleState(
                    energy = EnergyState.Fuel(levelPercent = 40f, estimatedRangeKm = 200f)
                ),
                VehicleDataSourceKind.VEHICLE
            )
        )

        assertEquals(VehicleType.COMBUSTION, viewModel.uiState.value.vehicleType)
        collector.cancel()
    }

    @Test
    fun `settings changes are reflected in the ui state`() = runTest {
        val viewModel = viewModel()
        val collector = backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }

        settings.setAnimationsEnabled(false)

        assertFalse(viewModel.uiState.value.settings.animationsEnabled)
        collector.cancel()
    }

    private class RecordingVehicleRepository : VehicleRepository {
        private val states = MutableSharedFlow<VehicleDataState>(replay = 1)

        var resetTripCount: Int = 0
            private set
        var resetDemoCount: Int = 0
            private set
        var lastDriveMode: DriveMode? = null
            private set

        override val vehicleData: Flow<VehicleDataState> = states

        suspend fun emit(state: VehicleDataState) = states.emit(state)

        override suspend fun resetTrip() {
            resetTripCount++
        }

        override suspend fun selectDriveMode(mode: DriveMode) {
            lastDriveMode = mode
        }

        override suspend fun resetDemoData() {
            resetDemoCount++
        }
    }

    private class FixedTimeProvider(val fixed: LocalDateTime) : TimeProvider {
        override fun now(): LocalDateTime = fixed
    }
}
