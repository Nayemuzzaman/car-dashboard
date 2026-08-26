package com.csjotlab.cardashboard.vehicle.protocol

import com.csjotlab.cardashboard.vehicle.domain.DiagnosticStatus
import com.csjotlab.cardashboard.vehicle.domain.DtcSystem
import com.csjotlab.cardashboard.vehicle.domain.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcDecoderTest {

    // --- decodePair -------------------------------------------------------

    @Test
    fun `decodes a powertrain misfire code`() {
        // 0x03 0x01 -> P0301
        assertEquals("P0301", DtcDecoder.decodePair(0x03, 0x01))
    }

    @Test
    fun `decodes each system letter from the top two bits`() {
        assertEquals("P0100", DtcDecoder.decodePair(0x01, 0x00))
        assertEquals("C0100", DtcDecoder.decodePair(0x41, 0x00))
        assertEquals("B0100", DtcDecoder.decodePair(0x81, 0x00))
        assertEquals("U0100", DtcDecoder.decodePair(0xC1, 0x00))
    }

    @Test
    fun `decodes hex digits above nine`() {
        assertEquals("P1ABC", DtcDecoder.decodePair(0x1A, 0xBC))
    }

    @Test
    fun `the all zero pair is padding not a code`() {
        assertNull(DtcDecoder.decodePair(0x00, 0x00))
    }

    // --- classify ---------------------------------------------------------

    @Test
    fun `classifies a generic powertrain misfire code`() {
        val classification = DtcDecoder.classify("P0301")!!

        assertEquals(DtcSystem.Powertrain, classification.system)
        assertFalse(classification.manufacturerSpecific)
        assertEquals("Ignition system or misfire", classification.subsystem)
    }

    @Test
    fun `second character one marks a manufacturer specific code`() {
        assertTrue(DtcDecoder.classify("P1301")!!.manufacturerSpecific)
        assertTrue(DtcDecoder.classify("P3301")!!.manufacturerSpecific)
        assertFalse(DtcDecoder.classify("P2301")!!.manufacturerSpecific)
    }

    @Test
    fun `non powertrain codes get no subsystem`() {
        assertNull(DtcDecoder.classify("B0100")!!.subsystem)
    }

    @Test
    fun `malformed codes classify as null rather than guessing`() {
        assertNull(DtcDecoder.classify(""))
        assertNull(DtcDecoder.classify("P030"))
        assertNull(DtcDecoder.classify("X0301"))
        assertNull(DtcDecoder.classify("P9301"))   // second char must be 0..3
        assertNull(DtcDecoder.classify("P03G1"))   // not hex
    }

    // --- severityFor ------------------------------------------------------

    @Test
    fun `permanent codes are critical`() {
        assertEquals(Severity.Critical, DtcDecoder.severityFor(DiagnosticStatus.Permanent, milOn = false))
    }

    @Test
    fun `an illuminated MIL is critical whatever the status`() {
        assertEquals(Severity.Critical, DtcDecoder.severityFor(DiagnosticStatus.Stored, milOn = true))
        assertEquals(Severity.Critical, DtcDecoder.severityFor(DiagnosticStatus.Pending, milOn = true))
    }

    @Test
    fun `stored codes with the MIL off are warnings`() {
        assertEquals(Severity.Warning, DtcDecoder.severityFor(DiagnosticStatus.Stored, milOn = false))
    }

    @Test
    fun `pending codes with the MIL off are informational`() {
        assertEquals(Severity.Info, DtcDecoder.severityFor(DiagnosticStatus.Pending, milOn = false))
    }
}
