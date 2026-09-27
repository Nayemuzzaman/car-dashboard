package com.csjotlab.cardashboard.di

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.csjotlab.cardashboard.BuildConfig
import com.csjotlab.cardashboard.nav.data.NavigationRepository
import com.csjotlab.cardashboard.nav.data.NavigationSession
import com.csjotlab.cardashboard.nav.data.PersistentRecentDestinationsStore
import com.csjotlab.cardashboard.nav.data.RecentDestinationsStore
import com.csjotlab.cardashboard.nav.data.SharedPreferencesStringStorage
import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.NavigationPhase
import com.csjotlab.cardashboard.nav.geocoding.CoordinateGeocodingEngine
import com.csjotlab.cardashboard.nav.geocoding.FirstSuccessNearbySearch
import com.csjotlab.cardashboard.nav.geocoding.GeocodingEngine
import com.csjotlab.cardashboard.nav.geocoding.NearbyCategoryGeocodingEngine
import com.csjotlab.cardashboard.nav.geocoding.OverpassCategorySearch
import com.csjotlab.cardashboard.nav.geocoding.PhotonGeocodingEngine
import com.csjotlab.cardashboard.nav.location.CompassHeadingProvider
import com.csjotlab.cardashboard.nav.location.GpsLocationProvider
import com.csjotlab.cardashboard.nav.routing.FallbackRoutingEngine
import com.csjotlab.cardashboard.nav.routing.HttpRoutingEngine
import com.csjotlab.cardashboard.nav.routing.OsrmRoutingEngine
import com.csjotlab.cardashboard.nav.routing.StraightLineRoutingEngine
import com.csjotlab.cardashboard.nav.routing.ValhallaRoutingEngine
import com.csjotlab.cardashboard.nav.service.NavigationService
import com.csjotlab.cardashboard.nav.vehicle.NoVehicleDataProvider
import com.csjotlab.cardashboard.nav.vehicle.VehicleDataProvider
import com.csjotlab.cardashboard.vehicle.domain.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Hand-rolled graph for the navigation subsystem, mirroring [VehicleContainer].
 *
 * The navigation graph is process-scoped for the same reason the vehicle graph is: a rotation must
 * not stop and restart GPS/compass providers mid-drive, and leaving the map for the dashboard must
 * not lose the trip. The sensors, however, only run while someone needs them — the visible
 * navigation screen or the guidance service — see [acquireSensors].
 */
class NavigationContainer(
    context: Context,
    private val applicationScope: CoroutineScope,
    vehicleDataProvider: VehicleDataProvider = NoVehicleDataProvider,
) {
    private val appContext = context.applicationContext
    private val clock = SystemClock

    private val locationProvider = GpsLocationProvider(appContext, clock)
    private val headingProvider = CompassHeadingProvider(appContext)
    // GraphHopper is opt-in (empty URL = skip it entirely, so a phone never waits on a connect
    // timeout to a server that isn't there); Valhalla is the public default because it reports and
    // avoids tolls; OSRM is the fallback (no toll data); straight-line is the honest last resort.
    private val routingEngine = FallbackRoutingEngine(
        listOfNotNull(
            BuildConfig.ROUTING_BASE_URL.takeIf { it.isNotBlank() }?.let { HttpRoutingEngine(baseUrl = it) },
            ValhallaRoutingEngine(baseUrl = BuildConfig.VALHALLA_BASE_URL),
            OsrmRoutingEngine(baseUrl = BuildConfig.OSRM_BASE_URL),
            StraightLineRoutingEngine(),
        ),
    )

    // Text search: Photon. "Nearest fuel/parking/…": Overpass around the car. Coordinates: local.
    val geocodingEngine: GeocodingEngine = CoordinateGeocodingEngine(
        NearbyCategoryGeocodingEngine(
            delegate = PhotonGeocodingEngine(baseUrl = BuildConfig.GEOCODING_BASE_URL),
            nearby = FirstSuccessNearbySearch(
                BuildConfig.OVERPASS_BASE_URLS.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { OverpassCategorySearch(baseUrl = it) },
            ),
        ),
    )

    val recentDestinations: RecentDestinationsStore = PersistentRecentDestinationsStore(
        SharedPreferencesStringStorage(
            appContext.getSharedPreferences("navigation", Context.MODE_PRIVATE),
            key = "recent_destinations",
        ),
    )

    val session = NavigationSession()

    val repository = NavigationRepository(
        routingEngine = routingEngine,
        locationProvider = locationProvider,
        headingProvider = headingProvider,
        clock = clock,
        scope = applicationScope,
        vehicleDataProvider = vehicleDataProvider,
    )

    private val sensorLock = Mutex()
    private var sensorUsers = 0

    init {
        // Guidance keeps running when the screen turns off or the driver switches apps: a
        // foreground service holds the sensors and shows the next instruction while it is active.
        applicationScope.launch {
            repository.snapshot
                .map { it.guidanceActive && it.state.route != null && it.state.phase != NavigationPhase.Arrived }
                .distinctUntilChanged()
                .collect { active -> if (active) startGuidanceService() else stopGuidanceService() }
        }
    }

    /** One more user needs GPS/compass; the first starts them. Pair with [releaseSensors]. */
    fun acquireSensors() {
        applicationScope.launch {
            sensorLock.withLock { if (sensorUsers++ == 0) repository.start() }
        }
    }

    /** One user no longer needs GPS/compass; the last stops them. */
    fun releaseSensors() {
        applicationScope.launch {
            sensorLock.withLock {
                if (sensorUsers == 0) return@withLock
                if (--sensorUsers == 0) repository.pauseSensors()
            }
        }
    }

    /** Re-starts the providers after a runtime permission grant, which the earlier start skipped. */
    fun restartProviders() {
        applicationScope.launch { sensorLock.withLock { if (sensorUsers > 0) repository.start() } }
    }

    fun setDestination(point: GeoPoint?) {
        repository.setDestination(point)
    }

    /** Ends the trip from outside the screen (the notification's End action). */
    fun endTrip() {
        repository.setGuidanceActive(false)
        session.destination.value = null
        repository.setDestination(null)
    }

    private fun startGuidanceService() {
        try {
            val intent = Intent(appContext, NavigationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appContext.startForegroundService(intent) else appContext.startService(intent)
        } catch (t: Throwable) {
            // Starting a foreground service from the background is refused on Android 12+; guidance
            // still works while the screen is visible.
            Log.w(TAG, "Guidance service not started: ${t.message}")
        }
    }

    private fun stopGuidanceService() {
        appContext.stopService(Intent(appContext, NavigationService::class.java))
    }

    /**
     * Stops the providers and then tears down [applicationScope], the same order and rationale as
     * [VehicleContainer.shutdown].
     */
    suspend fun shutdown(): Unit = withContext(NonCancellable) {
        try {
            stopGuidanceService()
            repository.stop()
        } finally {
            applicationScope.coroutineContext.job.cancelAndJoin()
        }
    }

    private companion object {
        const val TAG = "CarDash/Nav"
    }
}
