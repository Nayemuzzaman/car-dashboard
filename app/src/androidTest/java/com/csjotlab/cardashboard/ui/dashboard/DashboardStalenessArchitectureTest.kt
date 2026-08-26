package com.csjotlab.cardashboard.ui.dashboard

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.csjotlab.cardashboard.ui.theme.CarDashboardTheme
import com.csjotlab.cardashboard.vehicle.data.VehicleRepository
import com.csjotlab.cardashboard.vehicle.domain.Gear
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.SystemClock
import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import com.csjotlab.cardashboard.vehicle.source.MockStep
import com.csjotlab.cardashboard.vehicle.source.MockVehicleDataSource
import com.csjotlab.cardashboard.vehicle.source.VehicleDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** A real Mock -> Repository -> ViewModel -> Compose stale-and-recovery path. */
class DashboardStalenessArchitectureTest {

    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Constructed lazily, on first touch from inside the composition — never as field initializers.
     *
     * `VehicleRepository` shares with `SharingStarted.Eagerly`, so building it starts the mock
     * script immediately on a real wall clock. As field initializers these run when JUnit
     * instantiates the test class, which is *before* `setContent`, so the whole timeline raced the
     * activity launch and the first frame. Every observable window is bounded by `staleTimeoutMs`,
     * so a slow first composition could miss a state entirely and time out against a screen that
     * had already moved on.
     */
    private val sourceLazy = lazy {
        MockVehicleDataSource(
            clock = SystemClock,
            scope = scope,
            script = listOf(
                MockStep(300) { frame, _ ->
                    frame.copy(connection = VehicleConnectionState.Connecting)
                },
                MockStep(300) { frame, _ ->
                    frame.copy(connection = VehicleConnectionState.Reading)
                },
                MockStep(300) { frame, nowMs ->
                    frame.copy(state = telemetry(speed = 60f, nowMs = nowMs))
                },
                // Longer than the repository watchdog: absence of a response must clear the screen.
                MockStep(2_500) { frame, nowMs ->
                    frame.copy(state = telemetry(speed = 61f, nowMs = nowMs))
                },
            ),
        )
    }

    private val repositoryLazy = lazy {
        VehicleRepository(
            sources = MutableStateFlow<VehicleDataSource?>(sourceLazy.value),
            clock = SystemClock,
            // Widened from 500 ms with the script stretched to match: the assertions below observe
            // three successive states, and each window is exactly staleTimeoutMs wide.
            scope = scope,
            staleTimeoutMs = 1_200,
        )
    }

    private val viewModelLazy = lazy { DashboardViewModel(repositoryLazy.value) }

    @After
    fun tearDownArchitecture() {
        if (repositoryLazy.isInitialized()) {
            runBlocking { repositoryLazy.value.shutdown() }
        }
        scope.cancel()
    }

    @Test
    fun staleResponseGapClearsTelemetryAndNextVehicleResponseRestoresIt() {
        compose.setContent {
            // First touch of the lazy graph happens here, inside composition — so the mock script
            // starts with the UI already up rather than racing it.
            val viewModel = viewModelLazy.value
            val uiState by viewModel.uiState.collectAsState()
            CarDashboardTheme {
                DashboardScreen(
                    uiState = uiState,
                    onDriveModeLabelChanged = viewModel::setDriveModeLabel,
                    onIssueClick = {},
                )
            }
        }

        waitForText("60", timeoutMs = 4_000)
        compose.onNodeWithText("60").assertIsDisplayed()

        waitForText("Vehicle communication unavailable", timeoutMs = 4_000)
        compose.onNodeWithText("60").assertDoesNotExist()
        compose.onNodeWithText("All clear").assertDoesNotExist()

        waitForText("61", timeoutMs = 4_000)
        compose.onNodeWithText("61").assertIsDisplayed()
        waitForText("Reading data")
    }

    /** See [DashboardArchitectureTest]: the default `atLeastOneRootRequired = true` throws rather
     *  than returning empty when no root is attached, which is a poll iteration, not a failure. */
    private fun waitForText(text: String, timeoutMs: Long = 2_000) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithText(text)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
    }

    private companion object {
        fun telemetry(speed: Float, nowMs: Long) = VehicleState.unavailable(VehicleSourceId.MOCK).copy(
            speedKph = Signal.Value(speed, nowMs),
            engineRpm = Signal.Value(2_100, nowMs),
            fuelLevelPercent = Signal.Value(62f, nowMs),
            coolantTemperatureCelsius = Signal.Value(91, nowMs),
            gear = Signal.Value(Gear.Drive, nowMs),
            lastUpdatedMs = nowMs,
        )
    }
}
