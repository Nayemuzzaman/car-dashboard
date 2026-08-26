package com.csjotlab.cardashboard.vehicle.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleStateTest {

    @Test
    fun `unavailable state holds no values at all`() {
        val state = VehicleState.unavailable(VehicleSourceId.NONE)

        assertTrue(
            "unavailable() must not fabricate any reading",
            state.allSignals.none { it.isValue }
        )
        assertNull(state.lastUpdatedMs)
        assertEquals(VehicleSourceId.NONE, state.source)
    }

    @Test
    fun `unavailable state uses Unknown rather than Unsupported`() {
        val state = VehicleState.unavailable(VehicleSourceId.NONE)

        // Nothing has been discovered yet, so nothing may claim to be unsupported.
        assertTrue(state.allSignals.all { it == Signal.Unknown })
    }

    @Test
    fun `allSignals covers every telemetry field`() {
        assertEquals(12, VehicleState.unavailable(VehicleSourceId.NONE).allSignals.size)
    }

    @Test
    fun `a populated field keeps its value and timestamp`() {
        val state = VehicleState.unavailable(VehicleSourceId.OBD_USB)
            .copy(speedKph = Signal.Value(60f, 5_000L), lastUpdatedMs = 5_000L)

        assertEquals(60f, state.speedKph.valueOrNull())
        assertEquals(5_000L, state.lastUpdatedMs)
        assertEquals(11, state.allSignals.count { it == Signal.Unknown })
    }
}
