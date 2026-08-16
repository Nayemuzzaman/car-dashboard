package com.example.cardashboard.domain.repository

import com.example.cardashboard.domain.model.DashboardSettings
import com.example.cardashboard.domain.model.SpeedUnit
import com.example.cardashboard.domain.model.TemperatureUnit
import com.example.cardashboard.domain.model.ThemePreference
import com.example.cardashboard.domain.model.VehicleType
import kotlinx.coroutines.flow.Flow

/** Persisted user preferences. Emits the stored values immediately on collection. */
interface SettingsRepository {

    val settings: Flow<DashboardSettings>

    suspend fun setSpeedUnit(unit: SpeedUnit)

    suspend fun setTemperatureUnit(unit: TemperatureUnit)

    suspend fun setDemoModeEnabled(enabled: Boolean)

    suspend fun setDemoVehicleType(type: VehicleType)

    suspend fun setThemePreference(preference: ThemePreference)

    suspend fun setAnimationsEnabled(enabled: Boolean)
}
