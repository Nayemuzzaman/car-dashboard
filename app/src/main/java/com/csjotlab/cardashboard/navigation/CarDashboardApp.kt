package com.csjotlab.cardashboard.navigation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
            val navigationViewModel: NavigationViewModel = viewModel(
                factory = NavigationViewModel.Factory(
                    application.navigation.repository,
                    application.navigation.recentDestinations,
                    application.navigation.geocodingEngine,
                ),
            )

            val locationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) {
                    (context.applicationContext as? CarDashboardApplication)
                        ?.navigation
                        ?.restartProviders()
                }
            }

            LaunchedEffect(Unit) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }

            val uiState by navigationViewModel.uiState.collectAsStateWithLifecycle()
            val navState by navigationViewModel.navigationState.collectAsStateWithLifecycle()
            val screenMode by navigationViewModel.screenMode.collectAsStateWithLifecycle()
            val followMode by navigationViewModel.followMode.collectAsStateWithLifecycle()
            val origin by navigationViewModel.origin.collectAsStateWithLifecycle()
            val destination by navigationViewModel.destination.collectAsStateWithLifecycle()
            val searchQuery by navigationViewModel.searchQuery.collectAsStateWithLifecycle()
            val search by navigationViewModel.search.collectAsStateWithLifecycle()
            val recent by navigationViewModel.recentDestinations.collectAsStateWithLifecycle()
            NavigationScreen(
                uiState = uiState,
                navigationState = navState,
                screenMode = screenMode,
                followMode = followMode,
                origin = origin,
                destination = destination,
                searchQuery = searchQuery,
                search = search,
                recentDestinations = recent,
                actions = NavigationActions(
                    onSearchQueryChanged = navigationViewModel::onSearchQueryChanged,
                    onSelectDestination = navigationViewModel::selectDestination,
                    onSelectOrigin = navigationViewModel::selectOrigin,
                    onUseCurrentLocation = navigationViewModel::useCurrentLocationAsOrigin,
                    onMapTap = navigationViewModel::selectMapPoint,
                    onStart = navigationViewModel::startGuidance,
                    onEnd = navigationViewModel::endNavigation,
                    onRecenter = navigationViewModel::recenter,
                    onUserMovedMap = navigationViewModel::onUserMovedMap,
                    onSwapEndpoints = navigationViewModel::swapEndpoints,
                    onAvoidTollsChanged = navigationViewModel::setAvoidTolls,
                    onBack = { navController.popBackStack() },
                ),
            )
        }
    }
}
