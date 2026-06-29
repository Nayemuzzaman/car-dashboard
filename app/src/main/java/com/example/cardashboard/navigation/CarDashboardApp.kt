package com.example.cardashboard.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.cardashboard.ui.dashboard.DashboardScreen

@Composable
fun CarDashboardApp() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = DashboardRoute.route
    ) {
        composable(DashboardRoute.route) {
            DashboardScreen()
        }
    }
}
