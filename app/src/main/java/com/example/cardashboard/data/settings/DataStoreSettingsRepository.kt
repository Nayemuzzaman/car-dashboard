package com.example.cardashboard.data.settings

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.example.cardashboard.domain.model.DashboardSettings
import com.example.cardashboard.domain.model.SpeedUnit
import com.example.cardashboard.domain.model.TemperatureUnit
import com.example.cardashboard.domain.model.ThemePreference
import com.example.cardashboard.domain.model.VehicleType
import com.example.cardashboard.domain.repository.SettingsRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Stores [DashboardSettings] in a Preferences DataStore. */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>
) : SettingsRepository {

    override val settings: Flow<DashboardSettings> = dataStore.data
        .catch { cause ->
            // A read failure must not take the dashboard down; fall back to defaults.
            if (cause !is IOException) throw cause
            Log.w(TAG, "Could not read dashboard settings, using defaults", cause)
            emit(emptyPreferences())
        }
        .map(SettingsPreferences::toSettings)

    override suspend fun setSpeedUnit(unit: SpeedUnit) =
        edit { it[SettingsPreferences.SPEED_UNIT] = unit.name }

    override suspend fun setTemperatureUnit(unit: TemperatureUnit) =
        edit { it[SettingsPreferences.TEMPERATURE_UNIT] = unit.name }

    override suspend fun setDemoModeEnabled(enabled: Boolean) =
        edit { it[SettingsPreferences.DEMO_MODE_ENABLED] = enabled }

    override suspend fun setDemoVehicleType(type: VehicleType) =
        edit { it[SettingsPreferences.DEMO_VEHICLE_TYPE] = type.name }

    override suspend fun setThemePreference(preference: ThemePreference) =
        edit { it[SettingsPreferences.THEME_PREFERENCE] = preference.name }

    override suspend fun setAnimationsEnabled(enabled: Boolean) =
        edit { it[SettingsPreferences.ANIMATIONS_ENABLED] = enabled }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        try {
            dataStore.updateData { current ->
                current.toMutablePreferences().apply(block)
            }
        } catch (error: IOException) {
            // Losing a preference write is recoverable; crashing the cluster is not.
            Log.w(TAG, "Could not persist dashboard settings", error)
        }
    }

    private companion object {
        const val TAG = "SettingsRepository"
    }
}
