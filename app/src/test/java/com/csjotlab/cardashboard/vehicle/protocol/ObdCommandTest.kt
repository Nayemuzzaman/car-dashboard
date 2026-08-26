package com.csjotlab.cardashboard.vehicle.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdCommandTest {

    /**
     * The read-only boundary is structural, not a convention. Widening this set requires
     * deliberately editing this test, which is the point.
     */
    @Test
    fun `the command set contains only read services`() {
        val subclasses = ObdCommand::class.sealedSubclasses.map { it.simpleName }.toSet()

        assertEquals(
            setOf("CurrentData", "StoredDtcs", "PendingDtcs", "PermanentDtcs", "SupportedPids"),
            subclasses
        )
    }

    /**
     * Every no-argument (data object) subtype is picked up via reflection, so a future singleton
     * command — e.g. a hypothetical `Vin` returning "0902" — is covered automatically without
     * anyone remembering to add it to a hand-written list. Subtypes that take constructor
     * arguments (CurrentData, SupportedPids) still need an explicit instance to construct, same as
     * today.
     */
    private fun allConstructibleRequests(): List<String> {
        val objectRequests = ObdCommand::class.sealedSubclasses
            .mapNotNull { it.objectInstance }
            .map { it.request }
        val parameterizedRequests = listOf(
            ObdCommand.CurrentData(0x0D).request,
            ObdCommand.SupportedPids(0x00).request,
        )
        return objectRequests + parameterizedRequests
    }

    @Test
    fun `no command can request a write service`() {
        val requests = allConstructibleRequests()

        // Mode 04 clears DTCs; mode 2F is an actuator control service. Neither may be constructible.
        assertTrue(requests.none { it.startsWith("04") })
        assertTrue(requests.none { it.startsWith("2F") })
        assertTrue(requests.none { it.startsWith("2E") })
        assertTrue(requests.none { it.startsWith("31") && it.length == 2 })
    }

    @Test
    fun `requests are formatted as two hex digit pairs`() {
        assertEquals("010D", ObdCommand.CurrentData(0x0D).request)
        assertEquals("01A6", ObdCommand.CurrentData(0xA6).request)
        assertEquals("03", ObdCommand.StoredDtcs.request)
        assertEquals("07", ObdCommand.PendingDtcs.request)
        assertEquals("0A", ObdCommand.PermanentDtcs.request)
        assertEquals("0100", ObdCommand.SupportedPids(0x00).request)
        assertEquals("0120", ObdCommand.SupportedPids(0x20).request)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `CurrentData rejects a pid outside one byte`() {
        ObdCommand.CurrentData(0x102)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `CurrentData rejects a negative pid`() {
        ObdCommand.CurrentData(-1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `SupportedPids rejects a basePid outside one byte`() {
        ObdCommand.SupportedPids(0x102)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `SupportedPids rejects a negative basePid`() {
        ObdCommand.SupportedPids(-1)
    }

    @Test
    fun `never reads the VIN via mode 09 PID 02`() {
        assertTrue(allConstructibleRequests().none { it.startsWith("09") })
    }
}
