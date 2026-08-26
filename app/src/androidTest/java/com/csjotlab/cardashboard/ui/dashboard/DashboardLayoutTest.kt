package com.csjotlab.cardashboard.ui.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.csjotlab.cardashboard.ui.theme.CarDashboardTheme
import com.csjotlab.cardashboard.ui.theme.DashboardSpacing
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Density-independent guards for both responsive branches and all three drive-mode controls. */
class DashboardLayoutTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun landscapeFloorShowsEveryPanelContractWithoutClipping() {
        showAt(widthDp = 900, heightDp = 348)

        listOf("RPM", "Fuel", "Gear", "Temp", "Seatbelt", "Door", "Tire Pressure", "Check Engine").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        listOf("Eco", "Comfort", "Sport").forEach {
            assertAtLeastOneDisplayed(it)
        }
    }

    /**
     * The same floor, but carrying the longest strings the dashboard can produce.
     *
     * Rendering only [VehicleSnapshot.Disconnected] measures the cheapest possible content: every
     * value is a single em dash and every helper is "Not reported". A populated state has multi-word
     * helpers ("Rear left open", "Front right low"), a partial-coverage count and a DTC in the
     * header — the case that would actually overflow the 348 dp floor.
     */
    @Test
    fun landscapeFloorSurvivesTheLongestPopulatedContent() {
        showAt(widthDp = 900, heightDp = 348, snapshot = populated)

        listOf("RPM", "Fuel", "Gear", "Temp", "Seatbelt", "Door", "Tire Pressure", "Check Engine").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        listOf("Rear left open", "Front right low").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test
    fun portraitShowsTheFourFixedMetricTiles() {
        showAt(widthDp = 411, heightDp = 900)

        listOf("RPM", "Fuel", "Gear", "Temp").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        // A disconnected dashboard must not let the health panel claim the vehicle reported
        // cleanly. "No issues reported" is a positive safety statement; with no connection there
        // is nothing to have reported.
        compose.onNodeWithText(HEALTH_NO_ISSUES).assertDoesNotExist()
        assertAtLeastOneDisplayed(HEALTH_DISCONNECTED)
    }

    /**
     * Direct guards for two protected portrait dimensions that were only ever covered incidentally.
     *
     * Both are measured as distances rather than by reaching for a tile node, because the tiles
     * carry no test tag and their labels are the only stable handles.
     *
     * - `SpeedPanel` is pinned at a fixed `height(360.dp)`; as a `weight(1f)` child of the
     *   unbounded scrolling Column it would measure to zero. The gap from the Speed heading down to
     *   the first metric row is the observable consequence.
     * - `MetricTile` keeps its `heightIn(min = 128.dp)` floor in portrait (it is dropped only when
     *   compact). Without it the tiles collapse to wrap-content, which shows up as the two metric
     *   rows sitting closer together than one floor's worth of height.
     */
    @Test
    fun portraitHoldsItsProtectedFixedDimensions() {
        showAt(widthDp = 411, heightDp = 900)

        val speedTop = compose.onNodeWithText("Speed").getUnclippedBoundsInRoot().top
        val firstRowTop = compose.onNodeWithText("RPM").getUnclippedBoundsInRoot().top
        val secondRowTop = compose.onNodeWithText("Gear").getUnclippedBoundsInRoot().top

        assertTrue(
            "SpeedPanel collapsed: only ${firstRowTop - speedTop} between the Speed heading " +
                "and the metric grid, so its fixed 360 dp height is not in effect",
            (firstRowTop - speedTop) > 300.dp,
        )
        // Derived from the production constants rather than hard-coded: in portrait the grid is
        // not compact, so its gap is DashboardSpacing.medium and each tile is floored at 128 dp,
        // making the row pitch exactly 144 dp. Wrap-content tiles measure 122 dp here, so the
        // pitch drops to 138 dp the moment the floor is removed — which is what this discriminates.
        // A `>= 128.dp` threshold would NOT: both values clear it.
        val flooredRowPitch = METRIC_TILE_MIN_HEIGHT + DashboardSpacing.medium
        assertTrue(
            "metric tiles lost their 128 dp portrait floor: row pitch is " +
                "${secondRowTop - firstRowTop}, expected at least $flooredRowPitch",
            (secondRowTop - firstRowTop) >= flooredRowPitch,
        )
    }

    /**
     * GLOBAL-CONSTRAINTS requires exactly four warning rows *in every state*. Every other on-device
     * assertion of the four rows is in landscape; without this one, portrait is unchecked.
     */
    @Test
    fun portraitShowsTheFourFixedWarningRows() {
        showAt(widthDp = 411, heightDp = 900, snapshot = populated)

        listOf("Seatbelt", "Door", "Tire Pressure", "Check Engine").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun allThreeDriveModesAreSelectable() {
        showAt(widthDp = 900, heightDp = 360)

        listOf("Eco", "Comfort", "Sport").forEach { mode ->
            compose.onNodeWithText(mode).performClick()
            // The selected mode appears once on its chip and once in the panel header. Merely
            // finding the clicked chip again would not prove that selection state changed.
            compose.onAllNodesWithText(mode).assertCountEquals(2)
        }
    }

    private fun showAt(
        widthDp: Int,
        heightDp: Int,
        snapshot: VehicleSnapshot = VehicleSnapshot.Disconnected,
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
                            uiState = VehicleStateFormatter.toUiState(snapshot, "Comfort"),
                            onDriveModeLabelChanged = {},
                            onIssueClick = {},
                        )
                    }
                }
            }
        }
    }

    /** An active door, an active tyre, partial seatbelt coverage and a stored DTC. */
    private val populated: VehicleSnapshot
        get() {
            val state = VehicleState.unavailable(VehicleSourceId.MOCK).copy(
                speedKph = Signal.Value(60f, NOW_MS),
                engineRpm = Signal.Value(2100, NOW_MS),
                fuelLevelPercent = Signal.Value(62f, NOW_MS),
                coolantTemperatureCelsius = Signal.Value(91, NOW_MS),
                gear = Signal.Value(Gear.Drive, NOW_MS),
                odometerKm = Signal.Value(12_345.0, NOW_MS),
                doors = Signal.Value(
                    DoorPosition.entries.associateWith {
                        if (it == DoorPosition.RearLeft) DoorState.Open else DoorState.Closed
                    },
                    NOW_MS,
                ),
                // Deliberately partial, so the Seatbelt row renders its "n of m not reported" form.
                seatbelts = Signal.Value(mapOf(SeatPosition.Driver to SeatbeltState.Buckled), NOW_MS),
                tirePressuresKpa = Signal.Value(
                    TirePosition.entries.associateWith {
                        if (it == TirePosition.FrontRight) 150f else 230f
                    },
                    NOW_MS,
                ),
                malfunctionIndicatorLampOn = Signal.Value(true, NOW_MS),
                lastUpdatedMs = NOW_MS,
            )
            return VehicleSnapshot(
                state = state,
                diagnostics = VehicleDiagnosticsState(
                    issues = listOf(
                        DiagnosticIssue(
                            id = "dtc:P0301",
                            code = DiagnosticCode.Dtc("P0301"),
                            title = null,
                            description = null,
                            classification = null,
                            severity = Severity.Warning,
                            status = DiagnosticStatus.Stored,
                            source = DiagnosticSource.Mock,
                            firstSeenMs = NOW_MS,
                            lastSeenMs = NOW_MS,
                        ),
                    ),
                    malfunctionIndicatorLampOn = Signal.Value(true, NOW_MS),
                    storedDtcCount = Signal.Value(1, NOW_MS),
                    lastScanMs = NOW_MS,
                ),
                connection = VehicleConnectionState.Reading,
            )
        }

    private companion object {
        const val NOW_MS = 1_000L

        /** Mirrors the MetricTile floor in DashboardScreen.kt. */
        val METRIC_TILE_MIN_HEIGHT = 128.dp
    }

    private fun assertAtLeastOneDisplayed(text: String) {
        val nodes = compose.onAllNodesWithText(text)
        assertTrue("expected at least one node with text <$text>", nodes.fetchSemanticsNodes().isNotEmpty())
        nodes.onFirst().assertIsDisplayed()
    }
}
