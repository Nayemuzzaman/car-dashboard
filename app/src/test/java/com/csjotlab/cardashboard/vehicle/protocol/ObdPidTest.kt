package com.csjotlab.cardashboard.vehicle.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdPidTest {

    // A future typo (e.g. SPEED = 0x0E) would request the wrong PID from the vehicle and put a
    // wrong reading on the dashboard with nothing to stop it — the realistic-bitmask fixture in
    // SupportedPidSetTest happens to have bit 0x0E set too, so that test alone would not catch it.
    @Test fun `every ObdPid constant matches its SAE J1979 value`() {
        assertEquals(0x01, ObdPid.MONITOR_STATUS)
        assertEquals(0x05, ObdPid.COOLANT)
        assertEquals(0x0C, ObdPid.RPM)
        assertEquals(0x0D, ObdPid.SPEED)
        assertEquals(0x1F, ObdPid.RUN_TIME)
        assertEquals(0x21, ObdPid.DISTANCE_WITH_MIL)
        assertEquals(0x2F, ObdPid.FUEL_LEVEL)
        assertEquals(0x31, ObdPid.DISTANCE_SINCE_CLEAR)
        assertEquals(0x46, ObdPid.AMBIENT_TEMP)
        assertEquals(0xA6, ObdPid.ODOMETER)
    }

    @Test fun `speed is the raw byte in km per hour`() {
        assertEquals(60f, ObdPid.decodeSpeedKph(listOf(0x3C)))
        assertEquals(0f, ObdPid.decodeSpeedKph(listOf(0x00)))
    }

    @Test fun `speed at the top of its range is two five five`() {
        assertEquals(255f, ObdPid.decodeSpeedKph(listOf(0xFF)))
    }

    @Test fun `rpm is a quarter of the sixteen bit value`() {
        // 0x1AF8 = 6904; 6904 / 4 = 1726
        assertEquals(1726, ObdPid.decodeRpm(listOf(0x1A, 0xF8)))
    }

    @Test fun `rpm at zero and at the top of its range`() {
        assertEquals(0, ObdPid.decodeRpm(listOf(0x00, 0x00)))
        // 0xFFFF = 65535; 65535 / 4 = 16383 (integer division)
        assertEquals(16383, ObdPid.decodeRpm(listOf(0xFF, 0xFF)))
    }

    @Test fun `coolant temperature is offset by forty`() {
        assertEquals(91, ObdPid.decodeCoolantCelsius(listOf(0x83)))   // 131 - 40
        assertEquals(-40, ObdPid.decodeCoolantCelsius(listOf(0x00)))
    }

    @Test fun `coolant temperature at the top of its range`() {
        assertEquals(215, ObdPid.decodeCoolantCelsius(listOf(0xFF))) // 255 - 40
    }

    // PID 0x46, ambient air temperature: same wire format as coolant (A - 40), but a distinct
    // named decoder so a caller reaching for "the temperature function" for this PID does not have
    // to know it happens to share coolant's formula. See task-15 review item 3.
    @Test fun `ambient temperature is offset by forty, same formula as coolant`() {
        assertEquals(91, ObdPid.decodeAmbientCelsius(listOf(0x83)))   // 131 - 40
        assertEquals(-40, ObdPid.decodeAmbientCelsius(listOf(0x00)))
        assertEquals(215, ObdPid.decodeAmbientCelsius(listOf(0xFF))) // 255 - 40
    }

    @Test fun `ambient temperature decodes to null rather than zero on an empty frame`() {
        assertNull(ObdPid.decodeAmbientCelsius(emptyList()))
    }

    @Test fun `fuel level is a percentage of two five five`() {
        assertEquals(100f, ObdPid.decodeFuelPercent(listOf(0xFF)))
        assertEquals(50f, ObdPid.decodeFuelPercent(listOf(0x80))!!, 0.5f)
    }

    @Test fun `fuel level at zero is zero not null`() {
        assertEquals(0f, ObdPid.decodeFuelPercent(listOf(0x00)))
    }

    @Test fun `monitor status splits the MIL bit from the code count`() {
        val on = ObdPid.decodeMonitorStatus(listOf(0x83, 0x00, 0x00, 0x00))!!
        assertTrue(on.milOn)
        assertEquals(3, on.dtcCount)

        val off = ObdPid.decodeMonitorStatus(listOf(0x03, 0x00, 0x00, 0x00))!!
        assertTrue(!off.milOn)
        assertEquals(3, off.dtcCount)
    }

    @Test fun `monitor status handles a frame longer than expected`() {
        val status = ObdPid.decodeMonitorStatus(listOf(0x83, 0x00, 0x00, 0x00, 0x00, 0x00))!!
        assertTrue(status.milOn)
        assertEquals(3, status.dtcCount)
    }

    @Test fun `odometer is a thirty two bit value in tenths of a kilometre`() {
        // 0x0005DC10 = 384016 -> 38401.6 km
        assertEquals(38401.6, ObdPid.decodeOdometerKm(listOf(0x00, 0x05, 0xDC, 0x10))!!, 0.05)
    }

    @Test fun `odometer at zero and at the top of its range`() {
        assertEquals(0.0, ObdPid.decodeOdometerKm(listOf(0x00, 0x00, 0x00, 0x00))!!, 0.05)
        // 0xFFFFFFFF = 4294967295 -> 429496729.5 km
        assertEquals(429496729.5, ObdPid.decodeOdometerKm(listOf(0xFF, 0xFF, 0xFF, 0xFF))!!, 0.05)
    }

    @Test fun `distance is a sixteen bit value in kilometres`() {
        // 0x1234 = 4660
        assertEquals(4660, ObdPid.decodeDistanceKm(listOf(0x12, 0x34)))
        assertEquals(0, ObdPid.decodeDistanceKm(listOf(0x00, 0x00)))
        assertEquals(65535, ObdPid.decodeDistanceKm(listOf(0xFF, 0xFF)))
    }

    @Test fun `short or empty frames decode to null rather than zero`() {
        assertNull(ObdPid.decodeRpm(listOf(0x1A)))
        assertNull(ObdPid.decodeSpeedKph(emptyList()))
        assertNull(ObdPid.decodeMonitorStatus(listOf(0x83)))
        assertNull(ObdPid.decodeOdometerKm(listOf(0x00, 0x05)))
    }

    @Test fun `every decoder returns null rather than zero on an empty frame`() {
        assertNull(ObdPid.decodeSpeedKph(emptyList()))
        assertNull(ObdPid.decodeRpm(emptyList()))
        assertNull(ObdPid.decodeCoolantCelsius(emptyList()))
        assertNull(ObdPid.decodeFuelPercent(emptyList()))
        assertNull(ObdPid.decodeMonitorStatus(emptyList()))
        assertNull(ObdPid.decodeOdometerKm(emptyList()))
        assertNull(ObdPid.decodeDistanceKm(emptyList()))
    }

    @Test fun `distance decoder returns null on a truncated one byte frame`() {
        assertNull(ObdPid.decodeDistanceKm(listOf(0x12)))
    }

    // PID 0x1F, run time since engine start: same sixteen-bit wire format as decodeDistanceKm, but
    // its own named decoder — a caller reaching for the nearest-fitting existing function would
    // otherwise get seconds back from a method named "...Km". See task-15 review item 3.
    @Test fun `run time is a sixteen bit value in seconds`() {
        // 0x1234 = 4660
        assertEquals(4660, ObdPid.decodeRunTimeSeconds(listOf(0x12, 0x34)))
        assertEquals(0, ObdPid.decodeRunTimeSeconds(listOf(0x00, 0x00)))
        assertEquals(65535, ObdPid.decodeRunTimeSeconds(listOf(0xFF, 0xFF)))
    }

    @Test fun `run time decoder returns null on a truncated one byte frame`() {
        assertNull(ObdPid.decodeRunTimeSeconds(listOf(0x12)))
    }

    @Test fun `odometer decoder returns null on a truncated three byte frame`() {
        assertNull(ObdPid.decodeOdometerKm(listOf(0x00, 0x05, 0xDC)))
    }
}
