package com.csjotlab.cardashboard.vehicle.data

import com.csjotlab.cardashboard.vehicle.domain.Clock
import com.csjotlab.cardashboard.vehicle.domain.AdapterIdentity
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VehicleLogTest {

    @Test
    fun `policy events use the shared tag and successful diagnostics include count and codes`() {
        val entries = mutableListOf<Pair<String, String>>()
        val log = VehicleLog(clock = Clock { 1_000L }, sink = { tag, message -> entries += tag to message })

        log.sourceSelected(VehicleSourceId.OBD_USB)
        log.deviceAttached()
        log.permissionResult(granted = true)
        log.adapterIdentity(AdapterIdentity(rawIdentity = "ELM327 v1.5", elmCompatible = true))
        log.connectionEstablished()
        log.connectionLost("cable detached")
        log.unsupportedDevice("not ELM compatible")
        log.protocolError("CAN ERROR")
        log.diagnosticsScan(listOf("P0301", "P0420"))
        log.deviceDetached()

        assertTrue(entries.isNotEmpty())
        assertTrue(entries.all { (tag, _) -> tag == "CarDash/Vehicle" })
        assertTrue(entries.any { (_, message) -> message.contains("count=2") })
        assertTrue(entries.any { (_, message) -> message.contains("P0301,P0420") })
        assertTrue(entries.any { (_, message) -> message == "adapter identity: ELM327 v1.5" })
        assertFalse(entries.any { (_, message) -> message.contains("serial", ignoreCase = true) })
    }

    @Test
    fun `repeated telemetry inside ten seconds emits nothing`() {
        var nowMs = 0L
        val entries = mutableListOf<String>()
        val log = VehicleLog(
            clock = Clock { nowMs },
            telemetryWindowMs = 10_000L,
            sink = { _, message -> entries += message },
        )

        log.telemetrySummary("speed=60")
        nowMs = 1L
        log.telemetrySummary("speed=61")
        nowMs = 9_999L
        log.telemetrySummary("speed=62")

        assertEquals(listOf("telemetry: speed=60"), entries)

        nowMs = 10_000L
        log.telemetrySummary("speed=63")
        assertEquals(listOf("telemetry: speed=60", "telemetry: speed=63"), entries)
    }

    @Test
    fun `telemetry summaries are disabled outside debug builds`() {
        val entries = mutableListOf<String>()
        val log = VehicleLog(
            clock = Clock { 1_000L },
            debugTelemetryEnabled = false,
            sink = { _, message -> entries += message },
        )

        log.telemetrySummary("speed=60")

        assertTrue(entries.isEmpty())
    }

    @Test
    fun `repeated protocol failures are rate limited by event kind`() {
        var nowMs = 0L
        val entries = mutableListOf<String>()
        val log = VehicleLog(
            clock = Clock { nowMs },
            eventWindowMs = 10_000L,
            sink = { _, message -> entries += message },
        )

        log.protocolError("STOPPED")
        nowMs = 500L
        log.protocolError("BUFFER FULL")
        nowMs = 10_000L
        log.protocolError("CAN ERROR")

        assertEquals(2, entries.size)
        assertTrue(entries.first().contains("STOPPED"))
        assertTrue(entries.last().contains("CAN ERROR"))
    }

    @Test
    fun `close failure is not suppressed by a recent protocol error`() {
        val entries = mutableListOf<String>()
        val log = VehicleLog(clock = Clock { 1_000L }, sink = { _, message -> entries += message })

        log.protocolError("STOPPED")
        log.closeFailure("close failed")

        assertEquals(
            listOf("protocol error: STOPPED", "transport close failed: close failed"),
            entries,
        )
    }

    @Test
    fun `concurrent callers emit one line for the same throttle key and window`() {
        val callers = 16
        val rounds = 50
        val emissions = AtomicInteger()
        val executor = Executors.newFixedThreadPool(callers)

        try {
            repeat(rounds) {
                val gate = CyclicBarrier(callers)
                val log = VehicleLog(
                    clock = Clock {
                        gate.await(5, TimeUnit.SECONDS)
                        1_000L
                    },
                    sink = { _, _ -> emissions.incrementAndGet() },
                )
                val calls = List(callers) { Callable { log.protocolError("STOPPED") } }
                executor.invokeAll(calls).forEach { it.get() }
            }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(rounds, emissions.get())
    }
}
