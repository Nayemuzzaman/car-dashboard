package com.csjotlab.cardashboard.vehicle.data

import app.cash.turbine.test
import com.csjotlab.cardashboard.vehicle.domain.Clock
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticCode
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticIssue
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticSource
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticStatus
import com.csjotlab.cardashboard.vehicle.domain.Severity
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleDiagnosticsState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import com.csjotlab.cardashboard.vehicle.domain.allSignals
import com.csjotlab.cardashboard.vehicle.domain.isValue
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull
import com.csjotlab.cardashboard.vehicle.fakes.ControllableVehicleDataSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleStalenessTest {

    private fun liveState(speedKph: Float, atMs: Long) =
        VehicleState.unavailable(VehicleSourceId.OBD_USB)
            .copy(speedKph = Signal.Value(speedKph, atMs), lastUpdatedMs = atMs)

    private fun oneIssue(atMs: Long) = VehicleDiagnosticsState(
        issues = listOf(
            DiagnosticIssue(
                id = "dtc:P0301",
                code = DiagnosticCode.Dtc("P0301"),
                title = null,
                description = null,
                classification = null,
                severity = Severity.Warning,
                status = DiagnosticStatus.Stored,
                source = DiagnosticSource.Obd2,
                firstSeenMs = atMs,
                lastSeenMs = atMs,
            ),
        ),
        malfunctionIndicatorLampOn = Signal.Value(true, atMs),
        storedDtcCount = Signal.Value(1, atMs),
        lastScanMs = atMs,
    )

    @Test
    fun `silence for the stale timeout reports communication unavailable and clears telemetry`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val repository = VehicleRepository(
            sources = MutableStateFlow(source),
            clock = Clock { currentTime },
            scope = backgroundScope,
            staleTimeoutMs = 3_000L,
        )

        repository.snapshot.test {
            awaitItem()
            source.emitConnection(VehicleConnectionState.Reading)
            source.emitState(liveState(60f, currentTime))
            source.emitDiagnostics(oneIssue(currentTime))
            runCurrent()
            assertTrue(expectMostRecentItem().diagnostics.issues.isNotEmpty())

            advanceTimeBy(3_500L)
            runCurrent()

            val stale = expectMostRecentItem()
            assertTrue(stale.connection is VehicleConnectionState.VehicleCommunicationUnavailable)
            assertTrue(stale.state.allSignals.none { it.isValue })
            assertTrue(
                "going stale must clear the diagnostics too, not just the telemetry",
                stale.diagnostics.issues.isEmpty(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the connection is still live one millisecond before the threshold and stale just after`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val repository = VehicleRepository(
            sources = MutableStateFlow(source),
            clock = Clock { currentTime },
            scope = backgroundScope,
            staleTimeoutMs = 3_000L,
        )

        repository.snapshot.test {
            awaitItem()
            source.emitConnection(VehicleConnectionState.Reading)
            source.emitState(liveState(60f, currentTime))
            runCurrent()
            expectMostRecentItem()
            assertEquals(0L, currentTime)

            // Just below the threshold: nothing new may be published, and the live values stand.
            advanceTimeBy(2_999L)
            runCurrent()
            assertEquals(2_999L, currentTime)
            expectNoEvents()
            assertTrue(repository.snapshot.value.connection is VehicleConnectionState.Reading)
            assertEquals(60f, repository.snapshot.value.state.speedKph.valueOrNull())

            // Two more milliseconds crosses it.
            advanceTimeBy(2L)
            runCurrent()
            assertEquals(3_001L, currentTime)

            val stale = awaitItem()
            assertTrue(
                "3000ms of silence must be stale, got ${stale.connection}",
                stale.connection is VehicleConnectionState.VehicleCommunicationUnavailable,
            )
            assertTrue(stale.state.allSignals.none { it.isValue })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the default timeout is three seconds and names itself in the failure reason`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        // Three-arg constructor on purpose: this is the only test that exercises the default.
        val repository = VehicleRepository(
            MutableStateFlow(source),
            Clock { currentTime },
            backgroundScope,
        )

        repository.snapshot.test {
            awaitItem()
            source.emitConnection(VehicleConnectionState.Reading)
            source.emitState(liveState(60f, currentTime))
            runCurrent()
            expectMostRecentItem()

            advanceTimeBy(2_999L)
            runCurrent()
            expectNoEvents()
            assertTrue(repository.snapshot.value.connection is VehicleConnectionState.Reading)

            advanceTimeBy(2L)
            runCurrent()
            assertEquals(3_001L, currentTime)

            val stale = awaitItem().connection
            assertEquals(
                VehicleConnectionState.VehicleCommunicationUnavailable(
                    "No response from vehicle for 3000ms",
                ),
                stale,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `continued updates keep the connection live`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val repository = VehicleRepository(
            sources = MutableStateFlow(source),
            clock = Clock { currentTime },
            scope = backgroundScope,
            staleTimeoutMs = 3_000L,
        )

        repository.snapshot.test {
            awaitItem()
            source.emitConnection(VehicleConnectionState.Reading)
            repeat(5) { tick ->
                source.emitState(liveState(60f + tick, currentTime))
                advanceTimeBy(1_000L)
                runCurrent()
            }

            val item = expectMostRecentItem()
            assertTrue(item.connection is VehicleConnectionState.Reading)
            assertEquals(
                "staying live is only meaningful if the values are still being carried",
                64f,
                item.state.speedKph.valueOrNull(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an idling vehicle whose readings never change is not reported as unreachable`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val repository = VehicleRepository(
            sources = MutableStateFlow(source),
            clock = Clock { currentTime },
            scope = backgroundScope,
            staleTimeoutMs = 3_000L,
        )

        repository.snapshot.test {
            awaitItem()
            source.emitConnection(VehicleConnectionState.Reading)
            // Stopped at a red light: speed 0, nothing moves, not one value changes for 5 seconds.
            source.emitState(liveState(0f, currentTime))
            runCurrent()
            expectMostRecentItem()

            repeat(5) {
                advanceTimeBy(1_000L)
                runCurrent()
                // The adapter answered this poll. Nothing changed, so there is no new value to
                // publish — only proof of contact.
                source.emitLiveness(currentTime)
                runCurrent()
            }

            assertEquals(5_000L, currentTime)
            expectNoEvents()
            assertTrue(
                "a healthy link with unchanging readings must never read as communication lost",
                repository.snapshot.value.connection is VehicleConnectionState.Reading,
            )
            assertEquals(0f, repository.snapshot.value.state.speedKph.valueOrNull())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `proof of contact alone cannot outlive the stale timeout`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val repository = VehicleRepository(
            sources = MutableStateFlow(source),
            clock = Clock { currentTime },
            scope = backgroundScope,
            staleTimeoutMs = 3_000L,
        )

        repository.snapshot.test {
            awaitItem()
            source.emitConnection(VehicleConnectionState.Reading)
            source.emitState(liveState(0f, currentTime))
            runCurrent()
            expectMostRecentItem()

            advanceTimeBy(1_000L)
            runCurrent()
            source.emitLiveness(currentTime)
            runCurrent()

            // The adapter then stops answering: the beats stop with it.
            advanceTimeBy(3_001L)
            runCurrent()

            val stale = awaitItem()
            assertEquals(4_001L, currentTime)
            assertTrue(
                "the beat at 1000ms must buy exactly one more window, not immunity",
                stale.connection is VehicleConnectionState.VehicleCommunicationUnavailable,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `proof of contact after a stale transition brings the connection back`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val repository = VehicleRepository(
            sources = MutableStateFlow(source),
            clock = Clock { currentTime },
            scope = backgroundScope,
            staleTimeoutMs = 3_000L,
        )

        repository.snapshot.test {
            awaitItem()
            source.emitConnection(VehicleConnectionState.Reading)
            source.emitState(liveState(0f, currentTime))
            runCurrent()
            expectMostRecentItem()

            // A 3.1s hiccup at a red light. Nothing about the car changes while it happens.
            advanceTimeBy(3_100L)
            runCurrent()
            assertTrue(
                awaitItem().connection is VehicleConnectionState.VehicleCommunicationUnavailable,
            )

            // The adapter answers again. Nothing has changed, so there is no new value to publish —
            // only proof of contact. That must be enough to recover.
            source.emitLiveness(currentTime)
            runCurrent()

            val recovered = awaitItem()
            assertTrue(
                "a beat after a stall must restore the connection, got ${recovered.connection}",
                recovered.connection is VehicleConnectionState.Reading,
            )
            assertEquals(0f, recovered.state.speedKph.valueOrNull())

            // ...and the recovered connection behaves like any other live one: a fresh window,
            // then stale again once the beats stop.
            advanceTimeBy(2_999L)
            runCurrent()
            expectNoEvents()
            assertTrue(repository.snapshot.value.connection is VehicleConnectionState.Reading)

            advanceTimeBy(2L)
            runCurrent()
            assertTrue(
                awaitItem().connection is VehicleConnectionState.VehicleCommunicationUnavailable,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a timeout that loses the race to fresh contact is discarded`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val repository = VehicleRepository(
            sources = MutableStateFlow(source),
            clock = Clock { currentTime },
            scope = backgroundScope,
            staleTimeoutMs = 3_000L,
        )

        repository.snapshot.test {
            awaitItem()

            // Scheduled before the watchdog exists, so at t=3000 this beat is dispatched ahead of
            // the watchdog and reaches the collector first, leaving the watchdog's already-sent
            // timeout queued behind it. Cancelling the watchdog cannot retract that timeout — it is
            // in flight — so only a generation check can discard it.
            backgroundScope.launch {
                delay(3_000L)
                source.emitLiveness(3_000L)
            }

            source.emitConnection(VehicleConnectionState.Reading)
            source.emitState(liveState(0f, currentTime))
            runCurrent()
            expectMostRecentItem()

            advanceTimeBy(3_001L)
            runCurrent()

            expectNoEvents()
            assertTrue(
                "an obsolete timeout must never flash 'no response' on a link that just answered",
                repository.snapshot.value.connection is VehicleConnectionState.Reading,
            )
            assertEquals(0f, repository.snapshot.value.state.speedKph.valueOrNull())

            // The beat also has to have done its job: the window restarts from it, so the
            // connection survives to 6000ms and only then goes stale.
            advanceTimeBy(2_998L)
            runCurrent()
            assertEquals(5_999L, currentTime)
            expectNoEvents()

            advanceTimeBy(2L)
            runCurrent()
            assertTrue(
                awaitItem().connection is VehicleConnectionState.VehicleCommunicationUnavailable,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `proof of contact cannot resurrect telemetry after the source reports a disconnect`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val repository = VehicleRepository(
            sources = MutableStateFlow(source),
            clock = Clock { currentTime },
            scope = backgroundScope,
            staleTimeoutMs = 3_000L,
        )

        repository.snapshot.test {
            awaitItem()
            source.emitConnection(VehicleConnectionState.Reading)
            source.emitState(liveState(60f, currentTime))
            source.emitDiagnostics(oneIssue(currentTime))
            runCurrent()
            assertEquals(60f, expectMostRecentItem().state.speedKph.valueOrNull())

            source.emitConnection(VehicleConnectionState.ConnectionLost("cable removed"))
            runCurrent()
            expectMostRecentItem()

            // A beat is proof the adapter answered. It is not, and must never become, a claim that
            // the connection the source itself reports as lost is live again.
            source.emitLiveness(currentTime)
            advanceTimeBy(1_000L)
            runCurrent()
            source.emitLiveness(currentTime)
            runCurrent()

            // Past the stale window too: a beat must not even arm the watchdog on a dead
            // connection, or the honest ConnectionLost would be overwritten by a
            // VehicleCommunicationUnavailable that misdescribes what happened.
            advanceTimeBy(3_500L)
            runCurrent()

            expectNoEvents()
            val current = repository.snapshot.value
            assertEquals(
                VehicleConnectionState.ConnectionLost("cable removed"),
                current.connection,
            )
            assertTrue(
                "beats must never resurrect telemetry the source has disowned",
                current.state.allSignals.none { it.isValue },
            )
            assertTrue(current.diagnostics.issues.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a source that re-reports identical values with a fresh timestamp stays live`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val repository = VehicleRepository(
            sources = MutableStateFlow(source),
            clock = Clock { currentTime },
            scope = backgroundScope,
            staleTimeoutMs = 3_000L,
        )

        repository.snapshot.test {
            awaitItem()
            source.emitConnection(VehicleConnectionState.Reading)
            // Option 2 of the VehicleDataSource liveness contract: identical readings, but
            // lastUpdatedMs taken from the clock on every poll.
            repeat(5) {
                source.emitState(liveState(0f, currentTime))
                advanceTimeBy(1_000L)
                runCurrent()
            }

            assertEquals(5_000L, currentTime)
            assertTrue(
                repository.snapshot.value.connection is VehicleConnectionState.Reading,
            )
            assertEquals(0f, repository.snapshot.value.state.speedKph.valueOrNull())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `data that arrives already older than the timeout is stale immediately`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val repository = VehicleRepository(
            sources = MutableStateFlow(source),
            clock = Clock { currentTime },
            scope = backgroundScope,
            staleTimeoutMs = 3_000L,
        )

        repository.snapshot.test {
            awaitItem()
            advanceTimeBy(10_000L)
            runCurrent()
            source.emitConnection(VehicleConnectionState.Reading)
            // A replayed cache entry from 4 seconds ago: already older than the timeout, so it must
            // not buy the source a further full window.
            source.emitState(liveState(60f, currentTime - 4_000L))
            runCurrent()

            val stale = expectMostRecentItem()
            assertEquals(10_000L, currentTime)
            assertTrue(
                "already-expired data must not reset the watchdog, got ${stale.connection}",
                stale.connection is VehicleConnectionState.VehicleCommunicationUnavailable,
            )
            assertTrue(stale.state.allSignals.none { it.isValue })
            cancelAndIgnoreRemainingEvents()
        }
    }
}
