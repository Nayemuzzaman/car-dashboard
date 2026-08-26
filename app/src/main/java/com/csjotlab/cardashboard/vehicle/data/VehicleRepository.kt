package com.csjotlab.cardashboard.vehicle.data

import com.csjotlab.cardashboard.vehicle.domain.Clock
import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleDiagnosticsState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import com.csjotlab.cardashboard.vehicle.domain.isLiveData
import com.csjotlab.cardashboard.vehicle.source.VehicleDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

data class VehicleSnapshot(
    val state: VehicleState,
    val diagnostics: VehicleDiagnosticsState,
    val connection: VehicleConnectionState,
) {
    companion object {
        val Disconnected = VehicleSnapshot(
            state = VehicleState.unavailable(VehicleSourceId.NONE),
            diagnostics = VehicleDiagnosticsState.empty(),
            connection = VehicleConnectionState.Disconnected,
        )
    }
}

/**
 * Owns the currently active source and publishes one coherent snapshot.
 *
 * Three invariants matter more than anything else here:
 *  - a real source that fails is never replaced by plausible values (there is no fallback path);
 *  - when the connection is not live, telemetry is cleared in the same emission;
 *  - a source that throws produces a failure state, never a dead scope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VehicleRepository(
    sources: Flow<VehicleDataSource?>,
    private val clock: Clock,
    scope: CoroutineScope,
    private val staleTimeoutMs: Long = 3_000L,
) {
    private var active: VehicleDataSource? = null

    val snapshot: StateFlow<VehicleSnapshot> = sources
        .flatMapLatest { source ->
            val stream = if (source == null) {
                flow {
                    switchTo(null)
                    emit(VehicleSnapshot.Disconnected)
                }
            } else {
                combine(
                    source.vehicleState,
                    source.diagnostics,
                    source.connectionState,
                ) { state, diagnostics, connection ->
                    if (connection.isLiveData) {
                        VehicleSnapshot(state, diagnostics, connection)
                    } else {
                        // Not live: publish the connection state, but never stale readings.
                        VehicleSnapshot(
                            state = VehicleState.unavailable(source.id),
                            diagnostics = VehicleDiagnosticsState.empty(),
                            connection = connection,
                        )
                    }
                }
                    .withStaleTimeout(source.id, staleTimeoutMs, clock, source.liveness)
                    // start()/stop() sit inside the catch below on purpose: an adapter that throws
                    // while opening must surface as a failure state, not as a dead scope.
                    .onStart { switchTo(source) }
            }
            stream.catch { cause ->
                if (cause is CancellationException) throw cause
                emit(failure(source, cause))
            }
        }
        // No distinctUntilChanged() here: stateIn's MutableStateFlow already suppresses a value
        // equal to the one it is holding, so a second operator would be dead weight. Proven by
        // `identical consecutive states are not re-emitted`, which drives a non-conflating source.
        .stateIn(scope, SharingStarted.Eagerly, VehicleSnapshot.Disconnected)

    init {
        // Without this a cancelled owner (a viewModelScope, in production) would leave a real USB
        // connection open forever: nothing else ever calls stop() on the last active source.
        scope.coroutineContext.job.invokeOnCompletion { stopActiveBestEffort() }
    }

    /**
     * Explicit teardown for owners that outlive their scope or want to await the stop.
     *
     * A failure in the source's `stop()` propagates to the caller, deliberately and unlike the
     * scope-cancellation path below: `shutdown()` is called on purpose by code that is in a
     * position to handle or log the failure, whereas the cancellation path runs from a job
     * completion handler where there is nobody left to tell.
     */
    suspend fun shutdown() {
        val previous = active
        active = null
        previous?.stop()
    }

    private suspend fun switchTo(next: VehicleDataSource?) {
        val previous = active
        if (previous === next) return
        active = next
        previous?.stop()
        if (next != null) {
            val activation = next.start()
            activation.activate()
        }
    }

    private fun stopActiveBestEffort() {
        val previous = active ?: return
        active = null
        // The scope is already dead, so the stop cannot run on it. Unconfined runs the body
        // synchronously up to the first real suspension, which is as much as a completion handler
        // can honestly promise.
        //
        // runCatching is load-bearing, not defensive noise: NonCancellable is not a real parent
        // job, so an escaping throwable would reach handleCoroutineException and then the thread's
        // default uncaught handler — which on Android takes the process down. A USB close() raising
        // IOException while the user merely rotates the screen must not crash the app. There is no
        // caller left to report it to, so it is swallowed here on purpose; shutdown() above is the
        // path that does surface it.
        CoroutineScope(NonCancellable + Dispatchers.Unconfined).launch {
            runCatching { previous.stop() }
        }
    }

    private fun failure(source: VehicleDataSource?, cause: Throwable) = VehicleSnapshot(
        state = VehicleState.unavailable(source?.id ?: VehicleSourceId.NONE),
        diagnostics = VehicleDiagnosticsState.empty(),
        connection = VehicleConnectionState.Error(cause.message ?: "source failure"),
    )
}

