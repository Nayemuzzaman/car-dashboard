package com.csjotlab.cardashboard.ui.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.csjotlab.cardashboard.debug.DebugMockModeToggle
import com.csjotlab.cardashboard.ui.theme.CarDashboardTheme
import com.csjotlab.cardashboard.vehicle.data.VehicleSnapshot
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticCode
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticIssue
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticSource
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticStatus
import com.csjotlab.cardashboard.vehicle.domain.Severity
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleDiagnosticsState
import com.csjotlab.cardashboard.vehicle.domain.SeatPosition
import com.csjotlab.cardashboard.vehicle.domain.SeatbeltState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VehicleHealthPanelTest {

    private companion object {
        /** What the Pixel 7a's landscape window measured, insets excluded. */
        const val LANDSCAPE_MEASURED_DP = 360

        /**
         * The smallest outer box that still selects the landscape layout. The `maxHeight >= 300.dp`
         * threshold is read from the inner constraints, so the 24 dp screen padding at each end has
         * to be added back.
         */
        const val WIDE_LAYOUT_FLOOR_DP = 348
    }

    @get:Rule val compose = createComposeRule()

    private val issue = DiagnosticUi(
        id = "dtc:P0301",
        code = "P0301",
        headline = "Engine diagnostic code detected",
        statusLabel = "Stored",
        severity = Severity.Warning,
    )

    /**
     * The brief wrote this as two `setContent` calls in one test. `ComposeContentTestRule.setContent`
     * throws `Cannot call setContent twice per test!`, so the content is driven from state instead.
     * That is also the stronger form: it asserts each message is *absent* in the other state, which
     * is the actual claim ("must not look the same") rather than two independent presence checks.
     */
    @Test
    fun disconnected_and_healthy_must_not_look_the_same() {
        val connected = mutableStateOf(false)
        compose.setContent {
            VehicleHealthPanel(emptyList(), isConnected = connected.value, onIssueClick = {})
        }

        compose.onNodeWithText("Vehicle not connected").assertIsDisplayed()
        compose.onNodeWithText("No issues reported").assertDoesNotExist()

        compose.runOnIdle { connected.value = true }

        compose.onNodeWithText("No issues reported").assertIsDisplayed()
        compose.onNodeWithText("Vehicle not connected").assertDoesNotExist()
    }

    @Test
    fun an_issue_shows_its_code_and_never_invents_a_description() {
        compose.setContent { VehicleHealthPanel(listOf(issue), isConnected = true, onIssueClick = {}) }

        compose.onNodeWithText("P0301").assertIsDisplayed()
        compose.onNodeWithText("Engine diagnostic code detected").assertIsDisplayed()
        compose.onNodeWithText("Stored").assertIsDisplayed()
    }

    @Test
    fun tapping_an_issue_reports_its_id() {
        var clicked: String? = null
        compose.setContent {
            VehicleHealthPanel(listOf(issue), isConnected = true, onIssueClick = { clicked = it })
        }

        compose.onNodeWithText("P0301").performClick()
        assertEquals("dtc:P0301", clicked)
    }

    @Test
    fun the_simulation_banner_states_plainly_that_the_data_is_not_real() {
        compose.setContent { SimulationBanner() }
        compose.onNodeWithText("SIMULATED DATA — NOT A REAL VEHICLE").assertIsDisplayed()
    }

    /**
     * The landscape height guard, at the height a Pixel 7a's landscape window actually measured.
     *
     * All three columns are asserted, not just the third. The banners are unweighted, so wherever
     * they sit they take height from something; putting them in a full-width row above the content
     * taxed **every** column, and column two is the tightest of the three. Asserting only the
     * warning rows proved the clipping had left column three without proving it had not simply
     * moved next door — which is exactly what had happened, silently, because `MetricTile` drops
     * its 128 dp floor when compact and so squeezes instead of failing.
     */
    @Test
    fun compact_landscape_shows_all_three_columns_intact() {
        showLandscape(heightDp = LANDSCAPE_MEASURED_DP)

        // Twice, not once: the connection state reaches the screen through the new banner *and*
        // through the Speed panel header, which has carried it since before this task. Pinning the
        // count keeps that redundancy visible instead of silently accepted — if the header value is
        // ever dropped in favour of the banner, this line is the reminder to say so on purpose.
        compose.onAllNodesWithText("Reading data").assertCountEquals(2)

        assertEveryColumnIsIntact()
    }

    /**
     * The same guard at the **floor of the wide layout**, which is the lowest height the layout
     * claims to support and was previously untested.
     *
     * The `maxWidth >= 720.dp && maxHeight >= 300.dp` threshold is evaluated on the *inner*
     * constraints, after `windowInsetsPadding` and the 24 dp screen padding on each side. The
     * smallest outer box that still selects the landscape layout is therefore 300 + 48 = 348 dp;
     * at 347 dp the screen falls back to the scrolling portrait layout, as it is designed to.
     *
     * Everything this task is responsible for survives here. The metric tile helper does not get
     * its full second line at this height — that is measured, pre-existing, identical with and
     * without these banners, and reported rather than asserted away. Hence no helper-height
     * assertion in this test, and the uniformity check inside [assertEveryColumnIsIntact] instead.
     */
    @Test
    fun compact_landscape_at_the_wide_layout_floor_shows_all_three_columns_intact() {
        showLandscape(heightDp = WIDE_LAYOUT_FLOOR_DP)
        assertEveryColumnIsIntact()
    }

    @Test
    fun debugControlDoesNotOverlapWarningsAtTheWideLayoutFloor() {
        showDashboard(
            uiState = twoActiveWarningsUi,
            widthDp = 900,
            heightDp = WIDE_LAYOUT_FLOOR_DP,
            showDebugControl = true,
        )

        assertNodesDoNotOverlap("MOCK OFF", "2 active")
        assertEveryColumnIsIntact()
    }

    @Test
    fun debugControlDoesNotOverlapWarningsAtNormalLandscapeHeight() {
        showDashboard(
            uiState = twoActiveWarningsUi,
            widthDp = 900,
            heightDp = LANDSCAPE_MEASURED_DP,
            showDebugControl = true,
        )

        assertNodesDoNotOverlap("MOCK OFF", "2 active")
        assertEveryColumnIsIntact()
    }

    @Test
    fun debugControlDoesNotOverlapPortraitUsbPermissionAction() {
        val permissionUi = DashboardUiState.disconnected("Comfort").copy(
            connectionLabel = "USB permission required",
            connectionActionLabel = "Grant USB access",
        )

        showDashboard(
            uiState = permissionUi,
            widthDp = 411,
            heightDp = 900,
            showDebugControl = true,
        )

        assertNodesDoNotOverlap("MOCK OFF", "Grant USB access")
    }

    /**
     * Column one (both banners), column two (four tiles plus the odometer) and column three (four
     * warning rows plus the mode chips). Height comparisons rather than bare `assertIsDisplayed`,
     * because `assertIsDisplayed` is satisfied by a node that is only partly on screen and the way
     * this layout fails is a row losing its bottom half rather than vanishing.
     */
    private fun assertEveryColumnIsIntact() {
        // Column one: the safety banner has to survive the squeeze like everything else, and on
        // one line — the wording is fixed, so a wrap here means the column got too narrow.
        compose.onNodeWithText(SIMULATED_DATA_LABEL).assertIsDisplayed()
        val bannerHeight = compose.onNodeWithText(SIMULATED_DATA_LABEL)
            .fetchSemanticsNode().boundsInRoot.height
        val speedLabel = compose.onNodeWithText("Speed").fetchSemanticsNode().boundsInRoot.height
        assertTrue(
            "the simulation banner wrapped to more than one line: $bannerHeight",
            bannerHeight < speedLabel * 1.5f,
        )

        // Column two: four tiles in two fixed rows, and the odometer beneath them.
        listOf("RPM", "Fuel", "Gear", "Temp", "Total", "Trip A").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        val helpers = metricHelperBounds()
        assertEquals("MetricGrid must render four tiles", 4, helpers.size)
        // The two tile rows share the grid's height equally; if one row is squeezed and the other
        // is not, the grid has reflowed.
        val topRow = helpers[1].bottom - compose.onNodeWithText("RPM").fetchSemanticsNode().boundsInRoot.top
        val bottomRow = helpers[3].bottom - compose.onNodeWithText("Gear").fetchSemanticsNode().boundsInRoot.top
        assertEquals("the two metric tile rows are not the same height", topRow, bottomRow, 0.5f)
        helpers.forEach {
            assertEquals("the metric tiles are squeezed unevenly", helpers[0].height, it.height, 0.5f)
        }

        // The compact tile gives the helper exactly one line, ellipsized. Both bounds matter and
        // they fail in opposite directions:
        //  - above 1.5x the label, the helper wrapped to a second line, and the tile has no room
        //    for one — that second line is what used to be cut off silently;
        //  - below 0.8x, the single line is itself being clipped to a fragment.
        // A whole ellipsized line sits at ~1.05x. The wording is never shortened to fit; see
        // MetricTile for why a paraphrase would be a new claim rather than a shorter one.
        val tileLabel = compose.onNodeWithText("RPM").fetchSemanticsNode().boundsInRoot.height
        helpers.forEach {
            assertTrue(
                "a metric tile helper wrapped instead of ellipsizing: ${it.height} vs label $tileLabel",
                it.height < tileLabel * 1.5f,
            )
            assertTrue(
                "a metric tile helper is clipped to a fragment: ${it.height} vs label $tileLabel",
                it.height > tileLabel * 0.8f,
            )
        }

        // Column three: four warning rows, all the same height, plus both ends of the mode chips.
        listOf("Seatbelt", "Door", "Tire Pressure", "Check Engine").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        val first = compose.onNodeWithText("Seatbelt").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithText("Check Engine").fetchSemanticsNode().boundsInRoot
        assertEquals("the last warning row is clipped", first.height, last.height, 0.5f)
        compose.onNodeWithText("Eco").assertIsDisplayed()
        compose.onNodeWithText("Sport").assertIsDisplayed()
    }

    /**
     * The other half of the ruling: `maxLines = 1` belongs to the **compact** branch only.
     *
     * Portrait never sets `compact`, so its tiles must still lay the helper out in full however
     * many lines that takes. Rendered narrow on purpose — at 320 dp the portrait tiles are about
     * 136 dp wide and "Not reported by vehicle" cannot fit on one line — so a helper that is still
     * one line here would prove the ellipsis had leaked out of the compact branch and started
     * hiding words in the layout that has room for them.
     */
    @Test
    fun portrait_still_lays_the_whole_helper_out_when_the_tile_is_narrow() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                CarDashboardTheme {
                    Box(
                        Modifier
                            .size(320.dp, 900.dp)
                            .consumeWindowInsets(WindowInsets.systemBars),
                    ) {
                        DashboardScreen(
                            uiState = VehicleStateFormatter.toUiState(simulatedWithOneCode, "Comfort"),
                            onDriveModeLabelChanged = {},
                            onIssueClick = {},
                        )
                    }
                }
            }
        }

        val label = compose.onNodeWithText("RPM").fetchSemanticsNode().boundsInRoot.height
        val helpers = metricHelperBounds()
        assertEquals("MetricGrid must render four tiles", 4, helpers.size)
        helpers.forEach {
            assertTrue(
                "the non-compact tile truncated its helper instead of wrapping: ${it.height} vs label $label",
                it.height > label * 1.5f,
            )
        }
    }

    private fun metricHelperBounds() =
        compose.onAllNodesWithText(NOT_REPORTED).fetchSemanticsNodes().map { it.boundsInRoot }

    private fun assertNodesDoNotOverlap(firstText: String, secondText: String) {
        val first = compose.onNodeWithText(firstText).fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText(secondText).fetchSemanticsNode().boundsInRoot
        val overlaps = first.left < second.right && second.left < first.right &&
            first.top < second.bottom && second.top < first.bottom
        assertFalse("$firstText $first overlaps $secondText $second", overlaps)
    }

    /**
     * Two deliberate choices about the harness:
     *  - the density is pinned to 1, because a 900 dp box does not fit a 411 dp-wide portrait
     *    window and the third column would be clipped off-screen for reasons that have nothing to
     *    do with the layout. Every threshold in `DashboardScreen` is expressed in dp, so pinning
     *    density tests the same geometry the device sees while making the test independent of the
     *    device's rotation;
     *  - the system-bar insets are consumed so the box is exactly the stated size.
     */
    private fun showLandscape(heightDp: Int) {
        showDashboard(
            uiState = VehicleStateFormatter.toUiState(simulatedWithOneCode, "Comfort"),
            widthDp = 900,
            heightDp = heightDp,
        )
    }

    private fun showDashboard(
        uiState: DashboardUiState,
        widthDp: Int,
        heightDp: Int,
        showDebugControl: Boolean = false,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                CarDashboardTheme {
                    Box(
                        Modifier
                            .size(widthDp.dp, heightDp.dp)
                            .consumeWindowInsets(WindowInsets.systemBars),
                    ) {
                        DashboardScreen(
                            uiState = uiState,
                            onDriveModeLabelChanged = {},
                            onIssueClick = {},
                            debugContent = if (showDebugControl) {
                                { modifier -> DebugMockModeToggle(modifier) }
                            } else {
                                { _ -> }
                            },
                        )
                    }
                }
            }
        }
    }

    private val simulatedWithOneCode = VehicleSnapshot(
        state = VehicleState.unavailable(VehicleSourceId.MOCK),
        diagnostics = VehicleDiagnosticsState.empty().copy(
            issues = listOf(
                DiagnosticIssue(
                    id = "dtc:P0301",
                    code = DiagnosticCode.Dtc("P0301"),
                    title = null,
                    description = null,
                    classification = null,
                    severity = Severity.Warning,
                    status = DiagnosticStatus.Stored,
                    source = DiagnosticSource.Obd2,
                    firstSeenMs = 1L,
                    lastSeenMs = 1L,
                ),
            ),
        ),
        connection = VehicleConnectionState.Reading,
    )

    private val twoActiveWarningsUi = VehicleStateFormatter.toUiState(
        VehicleSnapshot(
            state = VehicleState.unavailable(VehicleSourceId.MOCK).copy(
                seatbelts = Signal.Value(
                    SeatPosition.entries.associateWith { position ->
                        if (position == SeatPosition.Driver) {
                            SeatbeltState.Unbuckled
                        } else {
                            SeatbeltState.Buckled
                        }
                    },
                    0L,
                ),
                malfunctionIndicatorLampOn = Signal.Value(true, 0L),
            ),
            diagnostics = VehicleDiagnosticsState.empty(),
            connection = VehicleConnectionState.Reading,
        ),
        "Comfort",
    )
}
