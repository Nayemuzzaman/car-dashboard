package com.csjotlab.cardashboard.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.csjotlab.cardashboard.vehicle.data.VehicleRepository
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticIssue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Maps repository snapshots to display state. The dashboard owns no timers and no data loops.
 */
class DashboardViewModel(
    private val repository: VehicleRepository,
) : ViewModel() {

    private val driveModeLabel = MutableStateFlow("Comfort")

    val uiState: StateFlow<DashboardUiState> =
        combine(repository.snapshot, driveModeLabel) { snapshot, mode ->
            VehicleStateFormatter.toUiState(snapshot, mode)
        }
            .distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = DashboardUiState.disconnected(driveModeLabel.value),
            )

    /** One repository-derived issue state for the lifetime of this ViewModel. */
    val diagnosticIssues: StateFlow<List<DiagnosticIssue>> =
        repository.snapshot
            .map { snapshot -> snapshot.diagnostics.issues }
            .distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = repository.snapshot.value.diagnostics.issues,
            )

    /** The drive mode is a display preference owned by the UI, not vehicle data. */
    fun setDriveModeLabel(label: String) {
        driveModeLabel.value = label
    }

    /** A cold per-ID view; collecting it creates no ViewModel-owned sharing job. */
    fun issueById(id: String): Flow<DiagnosticIssue?> =
        diagnosticIssues
            .map { issues -> issues.firstOrNull { it.id == id } }
            .distinctUntilChanged()

    class Factory(private val repository: VehicleRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DashboardViewModel(repository) as T
    }
}
