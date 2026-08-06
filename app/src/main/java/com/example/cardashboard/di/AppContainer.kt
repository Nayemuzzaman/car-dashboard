package com.example.cardashboard.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.example.cardashboard.data.demo.DemoVehicleDataSource
import com.example.cardashboard.data.settings.DataStoreSettingsRepository
import com.example.cardashboard.data.vehicle.DefaultVehicleRepository
import com.example.cardashboard.data.vehicle.NoVehicleInterfaceDataSource
import com.example.cardashboard.data.vehicle.VehicleDataSource
import com.example.cardashboard.domain.model.VehicleType
import com.example.cardashboard.domain.repository.SettingsRepository
import com.example.cardashboard.domain.repository.VehicleRepository
import com.example.cardashboard.util.SystemTimeProvider
import com.example.cardashboard.util.TimeProvider

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "dashboard_settings"
)

/**
 * The app's dependency graph.
 *
 * Two repositories and a clock do not justify a DI framework, so this is a plain container created
 * once by [com.example.cardashboard.CarDashboardApplication]. Tests build their own instances
 * directly and never touch it.
 */
interface AppContainer {
    val settingsRepository: SettingsRepository
    val vehicleRepository: VehicleRepository
    val timeProvider: TimeProvider
}

class DefaultAppContainer(context: Context) : AppContainer {

    private val applicationContext = context.applicationContext

    override val settingsRepository: SettingsRepository by lazy {
        DataStoreSettingsRepository(applicationContext.settingsDataStore)
    }

    override val vehicleRepository: VehicleRepository by lazy {
        DefaultVehicleRepository(
            settingsRepository = settingsRepository,
            demoSourceFactory = { type: VehicleType -> DemoVehicleDataSource(type) },
            liveSource = liveVehicleDataSource
        )
    }

    override val timeProvider: TimeProvider = SystemTimeProvider

    /**
     * Swap this for a real integration (Android Automotive `CarPropertyManager`, or an authorised
     * manufacturer SDK) to connect actual vehicle data; nothing else in the app has to change.
     */
    private val liveVehicleDataSource: VehicleDataSource by lazy { NoVehicleInterfaceDataSource() }
}
