package com.csjotlab.cardashboard

import android.app.Application
import com.csjotlab.cardashboard.di.VehicleContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CarDashboardApplication : Application() {

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
    fun shutdownVehicleGraph() {
        val doomed = synchronized(lock) { vehicleContainer.also { vehicleContainer = null } }
            ?: return
        CoroutineScope(NonCancellable + Dispatchers.Default).launch {
            runCatching { doomed.shutdown() }
        }
    }
}
