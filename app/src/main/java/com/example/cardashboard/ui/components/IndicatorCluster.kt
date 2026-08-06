package com.example.cardashboard.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.cardashboard.R
import com.example.cardashboard.domain.model.Indicators
import com.example.cardashboard.domain.model.VehicleType
import com.example.cardashboard.ui.theme.DashboardSpacing
import com.example.cardashboard.ui.theme.LocalDashboardColors

/** Test tag prefix; a lamp's tag is `indicator_` plus its string resource entry name. */
const val INDICATOR_TAG_PREFIX = "indicator_"

/**
 * Description of one telltale, resolved from [Indicators] before rendering so the lamp itself stays
 * a dumb, reusable component.
 */
private data class Telltale(
    val key: String,
    val labelRes: Int,
    val active: Boolean,
    val iconRes: Int? = null,
    val glyphRes: Int? = null,
    val tone: Tone,
    val blinks: Boolean = false
)

private enum class Tone { OK, INFO, CAUTION, CRITICAL }

/**
 * The telltale strip.
 *
 * Active and inactive states differ by more than colour: an active lamp gains a filled background
 * and a bold label, an inactive one is a dim outline. Screen readers get an explicit on/off state
 * for every lamp.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IndicatorCluster(
    indicators: Indicators,
    vehicleType: VehicleType,
    modifier: Modifier = Modifier,
    animationsEnabled: Boolean = true,
    /**
     * Landscape keeps the lamps on one scrolling line so the cluster's height stays predictable on
     * short screens; compact layouts let them wrap instead.
     */
    singleScrollingRow: Boolean = false
) {
    val colors = LocalDashboardColors.current
    val telltales = telltalesOf(indicators, vehicleType)
    val activeFaults = indicators.activeFaultCount

    DashboardPanel(modifier = modifier) {
        PanelHeader(
            title = stringResource(R.string.indicators_title),
            trailing = if (activeFaults == 0) {
                stringResource(R.string.indicators_all_clear)
            } else {
                pluralStringResource(R.plurals.indicators_active_count, activeFaults, activeFaults)
            },
            trailingColor = if (activeFaults == 0) colors.ok else colors.caution
        )
        if (singleScrollingRow) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small)
            ) {
                telltales.forEach { telltale ->
                    IndicatorLamp(telltale = telltale, animationsEnabled = animationsEnabled)
                }
            }
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small),
                verticalArrangement = Arrangement.spacedBy(DashboardSpacing.small)
            ) {
                telltales.forEach { telltale ->
                    IndicatorLamp(telltale = telltale, animationsEnabled = animationsEnabled)
                }
            }
        }
    }
}

