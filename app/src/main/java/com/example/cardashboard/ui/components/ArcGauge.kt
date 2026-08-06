package com.example.cardashboard.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.cardashboard.ui.theme.DashboardSpacing
import com.example.cardashboard.ui.theme.LocalDashboardColors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** How long a gauge takes to travel to a new reading; matched to the data tick so motion is even. */
const val GAUGE_ANIMATION_MILLIS = 260

/**
 * A circular sweep gauge with a track, a filled progress arc, tick marks and an optional red zone.
 *
 * Drawing is sized from the smaller of the two canvas dimensions and inset by the stroke width, so
 * the gauge stays round and uncropped whatever box it is given.
 */
@Composable
fun ArcGauge(
    progress: Float,
    accent: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = DashboardSpacing.gaugeStroke,
    tickCount: Int = 9,
    redZoneStart: Float? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = LocalDashboardColors.current
    val safeProgress = progress.coerceIn(0f, 1f)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val diameter = min(size.width, size.height) - stroke
            if (diameter <= 0f) return@Canvas
            val topLeft = Offset(
                x = (size.width - diameter) / 2f,
                y = (size.height - diameter) / 2f
            )
            val arcSize = Size(diameter, diameter)

            drawArc(
                color = colors.gaugeTrack,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            if (redZoneStart != null && redZoneStart < 1f) {
                val zoneStart = START_ANGLE + SWEEP_ANGLE * redZoneStart.coerceIn(0f, 1f)
                drawArc(
                    color = colors.critical.copy(alpha = 0.55f),
                    startAngle = zoneStart,
                    sweepAngle = START_ANGLE + SWEEP_ANGLE - zoneStart,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt)
                )
            }

            if (safeProgress > 0f) {
                drawArc(
                    color = accent,
                    startAngle = START_ANGLE,
                    sweepAngle = SWEEP_ANGLE * safeProgress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }

            // Tick marks sit just inside the track and give the sweep a readable scale.
            if (tickCount > 1) {
                val centre = Offset(topLeft.x + diameter / 2f, topLeft.y + diameter / 2f)
                val outerRadius = diameter / 2f - stroke * 0.9f
                val innerRadius = outerRadius - stroke * 0.55f
                repeat(tickCount) { index ->
                    val fraction = index / (tickCount - 1f)
                    val angle = Math.toRadians((START_ANGLE + SWEEP_ANGLE * fraction).toDouble())
                    val cosA = cos(angle).toFloat()
                    val sinA = sin(angle).toFloat()
                    drawLine(
                        // At rest no tick is highlighted, so an idle gauge never looks partly lit.
                        color = if (safeProgress > 0f && fraction <= safeProgress) {
                            accent
                        } else {
                            colors.gaugeTrack
                        },
                        start = Offset(
                            centre.x + innerRadius * cosA,
                            centre.y + innerRadius * sinA
                        ),
                        end = Offset(
                            centre.x + outerRadius * cosA,
                            centre.y + outerRadius * sinA
                        ),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        }
        content()
    }
}

/**
 * Eases a gauge towards a new reading, or jumps straight to it when the driver has turned gauge
 * animations off.
 */
@Composable
fun animatedGaugeValue(target: Float, animate: Boolean): State<Float> = if (animate) {
    animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = GAUGE_ANIMATION_MILLIS, easing = LinearEasing),
        label = "gaugeValue"
    )
} else {
    rememberUpdatedState(target)
}

private const val START_ANGLE = 135f
private const val SWEEP_ANGLE = 270f
