package com.csjotlab.cardashboard.ui.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.csjotlab.cardashboard.ui.theme.CarDashboardTheme
import com.csjotlab.cardashboard.vehicle.data.VehicleSnapshot
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticCode
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticIssue
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticSource
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticStatus
import com.csjotlab.cardashboard.vehicle.domain.DoorPosition
import com.csjotlab.cardashboard.vehicle.domain.DoorState
import com.csjotlab.cardashboard.vehicle.domain.Gear
import com.csjotlab.cardashboard.vehicle.domain.SeatPosition
import com.csjotlab.cardashboard.vehicle.domain.SeatbeltState
import com.csjotlab.cardashboard.vehicle.domain.Severity
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.TirePosition
import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleDiagnosticsState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Formatter-to-screen contract checks for the eight states in the Task 13 matrix.
 *
 * The screen never receives a hand-built [DashboardUiState]. Every case crosses the production
 * [VehicleStateFormatter] boundary from an honest [VehicleSnapshot]. Geometry is pinned in dp so
 * these assertions do not depend on the Pixel's physical density or current orientation.
 */
class DashboardScreenStateTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun disconnectedShowsNoVehicleAndNoTelemetryValues() {
        show(VehicleSnapshot.Disconnected)

        assertAtLeastOneDisplayed("Vehicle not connected")
        // Exact, not `>= 7`. A loose floor would still pass if half the missing readings started
        // rendering as `0` instead of an em dash, which is the precise data-integrity regression
        // this assertion exists to catch.
        assertEquals(
            "every missing reading must render as an em dash",
            UNAVAILABLE_NODES_WHEN_DISCONNECTED,
            compose.onAllNodesWithText(UNAVAILABLE).fetchSemanticsNodes().size,
        )
        listOf("60", "2,100", "62%", "91 C", "D").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithText("All clear").assertDoesNotExist()
    }

    @Test
    fun healthyReadingShowsEveryMetricAndOnlyCompleteSafetyClaims() {
        show(reading(healthy))

        listOf("60", "2,100", "62%", "91 C", "D", "12,345 km", "4.2 km", "180 km").forEach {
            assertAtLeastOneDisplayed(it)
        }
        listOf("Secured", "All doors closed", "Nominal", "No fault", "All clear").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test
    fun oneWarningShowsTheReportedDoorAndOneActiveSummary() {
        val doors = DoorPosition.entries.associateWith { position ->
            if (position == DoorPosition.RearLeft) DoorState.Open else DoorState.Closed
        }

        show(reading(healthy.copy(doors = Signal.Value(doors, NOW_MS))))

        compose.onNodeWithText("Rear left open").assertIsDisplayed()
        compose.onNodeWithText("1 active").assertIsDisplayed()
        compose.onNodeWithText("All clear").assertDoesNotExist()
    }

    @Test
    fun multipleWarningsAndDiagnosticStayDistinct() {
        val doors = DoorPosition.entries.associateWith { position ->
            if (position == DoorPosition.RearLeft) DoorState.Open else DoorState.Closed
        }
        val tires = TirePosition.entries.associateWith { position ->
            if (position == TirePosition.FrontRight) 150f else 230f
        }
        val state = healthy.copy(
            doors = Signal.Value(doors, NOW_MS),
            tirePressuresKpa = Signal.Value(tires, NOW_MS),
            malfunctionIndicatorLampOn = Signal.Value(true, NOW_MS),
        )

        show(reading(state, diagnosticsWithP0301))

        compose.onNodeWithText("Rear left open").assertIsDisplayed()
        compose.onNodeWithText("Front right low").assertIsDisplayed()
        compose.onNodeWithText("Code P0301").assertIsDisplayed()
        compose.onNodeWithText("3 active · 1 issue").assertIsDisplayed()
    }

    @Test
    fun multipleDiagnosticsIncludeUnknownCodeAndResolutionRemovesOnlyThatIssue() {
        val unknown = issue("P9999")
        val snapshot = mutableStateOf(
            reading(
                healthy.copy(malfunctionIndicatorLampOn = Signal.Value(true, NOW_MS)),
                diagnosticsWithP0301.copy(
                    issues = diagnosticsWithP0301.issues + unknown,
                    storedDtcCount = Signal.Value(2, NOW_MS),
                ),
            ),
        )
        show(snapshot.value, snapshot = { snapshot.value }, widthDp = 411, heightDp = 900)

        compose.onNodeWithText("P9999").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("P0301").assertIsDisplayed()
        compose.onAllNodesWithText("Engine diagnostic code detected").assertCountEquals(2)

        compose.runOnIdle {
            snapshot.value = reading(
                healthy.copy(malfunctionIndicatorLampOn = Signal.Value(true, NOW_MS)),
                diagnosticsWithP0301,
            )
        }

        compose.onNodeWithText("P9999").assertDoesNotExist()
        compose.onNodeWithText("P0301").assertIsDisplayed()
    }

    @Test
    fun connectionLossClearsEveryPreviouslyVisibleReading() {
        val snapshot = mutableStateOf(reading(healthy, diagnosticsWithP0301))
        show(snapshot.value, snapshot = { snapshot.value })

        compose.onNodeWithText("60").assertIsDisplayed()
        // The landscape layout deliberately carries no VehicleHealthPanel, so the bare code is
        // never rendered here and asserting its absence would prove nothing. The diagnostic's only
        // on-screen trace in this layout is the Warnings header count — assert on that instead,
        // both before and after, so the clearing claim in this test's name is actually earned.
        compose.onNodeWithText("P0301").assertDoesNotExist()
        compose.onNodeWithText("All clear · 1 issue").assertIsDisplayed()

        compose.runOnIdle {
            snapshot.value = VehicleSnapshot(
                state = VehicleState.unavailable(VehicleSourceId.MOCK),
                diagnostics = VehicleDiagnosticsState.empty(),
                connection = VehicleConnectionState.ConnectionLost("removed"),
            )
        }

        assertAtLeastOneDisplayed("Connection lost")
        listOf("60", "2,100", "62%", "91 C", "D").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithText("All clear · 1 issue").assertDoesNotExist()
        compose.onNodeWithText("All clear").assertDoesNotExist()
    }

    @Test
    fun unsupportedAndUnknownRemainDifferentAndNeverLookSafe() {
        show(
            reading(
                healthy.copy(
                    engineRpm = Signal.Unknown,
                    fuelLevelPercent = Signal.Unsupported,
                    doors = Signal.Unsupported,
                    seatbelts = Signal.Unknown,
                ),
            ),
        )

        assertAtLeastOneDisplayed("Not available from this source")
        assertAtLeastOneDisplayed("Not reported by vehicle")
        assertAtLeastOneDisplayed("Not reported")
        compose.onNodeWithText("All doors closed").assertDoesNotExist()
        compose.onNodeWithText("Secured").assertDoesNotExist()
        compose.onNodeWithText("All clear").assertDoesNotExist()
    }

    @Test
    fun staleVehicleCommunicationClearsTelemetryAndDiagnostics() {
        // A real transition, not a static render: values and a diagnostic must be on screen first,
        // otherwise "clears" asserts the absence of things that were never present.
        val snapshot = mutableStateOf(reading(healthy, diagnosticsWithP0301))
        show(snapshot.value, snapshot = { snapshot.value })

        compose.onNodeWithText("60").assertIsDisplayed()
        compose.onNodeWithText("All clear · 1 issue").assertIsDisplayed()

        compose.runOnIdle {
            snapshot.value = VehicleSnapshot(
                state = VehicleState.unavailable(VehicleSourceId.MOCK),
                diagnostics = VehicleDiagnosticsState.empty(),
                connection = VehicleConnectionState.VehicleCommunicationUnavailable(
                    "No vehicle response for 3000 ms",
                ),
            )
        }

        assertAtLeastOneDisplayed("Vehicle communication unavailable")
        listOf("60", "2,100", "62%", "91 C", "D").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithText("All clear · 1 issue").assertDoesNotExist()
        compose.onNodeWithText("All clear").assertDoesNotExist()
    }

    @Test
    fun protocolErrorSurfacesWithoutInventingVehicleValues() {
        show(
            VehicleSnapshot(
                state = VehicleState.unavailable(VehicleSourceId.OBD_USB),
                diagnostics = VehicleDiagnosticsState.empty(),
                connection = VehicleConnectionState.Error("protocol failure"),
            ),
        )

        assertAtLeastOneDisplayed("Error")
        compose.onNodeWithText("60").assertDoesNotExist()
        compose.onNodeWithText("All clear").assertDoesNotExist()
    }

    private fun show(
        initial: VehicleSnapshot,
        snapshot: () -> VehicleSnapshot = { initial },
        widthDp: Int = 900,
        heightDp: Int = 360,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                CarDashboardTheme {
                    Box(
                        Modifier
                            .size(widthDp.dp, heightDp.dp)
                            .consumeWindowInsets(WindowInsets(0, 0, 0, 0)),
                    ) {
                        DashboardScreen(
                            uiState = VehicleStateFormatter.toUiState(snapshot(), "Comfort"),
                            onDriveModeLabelChanged = {},
                            onIssueClick = {},
                        )
                    }
                }
            }
        }
    }

    private fun assertAtLeastOneDisplayed(text: String) {
        val nodes = compose.onAllNodesWithText(text)
        assertTrue("expected at least one node with text <$text>", nodes.fetchSemanticsNodes().isNotEmpty())
        nodes.onFirst().assertIsDisplayed()
    }

    private fun reading(
        state: VehicleState,
        diagnostics: VehicleDiagnosticsState = VehicleDiagnosticsState.empty(),
    ) = VehicleSnapshot(state, diagnostics, VehicleConnectionState.Reading)

    private val healthy = VehicleState.unavailable(VehicleSourceId.MOCK).copy(
        speedKph = Signal.Value(60f, NOW_MS),
        engineRpm = Signal.Value(2100, NOW_MS),
        fuelLevelPercent = Signal.Value(62f, NOW_MS),
        coolantTemperatureCelsius = Signal.Value(91, NOW_MS),
        gear = Signal.Value(Gear.Drive, NOW_MS),
        tripDistanceKm = Signal.Value(4.2, NOW_MS),
        estimatedRangeKm = Signal.Value(180.0, NOW_MS),
        odometerKm = Signal.Value(12_345.0, NOW_MS),
        doors = Signal.Value(DoorPosition.entries.associateWith { DoorState.Closed }, NOW_MS),
        seatbelts = Signal.Value(SeatPosition.entries.associateWith { SeatbeltState.Buckled }, NOW_MS),
        tirePressuresKpa = Signal.Value(TirePosition.entries.associateWith { 230f }, NOW_MS),
        malfunctionIndicatorLampOn = Signal.Value(false, NOW_MS),
        lastUpdatedMs = NOW_MS,
    )

    private val diagnosticsWithP0301 = VehicleDiagnosticsState(
        issues = listOf(
            issue("P0301"),
        ),
        malfunctionIndicatorLampOn = Signal.Value(true, NOW_MS),
        storedDtcCount = Signal.Value(1, NOW_MS),
        lastScanMs = NOW_MS,
    )

    private fun issue(code: String) = DiagnosticIssue(
        id = "dtc:$code",
        code = DiagnosticCode.Dtc(code),
        title = null,
        description = null,
        classification = null,
        severity = Severity.Warning,
        status = DiagnosticStatus.Stored,
        source = DiagnosticSource.Mock,
        firstSeenMs = NOW_MS,
        lastSeenMs = NOW_MS,
    )

    private companion object {
        const val NOW_MS = 1_000L

        /**
         * Measured on-device, not estimated: speed, trip and range in SpeedPanel; four metric
         * values; two in OdometerPanel; the Warnings header summary; four warning-row statuses.
         */
        const val UNAVAILABLE_NODES_WHEN_DISCONNECTED = 14
    }
}
