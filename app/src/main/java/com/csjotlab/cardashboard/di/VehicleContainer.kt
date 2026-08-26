package com.csjotlab.cardashboard.di

import com.csjotlab.cardashboard.vehicle.data.VehicleRepository
import com.csjotlab.cardashboard.vehicle.domain.SystemClock
import com.csjotlab.cardashboard.vehicle.source.MockVehicleDataSource
import com.csjotlab.cardashboard.vehicle.source.VehicleDataSource
import com.csjotlab.cardashboard.vehicle.source.VehicleSourceSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/**
 * Hand-rolled graph. The app is small enough that a DI framework would cost more than it saves.
 *
 * ## Scope lifetime — process-scoped, and this container owns the scope
 *
 * [applicationScope] is deliberately **not** tied to a screen. The vehicle graph has to outlive
 * every Activity and ViewModel: if it did not, a rotation would stop and restart the active source
 * once per configuration change, which from Task 18 means closing and re-opening the USB device
 * while the user turns the phone. That is also why [VehicleRepository] shares with
 * `SharingStarted.Eagerly` and why the ViewModel's `WhileSubscribed(5_000)` back-pressure
 * deliberately stops at the ViewModel rather than reaching down here.
 *
 * The price of that decision is that nothing in the framework will ever stop the source for us —
 * Android offers no reliable "the process is ending" callback — so the graph has exactly one
 * teardown path, [shutdown], and it has to be called on purpose. Its production caller is
 * `CarDashboardApplication.shutdownVehicleGraph()`, driven from the last Activity finishing.
 *
 * A container that has been shut down is dead and is never revived: the Application drops its
 * reference and builds a fresh container (on a fresh scope) if the user launches again into the
 * same cached process.
 */
class VehicleContainer(
    private val applicationScope: CoroutineScope,
    debugBuild: Boolean,
) {
    /** Task 18 replaces this with real USB device availability. Until then, no real source exists. */
    private val realSourceAvailability = MutableStateFlow<VehicleDataSource?>(null)

    /**
     * Off at construction, and only ever changed by [setMockModeEnabled]. Mock mode is never
     * inferred from "no real source is present" — that would be exactly the fallback the spec
     * forbids.
     */
    private val _mockModeEnabled = MutableStateFlow(false)

    /**
     * Read-only view of the toggle, for the debug affordance that drives it. Exposed so that
     * affordance can render the container's *actual* state instead of keeping its own copy — a
     * second copy would survive a [shutdown] and rebuild that resets this one to false, and would
     * then claim mock mode was on while the fresh graph had it off.
     *
     * This is not a way in. Selection still requires `debugBuild` as well, inside
     * [VehicleSourceSelector], and nothing here can turn that half of the gate on.
     */
    val mockModeEnabled: StateFlow<Boolean> = _mockModeEnabled.asStateFlow()

    private val selector = VehicleSourceSelector(
        realSourceAvailability = realSourceAvailability,
        mockModeEnabled = _mockModeEnabled,
        debugBuild = debugBuild,
        // The mock drives its scripted timeline on the application scope, so it outlives the
        // screen that happens to be watching it rather than restarting on every rotation.
        mockSourceFactory = { MockVehicleDataSource(SystemClock, applicationScope) },
    )

    val repository = VehicleRepository(
        sources = selector.activeSource,
        clock = SystemClock,
        scope = applicationScope,
    )

    fun setMockModeEnabled(enabled: Boolean) {
        _mockModeEnabled.value = enabled
    }

    /**
     * Stops the active source and then tears down [applicationScope], waiting for it to finish
     * unwinding before returning.
     *
     * The order is the whole point. Stopping through [VehicleRepository.shutdown] first means the
     * source's `stop()` runs on a scope that is still alive and can be awaited, and a failure to
     * close reaches a caller that can log it. Cancelling first would demote the stop to the
     * repository's job-completion handler, which can only run best-effort on `Dispatchers
     * .Unconfined` up to the first real suspension — adequate for cancelling a scripted mock, not
     * for a `UsbDeviceConnection.close()`.
     *
     * Idempotent: the repository forgets the source it has stopped, and cancelling an
     * already-cancelled job is a no-op, so a second call is silent rather than a double `stop()`.
     *
     * Runs [NonCancellable] because a teardown abandoned half-way is precisely the leak this
     * method exists to prevent.
     */
    suspend fun shutdown(): Unit = withContext(NonCancellable) {
        try {
            repository.shutdown()
        } finally {
            applicationScope.coroutineContext.job.cancelAndJoin()
        }
    }
}
