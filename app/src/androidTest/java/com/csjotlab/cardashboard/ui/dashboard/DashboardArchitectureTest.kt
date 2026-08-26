package com.csjotlab.cardashboard.ui.dashboard

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.csjotlab.cardashboard.MainActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Device-level Task 13 milestone: the production Activity and graph, not injected Compose state.
 * The only way mock mode is enabled here is the real debug-source-set affordance.
 */
class DashboardArchitectureTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /**
     * Restores to PORTRAIT — the handset's natural orientation — not UNSPECIFIED.
     *
     * UNSPECIFIED hands control back to the sensor, so a rotation can still be settling while
     * `ActivityScenarioRule` finishes this activity and the *next* test class launches its own
     * Compose host. Pinning portrait and draining leaves nothing in flight at teardown.
     */
    @After
    fun restoreOrientation() {
        setOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
    }

    @Test
    fun debugMockWalksTheCompleteArchitectureTimelineAndClearsOnLoss() {
        setOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        waitForText("MOCK OFF")
        waitForText("Vehicle not connected")
        compose.onNodeWithText(SIMULATED_DATA_LABEL).assertDoesNotExist()
        assertTrue(
            "the production disconnected state must expose at least seven unavailable readings",
            compose.onAllNodesWithText(UNAVAILABLE).fetchSemanticsNodes().size >= 7,
        )

        compose.onNodeWithText("MOCK OFF").performClick()
        waitForText("MOCK ON")
        waitForText(SIMULATED_DATA_LABEL)

        // These states are emitted by MockVehicleDataSource through Repository and ViewModel.
        waitForText("Connecting", timeoutMs = 3_000)
        waitForText("Connected", timeoutMs = 3_000)
        waitForText("Reading data", timeoutMs = 3_000)

        waitForText("60", timeoutMs = 3_000)
        listOf("2,100", "62%", "91 C", "D").forEach { waitForText(it) }

        // Mode changes cross DashboardScreen -> ViewModel -> formatter: both the selected header
        // and the Gear helper must follow, not just the chip that was tapped.
        listOf("Eco", "Sport", "Comfort").forEach { mode ->
            compose.onNodeWithText(mode).performScrollTo().performClick()
            waitForTextCount(mode, 2)
            waitForText("$mode shift")
        }

        waitForText("Rear left open", timeoutMs = 3_000)
        waitForText("Front right low", timeoutMs = 3_000)
        waitForText("3 of 5 not reported", timeoutMs = 3_000)
        waitForText("Secured", timeoutMs = 3_000)
        waitForText("Code P0301", timeoutMs = 3_000)

        // Open the real NavHost destination from the live warning row. When the script disconnects
        // two seconds later, the repository clears diagnostics and the reactive detail route must
        // stop presenting the resolved issue as current.
        compose.onNodeWithText("Code P0301").performScrollTo().performClick()
        waitForText("P0301")
        waitForText("Stored")
        waitForText("No description available for this code")
        waitForText("Issue no longer reported", timeoutMs = 4_000)
        compose.onNodeWithText("Back").performClick()

        waitForText("Connection lost")
        listOf("60", "2,100", "62%", "91 C", "D", "Code P0301").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithText(SIMULATED_DATA_LABEL).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sportSelectionSurvivesPortraitLandscapePortraitRecreation() {
        // Pinned, not assumed. `performScrollTo` needs a scroll parent, and only the portrait
        // layout has one (landscape has no verticalScroll), so starting in whatever orientation
        // the handset happened to be left in would make this test order-dependent.
        setOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

        compose.onNodeWithText("Sport").performScrollTo().performClick()
        waitForTextCount("Sport", 2)

        setOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        waitForTextCount("Sport", 2, timeoutMs = 6_000)
        listOf("RPM", "Fuel", "Gear", "Temp", "Seatbelt", "Door", "Tire Pressure", "Check Engine").forEach {
            waitForText(it)
        }

        setOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        waitForTextCount("Sport", 2, timeoutMs = 6_000)
        compose.onNodeWithText("Speed").assertIsDisplayed()
    }

    /**
     * Requests an orientation the way the framework expects it: on the activity's own thread, then
     * drained.
     *
     * Writing `requestedOrientation` from the instrumentation thread and returning immediately
     * leaves the configuration change in flight. The next semantics query — in this class or the
     * one that runs after it — can then land in the window where the outgoing Compose root has been
     * unregistered and the incoming one is not yet attached.
     */
    private fun setOrientation(orientation: Int) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
        compose.waitForIdle()
    }

    /**
     * Polls with `atLeastOneRootRequired = false`.
     *
     * The default is `true`, which makes `fetchSemanticsNodes()` *throw*
     * `IllegalStateException: No compose hierarchies found in the app` rather than return empty
     * when no root is attached. Inside a `waitUntil` predicate that turns the perfectly normal
     * gap during an Activity recreation into a hard failure instead of another poll iteration.
     */
    private fun waitForText(text: String, timeoutMs: Long = 2_000) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithText(text)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
    }

    private fun waitForTextCount(text: String, count: Int, timeoutMs: Long = 2_000) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithText(text)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .size == count
        }
        compose.onAllNodesWithText(text).assertCountEquals(count)
    }
}
