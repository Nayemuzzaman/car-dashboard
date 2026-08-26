package com.csjotlab.cardashboard.ui.dashboard

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.csjotlab.cardashboard.vehicle.data.VehicleRepository
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
import com.csjotlab.cardashboard.vehicle.fakes.ControllableVehicleDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `speed updates propagate to the ui state`() = runTest(dispatcher.scheduler) {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val repository = VehicleRepository(MutableStateFlow(source), Clock { 0L }, backgroundScope)
        val viewModel = DashboardViewModel(repository)

        // uiState uses SharingStarted.WhileSubscribed, so the upstream only runs while something
        // is collecting. Keep a live subscriber for the duration of the test.
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        source.emitConnection(VehicleConnectionState.Reading)
        source.emitState(
            VehicleState.unavailable(VehicleSourceId.OBD_USB).copy(speedKph = Signal.Value(88f, 0L))
        )
        runCurrent()

        assertEquals("88", viewModel.uiState.value.speedText)
    }

    @Test
    fun `changing the drive mode label refreshes the gear helper`() = runTest(dispatcher.scheduler) {
        val source = ControllableVehicleDataSource(VehicleSourceId.MOCK)
        val repository = VehicleRepository(MutableStateFlow(source), Clock { 0L }, backgroundScope)
        val viewModel = DashboardViewModel(repository)

        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        source.emitConnection(VehicleConnectionState.Reading)
        source.emitState(
            VehicleState.unavailable(VehicleSourceId.MOCK)
                .copy(gear = Signal.Value(com.csjotlab.cardashboard.vehicle.domain.Gear.Drive, 0L))
        )
        viewModel.setDriveModeLabel("Sport")
        runCurrent()

        assertEquals("Sport shift", viewModel.uiState.value.metrics.first { it.label == "Gear" }.helper)
    }

    @Test
    fun `losing the connection clears every reading rather than holding the last one`() =
        runTest(dispatcher.scheduler) {
            val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
            val repository = VehicleRepository(MutableStateFlow(source), Clock { 0L }, backgroundScope)
            val viewModel = DashboardViewModel(repository)

            backgroundScope.launch { viewModel.uiState.collect {} }
            runCurrent()

            source.emitConnection(VehicleConnectionState.Reading)
            source.emitState(
                VehicleState.unavailable(VehicleSourceId.OBD_USB).copy(
                    speedKph = Signal.Value(88f, 0L),
                    engineRpm = Signal.Value(2100, 0L),
                )
            )
            runCurrent()
            assertEquals("88", viewModel.uiState.value.speedText)

            source.emitConnection(VehicleConnectionState.ConnectionLost("adapter unplugged"))
            runCurrent()

            val ui = viewModel.uiState.value
            assertEquals("Connection lost", ui.connectionLabel)
            assertEquals("a last-known reading must not survive the connection", UNAVAILABLE, ui.speedText)
            assertEquals(null, ui.speedForGauge)
            assertFalse(ui.isConnected)
            assertTrue("no tile may keep a stale value", ui.metrics.none { it.available })
            // The layout contract holds through the transition.
            assertEquals(4, ui.metrics.size)
            assertEquals(4, ui.warnings.size)
            assertTrue(ui.warnings.none { it.level == WarningLevel.Ok })
        }

    @Test
    fun `a silent vehicle degrades to communication unavailable with no numbers left`() =
        runTest(dispatcher.scheduler) {
            val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
            val repository = VehicleRepository(MutableStateFlow(source), Clock { 0L }, backgroundScope)
            val viewModel = DashboardViewModel(repository)

            backgroundScope.launch { viewModel.uiState.collect {} }
            runCurrent()

            source.emitConnection(VehicleConnectionState.Reading)
            source.emitState(
                VehicleState.unavailable(VehicleSourceId.OBD_USB).copy(speedKph = Signal.Value(88f, 0L))
            )
            runCurrent()
            assertEquals("88", viewModel.uiState.value.speedText)

            // The repository's 3 s staleness watchdog is the one place time must genuinely advance.
            advanceTimeBy(3_001)
            runCurrent()

            val ui = viewModel.uiState.value
            assertEquals("Vehicle communication unavailable", ui.connectionLabel)
            assertEquals(UNAVAILABLE, ui.speedText)
            assertEquals(null, ui.speedForGauge)
            assertFalse(ui.isConnected)
            assertTrue(ui.metrics.none { it.available })
            assertEquals(4, ui.metrics.size)
            assertEquals(4, ui.warnings.size)
        }

    @Test
    fun `a warning row's issue id resolves to the issue behind it`() = runTest(dispatcher.scheduler) {
        val source = ControllableVehicleDataSource(VehicleSourceId.MOCK)
        val repository = VehicleRepository(MutableStateFlow(source), Clock { 0L }, backgroundScope)
        val viewModel = DashboardViewModel(repository)

        source.emitConnection(VehicleConnectionState.Reading)
        source.emitDiagnostics(diagnosticsWith(storedDtc("P0301")))
        runCurrent()

        val issue = viewModel.issueById("dtc:P0301").first()
        assertEquals("dtc:P0301", issue?.id)
        assertEquals(DiagnosticCode.Dtc("P0301"), issue?.code)
        // The lookup carries the issue across untouched — no headline, no diagnosis, no invented
        // description sneaks in on the way to the detail screen.
        assertEquals(null, issue?.title)
        assertEquals(null, issue?.description)
    }

    @Test
    fun `an issue the vehicle has stopped reporting resolves to null`() = runTest(dispatcher.scheduler) {
        val source = ControllableVehicleDataSource(VehicleSourceId.MOCK)
        val repository = VehicleRepository(MutableStateFlow(source), Clock { 0L }, backgroundScope)
        val viewModel = DashboardViewModel(repository)

        source.emitConnection(VehicleConnectionState.Reading)
        source.emitDiagnostics(diagnosticsWith(storedDtc("P0301")))
        runCurrent()
        assertEquals("dtc:P0301", viewModel.issueById("dtc:P0301").first()?.id)

        source.emitDiagnostics(VehicleDiagnosticsState.empty())
        runCurrent()

        assertEquals(
            "a cleared issue must not be served from a snapshot taken when the row was drawn",
            null,
            viewModel.issueById("dtc:P0301").first(),
        )
        assertEquals(null, viewModel.issueById("dtc:never-reported").first())
    }

    @Test
    fun collectedIssueStartsWithTheCurrentRepositoryValue() = runTest(dispatcher.scheduler) {
        val source = ControllableVehicleDataSource(VehicleSourceId.MOCK)
        val repository = VehicleRepository(MutableStateFlow(source), Clock { 0L }, backgroundScope)

        source.emitConnection(VehicleConnectionState.Reading)
        source.emitDiagnostics(diagnosticsWith(storedDtc("P0301")))
        runCurrent()

        DashboardViewModel(repository).issueById("dtc:P0301").test {
            assertEquals("dtc:P0301", awaitItem()?.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun collectedIssueUpdatesWhenTheSameIdIsReplaced() = runTest(dispatcher.scheduler) {
        val source = ControllableVehicleDataSource(VehicleSourceId.MOCK)
        val repository = VehicleRepository(MutableStateFlow(source), Clock { 0L }, backgroundScope)
        val original = storedDtc("P0301")
        source.emitConnection(VehicleConnectionState.Reading)
        source.emitDiagnostics(diagnosticsWith(original))
        runCurrent()

        DashboardViewModel(repository).issueById("dtc:P0301").test {
            assertEquals(original, awaitItem())

            val replacement = original.copy(
                title = "Replacement title",
                severity = Severity.Critical,
                status = DiagnosticStatus.Permanent,
                lastSeenMs = 2_000L,
            )
            source.emitDiagnostics(diagnosticsWith(replacement))
            runCurrent()

            assertEquals(replacement, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun collectedIssueBecomesNullWhenDiagnosticsResolve() = runTest(dispatcher.scheduler) {
        val source = ControllableVehicleDataSource(VehicleSourceId.MOCK)
        val repository = VehicleRepository(MutableStateFlow(source), Clock { 0L }, backgroundScope)
        val issue = storedDtc("P0301")
        source.emitConnection(VehicleConnectionState.Reading)
        source.emitDiagnostics(diagnosticsWith(issue))
        runCurrent()

        DashboardViewModel(repository).issueById("dtc:P0301").test {
            assertEquals(issue, awaitItem())

            source.emitDiagnostics(VehicleDiagnosticsState.empty())
            runCurrent()

            assertEquals(null, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun collectedIssueBecomesNullAfterConnectionLoss() = runTest(dispatcher.scheduler) {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val repository = VehicleRepository(MutableStateFlow(source), Clock { 0L }, backgroundScope)
        val issue = storedDtc("P0301")
        source.emitConnection(VehicleConnectionState.Reading)
        source.emitDiagnostics(diagnosticsWith(issue))
        runCurrent()

        DashboardViewModel(repository).issueById("dtc:P0301").test {
            assertEquals(issue, awaitItem())

            source.emitConnection(VehicleConnectionState.ConnectionLost("adapter unplugged"))
            runCurrent()

            assertEquals(null, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun repeatedIssueLookupsDoNotAddViewModelScopeJobs() = runTest(dispatcher.scheduler) {
        val source = ControllableVehicleDataSource(VehicleSourceId.MOCK)
        val repository = VehicleRepository(MutableStateFlow(source), Clock { 0L }, backgroundScope)
        val viewModel = DashboardViewModel(repository)
        runCurrent()
        val scopeJob = requireNotNull(viewModel.viewModelScope.coroutineContext[Job])
        val jobsBeforeLookups = scopeJob.children.count()

        repeat(20) { index -> viewModel.issueById("dtc:P${index.toString().padStart(4, '0')}") }
        runCurrent()

        assertEquals(
            "issue lookups must be cold derivations, not ViewModel-scoped sharing jobs",
            jobsBeforeLookups,
            scopeJob.children.count(),
        )
    }

    private fun storedDtc(code: String) = DiagnosticIssue(
        id = "dtc:$code",
        code = DiagnosticCode.Dtc(code),
        title = null,
        description = null,
        classification = null,
        severity = Severity.Warning,
        status = DiagnosticStatus.Stored,
        source = DiagnosticSource.Mock,
        firstSeenMs = 1_000L,
        lastSeenMs = 1_000L,
    )

    private fun diagnosticsWith(vararg issues: DiagnosticIssue) = VehicleDiagnosticsState(
        issues = issues.toList(),
        malfunctionIndicatorLampOn = Signal.Value(true, 0L),
        storedDtcCount = Signal.Value(issues.size, 0L),
        lastScanMs = 0L,
    )
}
