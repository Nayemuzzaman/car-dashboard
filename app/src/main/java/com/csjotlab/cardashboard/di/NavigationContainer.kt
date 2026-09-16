package com.csjotlab.cardashboard.di

import android.content.Context
import com.csjotlab.cardashboard.BuildConfig
import com.csjotlab.cardashboard.nav.data.PersistentRecentDestinationsStore
import com.csjotlab.cardashboard.nav.data.NavigationRepository
import com.csjotlab.cardashboard.nav.data.RecentDestinationsStore
import com.csjotlab.cardashboard.nav.data.SharedPreferencesStringStorage
import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.geocoding.GeocodingEngine
import com.csjotlab.cardashboard.nav.geocoding.PhotonGeocodingEngine
import com.csjotlab.cardashboard.nav.location.CompassHeadingProvider
import com.csjotlab.cardashboard.nav.location.GpsLocationProvider
import com.csjotlab.cardashboard.nav.routing.FallbackRoutingEngine
import com.csjotlab.cardashboard.nav.routing.HttpRoutingEngine
import com.csjotlab.cardashboard.nav.routing.OsrmRoutingEngine
import com.csjotlab.cardashboard.nav.routing.StraightLineRoutingEngine
import com.csjotlab.cardashboard.nav.routing.ValhallaRoutingEngine
import com.csjotlab.cardashboard.vehicle.domain.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hand-rolled graph for the navigation subsystem, mirroring [VehicleContainer].
 *
 * The navigation graph is process-scoped for the same reason the vehicle graph is: a rotation must
 * not stop and restart GPS/compass providers mid-drive. It is built lazily the first time the
 * navigation screen asks for it, so the sensors are only running while navigation is actually used.
 */
class NavigationContainer(
    context: Context,
    private val applicationScope: CoroutineScope,
) {
    private val clock = SystemClock

    private val locationProvider = GpsLocationProvider(context, clock)
    private val headingProvider = CompassHeadingProvider(context)
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

    val geocodingEngine: GeocodingEngine = PhotonGeocodingEngine(baseUrl = BuildConfig.GEOCODING_BASE_URL)

    val recentDestinations: RecentDestinationsStore = PersistentRecentDestinationsStore(
        SharedPreferencesStringStorage(
            context.getSharedPreferences("navigation", Context.MODE_PRIVATE),
            key = "recent_destinations",
        ),
    )

    val repository = NavigationRepository(
        routingEngine = routingEngine,
        locationProvider = locationProvider,
        headingProvider = headingProvider,
        clock = clock,
        scope = applicationScope,
    )

    init {
        applicationScope.launch { repository.start() }
    }

    fun setDestination(point: GeoPoint?) {
        repository.setDestination(point)
    }

    /** Re-starts the providers after a runtime permission grant, which the initial start() skipped. */
    fun restartProviders() {
        applicationScope.launch { repository.start() }
    }

    /**
     * Stops the providers and then tears down [applicationScope], the same order and rationale as
     * [VehicleContainer.shutdown].
     */
    suspend fun shutdown(): Unit = withContext(NonCancellable) {
        try {
            repository.stop()
        } finally {
            applicationScope.coroutineContext.job.cancelAndJoin()
        }
    }
}
