package com.csjotlab.cardashboard.ui.navigation

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.csjotlab.cardashboard.BuildConfig
import com.csjotlab.cardashboard.nav.domain.NavigationState
import com.csjotlab.cardashboard.nav.geocoding.Place
import com.csjotlab.cardashboard.nav.map.ConfigurableMapStyleProvider
import com.csjotlab.cardashboard.nav.map.MapLibreNavigationMap
import com.csjotlab.cardashboard.nav.map.MapStyle
import com.csjotlab.cardashboard.nav.map.NavigationMap
import com.csjotlab.cardashboard.ui.theme.CarDashboardTheme
import com.csjotlab.cardashboard.ui.theme.DashboardSpacing
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

private const val PlanningZoom = 15.0
private const val FollowAnimationMs = 300L
private const val CenterAnimationMs = 500L
private val RouteFitPadding = 80.dp
private val RouteFitBottomPadding = 260.dp // keeps the route above the overview panel
private val AttributionTop = 88.dp // below the search bar; status-bar inset is added at runtime

/**
 * One full-screen map; the overlays depend on [screenMode]. The screen renders state and forwards
 * gestures — every decision (mode, follow, search policy) is made in [NavigationViewModel].
 */
@Composable
fun NavigationScreen(
    uiState: NavigationUiState,
    navigationState: NavigationState,
    screenMode: ScreenMode,
    followMode: Boolean,
    origin: Place?,
    destination: Place?,
    searchQuery: String,
    search: SearchUiState,
    recentDestinations: List<Place>,
    actions: NavigationActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val darkTheme = isSystemInDarkTheme()
    val statusBarTopPx = WindowInsets.systemBars.getTop(density)
    val currentActions by rememberUpdatedState(actions)
    var navigationMap by remember { mutableStateOf<NavigationMap?>(null) }
    val mapView = remember { MapView(context) }

    DisposableEffect(Unit) {
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        mapView.getMapAsync { mapLibreMap: MapLibreMap ->
            val wrapped = MapLibreNavigationMap(
                map = mapLibreMap,
                styleProvider = ConfigurableMapStyleProvider(BuildConfig.MAP_DAY_STYLE_URL, BuildConfig.MAP_NIGHT_STYLE_URL),
                attributionTopMarginPx = statusBarTopPx + with(density) { AttributionTop.roundToPx() },
            )
            wrapped.setMapTapListener { point -> currentActions.onMapTap(point) }
            wrapped.setUserGestureListener { currentActions.onUserMovedMap() }
            navigationMap = wrapped
        }
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(navigationMap, darkTheme) {
        navigationMap?.setStyle(if (darkTheme) MapStyle.Night else MapStyle.Day)
    }

    val route = navigationState.route
    LaunchedEffect(navigationMap, route) {
        val map = navigationMap ?: return@LaunchedEffect
        if (route != null) map.showRoute(route) else map.clearRoute()
    }

    val destinationPoint = destination?.point
    LaunchedEffect(navigationMap, destinationPoint) {
        val map = navigationMap ?: return@LaunchedEffect
        if (destinationPoint != null) map.showDestination(destinationPoint) else map.clearDestination()
    }

    // Fit once per resolved route while in overview — keyed on the route object, not on fixes.
    LaunchedEffect(navigationMap, screenMode, route) {
        val map = navigationMap ?: return@LaunchedEffect
        if (screenMode == ScreenMode.Overview && route != null) {
            map.fitRoute(
                route,
                paddingPx = with(density) { RouteFitPadding.roundToPx() },
                bottomPaddingPx = with(density) { RouteFitBottomPadding.roundToPx() },
            )
        }
    }

    val location = navigationState.location.valueOrNull()
    val heading = navigationState.headingDegrees.valueOrNull()
    LaunchedEffect(navigationMap, location, heading, screenMode, followMode) {
        val map = navigationMap ?: return@LaunchedEffect
        location ?: return@LaunchedEffect
        map.showPosition(location)
        if (!followMode) return@LaunchedEffect
        when (screenMode) {
            ScreenMode.Guidance ->
                if (heading != null) map.followHeading(heading, location, FollowAnimationMs)
                else map.centerOn(location, PlanningZoom, FollowAnimationMs)
            ScreenMode.Planning -> map.centerOn(location, PlanningZoom, CenterAnimationMs)
            ScreenMode.Overview, ScreenMode.Arrived -> Unit
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val resultsMaxHeight = maxHeight * 0.4f

            // The map runs edge-to-edge under the system bars; every overlay is inset from them.
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            MapControls(
                showRecenter = !followMode && screenMode != ScreenMode.Overview,
                onZoomIn = { navigationMap?.zoomIn() },
                onZoomOut = { navigationMap?.zoomOut() },
                onRecenter = actions.onRecenter,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(DashboardSpacing.medium),
            )

            when (screenMode) {
                ScreenMode.Planning -> PlanningOverlay(
                    origin = origin,
                    destination = destination,
                    searchQuery = searchQuery,
                    search = search,
                    recentDestinations = recentDestinations,
                    resultsMaxHeight = resultsMaxHeight,
                    actions = actions,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(DashboardSpacing.screenPadding),
                )

                ScreenMode.Overview -> Box(modifier = bottomPanelModifier()) {
                    OverviewPanel(
                        uiState = uiState,
                        destinationName = destination?.name,
                        onStart = actions.onStart,
                        onCancel = actions.onEnd,
                        onAvoidTollsChanged = actions.onAvoidTollsChanged,
                    )
                }

                ScreenMode.Guidance -> Box(modifier = bottomPanelModifier()) {
                    GuidancePanel(
                        uiState = uiState,
                        maneuverType = navigationState.nextManeuver?.type,
                        onEnd = actions.onEnd,
                    )
                }

                ScreenMode.Arrived -> Box(modifier = bottomPanelModifier()) {
                    ArrivedPanel(destinationName = destination?.name, onDone = actions.onEnd)
                }
            }
        }
    }
}

@Composable
private fun BoxScope.bottomPanelModifier(): Modifier = Modifier
    .align(Alignment.BottomCenter)
    .fillMaxWidth()
    .windowInsetsPadding(WindowInsets.systemBars)
    .padding(DashboardSpacing.screenPadding)

@Composable
private fun PlanningOverlay(
    origin: Place?,
    destination: Place?,
    searchQuery: String,
    search: SearchUiState,
    recentDestinations: List<Place>,
    resultsMaxHeight: Dp,
    actions: NavigationActions,
    modifier: Modifier = Modifier,
) {
    // Which endpoint the text field edits. Destination by default — that is what drivers type.
    var choosingStart by rememberSaveable { mutableStateOf(false) }
    val activeField = if (choosingStart) PlannerField.Start else PlannerField.Destination

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(DashboardSpacing.small)) {
        PlannerCard(
            originName = origin?.name,
            destinationName = destination?.name,
            activeField = activeField,
            query = searchQuery,
            onQueryChange = actions.onSearchQueryChanged,
            onFieldSelected = { field ->
                choosingStart = field == PlannerField.Start
                actions.onSearchQueryChanged("")
            },
            onSwap = actions.onSwapEndpoints,
            onUseCurrentLocation = {
                actions.onUseCurrentLocation()
                choosingStart = false
            },
            onBack = actions.onBack,
        )
        val onPick: (Place) -> Unit = { place ->
            if (choosingStart) {
                actions.onSelectOrigin(place)
                choosingStart = false
            } else {
                actions.onSelectDestination(place)
            }
        }
        when {
            searchQuery.isNotBlank() -> SearchResultsList(search = search, maxHeight = resultsMaxHeight, onSelect = onPick)
            recentDestinations.isNotEmpty() -> RecentDestinationsList(recent = recentDestinations, maxHeight = resultsMaxHeight, onSelect = onPick)
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun NavigationScreenPreview() {
    CarDashboardTheme {
        NavigationScreen(
            uiState = NavigationUiState.idle(),
            navigationState = NavigationState.idle(),
            screenMode = ScreenMode.Planning,
            followMode = true,
            origin = null,
            destination = null,
            searchQuery = "",
            search = SearchUiState.Empty,
            recentDestinations = emptyList(),
            actions = NavigationActions.None,
        )
    }
}
