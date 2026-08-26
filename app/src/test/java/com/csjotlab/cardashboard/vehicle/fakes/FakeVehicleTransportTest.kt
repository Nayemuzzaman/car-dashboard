package com.csjotlab.cardashboard.vehicle.fakes

import app.cash.turbine.test
import com.csjotlab.cardashboard.vehicle.transport.TransportEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeVehicleTransportTest {

    @Test
    fun `answers a known command with its canned reply and a prompt`() = runTest {
        val transport = FakeVehicleTransport(mapOf("010C" to "41 0C 1A F8"))
        transport.open()

        transport.incoming().test {
            transport.write("010C\r".toByteArray())
            val reply = String(awaitItem())
            assertEquals("41 0C 1A F8\r\r>", reply)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("010C"), transport.written)
    }

    @Test
    fun `answers an unknown command with NO DATA`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.open()

        transport.incoming().test {
            transport.write("01FF\r".toByteArray())
            assertEquals("NO DATA\r\r>", String(awaitItem()))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `detaching emits a Detached event`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.events.test {
            transport.open()
            assertEquals(TransportEvent.Opened, awaitItem())
            transport.detach()
            assertEquals(TransportEvent.Detached, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Silence -------------------------------------------------------------------------

    @Test
    fun `silencing one command withholds its reply while other commands still answer`() = runTest {
        val transport = FakeVehicleTransport(mapOf("010C" to "41 0C 1A F8"))
        transport.silence("010C")

        transport.incoming().test {
            transport.write("01FF\r".toByteArray())
            assertEquals("NO DATA\r\r>", String(awaitItem()))

            transport.write("010C\r".toByteArray())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a withheld command is still recorded as written`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.silence("010C")

        transport.incoming().test {
            transport.write("010C\r".toByteArray())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("010C"), transport.written)
    }

    @Test
    fun `going silent withholds replies to every subsequent write regardless of prior configuration`() = runTest {
        val transport = FakeVehicleTransport(mapOf("010C" to "41 0C 1A F8"))

        transport.incoming().test {
            transport.write("010C\r".toByteArray())
            assertEquals("41 0C 1A F8\r\r>", String(awaitItem()))

            transport.goSilent()
            transport.write("010C\r".toByteArray())
            transport.write("01FF\r".toByteArray())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Chunked delivery ------------------------------------------------------------------

    @Test
    fun `a chunked reply is delivered as separate emissions that concatenate to the full reply`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.respondInChunks("010C", listOf("41 0C ", "1A F8\r\r>"))

        transport.incoming().test {
            transport.write("010C\r".toByteArray())
            assertEquals("41 0C ", String(awaitItem()))
            assertEquals("1A F8\r\r>", String(awaitItem()))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the terminating prompt can arrive in a chunk after the payload`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.respondInChunks("010C", listOf("41 0C 1A F8\r\r", ">"))

        transport.incoming().test {
            transport.write("010C\r".toByteArray())
            val first = String(awaitItem())
            val second = String(awaitItem())
            assertTrue(!first.contains(">"))
            assertEquals(">", second)
            assertEquals("41 0C 1A F8\r\r>", first + second)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a chunk boundary can split a token across two emissions`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        // "1A" is split as "1" / "A F8\r\r>" — the accumulation loop must not assume tokens
        // arrive whole.
        transport.respondInChunks("010C", listOf("41 0C 1", "A F8\r\r>"))

        transport.incoming().test {
            transport.write("010C\r".toByteArray())
            val first = String(awaitItem())
            val second = String(awaitItem())
            assertEquals("41 0C 1A F8\r\r>", first + second)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Delayed delivery --------------------------------------------------------------------

    @Test
    fun `a delayed reply is not observed before its delay elapses`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.respondAfterDelay("010C", "41 0C 1A F8", delayMs = 500, scope = backgroundScope)

        transport.incoming().test {
            transport.write("010C\r".toByteArray())
            advanceTimeBy(499)
            runCurrent()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a delayed reply is observed once its delay elapses`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.respondAfterDelay("010C", "41 0C 1A F8", delayMs = 500, scope = backgroundScope)

        transport.incoming().test {
            transport.write("010C\r".toByteArray())
            advanceTimeBy(500)
            runCurrent()
            assertEquals("41 0C 1A F8\r\r>", String(awaitItem()))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `write returns immediately even though the configured reply is delayed`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.respondAfterDelay("010C", "41 0C 1A F8", delayMs = 5_000, scope = backgroundScope)

        transport.write("010C\r".toByteArray())
        // write() suspended only long enough to hand off; it did not itself wait out the delay.
        assertEquals(listOf("010C"), transport.written)
    }

    @Test
    fun `a reply already scheduled before going silent still arrives`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.respondAfterDelay("010C", "41 0C 1A F8", delayMs = 500, scope = backgroundScope)

        transport.incoming().test {
            transport.write("010C\r".toByteArray())
            transport.goSilent()
            advanceTimeBy(500)
            runCurrent()
            assertEquals("41 0C 1A F8\r\r>", String(awaitItem()))
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- fail() / TransportEvent.Failed -------------------------------------------------------

    @Test
    fun `fail emits a Failed event carrying the given reason`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.events.test {
            transport.open()
            assertEquals(TransportEvent.Opened, awaitItem())
            transport.fail("adapter detached mid-read")
            assertEquals(TransportEvent.Failed("adapter detached mid-read"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a failure can arrive after write but before a withheld reply ever completes`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.silence("010C")

        transport.events.test {
            transport.incoming().test {
                transport.write("010C\r".toByteArray())
                transport.fail("USB link dropped")
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(TransportEvent.Failed("USB link dropped"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Malformed bytes ---------------------------------------------------------------------

    @Test
    fun `a raw reply bypasses the canned framing entirely, including bytes that are not valid ELM327 output`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        val malformed = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 'X'.code.toByte(), 'Z'.code.toByte())
        transport.respondWithRawBytes("010C", malformed)

        transport.incoming().test {
            transport.write("010C\r".toByteArray())
            val received = awaitItem()
            assertTrue(received.contentEquals(malformed))
            cancelAndIgnoreRemainingEvents()
        }
    }
}
