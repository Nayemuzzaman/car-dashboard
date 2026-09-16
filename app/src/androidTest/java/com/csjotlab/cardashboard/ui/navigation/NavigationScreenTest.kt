package com.csjotlab.cardashboard.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.NavigationState
import com.csjotlab.cardashboard.nav.geocoding.Place
import com.csjotlab.cardashboard.ui.theme.CarDashboardTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val airport = Place("Airport", GeoPoint(23.84, 90.40), address = "Dhaka, Bangladesh", category = "aerodrome")

    private fun show(
        uiState: NavigationUiState = NavigationUiState.idle(),
        screenMode: ScreenMode = ScreenMode.Planning,
        followMode: Boolean = true,
        origin: Place? = null,
        destination: Place? = null,
        searchQuery: String = "",
        search: SearchUiState = SearchUiState.Empty,
        recent: List<Place> = emptyList(),
        actions: NavigationActions = NavigationActions.None,
    ) {
        rule.setContent {
            CarDashboardTheme {
                NavigationScreen(
                    uiState = uiState,
                    navigationState = NavigationState.idle(),
                    screenMode = screenMode,
                    followMode = followMode,
                    origin = origin,
                    destination = destination,
                    searchQuery = searchQuery,
                    search = search,
                    recentDestinations = recent,
                    actions = actions,
                )
            }
        }
    }

    @Test
    fun planningShowsTwoEndpointFieldsAndRecents() {
        show(recent = listOf(airport))

        rule.onNodeWithText("Your location").assertIsDisplayed()
        rule.onNodeWithText("Search destination").assertIsDisplayed()
        rule.onNodeWithContentDescription("Swap start and destination").assertIsDisplayed()
        rule.onNodeWithText("Recent").assertIsDisplayed()
        rule.onNodeWithText("Airport").assertIsDisplayed()
    }

    @Test
    fun tappingStartMakesItTheSearchFieldAndPicksAnOrigin() {
        var origin: Place? = null
        show(
            searchQuery = "sta",
            search = SearchUiState(listOf(SearchResultUi(Place("Station", GeoPoint(1.0, 1.0)), null)), null),
            actions = NavigationActions(onSelectOrigin = { origin = it }),
        )

        rule.onNodeWithText("Your location").performClick()
        // The destination row is now the inactive label and the start row holds the query.
        rule.onNodeWithText("Choose destination").assertIsDisplayed()
        rule.onNodeWithText("sta").assertIsDisplayed()
        rule.onNodeWithText("Station").performClick()

        assertTrue(origin?.name == "Station")
    }

    @Test
    fun swapButtonRaisesTheAction() {
        var swapped = false
        show(actions = NavigationActions(onSwapEndpoints = { swapped = true }))

        rule.onNodeWithContentDescription("Swap start and destination").performClick()

        assertTrue(swapped)
    }

    @Test
    fun searchResultsShowNameAddressAndDistanceAndSelect() {
        var selected: Place? = null
        show(
            searchQuery = "air",
            search = SearchUiState(listOf(SearchResultUi(airport, "2.3 km")), null),
            actions = NavigationActions(onSelectDestination = { selected = it }),
        )

        rule.onNodeWithText("Airport").assertIsDisplayed()
        rule.onNodeWithText("aerodrome · Dhaka, Bangladesh").assertIsDisplayed()
        rule.onNodeWithText("2.3 km").assertIsDisplayed()
        rule.onNodeWithText("Airport").performClick()
        assertTrue(selected == airport)
    }

    @Test
    fun searchFailureIsVisible() {
        show(searchQuery = "air", search = SearchUiState(emptyList(), SEARCH_UNAVAILABLE))

        rule.onNodeWithText(SEARCH_UNAVAILABLE).assertIsDisplayed()
    }

    @Test
    fun overviewShowsRouteSummaryAndStart() {
        val ui = NavigationUiState.idle().copy(
            statusLabel = ROUTE_READY,
            remainingDistanceText = "12 km",
            remainingTimeText = "17 min",
            etaText = "14:32",
            hasRoute = true,
        )
        var started = false
        show(uiState = ui, screenMode = ScreenMode.Overview, destination = airport, actions = NavigationActions(onStart = { started = true }))

        rule.onNodeWithText("Airport").assertIsDisplayed()
        rule.onNodeWithText("12 km").assertIsDisplayed()
        rule.onNodeWithText("Start").performClick()
        assertTrue(started)
    }

    @Test
    fun overviewShowsTollBadgeViaAndAvoidTollsToggle() {
        val ui = NavigationUiState.idle().copy(
            statusLabel = ROUTE_READY, remainingDistanceText = "74 km", remainingTimeText = "1 h 8 min", etaText = "16:10",
            hasRoute = true, tollLabel = TOLL_ROAD, viaText = "via Kyushu Expressway",
        )
        var avoid: Boolean? = null
        show(uiState = ui, screenMode = ScreenMode.Overview, destination = airport, actions = NavigationActions(onAvoidTollsChanged = { avoid = it }))

        rule.onNodeWithText(TOLL_ROAD).assertIsDisplayed()
        rule.onNodeWithText("via Kyushu Expressway").assertIsDisplayed()
        rule.onNodeWithContentDescription(AVOID_TOLLS).performClick()
        assertTrue(avoid == true)
    }

    @Test
    fun overviewSaysWhenTollInfoIsUnavailableOrUnavoidable() {
        val unknown = NavigationUiState.idle().copy(statusLabel = ROUTE_READY, hasRoute = true, tollLabel = TOLL_UNKNOWN)
        show(uiState = unknown, screenMode = ScreenMode.Overview, destination = airport)
        rule.onNodeWithText(TOLL_UNKNOWN).assertIsDisplayed()
    }

    @Test
    fun guidanceFlagsATolledNextStep() {
        val ui = NavigationUiState.idle().copy(
            statusLabel = NAVIGATING, maneuverText = "Keep left in 500 m", hasRoute = true,
            tollLabel = TOLL_ROAD, nextStepToll = true,
        )
        show(uiState = ui, screenMode = ScreenMode.Guidance, destination = airport)

        rule.onNodeWithText(NEXT_STEP_TOLL).assertIsDisplayed()
        rule.onNodeWithText(TOLL_ROAD).assertIsDisplayed()
    }

    @Test
    fun overviewWithoutRouteShowsStatusAndDisablesStart() {
        val ui = NavigationUiState.idle().copy(statusLabel = FINDING_ROUTE)
        show(uiState = ui, screenMode = ScreenMode.Overview, destination = airport)

        rule.onNodeWithText(FINDING_ROUTE).assertIsDisplayed()
        rule.onNodeWithText("Start").assertIsNotEnabled()
    }

    @Test
    fun guidanceShowsManeuverAndRecenterWhenNotFollowing() {
        val ui = NavigationUiState.idle().copy(
            statusLabel = NAVIGATING,
            maneuverText = "Turn left in 300 m",
            remainingDistanceText = "300 m",
            speedText = "36 km/h",
            hasRoute = true,
        )
        show(uiState = ui, screenMode = ScreenMode.Guidance, followMode = false, destination = airport)

        rule.onNodeWithText("Turn left in 300 m").assertIsDisplayed()
        rule.onNodeWithText(NAVIGATING).assertIsDisplayed()
        rule.onNodeWithText("End").assertIsDisplayed()
        rule.onNodeWithContentDescription("Recenter").assertIsDisplayed()
    }

    @Test
    fun straightLinePreviewIsLabelledInGuidance() {
        val ui = NavigationUiState.idle().copy(statusLabel = NAVIGATING, previewLabel = STRAIGHT_LINE_PREVIEW, hasRoute = true)
        show(uiState = ui, screenMode = ScreenMode.Guidance, destination = airport)

        rule.onNodeWithText(STRAIGHT_LINE_PREVIEW).assertIsDisplayed()
    }

    @Test
    fun arrivedShowsDone() {
        show(screenMode = ScreenMode.Arrived, destination = airport)

        rule.onNodeWithText(ARRIVED).assertIsDisplayed()
        rule.onNodeWithText("Done").assertIsDisplayed()
    }
}
