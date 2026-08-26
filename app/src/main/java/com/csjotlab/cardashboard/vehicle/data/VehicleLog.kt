package com.csjotlab.cardashboard.vehicle.data

import android.util.Log
import com.csjotlab.cardashboard.BuildConfig
import com.csjotlab.cardashboard.vehicle.domain.AdapterIdentity
import com.csjotlab.cardashboard.vehicle.domain.Clock
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared, policy-shaped logging for the vehicle stack.
 *
 * Callers choose a named lifecycle/protocol/diagnostics event rather than an arbitrary tag. USB
 * serial numbers are deliberately absent from every method signature. High-frequency telemetry is
 * opt-in through [telemetrySummary] and limited to one line per [telemetryWindowMs].
 */
class VehicleLog(
    private val clock: Clock,
    private val eventWindowMs: Long = 10_000L,
    private val telemetryWindowMs: Long = 10_000L,
    private val debugTelemetryEnabled: Boolean = BuildConfig.DEBUG,
    private val sink: (tag: String, message: String) -> Unit = ::logToAndroid,
) {
    private val lastEmissionMs = ConcurrentHashMap<String, Long>()

    fun sourceSelected(source: VehicleSourceId) = emit("source", "source selected: $source", eventWindowMs)

    fun deviceAttached() = emit("attach", "USB diagnostic device attached", eventWindowMs)

    fun deviceDetached() = emit("detach", "USB diagnostic device detached", eventWindowMs)

    fun permissionResult(granted: Boolean) = emit(
        key = "permission-${if (granted) "granted" else "denied"}",
        message = "USB permission ${if (granted) "granted" else "denied"}",
        windowMs = eventWindowMs,
    )

    fun adapterIdentity(identity: AdapterIdentity) =
        emit("adapter-identity", "adapter identity: ${identity.rawIdentity}", eventWindowMs)

    fun connectionEstablished() = emit("connected", "vehicle connection established", eventWindowMs)

    fun connectionLost(reason: String) = emit("connection-lost", "vehicle connection lost: $reason", eventWindowMs)

    fun unsupportedDevice(reason: String) = emit("unsupported", "unsupported diagnostic device: $reason", eventWindowMs)

    fun protocolError(reason: String) = emit("protocol-error", "protocol error: $reason", eventWindowMs)

    fun closeFailure(reason: String) = emit("close-failure", "transport close failed: $reason", eventWindowMs)

    fun diagnosticsFailed(reason: String) = emit("diagnostics-failed", "diagnostics scan failed: $reason", eventWindowMs)

    fun diagnosticsScan(codes: List<String>) {
        val reported = codes.distinct().sorted()
        val renderedCodes = reported.joinToString(separator = ",").ifEmpty { "none" }
        emit(
            key = "diagnostics-success",
            message = "diagnostics scan complete: count=${reported.size}, codes=$renderedCodes",
            windowMs = eventWindowMs,
        )
    }

    fun telemetrySummary(summary: String) {
        if (!debugTelemetryEnabled) return
        emit("telemetry", "telemetry: $summary", telemetryWindowMs)
    }

    private fun emit(key: String, message: String, windowMs: Long) {
        val nowMs = clock.nowMs()
        var shouldEmit = false
        lastEmissionMs.compute(key) { _, previous ->
            if (previous == null || nowMs - previous >= windowMs) {
                shouldEmit = true
                nowMs
            } else {
                previous
            }
        }
        if (shouldEmit) sink(TAG, message)
    }

    companion object {
        const val TAG = "CarDash/Vehicle"
    }
}

private fun logToAndroid(tag: String, message: String) {
    Log.i(tag, message)
}
