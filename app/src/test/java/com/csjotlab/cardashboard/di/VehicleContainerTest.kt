package com.csjotlab.cardashboard.di

import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composition root is the only place the mock gate can be bypassed, so the gate is asserted
 * here end to end — through the real selector and the real repository — rather than only on the
 * selector in isolation.
 *
 * Assertions read `state.source` rather than the connection state on purpose: the source identity
 * is settled the moment the repository switches to a source, so nothing here depends on how far the
 * mock's scripted timeline has advanced.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VehicleContainerTest {

    @Test
    fun `no source is active until something selects one`() = runTest {
        val container = VehicleContainer(backgroundScope, debugBuild = true)
        runCurrent()

        assertEquals(
            "the mock must never be auto-selected, not even in a debug build",
            VehicleSourceId.NONE,
            container.repository.snapshot.value.state.source,
        )
    }

    @Test
    fun `a debug build with the explicit toggle on selects the mock`() = runTest {
        val container = VehicleContainer(backgroundScope, debugBuild = true)
        runCurrent()

        container.setMockModeEnabled(true)
        runCurrent()

        assertEquals(
            VehicleSourceId.MOCK,
            container.repository.snapshot.value.state.source,
        )
    }

    @Test
    fun `a release build never reaches the mock however the toggle is set`() = runTest {
        val container = VehicleContainer(backgroundScope, debugBuild = false)
        runCurrent()

        container.setMockModeEnabled(true)
        runCurrent()

        assertEquals(
            "BuildConfig.DEBUG is half the gate and the container must not bypass it",
            VehicleSourceId.NONE,
            container.repository.snapshot.value.state.source,
        )
    }

    @Test
    fun `turning the toggle back off drops the mock rather than retaining its values`() = runTest {
        val container = VehicleContainer(backgroundScope, debugBuild = true)
        container.setMockModeEnabled(true)
        runCurrent()
        assertEquals(VehicleSourceId.MOCK, container.repository.snapshot.value.state.source)

        container.setMockModeEnabled(false)
        runCurrent()

        assertEquals(
            VehicleSourceId.NONE,
            container.repository.snapshot.value.state.source,
        )
    }

    /**
     * The scope the container is handed is the scope it tears down. Tests build their own so the
     * cancellation is observable and does not take `runTest`'s own scope with it.
     */
    private fun TestScope.ownedScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    @Test
    fun `shutdown cancels the scope the active source runs on`() = runTest {
        val scope = ownedScope()
        val container = VehicleContainer(scope, debugBuild = true)
        container.setMockModeEnabled(true)
        runCurrent()
        assertEquals(VehicleSourceId.MOCK, container.repository.snapshot.value.state.source)

        container.shutdown()

        assertFalse(
            "an unstopped source keeps polling a USB link with the screen off",
            scope.isActive,
        )
        // Requesting cancellation is not the same as having torn down. shutdown() is the hook a
        // real USB close() will hang off, so it must not return while the graph is still unwinding.
        assertTrue(
            "shutdown must await the teardown it started, not merely request it",
            scope.coroutineContext.job.isCompleted,
        )
    }

    @Test
    fun `after shutdown the scripted source stops advancing`() = runTest {
        val scope = ownedScope()
        val container = VehicleContainer(scope, debugBuild = true)
        container.setMockModeEnabled(true)
        advanceTimeBy(2_000)
        runCurrent()
        val whileRunning = container.repository.snapshot.value
        // Without this the test would also pass against a container that never started the mock at
        // all, which would prove nothing about shutdown.
        assertEquals(VehicleSourceId.MOCK, whileRunning.state.source)
        assertNotNull(
            "the script has to be producing readings before we can prove shutdown stops it",
            whileRunning.state.speedKph.valueOrNull(),
        )

        container.shutdown()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(
            "nothing may keep producing frames once the graph has been torn down",
            whileRunning,
            container.repository.snapshot.value,
        )
    }

    @Test
    fun `shutdown is idempotent`() = runTest {
        val scope = ownedScope()
        val container = VehicleContainer(scope, debugBuild = true)
        container.setMockModeEnabled(true)
        runCurrent()

        // The property under test is that the second call neither throws (a double stop() of a
        // source that has already released its handle) nor resurrects anything.
        container.shutdown()
        val afterFirst = container.repository.snapshot.value
        container.shutdown()

        assertFalse(scope.isActive)
        assertTrue(scope.coroutineContext.job.isCompleted)
        assertEquals(afterFirst, container.repository.snapshot.value)
    }

    @Test
    fun `no real source exists yet, so the dashboard starts disconnected`() = runTest {
        val container = VehicleContainer(backgroundScope, debugBuild = true)
        runCurrent()

        val snapshot = container.repository.snapshot.value
        assertEquals(
            com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState.Disconnected,
            snapshot.connection,
        )
        assertEquals(emptyList<Any>(), snapshot.diagnostics.issues)
    }
}
