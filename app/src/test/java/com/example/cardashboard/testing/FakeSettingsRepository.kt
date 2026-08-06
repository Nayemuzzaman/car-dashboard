package com.example.cardashboard.testing

import com.example.cardashboard.domain.model.DashboardSettings
import com.example.cardashboard.domain.model.SpeedUnit
import com.example.cardashboard.domain.model.TemperatureUnit
import com.example.cardashboard.domain.model.ThemePreference
import com.example.cardashboard.domain.model.VehicleType
import com.example.cardashboard.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory settings, so ViewModel and repository tests do not need DataStore or a file system. */
class FakeSettingsRepository(
    initial: DashboardSettings = DashboardSettings.DEFAULT
) : SettingsRepository {

    private val state = MutableStateFlow(initial)

    override val settings: StateFlow<DashboardSettings> = state

    val current: DashboardSettings get() = state.value

    override suspend fun setSpeedUnit(unit: SpeedUnit) {
        state.value = state.value.copy(speedUnit = unit)
    }

    override suspend fun setTemperatureUnit(unit: TemperatureUnit) {
        state.value = state.value.copy(temperatureUnit = unit)
    }

    override suspend fun setDemoModeEnabled(enabled: Boolean) {
        state.value = state.value.copy(demoModeEnabled = enabled)
    }

    override suspend fun setDemoVehicleType(type: VehicleType) {
        state.value = state.value.copy(demoVehicleType = type)
    }

    override suspend fun setThemePreference(preference: ThemePreference) {
        state.value = state.value.copy(themePreference = preference)
    }

    override suspend fun setAnimationsEnabled(enabled: Boolean) {
        state.value = state.value.copy(animationsEnabled = enabled)
    }
}
