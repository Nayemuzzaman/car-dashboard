package com.example.cardashboard.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.example.cardashboard.R
import com.example.cardashboard.domain.model.SpeedUnit
import com.example.cardashboard.domain.model.kmhToDisplay
import com.example.cardashboard.ui.theme.DashboardSpacing
import com.example.cardashboard.ui.theme.LocalDashboardColors
import kotlin.math.roundToInt

/** Test tags used by the instrumented dashboard tests. */
const val SPEED_VALUE_TAG = "speed_value"
const val SPEED_UNIT_TAG = "speed_unit"

/**
 * The main speedometer: the one reading that has to be legible without looking twice.
 *
 * The dial fills whatever box it is given and the numeral is sized from the resulting diameter, so
 * a phone in landscape — where the cluster only has a couple of hundred dp of height — gets a
 * smaller but complete gauge rather than a clipped one.
 *
 * Tapping the unit label switches between km/h and mph, so the cluster does not need an extra
 * button competing for attention. A `null` speed means no data and shows "--" rather than zero,
 * which would be a lie.
 */
@Composable
fun SpeedGauge(
    speedKmh: Float?,
    unit: SpeedUnit,
    accent: Color,
    animationsEnabled: Boolean,
    onToggleUnit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDashboardColors.current
    val unitLabel = stringResource(unit.speedLabelRes)
    val displaySpeed = speedKmh?.kmhToDisplay(unit) ?: 0f
    val maxDisplaySpeed = MAX_DIAL_SPEED_KMH.kmhToDisplay(unit)

    val animatedProgress by animatedGaugeValue(
        target = (displaySpeed / maxDisplaySpeed).coerceIn(0f, 1f),
        animate = animationsEnabled
    )
    val animatedSpeed by animatedGaugeValue(target = displaySpeed, animate = animationsEnabled)

    val valueText = if (speedKmh == null) {
        stringResource(R.string.value_unavailable)
    } else {
        animatedSpeed.roundToInt().toString()
    }
    val speedDescription = stringResource(R.string.speed_value_description, valueText, unitLabel)
    val toggleDescription = stringResource(R.string.speed_unit_toggle_description, unitLabel)

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val diameter = min(maxWidth, maxHeight)
        val valueStyle = when {
            diameter >= LARGE_DIAL -> MaterialTheme.typography.displayLarge
            diameter >= MEDIUM_DIAL -> MaterialTheme.typography.displayMedium
            else -> MaterialTheme.typography.displaySmall
        }

        ArcGauge(
            progress = animatedProgress,
            accent = accent,
            modifier = Modifier.fillMaxSize(),
            strokeWidth = (diameter * STROKE_FRACTION).coerceIn(MIN_STROKE, MAX_STROKE),
            tickCount = TICK_COUNT
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DashboardSpacing.tiny)
            ) {
                Text(
                    text = valueText,
                    style = valueStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .testTag(SPEED_VALUE_TAG)
                        .semantics { contentDescription = speedDescription }
                )
                Text(
                    text = unitLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .testTag(SPEED_UNIT_TAG)
                        .sizeIn(
                            minWidth = DashboardSpacing.minTouchTarget,
                            minHeight = DashboardSpacing.minTouchTarget
                        )
                        .clickable(onClick = onToggleUnit)
                        .wrapContentSize(Alignment.Center)
                        .semantics {
                            contentDescription = toggleDescription
                            role = Role.Button
                        }
                )
            }
        }
    }
}

private const val TICK_COUNT = 13
private const val STROKE_FRACTION = 0.055f
private val MIN_STROKE = 6.dp
private val MAX_STROKE = 16.dp
private val LARGE_DIAL = 300.dp
private val MEDIUM_DIAL = 215.dp

/** Top of the dial. Above this the needle simply sits at full scale. */
private const val MAX_DIAL_SPEED_KMH = 200f
