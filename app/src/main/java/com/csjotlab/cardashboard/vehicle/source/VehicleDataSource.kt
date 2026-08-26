package com.csjotlab.cardashboard.vehicle.source

import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleDiagnosticsState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import kotlinx.coroutines.flow.Flow

/**
 * One vehicle-data provider. The dashboard never knows which implementation is active.
 *
 * Implementations must honour spec section 2.1: a field may only carry a value that the source
 * genuinely obtained for that field.
 *
 * ## Liveness contract — read this before writing a real source
 *
 * `VehicleRepository` reports `VehicleCommunicationUnavailable` when it has had no evidence of
 * contact with the vehicle for the stale timeout. **A change in the values is not that evidence.**
 * A car idling at a red light reports byte-identical readings for minutes while the link is
 * perfectly healthy, and a conflating flow (`StateFlow`, `distinctUntilChanged`) suppresses those
 * identical emissions entirely — so "no emission" must never be read as "no response".
 *
 * Every implementation must therefore supply at least one of:
 *
 *  1. **[liveness]** — one emission per *response actually received from the vehicle*, whether or
 *     not any value changed. This is the preferred mechanism: it is the only one that survives
 *     conflation, and it says what it means.
 *  2. **A [vehicleState] emission on every poll** whose [VehicleState.lastUpdatedMs] is taken from
 *     the shared clock each time, so that two consecutive identical readings are still distinct
 *     values and are therefore not conflated away.
 *
 * A source that does neither will be reported as unreachable after the stale timeout while it is
 * answering normally.
 *
 * ## The shared clock
 *
 * Every timestamp crossing this interface — [liveness] values and [VehicleState.lastUpdatedMs] —
 * must be read from **the same [com.csjotlab.cardashboard.vehicle.domain.Clock] instance the
 * `VehicleRepository` was constructed with**. The repository subtracts these from its own
 * `clock.nowMs()` to decide how much of the stale window is left, so a different epoch or a
 * monotonic source such as `System.nanoTime()` produces a meaningless age. It will not blow up
 * visibly: the result is clamped into the valid range, so a wrong epoch silently becomes either a
 * permanently full window or instant staleness. Pass the clock in; do not read the wall clock
 * directly, and never use a timestamp reported by the adapter or the vehicle.
 */
interface VehicleDataSource {
    val id: VehicleSourceId
    val vehicleState: Flow<VehicleState>
    val diagnostics: Flow<VehicleDiagnosticsState>
    val connectionState: Flow<VehicleConnectionState>

    /**
     * Proof that the vehicle answered. One emission per response actually received, carrying the
     * instant it was received, read from the shared clock. Emitted even when nothing changed.
     *
     * The repository trusts this absolutely — it has no independent way to check the link — so the
     * contract is deliberately narrow. Emit **only on a received response**:
     *
     *  - **never on send.** A request going out is not evidence that anything came back.
     *  - **never on timeout, and never on a retry.** A retry loop that beats each attempt would
     *    report a dead adapter as healthy forever, which is exactly the failure the stale timeout
     *    exists to catch.
     *  - **never from a keep-alive tick or any other timer.** A beat must be caused by the vehicle,
     *    not by the passage of time.
     *  - **never before [start] returns, and never after [stop].** A beat outside the source's
     *    lifetime describes a contact that could not have happened.
     *
     * The flow may be cold or hot, but it must not replay: a new subscriber must receive only beats
     * that occur after it subscribes. A replayed beat is a response dated to the wrong moment.
     *
     * A beat produced while nothing is collecting is **dropped, never buffered for a later
     * subscriber** — for the same reason. `MockVehicleDataSource` implements this with a
     * `MutableSharedFlow(replay = 0, onBufferOverflow = DROP_OLDEST)`, which discards emissions
     * made with no subscriber attached; a real source should do the equivalent rather than queue
     * them up.
     *
     * A source with no way to report this may return an empty flow, but must then satisfy option 2
     * of the contract on [VehicleDataSource].
     */
    val liveness: Flow<Long>

    /**
     * Prepares a start and returns its one-shot activation handle. Preparation must not open a
     * transport, read bytes, or emit liveness. The caller obtains the handle only after this
     * suspend call has returned, then calls [VehicleSourceActivation.activate] as a separate next
     * step. That program-order edge is the lifecycle guarantee: response-producing work cannot
     * exist before the caller has observed `start()` return, regardless of dispatcher concurrency.
     */
    suspend fun start(): VehicleSourceActivation
    suspend fun stop()
}

/** One-shot second phase of [VehicleDataSource.start]. Repeated or stale activation is a no-op. */
fun interface VehicleSourceActivation {
    suspend fun activate()
}
