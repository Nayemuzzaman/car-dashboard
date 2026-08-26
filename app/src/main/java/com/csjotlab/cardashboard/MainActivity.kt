package com.csjotlab.cardashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.csjotlab.cardashboard.navigation.CarDashboardApp
import com.csjotlab.cardashboard.ui.theme.CarDashboardTheme

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

    override fun onDestroy() {
        super.onDestroy()
        // `isFinishing` is what separates "the user has left the dashboard" from "this Activity is
        // being recreated for a rotation". Only the first may stop the vehicle source; the second
        // must not, which is the entire reason the graph is held on an application scope rather
        // than a viewModelScope.
        //
        // This is the only in-process signal Android offers that the graph is no longer wanted. If
        // the process is killed outright there is no callback at all — and nothing left to leak.
        if (isFinishing) {
            (application as? CarDashboardApplication)?.shutdownVehicleGraph()
        }
    }
}
