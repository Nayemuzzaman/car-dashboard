package com.csjotlab.cardashboard.ui.diagnostics

import com.csjotlab.cardashboard.vehicle.domain.DiagnosticCode
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticIssue
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticSource
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticStatus
import com.csjotlab.cardashboard.vehicle.domain.DtcClassification
import com.csjotlab.cardashboard.vehicle.domain.DtcSystem
import com.csjotlab.cardashboard.vehicle.domain.Severity
import com.csjotlab.cardashboard.vehicle.domain.SignalIssueKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class DiagnosticDetailUiStateTest {

    private fun issue(
        status: DiagnosticStatus = DiagnosticStatus.Stored,
        source: DiagnosticSource = DiagnosticSource.Obd2,
        title: String? = null,
    ) = DiagnosticIssue(
        id = "dtc:P0301",
        code = DiagnosticCode.Dtc("P0301"),
        title = title,
        description = null,
        classification = DtcClassification(DtcSystem.Powertrain, false, "Ignition system or misfire"),
        severity = Severity.Warning,
        status = status,
        source = source,
        firstSeenMs = 1_000L,
        lastSeenMs = 2_000L,
    )

    @Test
    fun `a code with no trusted mapping says so instead of inventing text`() {
        val state = DiagnosticDetailUiState.from(issue()) { "10:00" }

        assertEquals("Engine diagnostic code detected", state.problem)
        assertEquals("P0301", state.code)
        assertEquals("No description available for this code", state.description)
        assertEquals("Powertrain — Ignition system or misfire", state.affectedSystem)
    }

    @Test
    fun `stored codes are labelled as stored not live`() {
        assertEquals("Stored diagnostic code", DiagnosticDetailUiState.from(issue()) { "10:00" }.originLabel)
    }

    @Test
    fun `live signals are labelled as live`() {
        val live = issue(status = DiagnosticStatus.LiveSignal, source = DiagnosticSource.VehicleSignal)
        assertEquals("Live vehicle signal", DiagnosticDetailUiState.from(live) { "10:00" }.originLabel)
    }

    @Test
    fun `a trusted title is used when one exists`() {
        val state = DiagnosticDetailUiState.from(issue(title = "Cylinder 1 misfire detected")) { "10:00" }
        assertEquals("Cylinder 1 misfire detected", state.problem)
    }

    @Test
    fun `detected time comes from the formatter not from wall clock`() {
        assertEquals("10:00", DiagnosticDetailUiState.from(issue()) { "10:00" }.detectedTimeLabel)
    }

    /**
     * The whole point of the data-integrity boundary: a code the structural decoder cannot place
     * still renders as the code. It must not acquire a system, a subsystem or a named fault that
     * nothing supplied.
     */
    @Test
    fun `an unclassifiable code keeps its code and gains no invented diagnosis`() {
        val unknown = issue().copy(
            id = "dtc:X9999",
            code = DiagnosticCode.Dtc("X9999"),
            classification = null,
        )

        val state = DiagnosticDetailUiState.from(unknown) { "10:00" }

        assertEquals("X9999", state.code)
        assertEquals("Engine diagnostic code detected", state.problem)
        assertEquals("Unknown", state.affectedSystem)
        assertEquals("No description available for this code", state.description)
        // Nothing anywhere in the rendered state may name a component or claim a fault cause.
        val rendered = listOf(
            state.problem,
            state.code,
            state.statusLabel,
            state.severityLabel,
            state.detectedTimeLabel,
            state.affectedSystem,
            state.description,
            state.originLabel,
        ).joinToString(" ")
        assertFalse(rendered.contains("misfire", ignoreCase = true))
        assertFalse(rendered.contains("sensor", ignoreCase = true))
        assertTrue(rendered.contains("X9999"))
    }

    @Test
    fun `a classification with no subsystem names only the system`() {
        val chassis = issue().copy(
            classification = DtcClassification(DtcSystem.Chassis, manufacturerSpecific = false, subsystem = null),
        )
        assertEquals("Chassis", DiagnosticDetailUiState.from(chassis) { "10:00" }.affectedSystem)
    }

    @Test
    fun `a signal derived issue shows the signal rather than a code`() {
        val signal = issue(status = DiagnosticStatus.LiveSignal, source = DiagnosticSource.VehicleSignal).copy(
            id = "signal:DoorOpen",
            code = DiagnosticCode.SignalDerived(SignalIssueKind.DoorOpen, "Rear left"),
            classification = null,
        )

        val state = DiagnosticDetailUiState.from(signal) { "10:00" }

        assertEquals("Vehicle signal reported", state.problem)
        assertEquals("DoorOpen — Rear left", state.code)
        assertEquals("Live vehicle signal", state.originLabel)
    }

    @Test
    fun `every status and severity has its own label`() {
        val statuses = DiagnosticStatus.entries.associateWith { status ->
            DiagnosticDetailUiState.from(issue(status = status)) { "10:00" }.statusLabel
        }
        assertEquals(
            mapOf(
                DiagnosticStatus.Stored to "Stored",
                DiagnosticStatus.Pending to "Pending",
                DiagnosticStatus.Permanent to "Permanent",
                DiagnosticStatus.LiveSignal to "Live",
            ),
            statuses,
        )

        val severities = Severity.entries.associateWith { severity ->
            DiagnosticDetailUiState.from(issue().copy(severity = severity)) { "10:00" }.severityLabel
        }
        assertEquals(
            mapOf(
                Severity.Info to "Info",
                Severity.Warning to "Warning",
                Severity.Critical to "Critical",
            ),
            severities,
        )
    }

    @Test
    fun `the detected time is the first sighting not the last`() {
        val seen = mutableListOf<Long>()
        DiagnosticDetailUiState.from(issue()) { ms -> seen += ms; "10:00" }
        assertEquals(listOf(1_000L), seen)
    }

    /**
     * The production formatter, pinned. It renders the instant the data layer recorded — it must
     * never quietly become "now", and its wording must not drift with the machine's locale.
     */
    @Test
    fun `the production time formatter renders the recorded instant in a pinned format`() {
        val previous = TimeZone.getDefault()
        val previousLocale = Locale.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            Locale.setDefault(Locale.GERMANY)
            // 2026-08-11T10:00:00Z
            assertEquals("11 Aug 2026, 10:00", formatDetectedTime(1_786_442_400_000L))
        } finally {
            TimeZone.setDefault(previous)
            Locale.setDefault(previousLocale)
        }
    }
}
