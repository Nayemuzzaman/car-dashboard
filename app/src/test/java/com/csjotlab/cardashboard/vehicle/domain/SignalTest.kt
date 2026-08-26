package com.csjotlab.cardashboard.vehicle.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalTest {

    @Test
    fun `value exposes its payload and timestamp`() {
        val signal: Signal<Int> = Signal.Value(42, timestampMs = 1_000L)
        assertEquals(42, signal.valueOrNull())
        assertTrue(signal.isValue)
        assertEquals(1_000L, (signal as Signal.Value).timestampMs)
    }

    @Test
    fun `unknown has no value`() {
        val signal: Signal<Int> = Signal.Unknown
        assertNull(signal.valueOrNull())
        assertFalse(signal.isValue)
    }

    @Test
    fun `unsupported has no value`() {
        val signal: Signal<Int> = Signal.Unsupported
        assertNull(signal.valueOrNull())
        assertFalse(signal.isValue)
    }

    @Test
    fun `unknown and unsupported are distinct states`() {
        val unknown: Signal<Int> = Signal.Unknown
        val unsupported: Signal<Int> = Signal.Unsupported
        assertFalse("Unknown and Unsupported must never compare equal", unknown == unsupported)
    }

    @Test
    fun `manual gear carries its position`() {
        assertEquals(3, (Gear.Manual(3) as Gear.Manual).position)
    }
}
