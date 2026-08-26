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
import com.csjotlab.cardashboard.vehicle.fakes.ThrowingVehicleDataSource
import com.csjotlab.cardashboard.vehicle.source.VehicleDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleRepositoryTest {

    private val clock = Clock { 1_000L }

    private fun issue(code: String) = DiagnosticIssue(
        id = "dtc:$code",
        code = DiagnosticCode.Dtc(code),
        title = null,
        description = null,
        classification = null,
        severity = Severity.Warning,
        status = DiagnosticStatus.Stored,
        source = DiagnosticSource.Obd2,
        firstSeenMs = 1_000L,
        lastSeenMs = 1_000L,
    )

    private fun diagnostics(vararg codes: String) = VehicleDiagnosticsState(
        issues = codes.map(::issue),
        malfunctionIndicatorLampOn = Signal.Value(codes.isNotEmpty(), 1_000L),
        storedDtcCount = Signal.Value(codes.size, 1_000L),
        lastScanMs = 1_000L,
    )

    private fun VehicleSnapshot.dtcCodes(): List<String> =
        diagnostics.issues.mapNotNull { (it.code as? DiagnosticCode.Dtc)?.code }

    @Test
    fun `telemetry from the active source reaches the snapshot`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val sources = MutableStateFlow<VehicleDataSource?>(source)
        val repository = VehicleRepository(sources, clock, backgroundScope)

        repository.snapshot.test {
            awaitItem()   // initial
            source.emitConnection(VehicleConnectionState.Reading)
            source.emitState(
                VehicleState.unavailable(VehicleSourceId.OBD_USB)
                    .copy(speedKph = Signal.Value(60f, 1_000L), engineRpm = Signal.Value(2100, 1_000L))
            )
            runCurrent()

            val snapshot = expectMostRecentItem()
            assertEquals(60f, snapshot.state.speedKph.valueOrNull())
            assertEquals(2100, snapshot.state.engineRpm.valueOrNull())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `identical consecutive states are not re-emitted`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val repository = VehicleRepository(MutableStateFlow(source), clock, backgroundScope)
        val moving = VehicleState.unavailable(VehicleSourceId.OBD_USB).copy(speedKph = Signal.Value(60f, 1_000L))

        // The upstream must genuinely put the duplicate on the wire, or this test would be
        // measuring the fake's conflation instead of the repository's de-duplication.
        val upstream = mutableListOf<VehicleState>()
        val watcher = backgroundScope.launch { source.vehicleState.collect { upstream += it } }
        runCurrent()

        repository.snapshot.test {
            // Reading, or the repository would clear the state before de-duplication could ever
            // be the reason two emissions collapsed into one.
            source.emitConnection(VehicleConnectionState.Reading)
            source.emitState(moving)
            runCurrent()
            assertEquals(60f, expectMostRecentItem().state.speedKph.valueOrNull())

            source.emitState(moving)   // byte-identical
            runCurrent()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        watcher.cancel()
        assertEquals(
            "the source must have emitted the duplicate for this test to mean anything",
            listOf(VehicleState.unavailable(VehicleSourceId.OBD_USB), moving, moving),
            upstream,
        )
    }

    @Test
    fun `no real value survives a disconnect`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val repository = VehicleRepository(MutableStateFlow(source), clock, backgroundScope)

        repository.snapshot.test {
            awaitItem()
            source.emitConnection(VehicleConnectionState.Reading)
            source.emitState(VehicleState.unavailable(VehicleSourceId.OBD_USB).copy(speedKph = Signal.Value(60f, 1_000L)))
            // Populate the diagnostics too: an `issues.isEmpty()` assertion proves nothing unless
            // there was something there to clear.
            source.emitDiagnostics(diagnostics("P0301"))
            runCurrent()
            val live = expectMostRecentItem()
            assertEquals(60f, live.state.speedKph.valueOrNull())
            assertEquals(listOf("P0301"), live.dtcCodes())

            source.emitConnection(VehicleConnectionState.ConnectionLost("cable removed"))
            runCurrent()

            val cleared = expectMostRecentItem()
            assertTrue(
                "stale real values must not outlive the connection",
                cleared.state.allSignals.none { it.isValue }
            )
            assertTrue("diagnostics must not outlive the connection", cleared.diagnostics.issues.isEmpty())
            assertEquals(Signal.Unknown, cleared.diagnostics.malfunctionIndicatorLampOn)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `several concurrent diagnostics all reach the snapshot`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val repository = VehicleRepository(MutableStateFlow(source), clock, backgroundScope)

        repository.snapshot.test {
            awaitItem()
            source.emitConnection(VehicleConnectionState.Reading)
            source.emitDiagnostics(diagnostics("P0301"))
            runCurrent()
            assertEquals(listOf("P0301"), expectMostRecentItem().dtcCodes())

            source.emitDiagnostics(diagnostics("P0301", "P0420", "C1234"))
            runCurrent()

            val many = expectMostRecentItem()
            assertEquals(listOf("P0301", "P0420", "C1234"), many.dtcCodes())
            assertEquals(3, many.diagnostics.storedDtcCount.valueOrNull())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a resolved diagnostic leaves the snapshot while the connection stays live`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val repository = VehicleRepository(MutableStateFlow(source), clock, backgroundScope)

        repository.snapshot.test {
            awaitItem()
            source.emitConnection(VehicleConnectionState.Reading)
            source.emitState(VehicleState.unavailable(VehicleSourceId.OBD_USB).copy(speedKph = Signal.Value(60f, 1_000L)))
            source.emitDiagnostics(diagnostics("P0301", "P0420"))
            runCurrent()
            assertEquals(listOf("P0301", "P0420"), expectMostRecentItem().dtcCodes())

            source.emitDiagnostics(diagnostics("P0420"))
            runCurrent()
            assertEquals(listOf("P0420"), expectMostRecentItem().dtcCodes())

            source.emitDiagnostics(diagnostics())
            runCurrent()

            val resolved = expectMostRecentItem()
            assertTrue("a resolved DTC must leave the list", resolved.diagnostics.issues.isEmpty())
            assertTrue(
                "resolving a DTC is not a disconnect: telemetry must survive it",
                resolved.connection is VehicleConnectionState.Reading,
            )
            assertEquals(60f, resolved.state.speedKph.valueOrNull())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a throwing source reports a failure and clears telemetry without killing the scope`() = runTest {
        val sources = MutableStateFlow<VehicleDataSource?>(
            ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        )
        val repository = VehicleRepository(sources, clock, backgroundScope)

        repository.snapshot.test {
            awaitItem()
            sources.value = ThrowingVehicleDataSource(VehicleSourceId.OBD_USB)
            runCurrent()

            val failed = expectMostRecentItem()
            assertEquals(
                VehicleConnectionState.Error("adapter frame was garbage"),
                failed.connection,
            )
            assertTrue(
                "a failing real source must never leave plausible values behind",
                failed.state.allSignals.none { it.isValue },
            )
            assertTrue(failed.diagnostics.issues.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue("the owner's scope must survive a source failure", backgroundScope.isActive)
    }

    @Test
    fun `a source that throws while starting reports a failure`() = runTest {
        val source = ThrowingVehicleDataSource(
            VehicleSourceId.OBD_USB,
            failure = IllegalStateException("could not open the adapter"),
            failOnStart = true,
        )
        val repository = VehicleRepository(MutableStateFlow(source), clock, backgroundScope)

        repository.snapshot.test {
            runCurrent()
            val failed = expectMostRecentItem()
            assertEquals(
                VehicleConnectionState.Error("could not open the adapter"),
                failed.connection,
            )
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(backgroundScope.isActive)
    }

    @Test
    fun `cancelling the owner's scope stops the active source`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        VehicleRepository(MutableStateFlow(source), clock, scope)
        runCurrent()
        assertTrue("the source must have been started", source.started)

        scope.cancel()
        runCurrent()

        assertTrue("a cancelled owner must not leak a started source", !source.started)
    }

    @Test
    fun `a source that throws while stopping does not escape as an uncaught exception`() = runTest {
        val source = ControllableVehicleDataSource(
            VehicleSourceId.OBD_USB,
            stopFailure = java.io.IOException("usb close failed"),
        )
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        VehicleRepository(MutableStateFlow(source), clock, scope)
        runCurrent()
        assertTrue(source.started)

        // The teardown stop runs on a scope with no parent job, so anything it throws would go
        // straight to the thread's uncaught handler — which on Android kills the process. Rotating
        // the screen with a USB adapter attached is enough to hit it.
        val thread = Thread.currentThread()
        val original = thread.uncaughtExceptionHandler
        val escaped = mutableListOf<Throwable>()
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> escaped += e }
        try {
            scope.cancel()
            runCurrent()
        } finally {
            thread.uncaughtExceptionHandler = original
        }

        assertEquals("tearing down must never take the process with it", emptyList<Throwable>(), escaped)
        assertTrue("the source must still have been stopped", !source.started)
    }

    @Test
    fun `shutdown propagates a failure to stop to its caller`() = runTest {
        val failure = java.io.IOException("usb close failed")
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB, stopFailure = failure)
        val repository = VehicleRepository(MutableStateFlow(source), clock, backgroundScope)
        runCurrent()

        // Documented asymmetry: shutdown() is called deliberately, so its caller gets to know.
        val thrown = runCatching { repository.shutdown() }.exceptionOrNull()
        assertEquals(failure, thrown)
        assertTrue(!source.started)
    }

    @Test
    fun `shutdown stops the active source`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val repository = VehicleRepository(MutableStateFlow(source), clock, backgroundScope)
        runCurrent()
        assertTrue(source.started)

        repository.shutdown()

        assertTrue(!source.started)
    }

    @Test
    fun `removing the source clears everything and reports disconnected`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val sources = MutableStateFlow<VehicleDataSource?>(source)
        val repository = VehicleRepository(sources, clock, backgroundScope)

        repository.snapshot.test {
            awaitItem()
            source.emitConnection(VehicleConnectionState.Reading)
            source.emitState(VehicleState.unavailable(VehicleSourceId.OBD_USB).copy(speedKph = Signal.Value(60f, 1_000L)))
            runCurrent()
            expectMostRecentItem()

            sources.value = null
            runCurrent()

            val snapshot = expectMostRecentItem()
            assertEquals(VehicleConnectionState.Disconnected, snapshot.connection)
            assertEquals(VehicleSourceId.NONE, snapshot.state.source)
            assertTrue(snapshot.state.allSignals.none { it.isValue })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switching source starts the new one and stops the old`() = runTest {
        val mock = ControllableVehicleDataSource(VehicleSourceId.MOCK)
        val real = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val sources = MutableStateFlow<VehicleDataSource?>(mock)
        val repository = VehicleRepository(sources, clock, backgroundScope)

        repository.snapshot.test {
            awaitItem()
            runCurrent()
            assertTrue(mock.started)

            sources.value = real
            runCurrent()

            assertTrue("new source must be started", real.started)
            assertTrue("old source must be stopped", !mock.started)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
