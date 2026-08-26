package com.csjotlab.cardashboard.vehicle.domain

/** Identifying details of an attached USB device. Serial numbers are deliberately not carried. */
data class UsbDeviceDescriptor(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val chipset: String?,
)

data class AdapterIdentity(
    val rawIdentity: String,
    val elmCompatible: Boolean,
)

sealed interface VehicleConnectionState {
    data object Disconnected : VehicleConnectionState
    data class DeviceDetected(val device: UsbDeviceDescriptor) : VehicleConnectionState
    data class PermissionRequired(val device: UsbDeviceDescriptor) : VehicleConnectionState
    data class PermissionDenied(val device: UsbDeviceDescriptor) : VehicleConnectionState
    data object Connecting : VehicleConnectionState
    data class Connected(val adapter: AdapterIdentity, val protocol: String?) : VehicleConnectionState
    data object Reading : VehicleConnectionState

    /** Attached, but we have no driver for it or it is not an ELM327-compatible adapter. */
    data class UnsupportedDevice(val device: UsbDeviceDescriptor, val reason: String) : VehicleConnectionState

    /** The adapter answers, but cannot reach the vehicle bus. */
    data class VehicleCommunicationUnavailable(val reason: String) : VehicleConnectionState

    data class ConnectionLost(val reason: String) : VehicleConnectionState
    data class Error(val reason: String) : VehicleConnectionState
}

/** True only while telemetry is genuinely flowing. */
val VehicleConnectionState.isLiveData: Boolean
    get() = this is VehicleConnectionState.Reading

/** Failures that retrying by itself will not fix. */
val VehicleConnectionState.isTerminalFailure: Boolean
    get() = this is VehicleConnectionState.PermissionDenied ||
        this is VehicleConnectionState.UnsupportedDevice ||
        this is VehicleConnectionState.Error
