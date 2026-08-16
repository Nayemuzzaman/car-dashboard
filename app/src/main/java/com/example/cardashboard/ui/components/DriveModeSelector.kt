package com.example.cardashboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.example.cardashboard.R
import com.example.cardashboard.domain.model.DriveMode
import com.example.cardashboard.ui.theme.DashboardSpacing
import com.example.cardashboard.ui.theme.LocalDashboardColors

const val DRIVE_MODE_TAG_PREFIX = "drive_mode_"

/**
 * Drive-mode selector.
 *
 * The note under the chips is deliberate: the mode changes the cluster's presentation and the demo
 * simulation, and the app does not claim to command a vehicle.
 */
@Composable
fun DriveModeSelector(
    selected: DriveMode,
    onModeSelected: (DriveMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDashboardColors.current

    DashboardPanel(modifier = modifier) {
        PanelHeader(
            title = stringResource(R.string.drive_mode_title),
            trailing = stringResource(selected.labelRes)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small)
        ) {
            DriveMode.entries.forEach { mode ->
                val isSelected = mode == selected
                val accent = colors.accentFor(mode, MaterialTheme.colorScheme.primary)
                val label = stringResource(mode.labelRes)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = DashboardSpacing.minTouchTarget)
                        .clip(RoundedCornerShape(DashboardSpacing.small))
                        .background(if (isSelected) accent else colors.gaugeTrack)
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onModeSelected(mode) }
                        )
                        .padding(DashboardSpacing.small)
                        .testTag(DRIVE_MODE_TAG_PREFIX + mode.name.lowercase()),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.drive_mode_note),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textMuted
        )
    }
}

val DriveMode.labelRes: Int
    get() = when (this) {
        DriveMode.ECO -> R.string.drive_mode_eco
        DriveMode.NORMAL -> R.string.drive_mode_normal
        DriveMode.SPORT -> R.string.drive_mode_sport
    }
