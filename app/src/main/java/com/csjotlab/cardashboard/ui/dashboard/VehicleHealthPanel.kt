package com.csjotlab.cardashboard.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.csjotlab.cardashboard.ui.theme.CarDashboardTheme
import com.csjotlab.cardashboard.ui.theme.DashboardSpacing
import com.csjotlab.cardashboard.ui.theme.DashboardTextMuted
import com.csjotlab.cardashboard.ui.theme.DashboardWarning
import com.csjotlab.cardashboard.vehicle.domain.Severity

/**
 * "Nothing has been read" and "everything that was read is fine" are different facts and get
 * different sentences. Kept as constants because the landscape header, the panel and the tests all
 * have to agree on them, and because "no issues" is a claim that may only ever appear on the second.
 */
internal const val HEALTH_DISCONNECTED = "Vehicle not connected"
internal const val HEALTH_NO_ISSUES = "No issues reported"

/**
 * The diagnostics list, as itself: one row per reported issue, each showing the code and nothing
 * inferred from it.
 *
 * Portrait only. Landscape's third column is height-bounded — that bound is what clipped the layout
 * before commit `ecf85cd` — so landscape carries the same information as a count in the existing
 * Warnings header via [warningsHeaderValue] instead of a third panel.
 */
@Composable
fun VehicleHealthPanel(
    diagnostics: List<DiagnosticUi>,
    isConnected: Boolean,
    onIssueClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    DashboardPanel(
        modifier = modifier,
        contentPadding = if (compact) DashboardSpacing.compact else DashboardSpacing.medium,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(
                if (compact) DashboardSpacing.small else DashboardSpacing.medium,
            ),
        ) {
            PanelHeader(
                title = "Vehicle Health",
                // Blank rather than "0 reported": a zero is only meaningful once we know the list
                // is complete, and the body below is where that distinction is actually made.
                value = if (diagnostics.isEmpty()) "" else "${diagnostics.size} reported",
            )
            when {
                // Two different facts, two different messages. "No issues" would be a claim we
                // cannot make while disconnected.
                !isConnected -> Text(
                    text = HEALTH_DISCONNECTED,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DashboardTextMuted,
                )
                diagnostics.isEmpty() -> Text(
                    text = HEALTH_NO_ISSUES,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DashboardTextMuted,
                )
                else -> diagnostics.forEach { issue ->
                    DiagnosticRow(issue = issue, onClick = { onIssueClick(issue.id) })
                }
            }
        }
    }
}

/**
 * The landscape counterpart of [VehicleHealthPanel]: the diagnostic count folded into the Warnings
 * header, because the third column has no room for another panel.
 *
 * The warnings half is [VehicleStateFormatter.warningsSummary] verbatim and is never re-derived
 * here — the rule that separates "every row reported and is safe" from "nothing has been read"
 * lives in exactly one place, and a second copy of it is how a fabricated all-clear gets in.
 *
 * A zero count contributes nothing. "0 issues" beside an unknown summary would read as an all-clear
 * over data that was never collected, which is the same defect at a third layer.
 */
internal fun warningsHeaderValue(warnings: List<WarningUi>, diagnosticCount: Int): String {
    val summary = VehicleStateFormatter.warningsSummary(warnings)
    if (diagnosticCount <= 0) return summary
    val noun = if (diagnosticCount == 1) "issue" else "issues"
    return "$summary · $diagnosticCount $noun"
}

@Composable
private fun DiagnosticRow(
    issue: DiagnosticUi,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = issue.code,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = severityColor(issue.severity),
            )
            // The code is the fact. The headline is whatever the formatter could say without
            // naming a failed component, so it is presented as supporting text, not as a diagnosis.
            Text(
                text = issue.headline,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = issue.statusLabel,
            style = MaterialTheme.typography.labelMedium,
            color = DashboardTextMuted,
        )
    }
}

private fun severityColor(severity: Severity) = when (severity) {
    Severity.Critical, Severity.Warning -> DashboardWarning
    Severity.Info -> DashboardTextMuted
}

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun VehicleHealthPanelPreview() {
    CarDashboardTheme {
        VehicleHealthPanel(
            diagnostics = listOf(
                DiagnosticUi("dtc:P0301", "P0301", "Engine diagnostic code detected", "Stored", Severity.Warning),
            ),
            isConnected = true,
            onIssueClick = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
