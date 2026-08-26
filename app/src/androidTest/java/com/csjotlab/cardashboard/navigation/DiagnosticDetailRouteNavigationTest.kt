package com.csjotlab.cardashboard.navigation

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Rule
import org.junit.Test

/**
 * Pins the one thing `DiagnosticDetailRouteTest` cannot check on the JVM: that what
 * [DiagnosticDetailRoute.of] escapes is exactly what Navigation unescapes.
 *
 * `NavDeepLink` unescapes a captured path argument with `Uri.decode`, so the destination must
 * **not** decode a second time — and `Uri.decode` leaves `+` alone, so the escaping must not be
 * plain `URLEncoder` output either. Getting either half wrong yields a lookup miss and a permanent
 * "Issue no longer reported", which no JVM test would catch.
 *
 * Method names are plain rather than backticked: a backticked name containing spaces cannot dex at
 * minSdk 24.
 */
class DiagnosticDetailRouteNavigationTest {

    @get:Rule val compose = createComposeRule()

    private fun showNavigatingTo(issueId: String) {
        compose.setContent {
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = "start") {
                composable("start") {
                    Text(
                        text = "open",
                        modifier = Modifier.clickable {
                            navController.navigate(DiagnosticDetailRoute.of(issueId))
                        },
                    )
                }
                composable(DiagnosticDetailRoute.route) { entry ->
                    // Exactly what CarDashboardApp does: read the argument, and do not decode it.
                    Text(text = entry.arguments?.getString(DiagnosticDetailRoute.ARG).orEmpty())
                }
            }
        }
        compose.onNodeWithText("open").performClick()
    }

    @Test
    fun anIssueIdSurvivesTheRouteUnchanged() {
        showNavigatingTo("dtc:P0301")
        compose.onNodeWithText("dtc:P0301").assertIsDisplayed()
    }

    @Test
    fun anIdContainingASeparatorStillReachesTheDestination() {
        showNavigatingTo("signal:Door/RearLeft")
        compose.onNodeWithText("signal:Door/RearLeft").assertIsDisplayed()
    }

    @Test
    fun anIdContainingASpaceComesBackAsASpaceNotAPlus() {
        showNavigatingTo("signal:Rear left")
        compose.onNodeWithText("signal:Rear left").assertIsDisplayed()
    }

    @Test
    fun aLiteralPlusSurvivesTheRouteUnchanged() {
        showNavigatingTo("signal:A+B")
        compose.onNodeWithText("signal:A+B").assertIsDisplayed()
    }

    @Test
    fun literalPercentAndPercentLookingTextSurviveTheRouteUnchanged() {
        showNavigatingTo("signal:100%/%20/%2F")
        compose.onNodeWithText("signal:100%/%20/%2F").assertIsDisplayed()
    }

    @Test
    fun queryAndFragmentSeparatorsSurviveTheRouteUnchanged() {
        showNavigatingTo("signal:?mode=eco#stored")
        compose.onNodeWithText("signal:?mode=eco#stored").assertIsDisplayed()
    }

    @Test
    fun pathAndValueSeparatorsSurviveTheRouteUnchanged() {
        showNavigatingTo("signal:front/rear|left;right")
        compose.onNodeWithText("signal:front/rear|left;right").assertIsDisplayed()
    }

    @Test
    fun nonAsciiTextSurvivesTheRouteUnchanged() {
        showNavigatingTo("診断:後部ドア/開")
        compose.onNodeWithText("診断:後部ドア/開").assertIsDisplayed()
    }
}
