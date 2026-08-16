package com.example.cardashboard.data.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.emptyPreferences
import com.example.cardashboard.domain.model.DashboardSettings
import com.example.cardashboard.domain.model.SpeedUnit
import com.example.cardashboard.domain.model.TemperatureUnit
import com.example.cardashboard.domain.model.ThemePreference
import com.example.cardashboard.domain.model.VehicleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The stored-to-domain mapping, which is where a bad or outdated value on disk would otherwise take
 * the dashboard down.
 */
class SettingsPreferencesTest {

    @Test
    fun `an empty store yields the safe defaults`() {
        val settings = SettingsPreferences.toSettings(emptyPreferences())

        assertEquals(DashboardSettings.DEFAULT, settings)
        assertEquals(SpeedUnit.KILOMETERS_PER_HOUR, settings.speedUnit)
        assertEquals(TemperatureUnit.CELSIUS, settings.temperatureUnit)
        assertEquals(true, settings.demoModeEnabled)
        assertEquals(ThemePreference.DARK, settings.themePreference)
        assertEquals(true, settings.animationsEnabled)
    }

    @Test
    fun `stored values are read back exactly`() {
        val stored = mutablePreferencesOf(
            SettingsPreferences.SPEED_UNIT to SpeedUnit.MILES_PER_HOUR.name,
            SettingsPreferences.TEMPERATURE_UNIT to TemperatureUnit.FAHRENHEIT.name,
            SettingsPreferences.DEMO_MODE_ENABLED to false,
            SettingsPreferences.DEMO_VEHICLE_TYPE to VehicleType.ELECTRIC.name,
            SettingsPreferences.THEME_PREFERENCE to ThemePreference.SYSTEM.name,
            SettingsPreferences.ANIMATIONS_ENABLED to false
        )

        val settings = SettingsPreferences.toSettings(stored)

        assertEquals(SpeedUnit.MILES_PER_HOUR, settings.speedUnit)
        assertEquals(TemperatureUnit.FAHRENHEIT, settings.temperatureUnit)
        assertFalse(settings.demoModeEnabled)
        assertEquals(VehicleType.ELECTRIC, settings.demoVehicleType)
        assertEquals(ThemePreference.SYSTEM, settings.themePreference)
        assertFalse(settings.animationsEnabled)
    }

    @Test
    fun `an unrecognised stored value falls back to the default instead of throwing`() {
        val stored = mutablePreferencesOf(
            SettingsPreferences.SPEED_UNIT to "KNOTS",
            SettingsPreferences.THEME_PREFERENCE to "",
            SettingsPreferences.DEMO_VEHICLE_TYPE to "HYDROGEN"
        )

        val settings = SettingsPreferences.toSettings(stored)

        assertEquals(SpeedUnit.KILOMETERS_PER_HOUR, settings.speedUnit)
        assertEquals(ThemePreference.DARK, settings.themePreference)
        assertEquals(VehicleType.COMBUSTION, settings.demoVehicleType)
    }

    @Test
    fun `a partially written store keeps defaults for the missing keys`() {
        val stored = mutablePreferencesOf(
            SettingsPreferences.SPEED_UNIT to SpeedUnit.MILES_PER_HOUR.name
        )

        val settings = SettingsPreferences.toSettings(stored)

        assertEquals(SpeedUnit.MILES_PER_HOUR, settings.speedUnit)
        assertEquals(DashboardSettings.DEFAULT.temperatureUnit, settings.temperatureUnit)
        assertEquals(DashboardSettings.DEFAULT.demoModeEnabled, settings.demoModeEnabled)
    }
}
