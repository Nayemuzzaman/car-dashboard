package com.example.cardashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.cardashboard.navigation.CarDashboardApp
import com.example.cardashboard.ui.theme.CarDashboardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CarDashboardTheme {
                CarDashboardApp()
            }
        }
    }
}
