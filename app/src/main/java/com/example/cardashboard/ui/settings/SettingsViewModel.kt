package com.example.cardashboard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cardashboard.di.AppContainer
import com.example.cardashboard.domain.model.DashboardSettings
import com.example.cardashboard.domain.model.SpeedUnit
import com.example.cardashboard.domain.model.TemperatureUnit
import com.example.cardashboard.domain.model.ThemePreference
import com.example.cardashboard.domain.model.VehicleType
import com.example.cardashboard.domain.repository.SettingsRepository
import com.example.cardashboard.domain.repository.VehicleRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs the settings screen. Every change is written straight through to persistent storage. */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val vehicleRepository: VehicleRepository
) : ViewModel() {

    val settings: StateFlow<DashboardSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = DashboardSettings.DEFAULT
    )

    fun onSpeedUnitSelected(unit: SpeedUnit) = update { setSpeedUnit(unit) }

    fun onTemperatureUnitSelected(unit: TemperatureUnit) = update { setTemperatureUnit(unit) }

    fun onDemoModeChanged(enabled: Boolean) = update { setDemoModeEnabled(enabled) }

    fun onDemoVehicleTypeSelected(type: VehicleType) = update { setDemoVehicleType(type) }

    fun onThemePreferenceSelected(preference: ThemePreference) =
        update { setThemePreference(preference) }

    fun onAnimationsChanged(enabled: Boolean) = update { setAnimationsEnabled(enabled) }

    /** Puts the simulation back to its starting point, including odometer and energy level. */
    fun onResetDemoData() {
        viewModelScope.launch { vehicleRepository.resetDemoData() }
    }

    private fun update(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { settingsRepository.block() }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsRepository = container.settingsRepository,
                    vehicleRepository = container.vehicleRepository
                )
            }
        }
    }
}
