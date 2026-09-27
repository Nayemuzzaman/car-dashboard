package com.csjotlab.cardashboard

import android.app.Application
import com.csjotlab.cardashboard.di.NavigationContainer
import com.csjotlab.cardashboard.di.VehicleContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import com.csjotlab.cardashboard.nav.vehicle.VehicleRepositoryDataProvider

class CarDashboardApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // MapLibre needs a one-time bootstrap before any MapView is created. No API key is required
        // for the MapLibre demo tile server.
        MapLibre.getInstance(this, null, WellKnownTileServer.MapLibre)
    }

    private val lock = Any()
    private var vehicleContainer: VehicleContainer? = null

    /**
     * Built on first use, and rebuilt after [shutdownVehicleGraph].
     *
     * Rebuilding matters because Android keeps this `Application` instance alive in a cached
     * process after the user backs out of the app. A `by lazy` would hand the next launch the
     * corpse of the graph we tore down — a permanently disconnected dashboard with no way back.
     */
    val container: VehicleContainer
        get() = synchronized(lock) {
            vehicleContainer ?: VehicleContainer(
                // Owned by the container from here on: shutdown() cancels this scope.
                applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                debugBuild = BuildConfig.DEBUG,
            ).also { vehicleContainer = it }
        }

    private var navigationContainer: NavigationContainer? = null

    /**
     * Built lazily the first time the navigation screen asks for it, and rebuilt after shutdown.
     * GPS and compass run only while the navigation screen is visible or guidance is active.
     */
    val navigation: NavigationContainer
        get() = synchronized(lock) {
            navigationContainer ?: NavigationContainer(
                context = this,
                applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                // Vehicle speed/gear from the dashboard's source, when a real one is connected.
                vehicleDataProvider = VehicleRepositoryDataProvider(vehicleSnapshots()),
            ).also { navigationContainer = it }
        }

    /**
     * The one production teardown path for the vehicle graph. See [VehicleContainer] for why the
     * graph is process-scoped and therefore has to be stopped deliberately.
     *
     * Fire-and-forget by necessity: `Activity.onDestroy` cannot await a suspending call, and the
     * teardown must not run on the scope it is cancelling. The `runCatching` is there for the same
     * reason `VehicleRepository.stopActiveBestEffort` has one — an `IOException` from a USB
     * `close()` on the way out must not reach the thread's default uncaught handler and take the
     * process down as the user leaves.
     */
    /**
     * The vehicle graph's snapshots, looked up when navigation starts collecting. Both graphs are
     * torn down together when the activity finishes, so navigation never outlives this graph.
     */
    private fun vehicleSnapshots() = flow { emitAll(container.repository.snapshot) }

    fun shutdownVehicleGraph() {
        val doomed = synchronized(lock) { vehicleContainer.also { vehicleContainer = null } }
            ?: return
        CoroutineScope(NonCancellable + Dispatchers.Default).launch {
            runCatching { doomed.shutdown() }
        }
    }

    fun shutdownNavigationGraph() {
        val doomed = synchronized(lock) { navigationContainer.also { navigationContainer = null } }
            ?: return
        CoroutineScope(NonCancellable + Dispatchers.Default).launch {
            runCatching { doomed.shutdown() }
        }
    }
}
