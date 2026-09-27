package com.csjotlab.cardashboard.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.csjotlab.cardashboard.BuildConfig
import com.csjotlab.cardashboard.nav.domain.GpsQuality
import com.csjotlab.cardashboard.nav.domain.NavigationState
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.engine.NavigationCameraPolicy
import com.csjotlab.cardashboard.nav.engine.RouteIndex
import com.csjotlab.cardashboard.nav.geocoding.Place
import com.csjotlab.cardashboard.nav.geocoding.PlaceCategory
import com.csjotlab.cardashboard.nav.map.ConfigurableMapStyleProvider
import com.csjotlab.cardashboard.nav.map.MapInsets
import com.csjotlab.cardashboard.nav.map.MapLibreNavigationMap
import com.csjotlab.cardashboard.nav.map.MapStyle
import com.csjotlab.cardashboard.nav.map.MapThemeMode
import com.csjotlab.cardashboard.nav.map.VehicleMarker
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull
import org.maplibre.android.maps.MapView
import kotlin.math.abs
import kotlin.math.roundToInt

private const val PlanningZoom = 15.5
private const val CenterAnimationMs = 600L
/** In guidance the car sits low in the free map area, so most of the view is road ahead. */
private const val GuidanceVehicleFraction = 0.74
private val WidePanelWidth = 420.dp
private val ControlsColumnWidth = 80.dp

/**
 * One full-screen map; the overlays depend on [screenMode]. The screen renders state and forwards
 * gestures — every decision (mode, camera, search policy) is made in [NavigationViewModel], and every
 * number the camera uses comes from `nav.engine`.
 */
