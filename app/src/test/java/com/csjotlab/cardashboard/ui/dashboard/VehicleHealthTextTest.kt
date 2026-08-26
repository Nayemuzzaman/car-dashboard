package com.csjotlab.cardashboard.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the health surface that are plain text rules rather than layout: the simulation
 * banner's wording, and the landscape Warnings header that carries the diagnostic count instead of
 * a third panel.
 *
 * Everything here is deliberately *not* a Compose test — these are the rules that must hold no
 * matter how they are drawn, and a JVM test pins them without a device. The rendering itself
 * (which composable shows which string, tap routing, and whether any of it survives the compact
 * landscape height budget) is covered by `VehicleHealthPanelTest` on the device.
 */
class VehicleHealthTextTest {

    @Test
    fun `the simulation banner text is exactly the wording the spec requires`() {
        assertEquals("SIMULATED DATA — NOT A REAL VEHICLE", SIMULATED_DATA_LABEL)
    }

    /**
     * A hyphen would render almost identically and read as a typo-level difference, so the dash is
     * pinned by code point rather than by eye. This also fails loudly if the source file is ever
     * re-saved in a non-UTF-8 encoding, which would mangle the character while leaving the literal
     * above looking correct in a diff.
     */
    @Test
    fun `the simulation banner uses an em dash and not a hyphen`() {
        assertTrue("expected an em dash (U+2014)", SIMULATED_DATA_LABEL.contains('—'))
        assertFalse("a hyphen-minus is not an em dash", SIMULATED_DATA_LABEL.contains('-'))
        assertEquals(
            listOf(0x53, 0x49, 0x4D, 0x55, 0x4C, 0x41, 0x54, 0x45, 0x44),
            SIMULATED_DATA_LABEL.take(9).map { it.code },
        )
    }

    /** Portrait's panel and landscape's header must not disagree about what an issue is called. */
    @Test
    fun `disconnected and healthy are different sentences`() {
        assertEquals("Vehicle not connected", HEALTH_DISCONNECTED)
        assertEquals("No issues reported", HEALTH_NO_ISSUES)
    }

    @Test
    fun `no diagnostics means the header is the warnings summary untouched`() {
        assertEquals("All clear", warningsHeaderValue(allReportedAndSafe, diagnosticCount = 0))
    }

    /**
     * The defect this test exists for. Zero diagnostics on zero data must never render as a count,
     * because "0 issues" is an all-clear claim over nothing — the same fabricated all-clear already
     * fixed twice, once in the warning rows and once in the warnings header.
     */
    @Test
    fun `a zero count is never rendered`() {
        val header = warningsHeaderValue(nothingReported, diagnosticCount = 0)
        assertEquals(UNAVAILABLE, header)
        assertFalse(header.contains("0"))
        assertFalse(header.contains("issue"))
    }

    /**
     * Coverage is the formatter's business, not the header's. Delegating to [warningsSummary] is
     * what keeps a real diagnostic count from implying that everything *else* was checked.
     */
    @Test
    fun `an unknown warning row still reads as unknown even beside a real count`() {
        val header = warningsHeaderValue(nothingReported, diagnosticCount = 2)
        assertEquals("${UNAVAILABLE} · 2 issues", header)
        assertFalse("nothing was reported, so nothing is clear", header.contains("clear"))
    }

    @Test
    fun `the count is singular for one issue`() {
        assertEquals("All clear · 1 issue", warningsHeaderValue(allReportedAndSafe, diagnosticCount = 1))
    }

    @Test
    fun `an active warning count and a diagnostic count are both reported`() {
        assertEquals("1 active · 3 issues", warningsHeaderValue(oneActive, diagnosticCount = 3))
    }

    /** The header must be derived from the formatter, never re-counted here. */
    @Test
    fun `the header delegates its summary to the formatter`() {
        listOf(allReportedAndSafe, nothingReported, oneActive).forEach { rows ->
            assertTrue(
                warningsHeaderValue(rows, diagnosticCount = 0) ==
                    VehicleStateFormatter.warningsSummary(rows),
            )
        }
    }

    private val allReportedAndSafe = listOf(
        WarningUi("Seatbelt", WarningLevel.Ok, "Secured", null),
        WarningUi("Door", WarningLevel.Ok, "All doors closed", null),
        WarningUi("Tire Pressure", WarningLevel.Ok, "Nominal", null),
        WarningUi("Check Engine", WarningLevel.Ok, "No fault", null),
    )

    private val nothingReported = List(4) {
        WarningUi("row $it", WarningLevel.NotReported, "Not reported", null)
    }

    private val oneActive = allReportedAndSafe.mapIndexed { index, row ->
        if (index == 1) row.copy(level = WarningLevel.Active) else row
    }
}
