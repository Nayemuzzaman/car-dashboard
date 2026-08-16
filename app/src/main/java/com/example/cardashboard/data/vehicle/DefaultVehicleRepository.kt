package com.example.cardashboard.data.vehicle

import com.example.cardashboard.domain.model.DriveMode
import com.example.cardashboard.domain.model.VehicleDataState
import com.example.cardashboard.domain.model.VehicleDataUnavailableReason
import com.example.cardashboard.domain.model.VehicleState
import com.example.cardashboard.domain.model.VehicleType
import com.example.cardashboard.domain.model.sanitized
import com.example.cardashboard.domain.repository.SettingsRepository
import com.example.cardashboard.domain.repository.VehicleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Chooses the active data source from the user's settings and guarantees that whatever reaches the
 * UI has been clamped to displayable values.
 *
 * The UI never learns which implementation is running; it only sees the
 * [com.example.cardashboard.domain.model.VehicleDataSourceKind] tag carried by each snapshot, which
 * is what the "DEMO DATA" badge is driven from.
 */
class DefaultVehicleRepository(
    private val settingsRepository: SettingsRepository,
    private val demoSourceFactory: (VehicleType) -> VehicleDataSource,
    private val liveSource: VehicleDataSource
) : VehicleRepository {

    /**
     * The source currently selected by settings. Kept after collection stops so that a command
     * issued from another screen — resetting the trip from settings, say — still lands.
     */
    private val activeSource = MutableStateFlow<VehicleDataSource?>(null)

    /** Remembered so a mode chosen before, or across, a source switch is not silently dropped. */
    private var selectedDriveMode: DriveMode = DriveMode.NORMAL

    /** Last good snapshot, so a dropout can dim the cluster instead of blanking it. */
    private var lastGoodState: VehicleState? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override val vehicleData: Flow<VehicleDataState> = settingsRepository.settings
        .map { SourceSelection(it.demoModeEnabled, it.demoVehicleType) }
        .distinctUntilChanged()
        .flatMapLatest { selection ->
            val source = if (selection.demoEnabled) {
                demoSourceFactory(selection.vehicleType)
            } else {
                liveSource
            }
            source.selectDriveMode(selectedDriveMode)
            activeSource.value = source
            // Readings belong to the source that produced them. Keeping demo values on screen after
            // the driver switches to the vehicle source would present simulated data as real.
            lastGoodState = null
            source.observe()
                .map { state -> state.normalized() }
                .catch { cause ->
                    if (cause is CancellationException) throw cause
                    // A source failure is usually transient: report it, keep the last reading.
                    emit(
                        VehicleDataState.Unavailable(
                            reason = VehicleDataUnavailableReason.READ_FAILED,
                            lastKnownState = lastGoodState
                        )
                    )
                }
        }

    override suspend fun resetTrip() {
        activeSource.value?.resetTrip()
    }

    override suspend fun selectDriveMode(mode: DriveMode) {
        selectedDriveMode = mode
        activeSource.value?.selectDriveMode(mode)
    }

    override suspend fun resetDemoData() {
        activeSource.value?.reset()
    }

    /** Single point where every snapshot is clamped before anything downstream sees it. */
    private fun VehicleDataState.normalized(): VehicleDataState = when (this) {
        is VehicleDataState.Available -> {
            val safe = vehicleState.sanitized()
            lastGoodState = safe
            copy(vehicleState = safe)
        }

        is VehicleDataState.Unavailable ->
            if (lastGoodState == null) this else copy(lastKnownState = lastGoodState)

        VehicleDataState.Loading -> this
    }

    private data class SourceSelection(
        val demoEnabled: Boolean,
        val vehicleType: VehicleType
    )
}