@Composable
fun NavigationScreen(
    uiState: NavigationUiState,
    navigationState: NavigationState,
    screenMode: ScreenMode,
    cameraMode: CameraMode,
    headingUp: Boolean,
    themeMode: MapThemeMode,
    origin: Place?,
    destination: Place?,
    searchQuery: String,
    search: SearchUiState,
    recentDestinations: List<Place>,
    actions: NavigationActions,
    modifier: Modifier = Modifier,
    activeCategory: PlaceCategory? = null,
    routeAlternatives: List<Route> = emptyList(),
    hasLocationPermission: Boolean = true,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val systemDark = isSystemInDarkTheme()
    val currentActions by rememberUpdatedState(actions)
    val currentScreenMode by rememberUpdatedState(screenMode)
    var navigationMap by remember { mutableStateOf<MapLibreNavigationMap?>(null) }
    val mapView = remember { MapView(context) }
    val mapBearing = remember { mutableFloatStateOf(0f) }
    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    var topOverlayPx by remember { mutableIntStateOf(0) }
    var bottomOverlayPx by remember { mutableIntStateOf(0) }

    // The MapView follows the host lifecycle (paused in the background, not only when disposed).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var created = false
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> if (!created) { mapView.onCreate(null); created = true }
                Lifecycle.Event.ON_START -> if (!started) { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { mapView.onStop(); started = false }
                else -> Unit
            }
        }
        // Adding the observer replays the events up to the current state (create/start/resume).
        lifecycle.addObserver(observer)
        if (!created) { mapView.onCreate(null); created = true }
        mapView.getMapAsync { mapLibreMap ->
            val wrapped = MapLibreNavigationMap(
                map = mapLibreMap,
                styleProvider = ConfigurableMapStyleProvider(BuildConfig.MAP_DAY_STYLE_URL, BuildConfig.MAP_NIGHT_STYLE_URL),
                density = density.density,
                touchView = mapView,
            )
            // A stray tap while driving must never replace the destination: taps only pick one while planning.
            wrapped.setMapTapListener { point -> if (currentScreenMode == ScreenMode.Planning) currentActions.onMapTap(point) }
            wrapped.setUserGestureListener { currentActions.onUserMovedMap() }
            wrapped.setBearingListener { mapBearing.floatValue = it }
            navigationMap = wrapped
        }
        onDispose {
            lifecycle.removeObserver(observer)
            navigationMap?.release()
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Keep the display on while guiding: a sleeping screen mid-junction is a safety problem.
    val view = LocalView.current
    DisposableEffect(screenMode == ScreenMode.Guidance) {
        view.keepScreenOn = screenMode == ScreenMode.Guidance
        onDispose { view.keepScreenOn = false }
    }

    BackHandler(enabled = screenMode != ScreenMode.Planning) {
        when (screenMode) {
            ScreenMode.Guidance -> confirmEnd = true
            else -> actions.onEnd()
        }
    }

    val position = navigationState.displayLocation
    val fix = navigationState.location.valueOrNull() ?: position
    val mapStyle = resolveMapStyle(themeMode, systemDark, fix, System.currentTimeMillis())
    val colors = if (mapStyle == MapStyle.Night) NightNavColors else DayNavColors

    LaunchedEffect(navigationMap, mapStyle) { navigationMap?.setStyle(mapStyle) }

    val route = navigationState.route
    val showAlternatives = screenMode == ScreenMode.Overview
    LaunchedEffect(navigationMap, route, routeAlternatives, showAlternatives) {
        navigationMap?.showRoute(route, if (showAlternatives) routeAlternatives else emptyList())
    }

    val guiding = screenMode == ScreenMode.Guidance || screenMode == ScreenMode.Arrived
    val along = navigationState.distanceAlongRouteMeters.takeIf { guiding }
    LaunchedEffect(navigationMap, route, along) { navigationMap?.setRouteProgress(along) }

    val heading = navigationState.headingDegrees.valueOrNull()
    val degraded = navigationState.gpsQuality == GpsQuality.Degraded || navigationState.gpsQuality == GpsQuality.Lost
    LaunchedEffect(navigationMap, position, heading, degraded) {
        navigationMap?.updateVehicle(position?.let { VehicleMarker(it, heading, degraded) })
    }

    val destinationPoint = destination?.point
    LaunchedEffect(navigationMap, destinationPoint) { navigationMap?.showDestination(destinationPoint) }

    val resultPoints = if (screenMode == ScreenMode.Planning) search.results.map { it.place.point } else emptyList()
    LaunchedEffect(navigationMap, resultPoints) { navigationMap?.showSearchResults(resultPoints) }

    NavThemeRoot(colors = colors, modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(colors.panelVariant)) {
            val wide = maxWidth >= 600.dp && maxWidth > maxHeight
            val panelWidthPx = with(density) { (WidePanelWidth + 24.dp).roundToPx() }
            val panelModifier = if (wide) Modifier.width(WidePanelWidth + 24.dp) else Modifier.fillMaxWidth()
            val resultsMaxHeight = maxHeight * 0.4f

            // Everything the camera does, in one place, keyed on what it depends on — not on each fix.
            val speed = navigationState.speedMps.valueOrNull()
            val followZoom = NavigationCameraPolicy.followZoom(speed, navigationState.distanceToManeuverMeters)
            val zoomKey = (followZoom * 4).roundToInt()
            val insets = if (wide) MapInsets(left = panelWidthPx) else MapInsets(top = topOverlayPx, bottom = bottomOverlayPx)
            // Fitted views (route, search results) also keep clear of the round controls on the
            // right; following does not, so the car stays centred.
            val fitInsets = insets.copy(right = with(density) { ControlsColumnWidth.roundToPx() })
            val hasPosition = position != null
            val categoryFitKey = if (activeCategory != null) resultPoints else emptyList()
            // Only a following camera cares about the speed-based zoom; a fitted overview must not
            // re-animate every time the speed changes.
            val followZoomKey = if (cameraMode == CameraMode.Follow) zoomKey else 0
            val bottomInsetPx = WindowInsets.systemBars.getBottom(density)
            LaunchedEffect(navigationMap, bottomOverlayPx, wide) {
                navigationMap?.setOverlayInsets(
                    if (wide) MapInsets(left = panelWidthPx, bottom = bottomInsetPx) else MapInsets(bottom = maxOf(bottomOverlayPx, bottomInsetPx)),
                )
            }
            LaunchedEffect(navigationMap, screenMode, cameraMode, headingUp, followZoomKey, insets, route, routeAlternatives, destinationPoint, hasPosition, categoryFitKey) {
                val map = navigationMap ?: return@LaunchedEffect
                when (cameraMode) {
                    CameraMode.Free -> map.stopFollowing()
                    CameraMode.Follow -> when (screenMode) {
                        ScreenMode.Guidance, ScreenMode.Arrived ->
                            map.follow(headingUp, followZoomKey / 4.0, insets, GuidanceVehicleFraction)
                        ScreenMode.Planning, ScreenMode.Overview ->
                            if (categoryFitKey.isNotEmpty() && screenMode == ScreenMode.Planning) map.fitPoints(categoryFitKey + listOfNotNull(position), fitInsets)
                            else map.follow(headingUp = false, zoom = PlanningZoom, insets = insets)
                    }
                    CameraMode.Overview -> when {
                        route != null && screenMode == ScreenMode.Guidance -> {
                            val remaining = RouteIndex(route).split((navigationState.distanceAlongRouteMeters ?: 0f).toDouble()).second
                            map.fitPoints(remaining + listOfNotNull(position), fitInsets)
                        }
                        route != null -> map.fitPoints(route.geometry + routeAlternatives.flatMap { it.geometry }, fitInsets)
                        destinationPoint != null -> map.centerOn(destinationPoint, PlanningZoom, CenterAnimationMs)
                        position != null -> map.follow(headingUp = false, zoom = PlanningZoom, insets = insets)
                    }
                }
            }

            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            // ---------------------------------------------------------------- top overlay
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .then(panelModifier)
                    .onSizeChanged { topOverlayPx = it.height }
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (screenMode) {
                    ScreenMode.Planning -> PlanningOverlay(
                        origin = origin,
                        destination = destination,
                        searchQuery = searchQuery,
                        search = search,
                        activeCategory = activeCategory,
                        recentDestinations = recentDestinations,
                        resultsMaxHeight = resultsMaxHeight,
                        hasLocationPermission = hasLocationPermission,
                        actions = actions,
                    )
                    ScreenMode.Guidance -> {
                        ManeuverBanner(uiState = uiState, maneuverType = navigationState.nextManeuver?.type, thenType = navigationState.thenManeuver?.type)
                        GuidanceStatusRow(uiState = uiState)
                    }
                    ScreenMode.Overview, ScreenMode.Arrived -> Unit
                }
            }

            // ---------------------------------------------------------------- bottom overlay
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .then(panelModifier)
                    .onSizeChanged { bottomOverlayPx = it.height }
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (screenMode) {
                    ScreenMode.Planning -> Unit
                    ScreenMode.Overview -> OverviewPanel(
                        uiState = uiState,
                        destinationName = destination?.name,
                        destinationAddress = destination?.address,
                        onStart = actions.onStart,
                        onCancel = actions.onEnd,
                        onAvoidTollsChanged = actions.onAvoidTollsChanged,
                        onSelectRoute = actions.onSelectRoute,
                        onRetry = actions.onRetryRoute,
                    )
                    ScreenMode.Guidance -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SpeedBubble(uiState.speedText)
                            Spacer(Modifier.weight(1f))
                            uiState.currentRoadText?.let { RoadNamePill(it) }
                            Spacer(Modifier.weight(1f))
                        }
                        TripBar(
                            uiState = uiState,
                            onEnd = actions.onEnd,
                            onOverview = actions.onShowOverview,
                            showOverview = cameraMode != CameraMode.Overview,
                        )
                    }
                    ScreenMode.Arrived -> ArrivedPanel(destinationName = destination?.name, onDone = actions.onEnd)
                }
            }

            // ---------------------------------------------------------------- right edge
            val bearingOff by remember { derivedStateOf { abs(mapBearing.floatValue) > 1f && abs(mapBearing.floatValue) < 359f } }
            val showCompass = screenMode == ScreenMode.Guidance || bearingOff
            val showRecenter = when (screenMode) {
                ScreenMode.Guidance, ScreenMode.Arrived -> cameraMode != CameraMode.Follow
                ScreenMode.Planning, ScreenMode.Overview -> hasPosition && cameraMode != CameraMode.Follow
            }
            MapControls(
                showRecenter = showRecenter,
                onZoomIn = { navigationMap?.zoomIn() },
                onZoomOut = { navigationMap?.zoomOut() },
                onRecenter = actions.onRecenter,
                compass = if (showCompass) {
                    {
                        CompassControl(
                            bearing = { mapBearing.floatValue },
                            headingUp = if (screenMode == ScreenMode.Guidance) headingUp else null,
                            onClick = { if (screenMode == ScreenMode.Guidance) actions.onToggleHeadingUp() else navigationMap?.resetNorth() },
                        )
                    }
                } else null,
                themeMode = if (screenMode == ScreenMode.Planning || screenMode == ScreenMode.Overview) themeMode else null,
                onThemeModeChanged = actions.onThemeModeChanged,
                // Stacked just above the bottom overlay, so they never cover a panel.
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = with(density) { (if (wide) 0 else bottomOverlayPx).toDp() })
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(12.dp),
            )

            if (confirmEnd) {
                EndNavigationDialog(
                    onConfirm = { confirmEnd = false; actions.onEnd() },
                    onDismiss = { confirmEnd = false },
                )
            }
        }
    }
}

