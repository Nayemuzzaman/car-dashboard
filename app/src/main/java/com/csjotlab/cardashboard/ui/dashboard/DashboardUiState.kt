package com.csjotlab.cardashboard.ui.dashboard

import com.csjotlab.cardashboard.vehicle.domain.Severity

const val UNAVAILABLE = "—"
const val NOT_REPORTED = "Not reported by vehicle"

/**
 * `Signal.Unsupported` says only that the *currently active source* cannot supply the field. It is
 * not a statement about the car, so its helper text must not be phrased as one — a different
 * adapter or an OEM source may well report the same field.
 */
const val NOT_SUPPORTED_BY_SOURCE = "Not available from this source"

/**
 * Three states, not two. "No data" must never render the same as "checked and fine" — that would be
 * a fabricated safety claim. Spec section 7.1.
 */
enum class WarningLevel { Ok, Active, NotReported }

/**
 * Why a tile has (or has not) a value. [Unsupported] and [Unknown] both render as [UNAVAILABLE],
 * but they are different statements and must not collapse into one at the UI boundary:
 * [Unsupported] is about the active source, [Unknown] is "no reading has arrived".
 */
enum class MetricAvailability { Available, Unsupported, Unknown }

data class MetricUi(
    val label: String,
    val value: String,
    val helper: String,
    val available: Boolean,
    val availability: MetricAvailability,
)

data class WarningUi(
    val label: String,
    val level: WarningLevel,
    val helper: String,
    /** Non-null when this row has a diagnostic issue behind it and can be opened. */
    val issueId: String?,
)

data class DiagnosticUi(
    val id: String,
    val code: String,
    val headline: String,
    val statusLabel: String,
    val severity: Severity,
)

data class DashboardUiState(
    val speedText: String,
    /** Null means "no reading", which the gauge must render as no position — not as zero. */
    val speedForGauge: Int?,
    val maxSpeedKph: Int,
    val tripText: String,
    val rangeText: String,
    val odometerText: String,
    val metrics: List<MetricUi>,
    val warnings: List<WarningUi>,
    val diagnostics: List<DiagnosticUi>,
    val connectionLabel: String,
    val connectionActionLabel: String?,
    val isSimulated: Boolean,
    val isConnected: Boolean,
) {
    companion object {
        fun disconnected(driveModeLabel: String): DashboardUiState =
            VehicleStateFormatter.toUiState(
                com.csjotlab.cardashboard.vehicle.data.VehicleSnapshot.Disconnected,
                driveModeLabel,
            )
    }
}
