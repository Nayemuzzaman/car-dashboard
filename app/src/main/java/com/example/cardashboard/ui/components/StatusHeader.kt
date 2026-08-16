package com.example.cardashboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cardashboard.R
import com.example.cardashboard.ui.theme.DashboardSpacing
import com.example.cardashboard.ui.theme.LocalDashboardColors
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

const val DEMO_BADGE_TAG = "demo_badge"
const val SETTINGS_BUTTON_TAG = "settings_button"
const val STATUS_MESSAGE_TAG = "status_message"

/**
 * The strip across the top: clock and date on the left, data-source honesty in the middle, settings
 * on the right.
 */
@Composable
fun StatusHeader(
    now: LocalDateTime?,
    isDemoData: Boolean,
    statusMessage: String?,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDashboardColors.current
    val timePattern = stringResource(R.string.clock_time_pattern)
    val datePattern = stringResource(R.string.clock_date_pattern)
    val unavailable = stringResource(R.string.value_unavailable)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.medium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DashboardSpacing.tiny)) {
            Text(
                text = now?.format(DateTimeFormatter.ofPattern(timePattern)) ?: unavailable,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = now?.format(DateTimeFormatter.ofPattern(datePattern)) ?: unavailable,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textMuted
            )
        }

        if (isDemoData) {
            DemoBadge()
        }

        if (statusMessage != null) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.labelMedium,
                color = colors.caution,
                modifier = Modifier
                    .weight(1f)
                    .testTag(STATUS_MESSAGE_TAG)
                    // Announced when it appears: it explains why the cluster stopped updating.
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        } else {
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
        }

        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .size(DashboardSpacing.minTouchTarget)
                .testTag(SETTINGS_BUTTON_TAG)
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = stringResource(R.string.settings_open),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** Always visible while simulated values are on screen. */
@Composable
private fun DemoBadge(modifier: Modifier = Modifier) {
    val colors = LocalDashboardColors.current
    val description = stringResource(R.string.badge_demo_data_description)
    Text(
        text = stringResource(R.string.badge_demo_data),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = colors.caution,
        modifier = modifier
            .testTag(DEMO_BADGE_TAG)
            .background(colors.caution.copy(alpha = 0.14f), RoundedCornerShape(DashboardSpacing.tiny))
            .border(1.dp, colors.caution.copy(alpha = 0.6f), RoundedCornerShape(DashboardSpacing.tiny))
            .padding(horizontal = DashboardSpacing.small, vertical = DashboardSpacing.tiny)
            .semantics { contentDescription = description }
    )
}
