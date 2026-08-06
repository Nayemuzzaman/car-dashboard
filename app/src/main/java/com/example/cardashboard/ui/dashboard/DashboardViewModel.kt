package com.example.cardashboard.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cardashboard.di.AppContainer
import com.example.cardashboard.domain.model.DriveMode
import com.example.cardashboard.domain.model.SpeedUnit
import com.example.cardashboard.domain.model.VehicleDataState
import com.example.cardashboard.domain.repository.SettingsRepository
import com.example.cardashboard.domain.repository.VehicleRepository
import com.example.cardashboard.util.TimeProvider
import java.time.LocalDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Holds the dashboard's state and is the only place that talks to the repositories.
 *
 * Everything runs in [viewModelScope], so the demo simulation and the clock stop when the ViewModel
 * is cleared. The state flow uses [SharingStarted.WhileSubscribed] so the simulation also pauses
 * shortly after the screen stops being visible instead of ticking in the background.
 */
class DashboardViewModel(
    private val vehicleRepository: VehicleRepository,
    private val settingsRepository: SettingsRepository,
    timeProvider: TimeProvider,
    clockTickMillis: Long = DEFAULT_CLOCK_TICK_MILLIS
) : ViewModel() {

    private val tripResetConfirmationVisible = MutableStateFlow(false)

    private val clock: Flow<LocalDateTime> = flow {
        while (true) {
            emit(timeProvider.now())
            delay(clockTickMillis)
        }
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        // Seeded with Loading so the clock, the settings and the theme are live from the first
        // frame instead of waiting for the data source to produce its first snapshot.
        vehicleRepository.vehicleData.onStart { emit(VehicleDataState.Loading) },
        settingsRepository.settings,
        clock,
        tripResetConfirmationVisible
    ) { dataState, settings, now, confirmationVisible ->
        DashboardUiState(
            dataState = dataState,
            settings = settings,
            now = now,
            tripResetConfirmationVisible = confirmationVisible
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = DashboardUiState()
    )

    fun onDriveModeSelected(mode: DriveMode) {
        viewModelScope.launch { vehicleRepository.selectDriveMode(mode) }
    }

    /** Flips between km/h and mph and persists the choice. */
    fun onToggleSpeedUnit() {
        val next = when (uiState.value.settings.speedUnit) {
            SpeedUnit.KILOMETERS_PER_HOUR -> SpeedUnit.MILES_PER_HOUR
            SpeedUnit.MILES_PER_HOUR -> SpeedUnit.KILOMETERS_PER_HOUR
        }
        viewModelScope.launch { settingsRepository.setSpeedUnit(next) }
    }

    /** Trip data is only ever cleared after the driver confirms; see [onConfirmTripReset]. */
    fun onRequestTripReset() {
        tripResetConfirmationVisible.value = true
    }

    fun onDismissTripReset() {
        tripResetConfirmationVisible.value = false
    }

    fun onConfirmTripReset() {
        tripResetConfirmationVisible.value = false
        viewModelScope.launch { vehicleRepository.resetTrip() }
    }

    companion object {
        const val DEFAULT_CLOCK_TICK_MILLIS = 1_000L
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DashboardViewModel(
                    vehicleRepository = container.vehicleRepository,
                    settingsRepository = container.settingsRepository,
                    timeProvider = container.timeProvider
                )
            }
        }
    }
}