/**
 * Everything the watchdog reacts to, funnelled into one stream so that every read and write of the
 * liveness bookkeeping happens in a single sequential `collect`. The timeout is an event rather
 * than a flag the watchdog coroutine sets, because a flag would be written from the watchdog and
 * read from the collector — a race on the exact transition this class exists to get right.
 */
private sealed interface ContactEvent {
    data class Data(val snapshot: VehicleSnapshot) : ContactEvent
    data class Beat(val atMs: Long) : ContactEvent

    /**
     * Carries the watchdog generation that produced it. Cancelling a watchdog cannot retract a
     * timeout it has already sent — by then the value may be sitting inside `merge`'s buffer, where
     * no amount of `tryReceive` draining can reach it — so the generation is what makes it
     * discardable. Without this, a beat and a timeout landing at the same instant publish a
     * spurious "no response from vehicle" on a link that just answered.
     */
    data class Timeout(val generation: Long) : ContactEvent
}

/**
 * Staleness is a connection-level fact, not a per-field one. Ageing fields individually would make
 * values flicker in and out; instead the whole connection degrades in one step. Spec section 3.2.
 *
 * The watchdog is armed against *evidence of contact*, never against *change in the values*: a
 * source reporting identical readings every 200 ms is healthy, not silent. Evidence is either a
 * [liveness] beat or a snapshot, and both carry the instant they describe, so the deadline is
 * `thatInstant + staleTimeoutMs` measured on [clock] rather than a blind timer — data that arrives
 * already older than the timeout goes stale immediately instead of buying another full window.
 *
 * Recovery is symmetric with that, and has to be. A source that reports contact but not change —
 * the preferred half of the [VehicleDataSource] contract, and what the USB source will be — has no
 * way to produce a snapshot while the car sits still. If only a snapshot could clear the stale
 * state, a three-second hiccup at a red light would pin the dashboard at "no response from vehicle"
 * with every signal `Unknown` until the light went green. So the first beat after a stale
 * transition republishes the last live snapshot verbatim: the beat is proof the vehicle answered,
 * and under the contract an answer with no new values means the last values still stand. They are
 * republished with their original timestamps — those say when the readings were taken, and
 * rewriting them would be fabricating a measurement time.
 *
 * What a beat can never do is invent a connection. [live] is set only from the source's own
 * [VehicleConnectionState], and any non-live snapshot drops the retained one, so no number of beats
 * can resurrect telemetry the source has disowned.
 */
private fun Flow<VehicleSnapshot>.withStaleTimeout(
    sourceId: VehicleSourceId,
    staleTimeoutMs: Long,
    clock: Clock,
    liveness: Flow<Long>,
): Flow<VehicleSnapshot> {
    val upstream = this
    return channelFlow {
        val timeouts = Channel<Long>(Channel.UNLIMITED)
        var watchdog: Job? = null
        var generation = 0L
        var live = false
        var stale = false
        var lastLive: VehicleSnapshot? = null

        // Bumping the generation is what actually retires the old watchdog; the cancel is just an
        // optimisation that usually stops it before it sends. Every disarm must bump, including the
        // ones not followed by an arm, or a timeout already in flight would still look current.
        fun disarm() {
            watchdog?.cancel()
            watchdog = null
            generation++
        }

        fun arm(asOfMs: Long?) {
            val gen = generation
            val age = asOfMs?.let { clock.nowMs() - it } ?: 0L
            val remainingMs = (staleTimeoutMs - age).coerceIn(0L, staleTimeoutMs)
            watchdog = launch {
                delay(remainingMs)
                timeouts.send(gen)
            }
        }

        merge(
            upstream.map<VehicleSnapshot, ContactEvent> { ContactEvent.Data(it) },
            liveness.map<Long, ContactEvent> { ContactEvent.Beat(it) },
            timeouts.receiveAsFlow().map<Long, ContactEvent> { ContactEvent.Timeout(it) },
        ).collect { event ->
            when (event) {
                is ContactEvent.Data -> {
                    disarm()
                    val snapshot = event.snapshot
                    live = snapshot.connection.isLiveData
                    stale = false
                    lastLive = snapshot.takeIf { live }
                    send(snapshot)
                    if (live) arm(snapshot.state.lastUpdatedMs)
                }

                is ContactEvent.Beat -> {
                    // A beat about a connection the source does not call live is not evidence of
                    // anything the dashboard should act on.
                    if (live) {
                        disarm()
                        if (stale) {
                            stale = false
                            lastLive?.let { send(it) }
                        }
                        arm(event.atMs)
                    }
                }

                is ContactEvent.Timeout -> {
                    // Anything but the current generation lost a race it does not know it was in.
                    if (event.generation == generation) {
                        stale = true
                        send(
                            VehicleSnapshot(
                                state = VehicleState.unavailable(sourceId),
                                diagnostics = VehicleDiagnosticsState.empty(),
                                connection = VehicleConnectionState.VehicleCommunicationUnavailable(
                                    "No response from vehicle for ${staleTimeoutMs}ms",
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }
}
