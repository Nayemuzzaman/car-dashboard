package com.example.cardashboard.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.cardashboard.domain.model.DashboardSettings
import com.example.cardashboard.domain.model.SpeedUnit
import com.example.cardashboard.domain.model.TemperatureUnit
import com.example.cardashboard.domain.model.ThemePreference
import com.example.cardashboard.domain.model.VehicleType

/**
 * Keys and the pure mapping between stored [Preferences] and [DashboardSettings].
 *
 * Reading is kept free of Android and of DataStore's IO so it can be unit tested directly, and so
 * that a value written by an older build — or corrupted on disk — falls back to the default instead
 * of throwing.
 */
internal object SettingsPreferences {

    val SPEED_UNIT = stringPreferencesKey("speed_unit")
    val TEMPERATURE_UNIT = stringPreferencesKey("temperature_unit")
    val DEMO_MODE_ENABLED = booleanPreferencesKey("demo_mode_enabled")
    val DEMO_VEHICLE_TYPE = stringPreferencesKey("demo_vehicle_type")
    val THEME_PREFERENCE = stringPreferencesKey("theme_preference")
    val ANIMATIONS_ENABLED = booleanPreferencesKey("animations_enabled")

    fun toSettings(preferences: Preferences): DashboardSettings {
        val defaults = DashboardSettings.DEFAULT
        return DashboardSettings(
            speedUnit = preferences.enumOrDefault(SPEED_UNIT, defaults.speedUnit),
            temperatureUnit = preferences.enumOrDefault(TEMPERATURE_UNIT, defaults.temperatureUnit),
            demoModeEnabled = preferences[DEMO_MODE_ENABLED] ?: defaults.demoModeEnabled,
            demoVehicleType = preferences.enumOrDefault(DEMO_VEHICLE_TYPE, defaults.demoVehicleType),
            themePreference = preferences.enumOrDefault(THEME_PREFERENCE, defaults.themePreference),
            animationsEnabled = preferences[ANIMATIONS_ENABLED] ?: defaults.animationsEnabled
        )
    }

    private inline fun <reified T : Enum<T>> Preferences.enumOrDefault(
        key: Preferences.Key<String>,
        default: T
    ): T {
        val stored = this[key] ?: return default
        return enumValues<T>().firstOrNull { it.name == stored } ?: default
    }
}
