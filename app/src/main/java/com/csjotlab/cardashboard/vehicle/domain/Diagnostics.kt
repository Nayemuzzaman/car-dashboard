package com.csjotlab.cardashboard.vehicle.domain

enum class DtcSystem { Powertrain, Chassis, Body, Network }

/**
 * What the SAE J2012 *code format* itself states. This is structural decoding, never a diagnosis:
 * it says which system the code belongs to, not which component failed.
 */
data class DtcClassification(
    val system: DtcSystem,
    val manufacturerSpecific: Boolean,
    val subsystem: String?,
)

enum class SignalIssueKind { DoorOpen, SeatbeltUnbuckled, TirePressureLow, MalfunctionIndicatorLamp }

sealed interface DiagnosticCode {
    /** A diagnostic trouble code exactly as reported, e.g. "P0301". */
    data class Dtc(val code: String) : DiagnosticCode

    /** An issue observed from a live vehicle signal rather than a stored code. */
    data class SignalDerived(val kind: SignalIssueKind, val detail: String) : DiagnosticCode
}

enum class DiagnosticStatus { Stored, Pending, Permanent, LiveSignal }

enum class Severity { Info, Warning, Critical }

enum class DiagnosticSource { Obd2, VehicleSignal, Mock }

data class DiagnosticIssue(
    val id: String,
    val code: DiagnosticCode,
    /** Null unless a trusted mapping supplied it. No such mapping ships in this version. */
    val title: String?,
    /** Null unless a trusted mapping supplied it. No such mapping ships in this version. */
    val description: String?,
    val classification: DtcClassification?,
    val severity: Severity,
    val status: DiagnosticStatus,
    val source: DiagnosticSource,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
)

data class VehicleDiagnosticsState(
    val issues: List<DiagnosticIssue>,
    val malfunctionIndicatorLampOn: Signal<Boolean>,
    val storedDtcCount: Signal<Int>,
    val lastScanMs: Long?,
) {
    companion object {
        fun empty(): VehicleDiagnosticsState = VehicleDiagnosticsState(
            issues = emptyList(),
            malfunctionIndicatorLampOn = Signal.Unknown,
            storedDtcCount = Signal.Unknown,
            lastScanMs = null,
        )
    }
}
