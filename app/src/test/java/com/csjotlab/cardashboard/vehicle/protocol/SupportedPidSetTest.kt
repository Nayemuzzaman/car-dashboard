package com.csjotlab.cardashboard.vehicle.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedPidSetTest {

    @Test
    fun `the top bit of the first byte means PID one is supported`() {
        val supported = SupportedPidSet.decode(0x00, listOf(0x80, 0x00, 0x00, 0x00))
        assertEquals(setOf(0x01), supported)
    }

    @Test
    fun `the bottom bit of the last byte means the next base PID is supported`() {
        val supported = SupportedPidSet.decode(0x00, listOf(0x00, 0x00, 0x00, 0x01))
        assertEquals(setOf(0x20), supported)
    }

    @Test
    fun `a realistic bitmask yields exactly the expected PIDs`() {
        // 0xBE1FA813 = 1011 1110  0001 1111  1010 1000  0001 0011
        // Bit i (0-indexed from the MSB of byte 0) supported => PID (i + 1) supported.
        val supported = SupportedPidSet.decode(0x00, listOf(0xBE, 0x1F, 0xA8, 0x13))
        val expected = setOf(
            0x01, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15,
            0x1C, 0x1F, 0x20,
        )
        assertEquals(expected, supported)
        // Named PIDs this dashboard actually consumes are among them.
        assertTrue(expected.contains(ObdPid.RPM))
        assertTrue(expected.contains(ObdPid.SPEED))
        assertTrue(expected.contains(ObdPid.COOLANT))
    }

    /**
     * Discovery failure is not capability: a garbled frame must decode to null ("undecodable"),
     * never to an empty set, or a consumer treating "not in the set" as Unsupported would mark
     * every field unsupported after one bad discovery response. See global constraints, "capability
     * discovery is authoritative" / "discovery failure is not capability".
     */
    @Test
    fun `a malformed frame is undecodable rather than reporting zero capability`() {
        assertNull(SupportedPidSet.decode(0x00, listOf(0xBE, 0x1F)))
        assertNull(SupportedPidSet.decode(0x00, emptyList()))
    }

    @Test
    fun `the second bank offsets by thirty two`() {
        assertEquals(setOf(0x21), SupportedPidSet.decode(0x20, listOf(0x80, 0x00, 0x00, 0x00)))
    }

    /**
     * An all-zero mask is a successfully decoded frame that happens to report no PIDs — this must
     * be distinguishable from an undecodable frame (above), which is null, not an empty set.
     */
    @Test
    fun `an all zero mask decodes successfully to no supported PIDs, distinct from undecodable`() {
        val decoded = SupportedPidSet.decode(0x00, listOf(0x00, 0x00, 0x00, 0x00))
        assertEquals(emptySet<Int>(), decoded)
    }

    @Test
    fun `an all one mask means every PID in the bank is supported`() {
        val supported = SupportedPidSet.decode(0x00, listOf(0xFF, 0xFF, 0xFF, 0xFF))
        assertEquals((0x01..0x20).toSet(), supported)
    }

    @Test
    fun `a frame longer than four bytes still decodes using only the first four`() {
        val supported = SupportedPidSet.decode(0x00, listOf(0x80, 0x00, 0x00, 0x00, 0xFF, 0xFF))
        assertEquals(setOf(0x01), supported)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an unaligned base PID is rejected rather than yielding a meaningless capability set`() {
        // 0x0D is a PID, not one of the mode-01 capability bank bases (00, 20, 40, 60, 80, A0, C0).
        SupportedPidSet.decode(0x0D, listOf(0x80, 0x00, 0x00, 0x00))
    }

    @Test
    fun `every valid bank base is accepted`() {
        for (basePid in setOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)) {
            assertEquals(emptySet<Int>(), SupportedPidSet.decode(basePid, listOf(0x00, 0x00, 0x00, 0x00)))
        }
    }
}
