package com.example.cardashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.cardashboard.domain.model.DashboardSettings
import com.example.cardashboard.navigation.CarDashboardApp
import com.example.cardashboard.ui.theme.CarDashboardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CarDashboardApplication).container

        setContent {
            // Read at the root because the theme has to wrap everything below it.
            val settings by remember { container.settingsRepository.settings }
                .collectAsStateWithLifecycle(initialValue = DashboardSettings.DEFAULT)

            CarDashboardTheme(themePreference = settings.themePreference) {
                CarDashboardApp(container = container)
            }
        }
    }
}
