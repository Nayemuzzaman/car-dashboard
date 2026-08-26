package com.csjotlab.cardashboard.vehicle.protocol

import com.csjotlab.cardashboard.vehicle.fakes.FakeVehicleTransport
import com.csjotlab.cardashboard.vehicle.transport.TransportEvent
import com.csjotlab.cardashboard.vehicle.transport.VehicleTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Elm327SessionTest {

    private fun session(responses: Map<String, String>) =
        Elm327Session(FakeVehicleTransport(responses).also { }, commandTimeoutMs = 1_000L)

    private val handshakeResponses = mapOf(
        "ATZ" to "ELM327 v1.5",
        "ATE0" to "OK",
        "ATL0" to "OK",
        "ATS0" to "OK",
        "ATH0" to "OK",
        "ATSP0" to "OK",
        "ATI" to "ELM327 v1.5",
        "0100" to "41 00 BE 1F A8 13",
    )

    @Test
    fun `a successful handshake reports the adapter and its capabilities`() = runTest {
        val result = session(handshakeResponses).handshake()

        assertTrue(result is HandshakeResult.Ready)
        val ready = result as HandshakeResult.Ready
        assertTrue(ready.identity.elmCompatible)
        assertTrue(ready.supportedPids.contains(ObdPid.SPEED))
        assertTrue(ready.supportedPids.contains(ObdPid.RPM))
    }

    @Test
    fun `a device that is not an ELM327 is rejected rather than guessed at`() = runTest {
        val result = session(handshakeResponses + ("ATI" to "SOME USB WIDGET")).handshake()
        assertTrue(result is HandshakeResult.NotElmCompatible)
    }

    @Test
    fun `an adapter that cannot reach the bus reports bus unavailable`() = runTest {
        val result = session(handshakeResponses + ("0100" to "UNABLE TO CONNECT")).handshake()
        assertTrue(result is HandshakeResult.BusUnavailable)
    }

    @Test
    fun `a data reply is parsed into its payload bytes`() = runTest {
        val result = session(mapOf("010C" to "41 0C 1A F8")).request(ObdCommand.CurrentData(ObdPid.RPM))
        assertEquals(ObdResult.Data(listOf(0x1A, 0xF8)), result)
    }

    @Test
    fun `responses without spaces parse identically`() = runTest {
        val result = session(mapOf("010C" to "410C1AF8")).request(ObdCommand.CurrentData(ObdPid.RPM))
        assertEquals(ObdResult.Data(listOf(0x1A, 0xF8)), result)
    }

    @Test
    fun `the SEARCHING preamble is stripped`() = runTest {
        val result = session(mapOf("010D" to "SEARCHING...\r41 0D 3C")).request(ObdCommand.CurrentData(ObdPid.SPEED))
        assertEquals(ObdResult.Data(listOf(0x3C)), result)
    }

    @Test
    fun `every documented non data reply maps to a typed result`() = runTest {
        assertEquals(ObdResult.NoData, session(mapOf("010D" to "NO DATA")).request(ObdCommand.CurrentData(ObdPid.SPEED)))
        assertTrue(session(mapOf("010D" to "UNABLE TO CONNECT")).request(ObdCommand.CurrentData(ObdPid.SPEED)) is ObdResult.BusError)
        assertTrue(session(mapOf("010D" to "CAN ERROR")).request(ObdCommand.CurrentData(ObdPid.SPEED)) is ObdResult.BusError)
        assertTrue(session(mapOf("010D" to "BUS INIT: ERROR")).request(ObdCommand.CurrentData(ObdPid.SPEED)) is ObdResult.BusError)
        assertTrue(session(mapOf("010D" to "STOPPED")).request(ObdCommand.CurrentData(ObdPid.SPEED)) is ObdResult.AdapterError)
        assertTrue(session(mapOf("010D" to "?")).request(ObdCommand.CurrentData(ObdPid.SPEED)) is ObdResult.AdapterError)
        assertTrue(session(mapOf("010D" to "BUFFER FULL")).request(ObdCommand.CurrentData(ObdPid.SPEED)) is ObdResult.AdapterError)
    }

    @Test
    fun `a mismatched response header is not accepted as data`() = runTest {
        // Reply to a different PID than the one requested.
        val result = session(mapOf("010D" to "41 0C 1A F8")).request(ObdCommand.CurrentData(ObdPid.SPEED))
        assertTrue(result is ObdResult.AdapterError)
    }

    @Test
    fun `garbage is rejected rather than partially decoded`() = runTest {
        assertTrue(session(mapOf("010D" to "ZZZZ")).request(ObdCommand.CurrentData(ObdPid.SPEED)) is ObdResult.AdapterError)
    }

    @Test
    fun `mode 03 decodes two stored codes and drops the padding pair`() = runTest {
        // 43 02 = two codes; 0301 = P0301; 0420 = P0420; trailing 0000 is padding.
        val codes = session(mapOf("03" to "43 02 03 01 04 20 00 00")).readDtcs(ObdCommand.StoredDtcs)
        assertEquals(listOf("P0301", "P0420"), codes)
    }

    @Test
    fun `mode 03 with no codes returns an empty list`() = runTest {
        assertEquals(emptyList<String>(), session(mapOf("03" to "43 00")).readDtcs(ObdCommand.StoredDtcs))
        assertEquals(emptyList<String>(), session(mapOf("03" to "NO DATA")).readDtcs(ObdCommand.StoredDtcs))
    }

    // ---------------------------------------------------------------------------------------
    // Framing: leading-byte alignment. Task 15's decoders deliberately tolerate over-length
    // frames, so nothing below this layer can catch a framer that leaves the echoed mode/PID
    // bytes in the payload — [0x00, 0x3C] would silently read as a confident "0 km/h".
    // ---------------------------------------------------------------------------------------

    @Test
    fun `byte zero of the payload is the first data byte, not the echoed mode or PID`() = runTest {
        val result = session(mapOf("010D" to "41 0D 3C")).request(ObdCommand.CurrentData(ObdPid.SPEED))

        val bytes = (result as ObdResult.Data).bytes
        assertEquals("the mode echo and PID echo must both be stripped", 1, bytes.size)
        assertEquals(0x3C, bytes[0])
        // End to end through the decoder that would have been lied to.
        assertEquals(60f, ObdPid.decodeSpeedKph(bytes))
    }

    @Test
    fun `a command echo and a CAN frame header are stripped before alignment`() = runTest {
        // An adapter that ignored ATE0 and ATH0: the request is echoed on its own line and the
        // reply carries an 11-bit CAN id plus a single-frame PCI length byte.
        val result = session(mapOf("010D" to "010D\r7E8 03 41 0D 3C")).request(ObdCommand.CurrentData(ObdPid.SPEED))
        assertEquals(ObdResult.Data(listOf(0x3C)), result)
    }

    @Test
    fun `a frame header whose declared length does not match is rejected rather than guessed`() = runTest {
        // 05 claims five bytes follow but three do. Stripping on a hunch here would misalign the
        // payload, so the frame is refused outright.
        val result = session(mapOf("010D" to "7E8 05 41 0D 3C")).request(ObdCommand.CurrentData(ObdPid.SPEED))
        assertTrue(result is ObdResult.AdapterError)
    }

    // ---------------------------------------------------------------------------------------
    // Framing: accumulation across chunks. A real serial port delivers a reply in however many
    // reads it feels like.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a reply split across chunks with the prompt arriving last is accumulated`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.respondInChunks("010D", listOf("41 ", "0D 3C", "\r\r>"))
        val session = Elm327Session(transport, commandTimeoutMs = 1_000L)

        assertEquals(
            ObdResult.Data(listOf(0x3C)),
            session.request(ObdCommand.CurrentData(ObdPid.SPEED)),
        )
    }

    @Test
    fun `a chunk boundary falling mid token does not corrupt the payload`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.respondInChunks("010C", listOf("4", "1 0", "C 1", "A F", "8\r", "\r>"))
        val session = Elm327Session(transport, commandTimeoutMs = 1_000L)

        assertEquals(
            ObdResult.Data(listOf(0x1A, 0xF8)),
            session.request(ObdCommand.CurrentData(ObdPid.RPM)),
        )
    }

    @Test
    fun `bytes that never terminate with a prompt are not treated as a complete reply`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.respondWithRawBytes("010D", "41 0D 3C".toByteArray())
        val session = Elm327Session(transport, commandTimeoutMs = 1_000L)

        val pending = async { session.request(ObdCommand.CurrentData(ObdPid.SPEED)) }
        runCurrent()
        assertFalse("an unterminated reply is not a reply", pending.isCompleted)

        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(ObdResult.Timeout, pending.await())
    }

    // ---------------------------------------------------------------------------------------
    // Timeouts. The boundary is pinned: a reply at 999 ms under a 1_000 ms budget is accepted,
    // a reply at 1_001 ms is not.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a silent adapter times out instead of hanging forever`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.silence("010D")
        val session = Elm327Session(transport, commandTimeoutMs = 1_000L)

        val pending = async { session.request(ObdCommand.CurrentData(ObdPid.SPEED)) }
        runCurrent()
        assertFalse(pending.isCompleted)

        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(ObdResult.Timeout, pending.await())
    }

    @Test
    fun `a reply arriving just inside the command timeout is accepted`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.respondAfterDelay("010D", "41 0D 3C", delayMs = 999L, scope = backgroundScope)
        val session = Elm327Session(transport, commandTimeoutMs = 1_000L)

        val pending = async { session.request(ObdCommand.CurrentData(ObdPid.SPEED)) }
        runCurrent()
        // Stops one millisecond short of the deadline, so only the reply can have been delivered.
        advanceTimeBy(999L)
        runCurrent()

        assertTrue("a reply 1 ms inside the budget must be accepted", pending.isCompleted)
        assertEquals(ObdResult.Data(listOf(0x3C)), pending.await())
    }

    @Test
    fun `a reply arriving just outside the command timeout is a timeout`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.respondAfterDelay("010D", "41 0D 3C", delayMs = 1_001L, scope = backgroundScope)
        val session = Elm327Session(transport, commandTimeoutMs = 1_000L)

        val pending = async { session.request(ObdCommand.CurrentData(ObdPid.SPEED)) }
        runCurrent()
        advanceTimeBy(999L)
        runCurrent()
        assertFalse("the budget has not expired at t=999", pending.isCompleted)

        // One more millisecond reaches the deadline, which is 1 ms ahead of the reply.
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(ObdResult.Timeout, pending.await())
    }

    @Test
    fun `an adapter that never answers the handshake reports bus unavailable rather than hanging`() = runTest {
        val transport = FakeVehicleTransport(handshakeResponses)
        transport.goSilent()
        val session = Elm327Session(transport, commandTimeoutMs = 1_000L)

        val pending = async { session.handshake() }
        runCurrent()
        advanceTimeBy(1_001L)
        runCurrent()

        assertTrue(pending.isCompleted)
        assertTrue(pending.await() is HandshakeResult.BusUnavailable)
        assertEquals("the handshake aborts on the first unanswered command", listOf("ATZ"), transport.written)
    }

    @Test
    fun `a transport failure ends the exchange instead of waiting out the timeout`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.silence("010D")
        val session = Elm327Session(transport, commandTimeoutMs = 10_000L)

        val pending = async { session.request(ObdCommand.CurrentData(ObdPid.SPEED)) }
        runCurrent()
        transport.fail("USB device detached")
        runCurrent()

        assertTrue("the failure must end the exchange without any time passing", pending.isCompleted)
        val result = pending.await()
        assertTrue(result is ObdResult.AdapterError)
        assertTrue((result as ObdResult.AdapterError).reason.contains("USB device detached"))
    }

    @Test
    fun `a late reply to a timed out command is not attributed to the next command`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.respondAfterDelay("010D", "41 0D 3C", delayMs = 1_500L, scope = backgroundScope)
        val session = Elm327Session(transport, commandTimeoutMs = 1_000L)

        val speed = async { session.request(ObdCommand.CurrentData(ObdPid.SPEED)) }
        runCurrent()
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(ObdResult.Timeout, speed.await())

        // The stale speed reply is still on the wire and lands during the next exchange.
        transport.silence("010C")
        val rpm = async { session.request(ObdCommand.CurrentData(ObdPid.RPM)) }
        runCurrent()
        advanceTimeBy(600L)
        runCurrent()

        val result = rpm.await()
        // The frame is discarded as the timed-out request's outstanding reply before the header
        // check ever sees it; either way the one forbidden outcome is a value.
        assertTrue("a speed reply may never populate the RPM field", result !is ObdResult.Data)
        assertEquals(ObdResult.Timeout, result)
    }

    @Test
    fun `concurrent requests are serialised so one command cannot consume another's reply`() = runTest {
        val transport = FakeVehicleTransport(mapOf("010C" to "41 0C 1A F8"))
        transport.respondAfterDelay("010D", "41 0D 3C", delayMs = 500L, scope = backgroundScope)
        val session = Elm327Session(transport, commandTimeoutMs = 1_000L)

        val speed = async { session.request(ObdCommand.CurrentData(ObdPid.SPEED)) }
        val rpm = async { session.request(ObdCommand.CurrentData(ObdPid.RPM)) }
        runCurrent()
        advanceTimeBy(600L)
        runCurrent()

        assertEquals(ObdResult.Data(listOf(0x3C)), speed.await())
        assertEquals(ObdResult.Data(listOf(0x1A, 0xF8)), rpm.await())
    }

    // ---------------------------------------------------------------------------------------
    // Capability discovery. "Undecodable" and "decoded as nothing" are different facts and must
    // stay different all the way up to the source layer.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an undecodable capability reply is a discovery failure, not an empty capability set`() = runTest {
        val result = session(handshakeResponses + ("0100" to "41 00 BE")).handshake()

        assertTrue(
            "a short bitmask means we do not know what is supported; it never means nothing is",
            result is HandshakeResult.CapabilityDiscoveryFailed,
        )
    }

    @Test
    fun `a bitmask that decodes to nothing supported is Ready with an empty set`() = runTest {
        val result = session(handshakeResponses + ("0100" to "41 00 00 00 00 00")).handshake()

        assertTrue(result is HandshakeResult.Ready)
        assertEquals(emptySet<Int>(), (result as HandshakeResult.Ready).supportedPids)
        assertEquals(emptySet<Int>(), result.undiscoveredBanks)
    }

    @Test
    fun `the bank walk continues only while the previous bank advertises the next`() = runTest {
        val transport = FakeVehicleTransport(
            handshakeResponses + mapOf(
                "0100" to "41 00 00 00 00 01", // only PID 0x20 (the next bank) is supported
                "0120" to "41 20 80 00 00 00", // PID 0x21 supported; 0x40 is not
            ),
        )
        val result = Elm327Session(transport, commandTimeoutMs = 1_000L).handshake()

        assertEquals(setOf(0x20, 0x21), (result as HandshakeResult.Ready).supportedPids)
        assertEquals(emptySet<Int>(), result.undiscoveredBanks)
        assertFalse("bank 0x40 was never advertised", transport.written.contains("0140"))
    }

    @Test
    fun `a bank the vehicle advertises but does not answer is undiscovered, not unsupported`() = runTest {
        // The 0100 bitmask ends in 0x13, whose low bit advertises bank 0x20, but no reply for 0120
        // is configured, so the adapter answers NO DATA. Once the chain breaks we know nothing
        // about any later bank either: the bitmask that would have advertised them never arrived.
        val result = session(handshakeResponses).handshake()

        assertEquals(
            setOf(0x20, 0x40, 0x60, 0x80, 0xA0),
            (result as HandshakeResult.Ready).undiscoveredBanks,
        )
        assertTrue(
            "the odometer's bank must not silently become unsupported",
            ObdPid.ODOMETER / 0x20 * 0x20 in result.undiscoveredBanks,
        )
    }

    @Test
    fun `the handshake puts nothing on the wire but AT configuration and capability reads`() = runTest {
        val transport = FakeVehicleTransport(handshakeResponses)
        Elm327Session(transport, commandTimeoutMs = 1_000L).handshake()

        assertEquals(
            listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATI", "0100", "0120"),
            transport.written,
        )
    }

    // ---------------------------------------------------------------------------------------
    // DTC reads. `readDtcs` cannot distinguish "scanned, no codes" from "the scan failed", so
    // the richer result exists for callers that must not retire a fault on a failed scan.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a failed DTC scan is reported as a failure and never as zero codes`() = runTest {
        assertTrue(
            session(mapOf("03" to "CAN ERROR")).readDtcResult(ObdCommand.StoredDtcs) is DtcReadResult.Failed,
        )

        val transport = FakeVehicleTransport(emptyMap())
        transport.silence("03")
        val timedOut = Elm327Session(transport, commandTimeoutMs = 1_000L)
        val pending = async { timedOut.readDtcResult(ObdCommand.StoredDtcs) }
        runCurrent()
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(DtcReadResult.Failed(ObdResult.Timeout), pending.await())
    }

    @Test
    fun `a scan that completes with no codes is a completed scan`() = runTest {
        // On CAN a vehicle with nothing stored answers "43 00" — a count of zero. That, and only
        // that, is a clean bill of health.
        assertEquals(
            DtcReadResult.Codes(emptyList()),
            session(mapOf("03" to "43 00")).readDtcResult(ObdCommand.StoredDtcs),
        )
    }

    @Test
    fun `an unanswered DTC service is a failed scan, not a clean one`() = runTest {
        // NO DATA means nothing on the bus answered the request at all. Reporting that as a
        // completed scan finding zero codes is a fabricated health claim, and it would license a
        // caller to retire faults it is still storing.
        assertEquals(
            DtcReadResult.Failed(ObdResult.NoData),
            session(mapOf("03" to "NO DATA")).readDtcResult(ObdCommand.StoredDtcs),
        )
    }

    @Test
    fun `pending and permanent DTC services use their own response headers`() = runTest {
        assertEquals(
            listOf("P0420"),
            session(mapOf("07" to "47 01 04 20")).readDtcs(ObdCommand.PendingDtcs),
        )
        assertEquals(
            listOf("U0100"),
            session(mapOf("0A" to "4A 01 C1 00")).readDtcs(ObdCommand.PermanentDtcs),
        )
        // A mode 07 reply must not be accepted for a mode 03 request.
        assertTrue(
            session(mapOf("03" to "47 01 04 20")).readDtcResult(ObdCommand.StoredDtcs) is DtcReadResult.Failed,
        )
    }

    @Test
    fun `a DTC payload that is not a count plus whole code pairs is refused, not reframed`() = runTest {
        // Payload 02 03 01 04: a count of two followed by three bytes. Reading the leading byte as
        // half of a code pair instead would manufacture P0203 and P0104 out of a truncated frame.
        val result = session(mapOf("03" to "43 02 03 01 04")).readDtcResult(ObdCommand.StoredDtcs)

        assertTrue("a truncated DTC frame is not a code list", result is DtcReadResult.Failed)
    }

    @Test
    fun `a DTC frame carrying fewer pairs than it declares is refused`() = runTest {
        // Declares three codes, carries one pair.
        val result = session(mapOf("03" to "43 03 03 01")).readDtcResult(ObdCommand.StoredDtcs)

        assertTrue(result is DtcReadResult.Failed)
    }

    @Test
    fun `a DTC frame declaring fewer codes than it carries is refused, not trimmed`() = runTest {
        // Declares zero codes and carries P0301. Trimming to the declared count reports a
        // completed clean scan on a fault-bearing frame — the same outcome as dropping an ECU's
        // whole line, arriving by a different route.
        val result = session(mapOf("03" to "43 00 03 01")).readDtcResult(ObdCommand.StoredDtcs)

        assertTrue("a frame whose count contradicts its codes is malformed", result is DtcReadResult.Failed)
    }

    @Test
    fun `a DTC frame declaring one code while carrying two is refused`() = runTest {
        val result = session(mapOf("03" to "43 01 03 01 04 20")).readDtcResult(ObdCommand.StoredDtcs)

        assertTrue("P0420 must not be dropped silently", result is DtcReadResult.Failed)
    }

    // ---------------------------------------------------------------------------------------
    // Multi-line replies. Mode 03 is a functional request, so every ECU that has something to say
    // answers, and with ATH0 the adapter prints one line per responder. Taking the first line and
    // discarding the rest reports a *completed* scan that found nothing on a vehicle that is
    // storing a fault — the worst outcome this layer can produce.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a second ECU's stored codes survive a first ECU's empty reply`() = runTest {
        // The engine ECU has nothing stored; the transmission ECU has P0701.
        val result = session(mapOf("03" to "43 00\r43 01 07 01")).readDtcResult(ObdCommand.StoredDtcs)

        assertEquals(DtcReadResult.Codes(listOf("P0701")), result)
    }

    @Test
    fun `codes from every responding ECU are unioned`() = runTest {
        val result = session(mapOf("03" to "43 01 03 01\r43 02 07 01 04 20")).readDtcResult(ObdCommand.StoredDtcs)

        assertEquals(DtcReadResult.Codes(listOf("P0301", "P0701", "P0420")), result)
    }

    @Test
    fun `the same code reported by two ECUs is listed once`() = runTest {
        val result = session(mapOf("03" to "43 01 03 01\r43 01 03 01")).readDtcResult(ObdCommand.StoredDtcs)

        assertEquals(DtcReadResult.Codes(listOf("P0301")), result)
    }

    @Test
    fun `a NO DATA line beside a DTC frame fails the scan rather than reporting a partial one`() = runTest {
        // A responder that was not understood is a responder that was not scanned. For a value
        // request a NO DATA line beside a reading is harmless, but for a fault scan the whole
        // point of the result is that every ECU was heard.
        val result = session(mapOf("03" to "NO DATA\r43 01 07 01")).readDtcResult(ObdCommand.StoredDtcs)

        assertTrue(result is DtcReadResult.Failed)
    }

    @Test
    fun `an error from one ECU fails the whole scan even when another ECU answered`() = runTest {
        // Half a scan is not a scan: a caller must not retire the codes it is holding on this.
        val result = session(mapOf("03" to "CAN ERROR\r43 01 07 01")).readDtcResult(ObdCommand.StoredDtcs)

        assertTrue(result is DtcReadResult.Failed)
    }

    @Test
    fun `capability bitmasks from every ECU are unioned`() = runTest {
        // First ECU advertises PID 0x0D only, second advertises PID 0x0C only.
        val result = session(
            handshakeResponses + ("0100" to "41 00 00 08 00 00\r41 00 00 10 00 00"),
        ).handshake()

        val ready = result as HandshakeResult.Ready
        assertEquals(setOf(ObdPid.RPM, ObdPid.SPEED), ready.supportedPids)
    }

    @Test
    fun `a bitmask one ECU could not deliver decodably fails the bank rather than shrinking it`() = runTest {
        val result = session(handshakeResponses + ("0100" to "41 00 BE 1F A8 13\r41 00 BE")).handshake()

        assertTrue(result is HandshakeResult.CapabilityDiscoveryFailed)
    }

    @Test
    fun `a NO DATA line alongside a data line does not mask the data`() = runTest {
        // One ECU has nothing for this PID and says so; another answers. Matching the word reply
        // against the whole blob instead of per line throws the reading away.
        val result = session(mapOf("010D" to "NO DATA\r41 0D 3C")).request(ObdCommand.CurrentData(ObdPid.SPEED))

        assertEquals(ObdResult.Data(listOf(0x3C)), result)
    }

    @Test
    fun `two ECUs disagreeing about a reading is not resolved by picking one`() = runTest {
        val result = session(mapOf("010D" to "41 0D 3C\r41 0D 50")).request(ObdCommand.CurrentData(ObdPid.SPEED))

        assertTrue("60 km/h and 80 km/h cannot both be the speed", result is ObdResult.AdapterError)
    }

    @Test
    fun `two ECUs agreeing about a reading is accepted`() = runTest {
        val result = session(mapOf("010D" to "41 0D 3C\r41 0D 3C")).request(ObdCommand.CurrentData(ObdPid.SPEED))

        assertEquals(ObdResult.Data(listOf(0x3C)), result)
    }

    @Test
    fun `a line that does not match the header is not silently dropped alongside one that does`() = runTest {
        val result = session(mapOf("010D" to "41 0D 3C\r41 0C 1A F8")).request(ObdCommand.CurrentData(ObdPid.SPEED))

        assertTrue(result is ObdResult.AdapterError)
    }

    // ---------------------------------------------------------------------------------------
    // Pipe synchronisation.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a stale reply is not served to the next request for the same PID`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.respondAfterDelay("010D", "41 0D 3C", delayMs = 1_500L, scope = backgroundScope)
        val session = Elm327Session(transport, commandTimeoutMs = 1_000L)

        val first = async { session.request(ObdCommand.CurrentData(ObdPid.SPEED)) }
        runCurrent()
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(ObdResult.Timeout, first.await())

        // The adapter's answer to the *first* request is still to come. The second request is for
        // the same PID, so the header check cannot tell them apart — only knowing that a reply is
        // still outstanding can.
        transport.silence("010D")
        val second = async { session.request(ObdCommand.CurrentData(ObdPid.SPEED)) }
        runCurrent()
        advanceTimeBy(1_001L)
        runCurrent()

        assertEquals(
            "a frame that answers a timed-out request is not a reading for the next one",
            ObdResult.Timeout,
            second.await(),
        )
    }

    @Test
    fun `an exchange skips exactly the outstanding frame and then reads its own`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        // The adapter is running late: this answers the FIRST request, at t=1500.
        transport.respondAfterDelay("010D", "41 0D 3C", delayMs = 1_500L, scope = backgroundScope)
        val session = Elm327Session(transport, commandTimeoutMs = 1_000L)

        val first = async { session.request(ObdCommand.CurrentData(ObdPid.SPEED)) }
        runCurrent()
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(ObdResult.Timeout, first.await())

        // The second request is written at t=1001 and the adapter, answering in order, replies to
        // it at t=1601 — after the stale frame it still owed. Exactly one frame must be skipped:
        // skipping none returns the stale 0x3C, skipping two returns nothing.
        transport.respondAfterDelay("010D", "41 0D 28", delayMs = 600L, scope = backgroundScope)
        val second = async { session.request(ObdCommand.CurrentData(ObdPid.SPEED)) }
        runCurrent()
        advanceTimeBy(999L)
        runCurrent()
        assertEquals(
            "the fresh reading, not the stale one the previous request abandoned",
            ObdResult.Data(listOf(0x28)),
            second.await(),
        )

        // And the pipe is synchronised again: an ordinary immediate reply is read as itself.
        transport.respondTo("010D", "41 0D 14")
        val third = async { session.request(ObdCommand.CurrentData(ObdPid.SPEED)) }
        runCurrent()
        assertEquals(ObdResult.Data(listOf(0x14)), third.await())
    }

    @Test
    fun `a request cancelled mid exchange still accounts for the reply it is owed`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.respondAfterDelay("010D", "41 0D 3C", delayMs = 500L, scope = backgroundScope)
        val session = Elm327Session(transport, commandTimeoutMs = 2_000L)

        // A polling job going away mid-exchange — exactly what a cancelled source-layer loop does.
        val abandoned = async { session.request(ObdCommand.CurrentData(ObdPid.SPEED)) }
        runCurrent()
        abandoned.cancel()
        runCurrent()

        // The adapter still owes a reply to that write, and it is for this very PID.
        transport.silence("010D")
        val next = async { session.request(ObdCommand.CurrentData(ObdPid.SPEED)) }
        runCurrent()
        advanceTimeBy(2_001L)
        runCurrent()

        assertEquals(
            "the cancelled command's frame is not this command's reading",
            ObdResult.Timeout,
            next.await(),
        )
    }

    @Test
    fun `a stream that never sends a prompt is bounded rather than buffered without limit`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.respondWithRawBytes("010D", ByteArray(8_192) { 'A'.code.toByte() })
        val session = Elm327Session(transport, commandTimeoutMs = 10_000L)

        val pending = async { session.request(ObdCommand.CurrentData(ObdPid.SPEED)) }
        runCurrent()

        assertTrue("the buffer must be given up on, not grown", pending.isCompleted)
        assertTrue(pending.await() is ObdResult.AdapterError)
    }

    // ---------------------------------------------------------------------------------------
    // A transport whose flows complete. FakeVehicleTransport's never do, but a callbackFlow-based
    // USB transport's will the moment the device is unplugged.
    // ---------------------------------------------------------------------------------------

    private class ClosedPipeTransport : VehicleTransport {
        override val events: Flow<TransportEvent> = emptyFlow()
        override suspend fun open() = Unit
        override suspend fun write(bytes: ByteArray) = Unit
        override fun incoming(): Flow<ByteArray> = emptyFlow()
        override suspend fun close() = Unit
    }

    @Test
    fun `a transport whose stream has ended yields a result instead of throwing`() = runTest {
        val session = Elm327Session(ClosedPipeTransport(), commandTimeoutMs = 1_000L)

        val result = session.request(ObdCommand.CurrentData(ObdPid.SPEED))

        assertTrue(result is ObdResult.AdapterError)
    }

    @Test
    fun `a handshake over an ended stream reports unavailable instead of throwing`() = runTest {
        val session = Elm327Session(ClosedPipeTransport(), commandTimeoutMs = 1_000L)

        assertTrue(session.handshake() is HandshakeResult.BusUnavailable)
    }
}
