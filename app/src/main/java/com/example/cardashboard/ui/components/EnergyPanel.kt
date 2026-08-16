package com.example.cardashboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.cardashboard.R
import com.example.cardashboard.domain.model.ChargingState
import com.example.cardashboard.domain.model.EnergyState
import com.example.cardashboard.domain.model.SpeedUnit
import com.example.cardashboard.domain.model.VehicleType
import com.example.cardashboard.ui.format.DashboardFormat
import com.example.cardashboard.ui.theme.DashboardSpacing
import com.example.cardashboard.ui.theme.LocalDashboardColors

const val ENERGY_LEVEL_TAG = "energy_level"

/**
 * Fuel tank or traction battery, drawn by the same component.
 *
 * Both powertrains have a level, a range and a low threshold, so only the extra electric
 * information — charging status and recuperation — is conditional.
 */
@Composable
fun EnergyPanel(
    energy: EnergyState?,
    vehicleType: VehicleType,
    speedUnit: SpeedUnit,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = LocalDashboardColors.current
    // Taken from the powertrain rather than the reading, so the panel is still labelled correctly
    // before the first snapshot arrives.
    val isElectric = vehicleType == VehicleType.ELECTRIC
    val title = stringResource(
        if (isElectric) R.string.energy_title_battery else R.string.energy_title_fuel
    )
    val unavailable = stringResource(R.string.value_unavailable)
    val distanceUnit = stringResource(speedUnit.distanceLabelRes)

    val levelText = if (energy == null) unavailable else DashboardFormat.percent(energy.levelPercent)
    val levelColor = when {
        energy == null -> colors.textMuted
        energy.isLow -> colors.caution
        else -> MaterialTheme.colorScheme.onSurface
    }
    val animatedLevel by animatedGaugeValue(
        target = (energy?.levelPercent ?: 0f) / 100f,
        animate = animationsEnabled
    )
    val levelDescription = stringResource(R.string.energy_level_description, title, levelText)
    val rangeText = energy?.estimatedRangeKm
        ?.let { "${DashboardFormat.range(it, speedUnit)} $distanceUnit" }
        ?: unavailable

    DashboardPanel(modifier = modifier) {
        PanelHeader(
            title = title,
            trailing = if (energy?.isLow == true) {
                stringResource(
                    if (isElectric) R.string.energy_low_battery else R.string.energy_low_fuel
                )
            } else {
                null
            },
            trailingColor = colors.caution
        )

        Text(
            text = stringResource(R.string.energy_level_percent, levelText),
            style = MaterialTheme.typography.displaySmall,
            color = levelColor,
            maxLines = 1,
            modifier = Modifier
                .testTag(ENERGY_LEVEL_TAG)
                .semantics { contentDescription = levelDescription }
        )

        LevelBar(
            fraction = animatedLevel,
            color = if (energy?.isLow == true) colors.caution else MaterialTheme.colorScheme.primary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)
        ) {
            Readout(
                label = stringResource(R.string.energy_range),
                value = rangeText,
                modifier = Modifier.weight(1f)
            )
            if (energy is EnergyState.Battery) {
                Readout(
                    label = stringResource(R.string.charging_status),
                    value = stringResource(energy.chargingState.labelRes),
                    valueColor = if (energy.isCharging) colors.ok else null,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (energy is EnergyState.Battery && energy.regenerativeBrakingActive) {
            Text(
                text = stringResource(R.string.regen_active),
                style = MaterialTheme.typography.labelMedium,
                color = colors.ok
            )
        }
    }
}

@Composable
private fun LevelBar(fraction: Float, color: Color) {
    val colors = LocalDashboardColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(LEVEL_BAR_HEIGHT)
            .background(colors.gaugeTrack, RoundedCornerShape(percent = 50))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(color, RoundedCornerShape(percent = 50))
        )
    }
}

private val ChargingState.labelRes: Int
    get() = when (this) {
        ChargingState.NOT_CHARGING -> R.string.charging_not_charging
        ChargingState.PLUGGED_IN_IDLE -> R.string.charging_plugged_in
        ChargingState.CHARGING -> R.string.charging_charging
        ChargingState.CHARGE_COMPLETE -> R.string.charging_complete
    }

private val LEVEL_BAR_HEIGHT = 10.dp
