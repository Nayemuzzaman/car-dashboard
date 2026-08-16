package com.example.cardashboard.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.example.cardashboard.domain.model.DashboardSettings
import com.example.cardashboard.domain.model.SpeedUnit
import com.example.cardashboard.domain.model.TemperatureUnit
import com.example.cardashboard.domain.model.ThemePreference
import com.example.cardashboard.domain.model.VehicleType
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Round-trips settings through a real Preferences DataStore backed by a temporary file. */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreSettingsRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scope = CoroutineScope(UnconfinedTestDispatcher())
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        file = File(temporaryFolder.newFolder(), "settings.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a fresh store reports the defaults`() = runTest {
        val repository = DataStoreSettingsRepository(dataStore)

        assertEquals(DashboardSettings.DEFAULT, repository.settings.first())
    }

    @Test
    fun `each setting survives a write and read back`() = runTest {
        val repository = DataStoreSettingsRepository(dataStore)

        repository.setSpeedUnit(SpeedUnit.MILES_PER_HOUR)
        repository.setTemperatureUnit(TemperatureUnit.FAHRENHEIT)
        repository.setDemoModeEnabled(false)
        repository.setDemoVehicleType(VehicleType.ELECTRIC)
        repository.setThemePreference(ThemePreference.LIGHT)
        repository.setAnimationsEnabled(false)

        val stored = repository.settings.first()
        assertEquals(SpeedUnit.MILES_PER_HOUR, stored.speedUnit)
        assertEquals(TemperatureUnit.FAHRENHEIT, stored.temperatureUnit)
        assertFalse(stored.demoModeEnabled)
        assertEquals(VehicleType.ELECTRIC, stored.demoVehicleType)
        assertEquals(ThemePreference.LIGHT, stored.themePreference)
        assertFalse(stored.animationsEnabled)
    }

    @Test
    fun `settings written by one repository are visible to a new one over the same store`() =
        runTest {
            DataStoreSettingsRepository(dataStore).setSpeedUnit(SpeedUnit.MILES_PER_HOUR)

            val restarted = DataStoreSettingsRepository(dataStore)

            assertEquals(SpeedUnit.MILES_PER_HOUR, restarted.settings.first().speedUnit)
        }

    @Test
    fun `writing one setting leaves the others alone`() = runTest {
        val repository = DataStoreSettingsRepository(dataStore)
        repository.setDemoVehicleType(VehicleType.ELECTRIC)

        repository.setAnimationsEnabled(false)

        val stored = repository.settings.first()
        assertEquals(VehicleType.ELECTRIC, stored.demoVehicleType)
        assertFalse(stored.animationsEnabled)
        assertEquals(DashboardSettings.DEFAULT.speedUnit, stored.speedUnit)
    }
}
