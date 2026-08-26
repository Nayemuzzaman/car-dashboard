package com.csjotlab.cardashboard.vehicle.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleConnectionStateTest {

    private val device = UsbDeviceDescriptor("/dev/bus/usb/001/002", vendorId = 0x0403, productId = 0x6001, chipset = "FTDI")

    @Test
    fun `only Reading counts as live data`() {
        assertTrue(VehicleConnectionState.Reading.isLiveData)
        assertFalse(VehicleConnectionState.Connected(AdapterIdentity("ELM327 v1.5", true), "ISO 15765-4").isLiveData)
        assertFalse(VehicleConnectionState.Disconnected.isLiveData)
        assertFalse(VehicleConnectionState.Connecting.isLiveData)
    }

    @Test
    fun `terminal failures are distinguished from recoverable ones`() {
        assertTrue(VehicleConnectionState.PermissionDenied(device).isTerminalFailure)
        assertTrue(VehicleConnectionState.UnsupportedDevice(device, "no driver").isTerminalFailure)
        assertTrue(VehicleConnectionState.Error("boom").isTerminalFailure)

        assertFalse(VehicleConnectionState.ConnectionLost("cable removed").isTerminalFailure)
        assertFalse(VehicleConnectionState.VehicleCommunicationUnavailable("UNABLE TO CONNECT").isTerminalFailure)
        assertFalse(VehicleConnectionState.Disconnected.isTerminalFailure)
    }

    @Test
    fun `states carry the context needed to explain themselves`() {
        val unsupported = VehicleConnectionState.UnsupportedDevice(device, "no USB-serial driver for chipset")
        assertEquals(0x0403, unsupported.device.vendorId)
        assertEquals("no USB-serial driver for chipset", unsupported.reason)

        val connected = VehicleConnectionState.Connected(AdapterIdentity("ELM327 v1.5", true), "ISO 15765-4 CAN")
        assertTrue(connected.adapter.elmCompatible)
        assertEquals("ISO 15765-4 CAN", connected.protocol)
    }
}
