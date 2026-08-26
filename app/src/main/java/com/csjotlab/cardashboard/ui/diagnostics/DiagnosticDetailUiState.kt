package com.csjotlab.cardashboard.ui.diagnostics

import com.csjotlab.cardashboard.vehicle.domain.DiagnosticCode
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticIssue
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticStatus
import com.csjotlab.cardashboard.vehicle.domain.Severity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders an epoch timestamp that the *data layer* recorded. It is passed into
 * [DiagnosticDetailUiState.from] as a function so the mapping itself never reads a clock — the time
 * shown is when the issue was first seen, never "now".
 *
 * The pattern is pinned to [Locale.US] so the field does not read differently on a differently
 * configured device; the zone is the device's, because the instant is being shown to whoever is
 * holding it. The formatter is built per call rather than shared: `SimpleDateFormat` is mutable and
 * not thread-safe, and a shared one would also freeze the time zone at class-load time.
 */
fun formatDetectedTime(epochMs: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(Date(epochMs))

/**
 * Everything the detail screen is allowed to say about one issue.
 *
 * The governing rule is that **a DTC's identity is its code**. Nothing here translates a code into a
 * named fault: [problem] falls back to a statement of the bare fact, [description] says outright
 * that there is no description rather than filling one in, and [affectedSystem] carries only what
 * `DtcDecoder`'s structural J2012 decoding put in the classification. A code the decoder could not
 * place keeps its code and gains nothing.
 */
data class DiagnosticDetailUiState(
    val problem: String,
    val code: String,
    val statusLabel: String,
    val severityLabel: String,
    val detectedTimeLabel: String,
    val affectedSystem: String,
    val description: String,
    val originLabel: String,
) {
    companion object {
        fun from(issue: DiagnosticIssue, formatTime: (Long) -> String) = DiagnosticDetailUiState(
            // Only a supplied title may name a fault. Otherwise we state the fact and stop.
            problem = issue.title ?: when (issue.code) {
                is DiagnosticCode.Dtc -> "Engine diagnostic code detected"
                is DiagnosticCode.SignalDerived -> "Vehicle signal reported"
            },
            code = when (val code = issue.code) {
                is DiagnosticCode.Dtc -> code.code
                is DiagnosticCode.SignalDerived -> "${code.kind} — ${code.detail}"
            },
            statusLabel = when (issue.status) {
                DiagnosticStatus.Stored -> "Stored"
                DiagnosticStatus.Pending -> "Pending"
                DiagnosticStatus.Permanent -> "Permanent"
                DiagnosticStatus.LiveSignal -> "Live"
            },
            severityLabel = when (issue.severity) {
                Severity.Info -> "Info"
                Severity.Warning -> "Warning"
                Severity.Critical -> "Critical"
            },
            // firstSeenMs, not lastSeenMs: "detected" is the first sighting.
            detectedTimeLabel = formatTime(issue.firstSeenMs),
            affectedSystem = issue.classification?.let { classification ->
                listOfNotNull(classification.system.name, classification.subsystem).joinToString(" — ")
            } ?: "Unknown",
            description = issue.description ?: "No description available for this code",
            // The spec requires live state and stored history to be visibly different.
            originLabel = if (issue.status == DiagnosticStatus.LiveSignal) {
                "Live vehicle signal"
            } else {
                "Stored diagnostic code"
            },
        )
    }
}
