package com.csjotlab.cardashboard.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.NavigationState
import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.geocoding.Place
import com.csjotlab.cardashboard.nav.map.MapThemeMode
import com.csjotlab.cardashboard.ui.theme.CarDashboardTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val airport = Place("Airport", GeoPoint(23.84, 90.40), address = "Dhaka, Bangladesh", category = "aerodrome")

    private fun show(
        uiState: NavigationUiState = NavigationUiState.idle(),
        screenMode: ScreenMode = ScreenMode.Planning,
        cameraMode: CameraMode = CameraMode.Follow,
        navigationState: NavigationState = NavigationState.idle(),
        themeMode: MapThemeMode = MapThemeMode.Day,
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
                    navigationState = navigationState,
                    screenMode = screenMode,
                    cameraMode = cameraMode,
                    headingUp = true,
                    themeMode = themeMode,
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
        rule.onNodeWithText("Where to?").assertIsDisplayed()
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
    }

    @Test
    fun overviewWithoutRouteShowsStatusAndDisablesStart() {
        val ui = NavigationUiState.idle().copy(statusLabel = FINDING_ROUTE)
        show(uiState = ui, screenMode = ScreenMode.Overview, destination = airport)

        rule.onNodeWithText(FINDING_ROUTE).assertIsDisplayed()
        rule.onNodeWithText("Start").assertIsNotEnabled()
    }

    private val guiding = NavigationUiState.idle().copy(
        statusLabel = NAVIGATING,
        maneuverText = "Turn right in 300 m",
        maneuverDistanceText = "300 m",
        instructionText = "Turn right onto Route 3",
        remainingDistanceText = "4.2 km",
        remainingTimeText = "9 min",
        etaText = "14:32",
        speedText = "36 km/h",
        hasRoute = true,
    )
    private val turningRight = NavigationState.idle().copy(nextManeuver = Maneuver(ManeuverType.TurnRight, null))

    @Test
    fun guidanceBannerShowsDistanceAndRoadNamedInstruction() {
        show(uiState = guiding, navigationState = turningRight, screenMode = ScreenMode.Guidance, destination = airport)

        rule.onNodeWithText("300 m").assertIsDisplayed()
        rule.onNodeWithText("Turn right onto Route 3").assertIsDisplayed()
        rule.onNodeWithContentDescription("Turn right").assertIsDisplayed()
        rule.onNodeWithText("14:32").assertIsDisplayed()
        rule.onNodeWithText("9 min · 4.2 km").assertIsDisplayed()
    }

    @Test
    fun guidanceShowsRecenterWhenNotFollowing() {
        show(uiState = guiding, screenMode = ScreenMode.Guidance, cameraMode = CameraMode.Free, destination = airport)

        rule.onNodeWithContentDescription(RECENTER).assertIsDisplayed()
    }

    @Test
    fun endIsVisibleInGuidanceAndEndsTheTrip() {
        var ended = false
        show(uiState = guiding, screenMode = ScreenMode.Guidance, destination = airport, actions = NavigationActions(onEnd = { ended = true }))

        rule.onNodeWithText(END).assertIsDisplayed().performClick()

        assertTrue(ended)
    }

    @Test
    fun endIsVisibleInNightModeToo() {
        show(uiState = guiding, screenMode = ScreenMode.Guidance, destination = airport, themeMode = MapThemeMode.Night)
        rule.onNodeWithText(END).assertIsDisplayed()
    }

    @Test
    fun backDuringGuidanceAsksBeforeEnding() {
        var ended = false
        show(uiState = guiding, screenMode = ScreenMode.Guidance, destination = airport, actions = NavigationActions(onEnd = { ended = true }))

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("End navigation?").assertIsDisplayed()
        assertTrue("nothing ends until the driver confirms", !ended)

        rule.onNodeWithText("Keep navigating").performClick()
        assertTrue(!ended)

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onAllNodesWithText(END).onLast().performClick()
        assertTrue(ended)
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
