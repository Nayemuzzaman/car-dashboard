package com.example.cardashboard.domain.model

/** Theme options the dashboard offers. */
enum class ThemePreference {
    /** Dark cluster theme — the default, and the readable choice at night. */
    DARK,

    /** Light cluster theme for bright daylight. */
    LIGHT,

    /** Follow the system setting. */
    SYSTEM
}

/**
 * User preferences, persisted with DataStore.
 *
 * Defaults are the safe ones: metric units, demo mode on (so a fresh install shows a working
 * cluster instead of an empty one), animations on, dark theme.
 */
data class DashboardSettings(
    val speedUnit: SpeedUnit = SpeedUnit.KILOMETERS_PER_HOUR,
    val temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    val demoModeEnabled: Boolean = true,
    /** Which powertrain the demo simulation models. Ignored when demo mode is off. */
    val demoVehicleType: VehicleType = VehicleType.COMBUSTION,
    val themePreference: ThemePreference = ThemePreference.DARK,
    val animationsEnabled: Boolean = true
) {
    companion object {
        val DEFAULT = DashboardSettings()
    }
}
