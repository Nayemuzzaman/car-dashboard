package com.example.cardashboard.ui.settings

import com.example.cardashboard.domain.model.DriveMode
import com.example.cardashboard.domain.model.SpeedUnit
import com.example.cardashboard.domain.model.TemperatureUnit
import com.example.cardashboard.domain.model.ThemePreference
import com.example.cardashboard.domain.model.VehicleDataState
import com.example.cardashboard.domain.model.VehicleType
import com.example.cardashboard.domain.repository.VehicleRepository
import com.example.cardashboard.testing.FakeSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val settings = FakeSettingsRepository()
    private val vehicleRepository = CountingVehicleRepository()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = SettingsViewModel(settings, vehicleRepository)

    @Test
    fun `exposes the stored settings`() = runTest {
        val stored = FakeSettingsRepository(
            com.example.cardashboard.domain.model.DashboardSettings(
                speedUnit = SpeedUnit.MILES_PER_HOUR,
                demoVehicleType = VehicleType.ELECTRIC
            )
        )
        val viewModel = SettingsViewModel(stored, vehicleRepository)
        val collector = backgroundScope.launch(dispatcher) { viewModel.settings.collect {} }

        assertEquals(SpeedUnit.MILES_PER_HOUR, viewModel.settings.value.speedUnit)
        assertEquals(VehicleType.ELECTRIC, viewModel.settings.value.demoVehicleType)
        collector.cancel()
    }

    @Test
    fun `every control writes through to storage`() = runTest {
        val viewModel = viewModel()

        viewModel.onSpeedUnitSelected(SpeedUnit.MILES_PER_HOUR)
        viewModel.onTemperatureUnitSelected(TemperatureUnit.FAHRENHEIT)
        viewModel.onDemoModeChanged(false)
        viewModel.onDemoVehicleTypeSelected(VehicleType.ELECTRIC)
        viewModel.onThemePreferenceSelected(ThemePreference.SYSTEM)
        viewModel.onAnimationsChanged(false)

        assertEquals(SpeedUnit.MILES_PER_HOUR, settings.current.speedUnit)
        assertEquals(TemperatureUnit.FAHRENHEIT, settings.current.temperatureUnit)
        assertFalse(settings.current.demoModeEnabled)
        assertEquals(VehicleType.ELECTRIC, settings.current.demoVehicleType)
        assertEquals(ThemePreference.SYSTEM, settings.current.themePreference)
        assertFalse(settings.current.animationsEnabled)
    }

    @Test
    fun `resetting demo data asks the repository and touches no setting`() = runTest {
        val viewModel = viewModel()
        val before = settings.current

        viewModel.onResetDemoData()

        assertEquals(1, vehicleRepository.resetDemoCount)
        assertEquals(before, settings.current)
    }

    private class CountingVehicleRepository : VehicleRepository {
        var resetDemoCount: Int = 0
            private set

        override val vehicleData: Flow<VehicleDataState> = emptyFlow()

        override suspend fun resetTrip() = Unit

        override suspend fun selectDriveMode(mode: DriveMode) = Unit

        override suspend fun resetDemoData() {
            resetDemoCount++
        }
    }
}