@Composable
private fun IndicatorLamp(
    telltale: Telltale,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = LocalDashboardColors.current
    val label = stringResource(telltale.labelRes)
    val stateLabel = stringResource(
        if (telltale.active) R.string.indicator_state_on else R.string.indicator_state_off
    )
    val activeColor = when (telltale.tone) {
        Tone.OK -> colors.ok
        Tone.INFO -> colors.beam
        Tone.CAUTION -> colors.caution
        Tone.CRITICAL -> colors.critical
    }
    val contentColor = if (telltale.active) activeColor else colors.inactiveIndicator

    // Turn signals and hazards are the only things allowed to flash, because that is what they are.
    val blinkAlpha by rememberBlinkAlpha(
        blinking = telltale.active && telltale.blinks && animationsEnabled
    )

    Column(
        modifier = modifier
            .sizeIn(minWidth = LAMP_MIN_WIDTH)
            .background(
                color = if (telltale.active) activeColor.copy(alpha = 0.16f) else Color.Transparent,
                shape = RoundedCornerShape(DashboardSpacing.small)
            )
            .border(
                width = 1.dp,
                color = if (telltale.active) activeColor.copy(alpha = 0.5f) else colors.panelBorder,
                shape = RoundedCornerShape(DashboardSpacing.small)
            )
            .padding(horizontal = DashboardSpacing.small, vertical = DashboardSpacing.small)
            // Cleared and re-set so a lamp reads as one item with an explicit on/off state,
            // rather than as an icon plus a stray label.
            .clearAndSetSemantics {
                testTag = INDICATOR_TAG_PREFIX + telltale.key
                contentDescription = label
                stateDescription = stateLabel
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DashboardSpacing.tiny)
    ) {
        if (telltale.iconRes != null) {
            Icon(
                painter = painterResource(telltale.iconRes),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier
                    .size(DashboardSpacing.indicatorIcon)
                    .alpha(blinkAlpha)
            )
        } else if (telltale.glyphRes != null) {
            Text(
                text = stringResource(telltale.glyphRes),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                modifier = Modifier
                    .sizeIn(minHeight = DashboardSpacing.indicatorIcon)
                    .alpha(blinkAlpha)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (telltale.active) FontWeight.Bold else FontWeight.Normal,
            color = if (telltale.active) contentColor else colors.textMuted,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

/** Pulses between full and dimmed while blinking, and sits at full brightness otherwise. */
@Composable
private fun rememberBlinkAlpha(blinking: Boolean) = if (blinking) {
    val transition = rememberInfiniteTransition(label = "indicatorBlink")
    transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = BLINK_HALF_PERIOD_MILLIS),
            repeatMode = RepeatMode.Reverse
        ),
        label = "indicatorBlinkAlpha"
    )
} else {
    androidx.compose.runtime.rememberUpdatedState(1f)
}

private fun telltalesOf(indicators: Indicators, vehicleType: VehicleType): List<Telltale> {
    val lowEnergyLabel = if (vehicleType == VehicleType.ELECTRIC) {
        R.string.indicator_low_energy_battery
    } else {
        R.string.indicator_low_energy_fuel
    }
    val lowEnergyIcon = if (vehicleType == VehicleType.ELECTRIC) {
        R.drawable.ic_indicator_low_battery
    } else {
        R.drawable.ic_indicator_low_fuel
    }
    return listOf(
        Telltale(
            key = "left_turn",
            labelRes = R.string.indicator_left_turn,
            active = indicators.leftTurnSignal || indicators.hazardLights,
            iconRes = R.drawable.ic_indicator_turn_left,
            tone = Tone.OK,
            blinks = true
        ),
        Telltale(
            key = "right_turn",
            labelRes = R.string.indicator_right_turn,
            active = indicators.rightTurnSignal || indicators.hazardLights,
            iconRes = R.drawable.ic_indicator_turn_right,
            tone = Tone.OK,
            blinks = true
        ),
        Telltale(
            key = "hazard",
            labelRes = R.string.indicator_hazard,
            active = indicators.hazardLights,
            iconRes = R.drawable.ic_indicator_hazard,
            tone = Tone.CAUTION,
            blinks = true
        ),
        Telltale(
            key = "high_beam",
            labelRes = R.string.indicator_high_beam,
            active = indicators.highBeam,
            iconRes = R.drawable.ic_indicator_high_beam,
            tone = Tone.INFO
        ),
        Telltale(
            key = "low_beam",
            labelRes = R.string.indicator_low_beam,
            active = indicators.lowBeam,
            iconRes = R.drawable.ic_indicator_low_beam,
            tone = Tone.OK
        ),
        Telltale(
            key = "parking_brake",
            labelRes = R.string.indicator_parking_brake,
            active = indicators.parkingBrake,
            glyphRes = R.string.glyph_parking_brake,
            tone = Tone.CRITICAL
        ),
        Telltale(
            key = "seat_belt",
            labelRes = R.string.indicator_seat_belt,
            active = indicators.seatBeltUnfastened,
            iconRes = R.drawable.ic_indicator_seat_belt,
            tone = Tone.CRITICAL
        ),
        Telltale(
            key = "door_open",
            labelRes = R.string.indicator_door_open,
            active = indicators.doorOpen,
            iconRes = R.drawable.ic_indicator_door_open,
            tone = Tone.CRITICAL
        ),
        Telltale(
            key = "engine",
            labelRes = R.string.indicator_engine,
            active = indicators.engineWarning,
            iconRes = R.drawable.ic_indicator_engine,
            tone = Tone.CAUTION
        ),
        Telltale(
            key = "low_energy",
            labelRes = lowEnergyLabel,
            active = indicators.lowEnergy,
            iconRes = lowEnergyIcon,
            tone = Tone.CAUTION
        ),
        Telltale(
            key = "tire_pressure",
            labelRes = R.string.indicator_tire_pressure,
            active = indicators.tirePressureWarning,
            iconRes = R.drawable.ic_indicator_tire_pressure,
            tone = Tone.CAUTION
        ),
        Telltale(
            key = "temperature",
            labelRes = R.string.indicator_temperature,
            active = indicators.temperatureWarning,
            iconRes = R.drawable.ic_indicator_temperature,
            tone = Tone.CRITICAL
        ),
        Telltale(
            key = "abs",
            labelRes = R.string.indicator_abs,
            active = indicators.absWarning,
            glyphRes = R.string.glyph_abs,
            tone = Tone.CAUTION
        ),
        Telltale(
            key = "traction_control",
            labelRes = R.string.indicator_traction_control,
            active = indicators.tractionControlWarning,
            glyphRes = R.string.glyph_traction_control,
            tone = Tone.CAUTION
        )
    )
}

private val LAMP_MIN_WIDTH = 76.dp
private const val BLINK_HALF_PERIOD_MILLIS = 340
