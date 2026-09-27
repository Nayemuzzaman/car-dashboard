package com.csjotlab.cardashboard.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.csjotlab.cardashboard.CarDashboardApplication
import com.csjotlab.cardashboard.debug.DebugMockModeToggle
import com.csjotlab.cardashboard.navigation.NavigationRoute
import com.csjotlab.cardashboard.ui.dashboard.DashboardScreen
import com.csjotlab.cardashboard.ui.dashboard.DashboardViewModel
import com.csjotlab.cardashboard.ui.diagnostics.DiagnosticDetailScreen
import com.csjotlab.cardashboard.ui.diagnostics.DiagnosticDetailUiState
import com.csjotlab.cardashboard.ui.diagnostics.formatDetectedTime
import com.csjotlab.cardashboard.ui.navigation.NavigationActions
import com.csjotlab.cardashboard.ui.navigation.NavigationChip
import com.csjotlab.cardashboard.ui.navigation.NavigationScreen
import com.csjotlab.cardashboard.ui.navigation.NavigationViewModel

@Composable
fun CarDashboardApp() {
    val navController = rememberNavController()
    // A ClassCastException here reads as a Compose crash with no hint of the cause. The usual cause
    // is an Application that is not ours: a dropped `android:name` in the manifest, a Compose
    // preview, or a test host that installs the default Application.
    val application = LocalContext.current.applicationContext as? CarDashboardApplication
        ?: error(
            "CarDashboardApplication is not installed — check android:name in AndroidManifest.xml"
        )
    val viewModel: DashboardViewModel = viewModel(
        factory = DashboardViewModel.Factory(application.container.repository)
    )

    NavHost(
        navController = navController,
        startDestination = DashboardRoute.route
    ) {
        composable(DashboardRoute.route) {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            DashboardScreen(
                uiState = uiState,
                onDriveModeLabelChanged = viewModel::setDriveModeLabel,
                onIssueClick = { issueId ->
                    navController.navigate(DiagnosticDetailRoute.of(issueId))
                },
                debugContent = { modifier ->
                    Row(
                        modifier = modifier,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        NavigationChip(onClick = { navController.navigate(NavigationRoute.route) })
                        DebugMockModeToggle()
                    }
                },
            )
        }
        composable(DiagnosticDetailRoute.route) { entry ->
            // Navigation has already unescaped the captured path argument with `Uri.decode`
            // (`NavDeepLink.getMatchingPathArguments`), and `DiagnosticDetailRoute.of` escapes
            // to match. Decoding again here would be a second pass over an already-decoded id
            // and would corrupt any id that legitimately contained a `%`.
            val issueId = entry.arguments?.getString(DiagnosticDetailRoute.ARG).orEmpty()
            val issues by viewModel.diagnosticIssues.collectAsStateWithLifecycle()
            val issue = issues.firstOrNull { it.id == issueId }
            DiagnosticDetailScreen(
                state = issue?.let { DiagnosticDetailUiState.from(it, ::formatDetectedTime) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(NavigationRoute.route) {
            val context = LocalContext.current
            val navigation = application.navigation
            val navigationViewModel: NavigationViewModel = viewModel(
                factory = NavigationViewModel.Factory(
                    navigation.repository,
                    navigation.recentDestinations,
                    navigation.geocodingEngine,
                    navigation.session,
                ),
            )

            fun locationGranted() = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

            var hasLocationPermission by remember { mutableStateOf(locationGranted()) }
            val locationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { grants ->
                hasLocationPermission = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || locationGranted()
                if (hasLocationPermission) navigation.restartProviders()
            }
            // The guidance notification is optional: guidance works without it, so a refusal is final.
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { }

            fun requestLocation() = locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )

            LaunchedEffect(Unit) {
                if (!hasLocationPermission) requestLocation()
            }

            // GPS and compass run while this screen is visible (and while the guidance service
            // holds them), not merely because the navigation graph exists.
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                var holding = false
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> if (!holding) { navigation.acquireSensors(); holding = true }
                        Lifecycle.Event.ON_STOP -> if (holding) { navigation.releaseSensors(); holding = false }
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    if (holding) navigation.releaseSensors()
                }
            }

            val uiState by navigationViewModel.uiState.collectAsStateWithLifecycle()
            val navState by navigationViewModel.navigationState.collectAsStateWithLifecycle()
            val screenMode by navigationViewModel.screenMode.collectAsStateWithLifecycle()
            val cameraMode by navigationViewModel.cameraMode.collectAsStateWithLifecycle()
            val headingUp by navigationViewModel.headingUp.collectAsStateWithLifecycle()
            val themeMode by navigationViewModel.themeMode.collectAsStateWithLifecycle()
            val origin by navigationViewModel.origin.collectAsStateWithLifecycle()
            val destination by navigationViewModel.destination.collectAsStateWithLifecycle()
            val searchQuery by navigationViewModel.searchQuery.collectAsStateWithLifecycle()
            val search by navigationViewModel.search.collectAsStateWithLifecycle()
            val activeCategory by navigationViewModel.activeCategory.collectAsStateWithLifecycle()
            val recent by navigationViewModel.recentDestinations.collectAsStateWithLifecycle()
            val alternatives by navigationViewModel.routeAlternatives.collectAsStateWithLifecycle()
            NavigationScreen(
                uiState = uiState,
                navigationState = navState,
                screenMode = screenMode,
                cameraMode = cameraMode,
                headingUp = headingUp,
                themeMode = themeMode,
                origin = origin,
                destination = destination,
                searchQuery = searchQuery,
                search = search,
                recentDestinations = recent,
                activeCategory = activeCategory,
                routeAlternatives = alternatives,
                hasLocationPermission = hasLocationPermission,
                actions = NavigationActions(
                    onSearchQueryChanged = navigationViewModel::onSearchQueryChanged,
                    onSelectDestination = navigationViewModel::selectDestination,
                    onSelectOrigin = navigationViewModel::selectOrigin,
                    onUseCurrentLocation = navigationViewModel::useCurrentLocationAsOrigin,
                    onMapTap = navigationViewModel::selectMapPoint,
                    onStart = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        navigationViewModel.startGuidance()
                    },
                    onEnd = navigationViewModel::endNavigation,
                    onRecenter = navigationViewModel::recenter,
                    onUserMovedMap = navigationViewModel::onUserMovedMap,
                    onSwapEndpoints = navigationViewModel::swapEndpoints,
                    onAvoidTollsChanged = navigationViewModel::setAvoidTolls,
                    onBack = { navController.popBackStack() },
                    onSearchCategory = navigationViewModel::searchCategory,
                    onSelectRoute = navigationViewModel::selectRoute,
                    onRetryRoute = navigationViewModel::retryRoute,
                    onShowOverview = navigationViewModel::showRouteOverview,
                    onToggleHeadingUp = navigationViewModel::toggleHeadingUp,
                    onThemeModeChanged = navigationViewModel::setThemeMode,
                    onRequestLocationPermission = ::requestLocation,
                ),
            )
        }
    }
}
