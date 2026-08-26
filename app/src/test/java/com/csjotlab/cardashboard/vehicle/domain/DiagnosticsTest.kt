package com.csjotlab.cardashboard.vehicle.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {

    @Test
    fun `a dtc issue carries no invented description`() {
        val issue = DiagnosticIssue(
            id = "dtc:P0301",
            code = DiagnosticCode.Dtc("P0301"),
            title = null,
            description = null,
            classification = DtcClassification(DtcSystem.Powertrain, false, "Ignition system or misfire"),
            severity = Severity.Warning,
            status = DiagnosticStatus.Stored,
            source = DiagnosticSource.Obd2,
            firstSeenMs = 1_000L,
            lastSeenMs = 1_000L,
        )

        assertNull("no trusted mapping ships, so title must stay null", issue.title)
        assertNull("no trusted mapping ships, so description must stay null", issue.description)
        assertEquals("P0301", (issue.code as DiagnosticCode.Dtc).code)
    }

    @Test
    fun `empty diagnostics state reports nothing and claims nothing`() {
        val state = VehicleDiagnosticsState.empty()

        assertTrue(state.issues.isEmpty())
        assertEquals(Signal.Unknown, state.malfunctionIndicatorLampOn)
        assertEquals(Signal.Unknown, state.storedDtcCount)
        assertNull(state.lastScanMs)
    }

    @Test
    fun `a signal derived issue records which signal produced it`() {
        val issue = DiagnosticIssue(
            id = "signal:DoorOpen:RearLeft",
            code = DiagnosticCode.SignalDerived(SignalIssueKind.DoorOpen, "RearLeft"),
            title = null,
            description = null,
            classification = null,
            severity = Severity.Warning,
            status = DiagnosticStatus.LiveSignal,
            source = DiagnosticSource.VehicleSignal,
            firstSeenMs = 2_000L,
            lastSeenMs = 2_500L,
        )

        assertEquals(DiagnosticStatus.LiveSignal, issue.status)
        assertEquals(SignalIssueKind.DoorOpen, (issue.code as DiagnosticCode.SignalDerived).kind)
    }
}
