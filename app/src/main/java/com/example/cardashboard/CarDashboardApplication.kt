package com.example.cardashboard

import android.app.Application
import com.example.cardashboard.di.AppContainer
import com.example.cardashboard.di.DefaultAppContainer

/** Owns the dependency container for the process lifetime. */
class CarDashboardApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
