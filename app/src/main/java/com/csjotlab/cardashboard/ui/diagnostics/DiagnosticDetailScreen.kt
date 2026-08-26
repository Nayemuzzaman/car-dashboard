package com.csjotlab.cardashboard.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.csjotlab.cardashboard.ui.dashboard.DashboardPanel
import com.csjotlab.cardashboard.ui.theme.CarDashboardTheme
import com.csjotlab.cardashboard.ui.theme.DashboardAccent
import com.csjotlab.cardashboard.ui.theme.DashboardSpacing
import com.csjotlab.cardashboard.ui.theme.DashboardTextMuted

/** Shown when the issue that was opened is no longer in the snapshot. */
internal const val ISSUE_GONE = "Issue no longer reported"

/**
 * One issue, field by field.
 *
 * Every string on this screen comes from [DiagnosticDetailUiState], which is where the "a DTC's
 * identity is its code" rule is enforced and tested. This file adds no text of its own beyond the
 * field labels, the back action and [ISSUE_GONE] — in particular it never summarises, never counts
 * and never says anything resembling an all-clear, because a detail screen has no basis for one.
 */
@Composable
fun DiagnosticDetailScreen(
    state: DiagnosticDetailUiState?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(DashboardSpacing.screenPadding)
                // Seven stacked panels do not fit a phone in either orientation, so the screen
                // scrolls rather than clipping its last field.
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DashboardSpacing.medium),
        ) {
            BackAction(onBack = onBack)

            if (state == null) {
                // Not an error and not an all-clear: the issue was in the snapshot when the row was
                // tapped and is not in it now. Say only that.
                DashboardPanel(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = ISSUE_GONE,
                        style = MaterialTheme.typography.bodyMedium,
                        color = DashboardTextMuted,
                    )
                }
                return@Column
            }

            // Where the issue came from, before any of its detail: a stored code and a live signal
            // are different kinds of statement and must not be read as the same thing.
            Text(
                text = state.originLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = DashboardAccent,
            )

            DetailPanel(label = "Problem", value = state.problem)
            DetailPanel(label = "Diagnostic code", value = state.code)
            DetailPanel(label = "Status", value = state.statusLabel)
            DetailPanel(label = "Severity", value = state.severityLabel)
            DetailPanel(label = "Detected time", value = state.detectedTimeLabel)
            DetailPanel(label = "Affected system", value = state.affectedSystem)
            DetailPanel(label = "Available description", value = state.description)
        }
    }
}

@Composable
private fun DetailPanel(label: String, value: String) {
    DashboardPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(DashboardSpacing.tight)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = DashboardTextMuted,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun BackAction(onBack: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = DashboardAccent,
        onClick = onBack,
    ) {
        Text(
            text = "Back",
            modifier = Modifier.padding(
                vertical = DashboardSpacing.small,
                horizontal = DashboardSpacing.medium,
            ),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            // Legible against the accent; the same near-black the mode chips and banners use.
            color = Color(0xFF03111D),
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun DiagnosticDetailScreenPreview() {
    CarDashboardTheme {
        DiagnosticDetailScreen(
            state = DiagnosticDetailUiState(
                problem = "Engine diagnostic code detected",
                code = "P0301",
                statusLabel = "Stored",
                severityLabel = "Warning",
                detectedTimeLabel = "11 Aug 2026, 10:00",
                affectedSystem = "Powertrain — Ignition system or misfire",
                description = "No description available for this code",
                originLabel = "Stored diagnostic code",
            ),
            onBack = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun DiagnosticDetailScreenGonePreview() {
    CarDashboardTheme {
        DiagnosticDetailScreen(state = null, onBack = {})
    }
}
