package com.example.cardashboard.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.cardashboard.di.AppContainer
import com.example.cardashboard.ui.dashboard.DashboardScreen
import com.example.cardashboard.ui.dashboard.DashboardViewModel
import com.example.cardashboard.ui.settings.SettingsScreen
import com.example.cardashboard.ui.settings.SettingsViewModel

/**
 * Wires the two screens together.
 *
 * Each destination gets its own ViewModel, created from [container] so the graph stays explicit and
 * tests can pass a different container.
 */
@Composable
fun CarDashboardApp(
    container: AppContainer,
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = DashboardRoute.route
    ) {
        composable(DashboardRoute.route) {
            val viewModel: DashboardViewModel = viewModel(
                factory = DashboardViewModel.factory(container)
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            DashboardScreen(
                uiState = uiState,
                onToggleSpeedUnit = viewModel::onToggleSpeedUnit,
                onDriveModeSelected = viewModel::onDriveModeSelected,
                onRequestTripReset = viewModel::onRequestTripReset,
                onConfirmTripReset = viewModel::onConfirmTripReset,
                onDismissTripReset = viewModel::onDismissTripReset,
                onOpenSettings = { navController.navigate(SettingsRoute.route) }
            )
        }

        composable(SettingsRoute.route) {
            val viewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.factory(container)
            )
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            SettingsScreen(
                settings = settings,
                onSpeedUnitSelected = viewModel::onSpeedUnitSelected,
                onTemperatureUnitSelected = viewModel::onTemperatureUnitSelected,
                onDemoModeChanged = viewModel::onDemoModeChanged,
                onDemoVehicleTypeSelected = viewModel::onDemoVehicleTypeSelected,
                onThemeSelected = viewModel::onThemePreferenceSelected,
                onAnimationsChanged = viewModel::onAnimationsChanged,
                onResetDemoData = viewModel::onResetDemoData,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
