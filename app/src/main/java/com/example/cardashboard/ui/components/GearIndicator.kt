package com.example.cardashboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cardashboard.R
import com.example.cardashboard.domain.model.Gear
import com.example.cardashboard.ui.theme.DashboardSpacing
import com.example.cardashboard.ui.theme.LocalDashboardColors

const val GEAR_INDICATOR_TAG = "gear_indicator"

/**
 * The gear selector readout.
 *
 * Every position is always on screen with the engaged one highlighted, which is how a real selector
 * display works — the driver reads the position of the highlight, not just a letter.
 */
@Composable
fun GearIndicator(
    gear: Gear?,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val colors = LocalDashboardColors.current
    val selectedName = gear?.let { stringResource(it.nameRes) }
        ?: stringResource(R.string.value_unavailable)
    val description = stringResource(R.string.gear_description, selectedName)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(GEAR_INDICATOR_TAG)
            // One announcement for the whole strip instead of five stray letters.
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.tiny),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Gear.entries.forEach { position ->
            val selected = position == gear
            Box(
                modifier = Modifier
                    .weight(1f)
                    .sizeIn(minHeight = MIN_HEIGHT)
                    .background(
                        color = if (selected) accent else Color.Transparent,
                        shape = RoundedCornerShape(DashboardSpacing.small)
                    )
                    .padding(vertical = DashboardSpacing.small),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(position.letterRes),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        colors.textMuted
                    }
                )
            }
        }
    }
}

val Gear.letterRes: Int
    get() = when (this) {
        Gear.PARK -> R.string.gear_park
        Gear.REVERSE -> R.string.gear_reverse
        Gear.NEUTRAL -> R.string.gear_neutral
        Gear.DRIVE -> R.string.gear_drive
        Gear.SPORT -> R.string.gear_sport
    }

val Gear.nameRes: Int
    get() = when (this) {
        Gear.PARK -> R.string.gear_park_name
        Gear.REVERSE -> R.string.gear_reverse_name
        Gear.NEUTRAL -> R.string.gear_neutral_name
        Gear.DRIVE -> R.string.gear_drive_name
        Gear.SPORT -> R.string.gear_sport_name
    }

private val MIN_HEIGHT = 44.dp
