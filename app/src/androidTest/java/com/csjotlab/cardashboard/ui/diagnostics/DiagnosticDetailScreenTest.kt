package com.csjotlab.cardashboard.ui.diagnostics

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.csjotlab.cardashboard.ui.theme.CarDashboardTheme
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticCode
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticIssue
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticSource
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticStatus
import com.csjotlab.cardashboard.vehicle.domain.Severity
import com.csjotlab.cardashboard.vehicle.protocol.DtcDecoder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Plain method names, not backticked ones: a backticked name containing spaces cannot dex at
 * minSdk 24.
 *
 * The screen has no logic worth testing on its own — every string comes from
 * `DiagnosticDetailUiState`, which is pinned by JVM tests. What only a real composition can show is
 * that all seven labelled fields actually reach the tree (seven stacked panels do not fit a phone,
 * and a non-scrolling column would silently drop the last of them) and that the empty state offers
 * a way back.
 */
class DiagnosticDetailScreenTest {

    @get:Rule val compose = createComposeRule()

    /**
     * Built from a real [DiagnosticIssue] through the production mapping, and classified by the
     * real [DtcDecoder], so the test cannot pass on a hand-written state the app never produces.
     */
    private fun stateFor(code: String) = DiagnosticDetailUiState.from(
        DiagnosticIssue(
            id = "dtc:$code",
            code = DiagnosticCode.Dtc(code),
            title = null,
            description = null,
            classification = DtcDecoder.classify(code),
            severity = Severity.Warning,
            status = DiagnosticStatus.Stored,
            source = DiagnosticSource.Obd2,
            firstSeenMs = 1_786_442_400_000L,
            lastSeenMs = 1_786_442_400_000L,
        ),
    ) { "11 Aug 2026, 10:00" }

    @Test
    fun everyLabelledFieldReachesTheComposition() {
        compose.setContent {
            CarDashboardTheme {
                DiagnosticDetailScreen(state = stateFor("P0301"), onBack = {})
            }
        }

        listOf(
            "Problem",
            "Diagnostic code",
            "Status",
            "Severity",
            "Detected time",
            "Affected system",
            "Available description",
        ).forEach { label ->
            compose.onNodeWithText(label).assertExists("the '$label' field is missing")
        }

        compose.onNodeWithText("Stored diagnostic code").assertIsDisplayed()
        compose.onNodeWithText("Engine diagnostic code detected").assertExists()
        compose.onNodeWithText("P0301").assertExists()
        compose.onNodeWithText("Powertrain — Ignition system or misfire").assertExists()
        compose.onNodeWithText("No description available for this code").assertExists()
        compose.onNodeWithText("11 Aug 2026, 10:00").assertExists()
    }

    /**
     * The data-integrity boundary, rendered. A code the structural decoder cannot place still shows
     * its code; nothing on screen names a component or a cause.
     */
    @Test
    fun anUnclassifiableCodeRendersWithoutAnInventedDiagnosis() {
        compose.setContent {
            CarDashboardTheme {
                DiagnosticDetailScreen(state = stateFor("X9999"), onBack = {})
            }
        }

        compose.onNodeWithText("X9999").assertExists()
        compose.onNodeWithText("Unknown").assertExists()
        compose.onNodeWithText("No description available for this code").assertExists()
        // No structural classification could be derived, so no system may be claimed.
        compose.onAllNodesWithText("Powertrain — Ignition system or misfire").assertCountEquals(0)
    }

    @Test
    fun anIssueThatIsNoLongerReportedSaysSoAndStillOffersAWayBack() {
        var backs = 0
        compose.setContent {
            CarDashboardTheme {
                DiagnosticDetailScreen(state = null, onBack = { backs++ })
            }
        }

        compose.onNodeWithText(ISSUE_GONE).assertIsDisplayed()
        // Nothing may be asserted about the vehicle from a missing issue.
        compose.onAllNodesWithText("No description available for this code").assertCountEquals(0)

        compose.onNodeWithText("Back").performClick()
        assertEquals(1, backs)
    }
}
