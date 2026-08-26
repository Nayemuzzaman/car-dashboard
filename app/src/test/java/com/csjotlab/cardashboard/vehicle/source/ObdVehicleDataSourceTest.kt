package com.csjotlab.cardashboard.vehicle.source

import app.cash.turbine.test
import com.csjotlab.cardashboard.vehicle.domain.Clock
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticCode
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticSource
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticStatus
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import com.csjotlab.cardashboard.vehicle.domain.allSignals
import com.csjotlab.cardashboard.vehicle.domain.isValue
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull
import com.csjotlab.cardashboard.vehicle.data.VehicleLog
import com.csjotlab.cardashboard.vehicle.fakes.FakeVehicleTransport
import com.csjotlab.cardashboard.vehicle.transport.VehicleTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * Every test drives a [FakeVehicleTransport]; nothing here touches USB.
 *
 * Virtual-time discipline: `advanceUntilIdle()` is never used. The source's polling loops never go
 * idle — `advanceUntilIdle()` would either spin forever or fast-forward through a pending `delay`
 * and fire it early, which is how this project has previously made timing tests pass for the wrong
 * reason. Every threshold below is crossed with an explicit [advanceTimeBy], and [runCurrent] is
 * used to flush work already due.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObdVehicleDataSourceTest {

    private val dispatcher = StandardTestDispatcher()
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Bank 0x00 advertises MONITOR_STATUS (0x01), COOLANT (0x05), RPM (0x0C) and SPEED (0x0D), and
     * its last byte (0x10) leaves bit 0 clear: the vehicle states that PID 0x20 — "PIDs 21-40
     * supported" — does not exist, so the capability walk terminates *conclusively* rather than
     * failing. Everything above 0x20 is therefore genuinely unsupported, not undiscovered.
     */
    private val handshake = mapOf(
        "ATZ" to "ELM327 v1.5", "ATE0" to "OK", "ATL0" to "OK", "ATS0" to "OK",
        "ATH0" to "OK", "ATSP0" to "OK", "ATI" to "ELM327 v1.5",
        "0100" to "41 00 BE 1F A8 10",
    )

    /** Explicit zero-code frames are completed reads; the fake's default NO DATA is a failed read. */
    private val completedEmptyDtcServices = mapOf(
        "03" to "43 00",
        "07" to "47 00",
        "0A" to "4A 00",
    )

    private fun transport(extra: Map<String, String>) = FakeVehicleTransport(handshake + extra)

    private fun source(
        transport: VehicleTransport,
        clock: Clock = Clock { 1_000L },
        fastIntervalMs: Long = 500L,
        slowIntervalMs: Long = 5_000L,
        diagnosticsIntervalMs: Long = 15_000L,
        reconnectDelayMs: Long = 2_000L,
        maxUnansweredExchanges: Int = 3,
        logThrottleMs: Long = 10_000L,
        dispatcher: CoroutineDispatcher = this.dispatcher,
    ) = ObdVehicleDataSource(
        transport = transport,
        clock = clock,
        fastIntervalMs = fastIntervalMs,
        slowIntervalMs = slowIntervalMs,
        diagnosticsIntervalMs = diagnosticsIntervalMs,
        reconnectDelayMs = reconnectDelayMs,
        maxUnansweredExchanges = maxUnansweredExchanges,
        dispatcher = dispatcher,
        vehicleLog = VehicleLog(
            clock = clock,
            eventWindowMs = logThrottleMs,
            telemetryWindowMs = logThrottleMs,
            sink = { tag, message -> logs += "[$tag] $message" },
        ),
    )

    private fun source(extra: Map<String, String>) = source(transport(extra))

    /**
     * The source owns a scope that outlives the test body, so every test must stop it: a live
     * polling loop left behind would keep the test scheduler busy forever.
     */
    private suspend fun <T> ObdVehicleDataSource.running(block: suspend () -> T): T {
        start().activate()
        try {
            return block()
        } finally {
            stop()
        }
    }

    private fun TestScope.collectBeats(source: ObdVehicleDataSource): Pair<List<Long>, Job> {
        val beats = mutableListOf<Long>()
        val job = backgroundScope.launch { source.liveness.collect { beats += it } }
        runCurrent()
        return beats to job
    }

    /**
     * Records everything a flow emits.
     *
     * Turbine's `expectMostRecentItem()` is unusable for "this did not change" assertions: the
     * state flows conflate an unchanged value away, so the correct behaviour produces *no* new item
     * and the assertion helper throws "No item was found" instead of handing back the value that
     * still stands. Keeping the whole history and asserting on [List.last] says what is meant.
     */
    private fun <T> TestScope.collectInto(flow: Flow<T>): Pair<List<T>, Job> {
        val seen = mutableListOf<T>()
        val job = backgroundScope.launch { flow.collect { seen += it } }
        runCurrent()
        return seen to job
    }

    // -- capability discovery is authoritative -------------------------------------------------

    @Test
    fun `supported PIDs become values`() = runTest(dispatcher) {
        val source = source(
            mapOf("010D" to "41 0D 3C", "010C" to "41 0C 1A F8", "0105" to "41 05 83"),
        )
        source.running {
            source.vehicleState.test {
                var state = awaitItem()
                while (!state.hasFastAndSlowReadings()) state = awaitItem()

                assertEquals(60f, state.speedKph.valueOrNull())
                assertEquals(1726, state.engineRpm.valueOrNull())
                assertEquals(91, state.coolantTemperatureCelsius.valueOrNull())
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    private fun VehicleState.hasFastAndSlowReadings(): Boolean =
        speedKph.valueOrNull() != null &&
            engineRpm.valueOrNull() != null &&
            coolantTemperatureCelsius.valueOrNull() != null

    @Test
    fun `PIDs absent from the capability bitmask are Unsupported not Unknown`() = runTest(dispatcher) {
        val source = source(mapOf("010D" to "41 0D 3C"))
        source.running {
            source.vehicleState.test {
                var state = awaitItem()
                while (state.speedKph.valueOrNull() == null) state = awaitItem()

                // A6 is not in the bitmask, so the odometer is a capability fact, not a pending read.
                assertEquals(Signal.Unsupported, state.odometerKm)
                assertEquals(Signal.Unsupported, state.fuelLevelPercent)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `an unsupported PID is never requested`() = runTest(dispatcher) {
        val transport = transport(mapOf("010D" to "41 0D 3C"))
        source(transport).running {
            advanceTimeBy(2_000L)
            runCurrent()
        }
        assertTrue("0x2F is unsupported here", transport.written.none { it == "012F" })
        assertTrue("0xA6 is unsupported here", transport.written.none { it == "01A6" })
    }

    @Test
    fun `fields with no generic PID at all are always Unsupported`() = runTest(dispatcher) {
        val source = source(mapOf("010D" to "41 0D 3C"))
        source.running {
            source.vehicleState.test {
                var state = awaitItem()
                while (state.speedKph.valueOrNull() == null) state = awaitItem()

                assertEquals(Signal.Unsupported, state.gear)
                assertEquals(Signal.Unsupported, state.tirePressuresKpa)
                assertEquals(Signal.Unsupported, state.doors)
                assertEquals(Signal.Unsupported, state.seatbelts)
                assertEquals(Signal.Unsupported, state.estimatedRangeKm)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    /**
     * PID 0x31 is "distance travelled since diagnostic codes were cleared". It is not trip
     * distance, and the global constraints forbid substituting it. Nothing may read it, so nothing
     * requests it.
     */
    @Test
    fun `trip distance is never substituted from the distance since codes cleared PID`() =
        runTest(dispatcher) {
            val transport = FakeVehicleTransport(
                handshake + mapOf(
                    // This bitmask advertises every PID in bank 0x00 including 0x20, and bank 0x20
                    // advertises 0x31, so the substitution would be available if it were allowed.
                    "0100" to "41 00 FF FF FF FF",
                    "0120" to "41 20 FF FF FF FF",
                    "0140" to "41 40 00 00 00 00",
                    "0131" to "41 31 00 7B",
                    "010D" to "41 0D 3C",
                ),
            )
            val source = source(transport)
            source.running {
                source.vehicleState.test {
                    var state = awaitItem()
                    while (state.speedKph.valueOrNull() == null) state = awaitItem()

                    assertEquals(Signal.Unsupported, state.tripDistanceKm)
                    cancelAndIgnoreRemainingEvents()
                }
                advanceTimeBy(11_000L)
                runCurrent()
            }
            assertTrue(
                "PID 31 must never reach the wire: nothing consumes it",
                transport.written.none { it == "0131" },
            )
        }

    @Test
    fun `a source with no capability at all reports no values whatsoever`() = runTest(dispatcher) {
        val source = source(mapOf("0100" to "41 00 00 00 00 00"))
        source.running {
            source.vehicleState.test {
                advanceTimeBy(2_000L)
                runCurrent()
                val state = expectMostRecentItem()
                assertTrue(
                    "capability discovery is authoritative — nothing may be inferred",
                    state.allSignals.none { it.isValue },
                )
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    /**
     * Discovery failure is not capability. One garbled bitmask must never mark a field
     * `Unsupported`, which is a permanent claim about the source.
     */
    @Test
    fun `capability discovery failure leaves every field Unknown never Unsupported`() =
        runTest(dispatcher) {
            val source = source(mapOf("0100" to "STOPPED"))
            source.running {
                source.vehicleState.test {
                    advanceTimeBy(2_000L)
                    runCurrent()
                    val state = expectMostRecentItem()
                    assertTrue(state.allSignals.none { it.isValue })
                    assertTrue(
                        "an undecodable bitmask is not a capability statement",
                        state.allSignals.none { it == Signal.Unsupported },
                    )
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

    /**
     * `Ready.undiscoveredBanks` gates the "unsupported" conclusion: bank 0x00 advertises bank 0x20,
     * bank 0x20 never decodes, so nothing is known about 0x20 or any bank after it.
     */
    @Test
    fun `PIDs in an undiscovered bank are Unknown never Unsupported`() = runTest(dispatcher) {
        val transport = FakeVehicleTransport(
            handshake + mapOf(
                // Last byte 0x11: PID 0x1C and PID 0x20 ("PIDs 21-40 supported") are advertised.
                "0100" to "41 00 BE 1F A8 11",
                "0120" to "STOPPED",
                "010D" to "41 0D 3C",
            ),
        )
        val source = source(transport)
        source.running {
            source.vehicleState.test {
                var state = awaitItem()
                while (state.speedKph.valueOrNull() == null) state = awaitItem()

                assertEquals("bank 0x20 never decoded", Signal.Unknown, state.fuelLevelPercent)
                assertEquals("bank 0xA0 was never reachable", Signal.Unknown, state.odometerKm)
                assertEquals("bank 0x00 decoded and 0x0D is in it", 60f, state.speedKph.valueOrNull())
                cancelAndIgnoreRemainingEvents()
            }
            advanceTimeBy(11_000L)
            runCurrent()
        }
        assertTrue(
            "an undiscovered PID is not polled — a value would not be licensed by discovery",
            transport.written.none { it == "012F" || it == "01A6" },
        )
    }

    // -- connection outcomes -------------------------------------------------------------------

    @Test
    fun `handshake failure reports communication unavailable and no values`() = runTest(dispatcher) {
        val source = source(mapOf("0100" to "UNABLE TO CONNECT"))
        source.running {
            source.connectionState.test {
                var state = awaitItem()
                while (state !is VehicleConnectionState.VehicleCommunicationUnavailable) state = awaitItem()
                cancelAndIgnoreRemainingEvents()
            }
            source.vehicleState.test {
                assertTrue(awaitItem().allSignals.none { it.isValue })
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `a non ELM device reports unsupported device`() = runTest(dispatcher) {
        val source = source(mapOf("ATI" to "SOME USB WIDGET"))
        source.running {
            source.connectionState.test {
                var state = awaitItem()
                while (state !is VehicleConnectionState.UnsupportedDevice) state = awaitItem()
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `a detached transport reports connection lost and clears telemetry`() = runTest(dispatcher) {
        val transport = transport(mapOf("010D" to "41 0D 3C"))
        val source = source(transport)
        source.running {
            source.connectionState.test {
                var state = awaitItem()
                while (state !is VehicleConnectionState.Reading) state = awaitItem()

                transport.detach()
                while (state !is VehicleConnectionState.ConnectionLost) state = awaitItem()
                assertEquals("USB device detached", state.reason)
                cancelAndIgnoreRemainingEvents()
            }
            source.vehicleState.test {
                assertTrue(awaitItem().allSignals.none { it.isValue })
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `a failed transport reports an error`() = runTest(dispatcher) {
        val transport = transport(mapOf("010D" to "41 0D 3C"))
        val source = source(transport)
        source.running {
            source.connectionState.test {
                var state = awaitItem()
                while (state !is VehicleConnectionState.Reading) state = awaitItem()

                transport.fail("the USB endpoint stopped responding")
                while (state !is VehicleConnectionState.Error) state = awaitItem()
                assertEquals("the USB endpoint stopped responding", state.reason)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    // -- recovery ------------------------------------------------------------------------------

    /**
     * An `Elm327Session` whose `unacknowledgedWrites` over-counts can never recover: every later
     * exchange skips a reply that will not come. So a run of timeouts must be treated as a lost
     * connection and answered with a *fresh* session, not as "the vehicle is quiet".
     *
     * The proof that the session is fresh is that polling resumes at all: with the poisoned session
     * reused, the three owed replies would swallow the next three exchanges — including `ATZ` — and
     * the re-handshake would report the adapter as unreachable forever.
     */
    @Test
    fun `a run of timeouts is reported as connection lost and answered with a fresh session`() =
        runTest(dispatcher) {
            val transport = transport(mapOf("010D" to "41 0D 3C", "010C" to "41 0C 1A F8"))
            val source = source(
                transport,
                slowIntervalMs = 600_000L,
                diagnosticsIntervalMs = 600_000L,
                reconnectDelayMs = 2_000L,
            )
            source.running {
                source.connectionState.test {
                    var state = awaitItem()
                    while (state !is VehicleConnectionState.Reading) state = awaitItem()

                    transport.silence("010D")
                    transport.silence("010C")
                    while (state !is VehicleConnectionState.ConnectionLost) state = awaitItem()

                    transport.respondTo("010D", "41 0D 50")
                    transport.respondTo("010C", "41 0C 1A F8")
                    while (state !is VehicleConnectionState.Reading) state = awaitItem()
                    cancelAndIgnoreRemainingEvents()
                }

                source.vehicleState.test {
                    var state = awaitItem()
                    while (state.speedKph.valueOrNull() != 80f) state = awaitItem()
                    assertEquals(80f, state.speedKph.valueOrNull())
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

    @Test
    fun `a thrown write reports connection lost and recovers with a fresh session`() =
        runTest(dispatcher) {
            val delegate = transport(mapOf("010D" to "41 0D 3C", "010C" to "41 0C 1A F8"))
            val transport = ThrowOnceOnCommandTransport(delegate, "010D")
            val source = source(
                transport,
                slowIntervalMs = 600_000L,
                diagnosticsIntervalMs = 600_000L,
                reconnectDelayMs = 1_000L,
            )
            val (connections, connectionJob) = collectInto(source.connectionState)
            val (states, stateJob) = collectInto(source.vehicleState)

            source.running {
                advanceTimeBy(1_500L)
                runCurrent()

                assertTrue(
                    "a write exception must become an observable lost connection",
                    connections.any {
                        it is VehicleConnectionState.ConnectionLost && it.reason.contains("write failed")
                    },
                )
                assertEquals(VehicleConnectionState.Reading, connections.last())
                assertEquals(
                    "the retry must use a usable fresh session rather than the abandoned one",
                    60f,
                    states.last().speedKph.valueOrNull(),
                )
            }
            connectionJob.cancel()
            stateJob.cancel()
        }

    private class ThrowOnceOnCommandTransport(
        private val delegate: VehicleTransport,
        private val command: String,
    ) : VehicleTransport by delegate {
        private var thrown = false

        override suspend fun write(bytes: ByteArray) {
            val written = String(bytes, Charsets.ISO_8859_1).trimEnd('\r')
            if (!thrown && written == command) {
                thrown = true
                throw IOException("write failed before the transport emitted a failure event")
            }
            delegate.write(bytes)
        }
    }

    @Test
    fun `an incoming flow exception clears telemetry and recovers with a fresh session without a failure beat`() =
        runTest(dispatcher) {
            val delegate = transport(mapOf("010D" to "41 0D 3C", "010C" to "NO DATA"))
            val transport = FailIncomingOnceOnCommandTransport(delegate, "010D", completeInstead = false)
            val source = source(
                transport,
                clock = Clock { currentTime },
                slowIntervalMs = 600_000L,
                diagnosticsIntervalMs = 600_000L,
                reconnectDelayMs = 1_000L,
            )
            val (connections, connectionJob) = collectInto(source.connectionState)
            val (beats, beatJob) = collectBeats(source)

            source.running {
                advanceTimeBy(1L)
                runCurrent()
                assertTrue(connections.any { it is VehicleConnectionState.ConnectionLost })
                assertTrue(source.vehicleState.first().allSignals.none { it.isValue })
                assertEquals("the capability reply beats; the failed incoming reply does not", 1, beats.size)

                advanceTimeBy(1_000L)
                runCurrent()
                assertTrue("recovery must construct and handshake a fresh session", delegate.written.count { it == "ATZ" } >= 2)
                assertEquals(60f, source.vehicleState.first().speedKph.valueOrNull())
            }
            connectionJob.cancel()
            beatJob.cancel()
        }

    @Test
    fun `an ended incoming stream reconnects instead of looping on adapter errors`() = runTest(dispatcher) {
        val delegate = transport(mapOf("010D" to "41 0D 3C", "010C" to "NO DATA"))
        val transport = FailIncomingOnceOnCommandTransport(delegate, "010D", completeInstead = true)
        val source = source(
            transport,
            slowIntervalMs = 600_000L,
            diagnosticsIntervalMs = 600_000L,
            reconnectDelayMs = 1_000L,
        )
        val (connections, job) = collectInto(source.connectionState)

        source.running {
            advanceTimeBy(1L)
            runCurrent()
            assertTrue("a provably ended byte stream is transport loss", connections.any { it is VehicleConnectionState.ConnectionLost })
            advanceTimeBy(1_000L)
            runCurrent()
            assertTrue(delegate.written.count { it == "ATZ" } >= 2)
            assertEquals(VehicleConnectionState.Reading, source.connectionState.first())
            assertEquals(60f, source.vehicleState.first().speedKph.valueOrNull())
        }
        job.cancel()
    }

    private class FailIncomingOnceOnCommandTransport(
        private val delegate: VehicleTransport,
        private val command: String,
        private val completeInstead: Boolean,
    ) : VehicleTransport by delegate {
        private var currentCommand = ""
        private var failed = false

        override suspend fun write(bytes: ByteArray) {
            currentCommand = String(bytes, Charsets.ISO_8859_1).trimEnd('\r')
            delegate.write(bytes)
        }

        override fun incoming(): Flow<ByteArray> {
            val source = delegate.incoming()
            return if (completeInstead) {
                source.takeWhile {
                    if (!failed && currentCommand == command) {
                        failed = true
                        false
                    } else {
                        true
                    }
                }
            } else {
                source.map { bytes ->
                    if (!failed && currentCommand == command) {
                        failed = true
                        throw IOException("incoming stream failed")
                    }
                    bytes
                }
            }
        }
    }

    /**
     * A car storing several faults answers every DTC service with an ISO-TP multi-frame reply that
     * no frame parser accepts. That is an adapter-level parse failure, not a dead link: the adapter
     * is answering, and re-handshaking cannot help. Counting it as evidence of a lost connection
     * would make such a car reconnect on every scan and never poll anything.
     */
    @Test
    fun `unparseable DTC replies are not mistaken for a lost connection`() = runTest(dispatcher) {
        val multiFrame = "010A\r0: 43 04 03 01 03 02\r1: 03 03 03 04 00 00"
        val transport = transport(
            mapOf(
                "010D" to "41 0D 3C",
                "010C" to "41 0C 1A F8",
                "0101" to "41 01 83 00 00 00",
                "03" to multiFrame,
                "07" to multiFrame,
                "0A" to multiFrame,
            ),
        )
        val source = source(transport, diagnosticsIntervalMs = 1_000L)
        val (seen, job) = collectInto(source.connectionState)
        source.running {
            advanceTimeBy(6_000L)
            runCurrent()
            assertEquals(VehicleConnectionState.Reading, seen.last())
            assertTrue(
                "the adapter answered every time; nothing was lost",
                seen.none { it is VehicleConnectionState.ConnectionLost },
            )
        }
        job.cancel()
    }

    @Test
    fun `a received adapter error breaks a consecutive timeout run without reconnecting`() =
        runTest(dispatcher) {
            val transport = transport(emptyMap())
            // SPEED arrives just after its budget. RPM then receives and discards that stale reply
            // before receiving its own explicit adapter error. Each cycle is therefore one Timeout
            // followed by one received AdapterError, never two consecutive timeouts.
            transport.respondAfterDelay("010D", "41 0D 3C", delayMs = 1_100L, scope = backgroundScope)
            transport.respondAfterDelay("010C", "STOPPED", delayMs = 300L, scope = backgroundScope)
            val source = source(
                transport,
                slowIntervalMs = 600_000L,
                diagnosticsIntervalMs = 600_000L,
                reconnectDelayMs = 600_000L,
                maxUnansweredExchanges = 2,
            )
            val (seen, job) = collectInto(source.connectionState)

            source.running {
                advanceTimeBy(5_000L)
                runCurrent()

                assertEquals(VehicleConnectionState.Reading, seen.last())
                assertTrue(
                    "received adapter errors separate the timeouts, so the session is not poisoned",
                    seen.none { it is VehicleConnectionState.ConnectionLost },
                )
            }
            job.cancel()
        }

    @Test
    fun `telemetry is cleared when the connection is lost`() = runTest(dispatcher) {
        val transport = transport(mapOf("010D" to "41 0D 3C", "010C" to "41 0C 1A F8"))
        val source = source(
            transport,
            slowIntervalMs = 600_000L,
            diagnosticsIntervalMs = 600_000L,
            reconnectDelayMs = 600_000L,
        )
        source.running {
            source.vehicleState.test {
                var state = awaitItem()
                while (state.speedKph.valueOrNull() == null) state = awaitItem()

                transport.goSilent()
                while (state.speedKph.valueOrNull() != null) state = awaitItem()
                assertTrue(
                    "a failing real source clears telemetry — it is never left showing the last value",
                    state.allSignals.none { it.isValue },
                )
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    /** A short-frame reply decodes to null; that must leave the field alone, never write a zero. */
    @Test
    fun `a malformed reply leaves the previous reading untouched`() = runTest(dispatcher) {
        val transport = transport(mapOf("010D" to "41 0D 3C", "010C" to "41 0C 1A F8"))
        val source = source(transport, slowIntervalMs = 600_000L, diagnosticsIntervalMs = 600_000L)
        val (seen, job) = collectInto(source.vehicleState)
        source.running {
            runCurrent()
            assertEquals(1726, seen.last().engineRpm.valueOrNull())

            // One data byte where the RPM decoder needs two: null, not 0 rpm.
            transport.respondTo("010C", "41 0C 1A")
            advanceTimeBy(2_000L)
            runCurrent()

            assertEquals(
                "a short frame is not a reading; the previous one stands and no zero is written",
                1726,
                seen.last().engineRpm.valueOrNull(),
            )
        }
        job.cancel()
    }

    // -- diagnostics ---------------------------------------------------------------------------

    @Test
    fun `stored DTCs become diagnostic issues with structural classification only`() =
        runTest(dispatcher) {
            val source = source(
                completedEmptyDtcServices +
                    mapOf("010D" to "41 0D 3C", "0101" to "41 01 83 00 00 00", "03" to "43 01 03 01"),
            )
            source.running {
                source.diagnostics.test {
                    var diagnostics = awaitItem()
                    while (diagnostics.issues.isEmpty()) diagnostics = awaitItem()

                    val issue = diagnostics.issues.first()
                    assertEquals("P0301", (issue.code as DiagnosticCode.Dtc).code)
                    assertEquals(null, issue.title)
                    assertEquals(null, issue.description)
                    assertEquals("Ignition system or misfire", issue.classification?.subsystem)
                    assertEquals(DiagnosticStatus.Stored, issue.status)
                    assertEquals(DiagnosticSource.Obd2, issue.source)
                    assertNotNull(diagnostics.lastScanMs)
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

    @Test
    fun `completed rescans retain first seen advance last seen and keep strongest status across codes`() =
        runTest(dispatcher) {
            var nowMs = 100L
            val transport = transport(
                mapOf(
                    "010D" to "41 0D 3C",
                    "0101" to "41 01 83 00 00 00",
                    "03" to "43 02 03 01 04 20",
                    "07" to "47 01 03 01",
                    "0A" to "4A 01 03 01",
                ),
            )
            val source = source(
                transport,
                clock = Clock { nowMs },
                diagnosticsIntervalMs = 1_000L,
            )
            val (seen, job) = collectInto(source.diagnostics)

            source.running {
                runCurrent()
                val initial = seen.last().issues.associateBy { (it.code as DiagnosticCode.Dtc).code }
                assertEquals(setOf("P0301", "P0420"), initial.keys)
                assertEquals(DiagnosticStatus.Permanent, initial.getValue("P0301").status)
                assertEquals(DiagnosticStatus.Stored, initial.getValue("P0420").status)
                assertEquals(100L, initial.getValue("P0301").firstSeenMs)

                nowMs = 500L
                advanceTimeBy(1_000L)
                runCurrent()
                val rescanned = seen.last().issues.associateBy { (it.code as DiagnosticCode.Dtc).code }
                assertEquals(100L, rescanned.getValue("P0301").firstSeenMs)
                assertEquals(500L, rescanned.getValue("P0301").lastSeenMs)
            }
            job.cancel()
        }

    @Test
    fun `the monitor status PID drives the malfunction indicator lamp`() = runTest(dispatcher) {
        val source = source(mapOf("010D" to "41 0D 3C", "0101" to "41 01 83 00 00 00"))
        source.running {
            source.diagnostics.test {
                var diagnostics = awaitItem()
                while (diagnostics.malfunctionIndicatorLampOn.valueOrNull() == null) diagnostics = awaitItem()

                assertEquals(true, diagnostics.malfunctionIndicatorLampOn.valueOrNull())
                assertEquals(3, diagnostics.storedDtcCount.valueOrNull())
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `a code missing from a completed rescan is retired`() = runTest(dispatcher) {
        val transport = transport(
            completedEmptyDtcServices +
                mapOf("010D" to "41 0D 3C", "0101" to "41 01 83 00 00 00", "03" to "43 01 03 01"),
        )
        val source = source(transport, diagnosticsIntervalMs = 1_000L)
        source.running {
            source.diagnostics.test {
                var diagnostics = awaitItem()
                while (diagnostics.issues.isEmpty()) diagnostics = awaitItem()

                transport.respondTo("03", "43 00")
                advanceTimeBy(2_000L)
                runCurrent()

                assertTrue(expectMostRecentItem().issues.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `a failed rescan does not retire existing issues`() = runTest(dispatcher) {
        val transport = transport(
            completedEmptyDtcServices +
                mapOf("010D" to "41 0D 3C", "0101" to "41 01 83 00 00 00", "03" to "43 01 03 01"),
        )
        val source = source(transport, diagnosticsIntervalMs = 1_000L)
        val (seen, job) = collectInto(source.diagnostics)
        source.running {
            runCurrent()
            assertTrue(seen.last().issues.isNotEmpty())

            transport.respondTo("03", "CAN ERROR")
            advanceTimeBy(2_000L)
            runCurrent()

            assertTrue(
                "a failed scan is not evidence that a fault cleared",
                seen.last().issues.isNotEmpty(),
            )
        }
        job.cancel()
    }

    /**
     * A bus that has gone quiet answers every DTC service with NO DATA. That is not a completed
     * scan finding nothing — no responder was heard at all — so the fault list must survive it.
     */
    @Test
    fun `a scan in which nothing answered does not retire existing issues`() = runTest(dispatcher) {
        val transport = transport(
            completedEmptyDtcServices +
                mapOf("010D" to "41 0D 3C", "0101" to "41 01 83 00 00 00", "03" to "43 01 03 01"),
        )
        val source = source(transport, diagnosticsIntervalMs = 1_000L)
        val (seen, job) = collectInto(source.diagnostics)
        source.running {
            runCurrent()
            assertTrue(seen.last().issues.isNotEmpty())

            transport.respondTo("03", "NO DATA")
            transport.respondTo("07", "NO DATA")
            transport.respondTo("0A", "NO DATA")
            advanceTimeBy(2_000L)
            runCurrent()

            assertTrue(
                "no responder was heard, so this is not a completed scan that found nothing",
                seen.last().issues.isNotEmpty(),
            )
        }
        job.cancel()
    }

    @Test
    fun `one failed DTC service retains issues even when the other services completed`() =
        runTest(dispatcher) {
            val transport = transport(
                mapOf(
                    "010D" to "41 0D 3C",
                    "0101" to "41 01 81 00 00 00",
                    "03" to "43 00",
                    "07" to "47 01 04 20",
                    "0A" to "4A 00",
                ),
            )
            val source = source(transport, diagnosticsIntervalMs = 1_000L)
            val (seen, job) = collectInto(source.diagnostics)

            source.running {
                runCurrent()
                assertEquals("P0420", (seen.last().issues.single().code as DiagnosticCode.Dtc).code)

                // Task 16 deliberately maps NO DATA to Failed: it is not a completed scan finding
                // zero codes. The successful stored/permanent reads must not license reconciliation.
                transport.respondTo("07", "NO DATA")
                advanceTimeBy(2_000L)
                runCurrent()

                assertEquals(
                    "a failed pending-code read is not evidence that P0420 was retired",
                    listOf("P0420"),
                    seen.last().issues.map { (it.code as DiagnosticCode.Dtc).code },
                )
            }
            job.cancel()
        }

    /** Handoff item 8: a multi-frame reply is not parseable, and must fail rather than half-read. */
    @Test
    fun `a multi frame DTC reply is a failed scan not a partial one`() = runTest(dispatcher) {
        val transport = transport(
            completedEmptyDtcServices + mapOf(
                "010D" to "41 0D 3C",
                "0101" to "41 01 83 00 00 00",
                "03" to "43 01 03 01",
            ),
        )
        val source = source(transport, diagnosticsIntervalMs = 1_000L)
        val (seen, job) = collectInto(source.diagnostics)
        source.running {
            runCurrent()
            assertTrue(seen.last().issues.isNotEmpty())

            // What an ELM327 prints for an ISO-TP multi-frame mode 03 reply.
            transport.respondTo("03", "010A\r0: 43 04 03 01 03 02\r1: 03 03 03 04 00 00")
            advanceTimeBy(2_000L)
            runCurrent()

            val issues = seen.last().issues
            assertEquals("the original code is retained, not replaced by a partial read", 1, issues.size)
            assertEquals("P0301", (issues.first().code as DiagnosticCode.Dtc).code)
        }
        job.cancel()
    }

    // -- liveness ------------------------------------------------------------------------------

    @Test
    fun `liveness beats carry the shared clock and only follow a received response`() =
        runTest(dispatcher) {
            val transport = transport(mapOf("010D" to "41 0D 3C", "010C" to "41 0C 1A F8"))
            val source = source(
                transport,
                clock = Clock { currentTime },
                slowIntervalMs = 600_000L,
                diagnosticsIntervalMs = 600_000L,
            )
            val (beats, job) = collectBeats(source)
            source.running {
                advanceTimeBy(1_200L)
                runCurrent()

                // Capability bank 0x00 answers once, then SPEED and RPM each answer at 0, 500 and
                // 1000 ms. AT acknowledgements are not vehicle responses and never beat.
                assertEquals(
                    "beats are stamped from the shared clock, not the adapter or nanoTime",
                    listOf(0L, 500L, 1_000L),
                    beats.distinct(),
                )
                assertEquals("one beat per response received, not one per poll", 7, beats.size)
            }
            job.cancel()
        }

    @Test
    fun `every successfully decoded capability bank emits exactly one beat`() = runTest(dispatcher) {
        val transport = FakeVehicleTransport(
            handshake.minus("0100") +
                mapOf(
                    "0100" to "41 00 00 00 00 01",
                    "0120" to "41 20 00 00 00 00",
                ),
        )
        val source = source(
            transport,
            clock = Clock { currentTime },
            fastIntervalMs = 600_000L,
            slowIntervalMs = 600_000L,
            diagnosticsIntervalMs = 600_000L,
        )
        val (beats, job) = collectBeats(source)

        source.running { runCurrent() }

        assertEquals("one beat per decoded bank and none for AT acknowledgements", listOf(0L, 0L), beats)
        job.cancel()
    }

    @Test
    fun `liveness never beats on a timeout`() = runTest(dispatcher) {
        val transport = transport(mapOf("010D" to "41 0D 3C", "010C" to "41 0C 1A F8"))
        val source = source(
            transport,
            clock = Clock { currentTime },
            slowIntervalMs = 600_000L,
            diagnosticsIntervalMs = 600_000L,
            reconnectDelayMs = 600_000L,
        )
        val (beats, job) = collectBeats(source)
        source.running {
            advanceTimeBy(600L)
            runCurrent()
            assertTrue(beats.isNotEmpty())

            transport.goSilent()
            advanceTimeBy(1_000L)
            runCurrent()
            val afterSilence = beats.size

            advanceTimeBy(20_000L)
            runCurrent()
            assertEquals(
                "a timeout, a retry and a keep-alive are not evidence that the vehicle answered",
                afterSilence,
                beats.size,
            )
        }
        job.cancel()
    }

    @Test
    fun `NO DATA causes no liveness beat`() = assertNoErrorBeat("NO DATA")

    @Test
    fun `bus error causes no liveness beat`() = assertNoErrorBeat("CAN ERROR")

    @Test
    fun `adapter error causes no liveness beat`() = assertNoErrorBeat("?")

    private fun assertNoErrorBeat(reply: String) = runTest(dispatcher) {
        val source = source(
            transport(mapOf("010D" to reply, "010C" to reply)),
            clock = Clock { currentTime },
            slowIntervalMs = 600_000L,
            diagnosticsIntervalMs = 600_000L,
            reconnectDelayMs = 600_000L,
        )
        val (beats, job) = collectBeats(source)

        source.running {
            advanceTimeBy(1L)
            runCurrent()
            assertEquals("only the successful capability bank may beat", 1, beats.size)
        }
        job.cancel()
    }

    @Test
    fun `a received but decoder-short data reply still proves contact exactly once`() = runTest(dispatcher) {
        val source = source(
            transport(mapOf("010D" to "NO DATA", "010C" to "41 0C 1A")),
            clock = Clock { currentTime },
            slowIntervalMs = 600_000L,
            diagnosticsIntervalMs = 600_000L,
        )
        val (beats, job) = collectBeats(source)

        source.running {
            advanceTimeBy(1L)
            runCurrent()
            assertEquals("one capability reply plus one framed PID reply", 2, beats.size)
            assertTrue(source.vehicleState.first().engineRpm is Signal.Unknown)
        }
        job.cancel()
    }

    @Test
    fun `liveness never beats before start or after stop`() = runTest(dispatcher) {
        val transport = transport(mapOf("010D" to "41 0D 3C", "010C" to "41 0C 1A F8"))
        val source = source(transport, clock = Clock { currentTime })
        val (beats, job) = collectBeats(source)

        advanceTimeBy(5_000L)
        runCurrent()
        assertTrue("nothing can have answered before start()", beats.isEmpty())

        source.start().activate()
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(beats.isNotEmpty())

        source.stop()
        val afterStop = beats.size
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals("a beat after stop() describes a contact that could not have happened", afterStop, beats.size)
        job.cancel()
    }

    @Test
    fun `an eager dispatcher cannot expose a beat before start returns`() = runTest(dispatcher) {
        val eager = UnconfinedTestDispatcher(testScheduler)
        val source = source(
            transport(mapOf("010D" to "41 0D 3C")),
            clock = Clock { currentTime },
            slowIntervalMs = 600_000L,
            diagnosticsIntervalMs = 600_000L,
            dispatcher = eager,
        )
        var startReturned = false
        val observations = mutableListOf<Boolean>()
        val job = backgroundScope.launch(eager) {
            source.liveness.collect { observations += startReturned }
        }

        val activation = source.start()
        startReturned = true
        activation.activate()
        runCurrent()

        try {
            assertTrue("the fixture must receive at least one vehicle response", observations.isNotEmpty())
            assertTrue(
                "activation cannot expose a response until start() has returned",
                observations.all { it },
            )
        } finally {
            source.stop()
            job.cancel()
        }
    }

    @Test
    fun `a stalled caller cannot schedule transport work before activating the returned handle`() =
        runTest(dispatcher) {
            val concurrent = RecordingConcurrentDispatcher()
            val source = source(
                transport(mapOf("010D" to "41 0D 3C")),
                slowIntervalMs = 600_000L,
                diagnosticsIntervalMs = 600_000L,
                dispatcher = concurrent,
            )
            val startReturned = CompletableDeferred<Unit>()
            val allowActivation = CountDownLatch(1)
            val firstBeat = CompletableDeferred<Long>()
            val beatJob = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                firstBeat.complete(source.liveness.first())
            }
            val caller = backgroundScope.async(Dispatchers.Default) {
                val activation = source.start()
                startReturned.complete(Unit)
                allowActivation.await()
                activation.activate()
            }

            try {
                startReturned.await()
                assertEquals(
                    "start prepares only; a concurrently scheduled worker cannot exist yet",
                    0,
                    concurrent.dispatchCount.get(),
                )
                assertFalse("no response can beat while the caller is stalled before activation", firstBeat.isCompleted)

                allowActivation.countDown()
                caller.await()
                withContext(Dispatchers.Default) {
                    withTimeout(5_000L) {
                        source.connectionState.first { it == VehicleConnectionState.Reading }
                    }
                }
                runCurrent()
                assertTrue("activation must make successful vehicle responses observable", firstBeat.isCompleted)
                firstBeat.await()
            } finally {
                allowActivation.countDown()
                source.stop()
                beatJob.cancel()
                concurrent.close()
            }
        }

    private class RecordingConcurrentDispatcher : CoroutineDispatcher(), Closeable {
        private val delegate = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val dispatchCount = AtomicInteger()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatchCount.incrementAndGet()
            delegate.dispatch(context, block)
        }

        override fun close() = delegate.close()
    }

    @Test
    fun `liveness does not replay to a late subscriber`() = runTest(dispatcher) {
        val transport = transport(mapOf("010D" to "41 0D 3C", "010C" to "41 0C 1A F8"))
        val source = source(transport, clock = Clock { currentTime }, fastIntervalMs = 100_000L)
        source.running {
            advanceTimeBy(600L)
            runCurrent()

            val late = mutableListOf<Long>()
            val job = backgroundScope.launch { source.liveness.collect { late += it } }
            runCurrent()
            assertTrue("a replayed beat is a response dated to the wrong moment", late.isEmpty())
            job.cancel()
        }
    }

    // -- safety boundary -----------------------------------------------------------------------

    @Test
    fun `only read only AT configuration and services 01 03 07 0A reach the wire`() =
        runTest(dispatcher) {
            val transport = transport(
                mapOf("010D" to "41 0D 3C", "0101" to "41 01 83 00 00 00", "03" to "43 01 03 01"),
            )
            source(transport).running {
                advanceTimeBy(20_000L)
                runCurrent()
            }

            val configuration = setOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATI")
            val dtcServices = setOf("03", "07", "0A")
            val currentData = Regex("^01[0-9A-F]{2}$")
            transport.written.forEach { command ->
                assertTrue(
                    "unexpected command on the wire: $command",
                    command in configuration || command in dtcServices || currentData.matches(command),
                )
            }
            assertTrue("mode 09 (VIN) must never be requested", transport.written.none { it.startsWith("09") })
            assertTrue("mode 04 (clear DTCs) must never be sent", transport.written.none { it.startsWith("04") })
            assertTrue(transport.written.isNotEmpty())
        }

    // -- logging -------------------------------------------------------------------------------

    /**
     * A differential test: the same sixty seconds of continuous failure is run twice, once with the
     * throttle disabled. The unthrottled run is what a naive implementation emits, so the assertion
     * measures the throttle rather than merely observing a small number.
     */
    @Test
    fun `repeated failures do not flood the log`() = runTest(dispatcher) {
        val throttled = failForSixtySeconds(logThrottleMs = 10_000L)
        val unthrottled = failForSixtySeconds(logThrottleMs = 0L)

        assertTrue("something must be reported", throttled.isNotEmpty())
        assertTrue(
            "the unthrottled baseline must be genuinely noisy for this test to mean anything",
            unthrottled.size > 40,
        )
        assertTrue(
            "logging must be rate limited; got ${throttled.size} lines:\n${throttled.joinToString("\n")}",
            // Four recurring event kinds can each appear at most seven times in the inclusive
            // 0..60 s window, plus the one-time source/diagnostic events.
            throttled.size <= 30,
        )
    }

    @Test
    fun `a successful diagnostics scan logs count and codes through the shared logger`() =
        runTest(dispatcher) {
            logs.clear()
            val source = source(
                completedEmptyDtcServices +
                    mapOf(
                        "010D" to "41 0D 3C",
                        "0101" to "41 01 82 00 00 00",
                        "03" to "43 02 03 01 04 20",
                    ),
            )

            source.running { runCurrent() }

            assertTrue(
                logs.any {
                    it == "[CarDash/Vehicle] diagnostics scan complete: count=2, codes=P0301,P0420"
                },
            )
        }

    @Test
    fun `successful handshake logs adapter identity but transport open is not relabelled as USB attach`() =
        runTest(dispatcher) {
            logs.clear()
            val source = source(mapOf("010D" to "41 0D 3C"))

            source.running { runCurrent() }

            assertTrue(logs.contains("[CarDash/Vehicle] adapter identity: ELM327 v1.5"))
            assertTrue(
                "TransportEvent.Opened is not evidence of a USB attachment event",
                logs.none { it.contains("device attached") },
            )
        }

    @Test
    fun `a close failure is logged safely while teardown still completes`() = runTest(dispatcher) {
        logs.clear()
        val delegate = transport(mapOf("010D" to "?", "010C" to "NO DATA"))
        val source = source(CloseThrowingTransport(delegate))

        source.start().activate()
        runCurrent()
        assertTrue(logs.any { it.startsWith("[CarDash/Vehicle] protocol error:") })
        source.stop()

        assertTrue(
            logs.any {
                it.startsWith("[CarDash/Vehicle] transport close failed:") &&
                    it.contains("close failed")
            },
        )
        source.connectionState.test {
            assertEquals(VehicleConnectionState.Disconnected, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private class CloseThrowingTransport(
        private val delegate: VehicleTransport,
    ) : VehicleTransport by delegate {
        override suspend fun close() {
            throw IOException("close failed after polling stopped")
        }
    }

    private suspend fun TestScope.failForSixtySeconds(logThrottleMs: Long): List<String> {
        logs.clear()
        val transport = transport(mapOf("010D" to "41 0D 3C", "010C" to "41 0C 1A F8"))
        val source = source(
            transport,
            clock = Clock { currentTime },
            slowIntervalMs = 600_000L,
            diagnosticsIntervalMs = 600_000L,
            reconnectDelayMs = 2_000L,
            logThrottleMs = logThrottleMs,
        )
        source.running {
            runCurrent()
            transport.silence("010D")
            transport.silence("010C")
            // Sixty seconds of continuous failure: a timeout every second and a reconnect cycle
            // every few seconds, each of which transitions the connection state three times.
            advanceTimeBy(60_000L)
            runCurrent()
        }
        return logs.toList()
    }

    @Test
    fun `start is idempotent`() = runTest(dispatcher) {
        val transport = transport(mapOf("010D" to "41 0D 3C"))
        val source = source(transport, slowIntervalMs = 600_000L, diagnosticsIntervalMs = 600_000L)
        source.running {
            source.start().activate()
            advanceTimeBy(100L)
            runCurrent()
            assertEquals(
                "a second start() must not open a second handshake",
                1,
                transport.written.count { it == "ATZ" },
            )
        }
    }

    @Test
    fun `activation after stop is stale and cannot resurrect transport work`() = runTest(dispatcher) {
        val transport = transport(mapOf("010D" to "41 0D 3C"))
        val source = source(transport, slowIntervalMs = 600_000L, diagnosticsIntervalMs = 600_000L)
        val stale = source.start()

        assertTrue("preparation itself must not touch the transport", transport.written.isEmpty())
        source.stop()
        stale.activate()
        runCurrent()
        assertTrue("a stopped generation cannot be resurrected", transport.written.isEmpty())

        source.start().activate()
        runCurrent()
        assertEquals("a fresh generation remains usable", 1, transport.written.count { it == "ATZ" })
        source.stop()
    }

    @Test
    fun `stop waits for polling cancellation cleanup before returning`() = runTest(dispatcher) {
        val delegate = transport(mapOf("010D" to "41 0D 3C", "010C" to "41 0C 1A F8"))
        val transport = CleanupOnCancellationTransport(delegate, "010D")
        val source = source(transport, slowIntervalMs = 600_000L, diagnosticsIntervalMs = 600_000L)

        source.start().activate()
        runCurrent()
        assertFalse(transport.cleanupComplete)

        source.stop()

        assertTrue(
            "stop must not return while an old polling session can still touch transport state",
            transport.cleanupComplete,
        )
    }

    @Test
    fun `a concurrent restart waits for stop cleanup and cannot overlap the old session`() =
        runTest(dispatcher) {
            val delegate = transport(mapOf("010D" to "41 0D 3C", "010C" to "41 0C 1A F8"))
            val transport = CleanupOnCancellationTransport(delegate, "010D")
            val source = source(transport, slowIntervalMs = 600_000L, diagnosticsIntervalMs = 600_000L)
            source.start().activate()
            runCurrent()

            val stopping = backgroundScope.async { source.stop() }
            runCurrent()
            val restarting = backgroundScope.async { source.start().activate() }
            runCurrent()

            assertFalse("start must wait behind the serialized stop cleanup", restarting.isCompleted)
            advanceTimeBy(1_000L)
            runCurrent()
            stopping.await()
            restarting.await()
            assertTrue(transport.cleanupComplete)
            assertEquals("the replacement starts only after cleanup", 2, delegate.written.count { it == "ATZ" })
            source.stop()
        }

    private class CleanupOnCancellationTransport(
        private val delegate: VehicleTransport,
        private val command: String,
    ) : VehicleTransport by delegate {
        var cleanupComplete = false
            private set

        override suspend fun write(bytes: ByteArray) {
            val written = String(bytes, Charsets.ISO_8859_1).trimEnd('\r')
            if (written != command) {
                delegate.write(bytes)
                return
            }

            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    delay(1_000L)
                    cleanupComplete = true
                }
            }
        }
    }
}
