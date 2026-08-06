package com.example.cardashboard.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.example.cardashboard.R
import com.example.cardashboard.domain.model.TemperatureUnit
import com.example.cardashboard.domain.model.Temperatures
import com.example.cardashboard.domain.model.VehicleType
import com.example.cardashboard.ui.format.DashboardFormat
import com.example.cardashboard.ui.theme.DashboardSpacing
import com.example.cardashboard.ui.theme.LocalDashboardColors

const val OUTSIDE_TEMPERATURE_TAG = "outside_temperature"

/**
 * Outside and powertrain temperatures.
 *
 * A sensor that reports nothing shows "--" plus a short explanation, so a missing reading is
 * visibly missing rather than silently shown as zero.
 */
@Composable
fun TemperaturePanel(
    temperatures: Temperatures?,
    vehicleType: VehicleType,
    unit: TemperatureUnit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDashboardColors.current
    val unavailable = stringResource(R.string.value_unavailable)
    val unitLabel = stringResource(unit.labelRes)
    val powertrainLabel = stringResource(
        if (vehicleType == VehicleType.ELECTRIC) {
            R.string.temperature_battery
        } else {
            R.string.temperature_engine
        }
    )

    val outside = temperatures?.outsideCelsius
    val powertrain = temperatures?.powertrainCelsius
    val outsideText = if (outside == null) {
        unavailable
    } else {
        "${DashboardFormat.temperature(outside, unit)} $unitLabel"
    }
    val powertrainText = if (powertrain == null) {
        unavailable
    } else {
        "${DashboardFormat.temperature(powertrain, unit)} $unitLabel"
    }
    val powertrainHot = (powertrain ?: 0f) >= Temperatures.POWERTRAIN_WARNING_CELSIUS

    DashboardPanel(modifier = modifier) {
        PanelHeader(title = stringResource(R.string.temperature_title))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)
        ) {
            Readout(
                label = stringResource(R.string.temperature_outside),
                value = outsideText,
                modifier = Modifier
                    .weight(1f)
                    .testTag(OUTSIDE_TEMPERATURE_TAG)
            )
            Readout(
                label = powertrainLabel,
                value = powertrainText,
                valueColor = if (powertrainHot) colors.critical else null,
                modifier = Modifier.weight(1f)
            )
        }
        if (outside == null || powertrain == null) {
            Text(
                text = stringResource(R.string.temperature_unavailable),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textMuted
            )
        }
    }
}
