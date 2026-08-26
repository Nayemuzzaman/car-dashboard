package com.csjotlab.cardashboard.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.csjotlab.cardashboard.CarDashboardApplication
import com.csjotlab.cardashboard.debug.DebugMockModeToggle
import com.csjotlab.cardashboard.ui.dashboard.DashboardScreen
import com.csjotlab.cardashboard.ui.dashboard.DashboardViewModel
import com.csjotlab.cardashboard.ui.diagnostics.DiagnosticDetailScreen
import com.csjotlab.cardashboard.ui.diagnostics.DiagnosticDetailUiState
import com.csjotlab.cardashboard.ui.diagnostics.formatDetectedTime

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
                debugContent = { modifier -> DebugMockModeToggle(modifier) },
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
    }
}
