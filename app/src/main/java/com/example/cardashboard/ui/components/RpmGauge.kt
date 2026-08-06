package com.example.cardashboard.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.example.cardashboard.R
import com.example.cardashboard.domain.model.VehicleType
import com.example.cardashboard.ui.format.DashboardFormat
import com.example.cardashboard.ui.theme.LocalDashboardColors

const val RPM_VALUE_TAG = "rpm_value"

/**
 * Revs, with a red zone near the top of the scale.
 *
 * Electric motors spin far higher than an engine, so the dial's full-scale value depends on the
 * powertrain rather than being one fixed number. Like the speedometer, the dial sizes itself from
 * the space it is given.
 */
@Composable
fun RpmGauge(
    rpm: Int?,
    vehicleType: VehicleType,
    accent: Color,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = LocalDashboardColors.current
    val maxRpm = when (vehicleType) {
        VehicleType.COMBUSTION -> COMBUSTION_MAX_RPM
        VehicleType.ELECTRIC -> ELECTRIC_MAX_RPM
    }
    val redZone = when (vehicleType) {
        VehicleType.COMBUSTION -> COMBUSTION_RED_ZONE_START
        VehicleType.ELECTRIC -> null
    }
    val animatedProgress by animatedGaugeValue(
        target = ((rpm ?: 0) / maxRpm.toFloat()).coerceIn(0f, 1f),
        animate = animationsEnabled
    )

    val unavailable = stringResource(R.string.value_unavailable)
    val valueText = if (rpm == null) unavailable else DashboardFormat.rpmThousands(rpm)
    val title = stringResource(
        if (vehicleType == VehicleType.ELECTRIC) R.string.motor_rpm_title else R.string.rpm_title
    )
    val description = stringResource(R.string.rpm_value_description, rpm?.toString() ?: unavailable)
    val scaleLabel = stringResource(R.string.rpm_unit)
    val inRedZone = redZone != null && animatedProgress >= redZone

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val diameter = min(maxWidth, maxHeight)
        val valueStyle = if (diameter >= LARGE_DIAL) {
            MaterialTheme.typography.displaySmall
        } else {
            MaterialTheme.typography.headlineMedium
        }
        val spaciousDial = diameter >= SPACIOUS_DIAL

        ArcGauge(
            progress = animatedProgress,
            accent = if (inRedZone) colors.critical else accent,
            modifier = Modifier.fillMaxSize(),
            strokeWidth = (diameter * STROKE_FRACTION).coerceIn(MIN_STROKE, MAX_STROKE),
            tickCount = TICK_COUNT,
            redZoneStart = redZone
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // A small dial only has room for two lines, so the scale joins the title there.
                if (spaciousDial) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textMuted
                    )
                }
                Text(
                    text = valueText,
                    style = valueStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier
                        .testTag(RPM_VALUE_TAG)
                        .semantics { contentDescription = description }
                )
                Text(
                    text = if (spaciousDial) scaleLabel else "$title $scaleLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textMuted,
                    maxLines = 1
                )
            }
        }
    }
}

private const val TICK_COUNT = 9
private const val STROKE_FRACTION = 0.06f
private val MIN_STROKE = 5.dp
private val MAX_STROKE = 12.dp
private val LARGE_DIAL = 190.dp
private val SPACIOUS_DIAL = 130.dp
private const val COMBUSTION_MAX_RPM = 8_000
private const val ELECTRIC_MAX_RPM = 16_000
private const val COMBUSTION_RED_ZONE_START = 0.78f