/** Provides the navigation palette to every overlay below it. */
@Composable
private fun NavThemeRoot(colors: NavColors, modifier: Modifier, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNavColors provides colors) {
        Box(modifier = modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun PlanningOverlay(
    origin: Place?,
    destination: Place?,
    searchQuery: String,
    search: SearchUiState,
    activeCategory: PlaceCategory?,
    recentDestinations: List<Place>,
    resultsMaxHeight: androidx.compose.ui.unit.Dp,
    hasLocationPermission: Boolean,
    actions: NavigationActions,
) {
    // Which endpoint the text field edits. Destination by default — that is what drivers type.
    var choosingStart by rememberSaveable { mutableStateOf(false) }
    val activeField = if (choosingStart) PlannerField.Start else PlannerField.Destination

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!hasLocationPermission) LocationPermissionBanner(onAllow = actions.onRequestLocationPermission)
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
        if (!choosingStart) CategoryChips(active = activeCategory, onSelect = actions.onSearchCategory)
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
    NavigationScreen(
        uiState = NavigationUiState.idle(),
        navigationState = NavigationState.idle(),
        screenMode = ScreenMode.Planning,
        cameraMode = CameraMode.Follow,
        headingUp = true,
        themeMode = MapThemeMode.Day,
        origin = null,
        destination = null,
        searchQuery = "",
        search = SearchUiState.Empty,
        recentDestinations = emptyList(),
        actions = NavigationActions.None,
    )
}
