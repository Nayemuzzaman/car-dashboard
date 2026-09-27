package com.csjotlab.cardashboard.ui.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A turn arrow drawn as vector paths, so it is crisp at banner size and identical on every device
 * (the Unicode arrow glyphs used before vary by font and are hard to read at a glance). Left-hand
 * maneuvers are the right-hand ones mirrored.
 */
@Composable
fun ManeuverIcon(type: ManeuverType, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.semantics { contentDescription = type.describe() }) {
        val mirrored = type in LEFT_TYPES
        scale(scaleX = if (mirrored) -1f else 1f, scaleY = 1f) { drawManeuver(type, color) }
    }
}

private val LEFT_TYPES = setOf(
    ManeuverType.TurnLeft, ManeuverType.SlightLeft, ManeuverType.SharpLeft, ManeuverType.KeepLeft, ManeuverType.RampLeft,
)

private fun DrawScope.drawManeuver(type: ManeuverType, color: Color) {
    val w = size.minDimension
    fun p(x: Float, y: Float) = Offset(x * w, y * w)
    val stroke = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val faint = color.copy(alpha = 0.35f)

    fun arrow(path: Path, from: Offset, tip: Offset, tint: Color = color) {
        drawPath(path, tint, style = stroke)
        arrowHead(from, tip, tint, w)
    }

    when (type) {
        ManeuverType.TurnLeft, ManeuverType.TurnRight -> arrow(
            Path().apply { moveTo(p(0.32f, 0.92f)); lineTo(p(0.32f, 0.52f)); quadraticBezierTo(0.32f * w, 0.32f * w, 0.52f * w, 0.32f * w); lineTo(p(0.72f, 0.32f)) },
            p(0.6f, 0.32f), p(0.86f, 0.32f),
        )
        ManeuverType.SlightLeft, ManeuverType.SlightRight -> arrow(
            Path().apply { moveTo(p(0.38f, 0.92f)); lineTo(p(0.38f, 0.58f)); lineTo(p(0.66f, 0.28f)) },
            p(0.55f, 0.40f), p(0.76f, 0.17f),
        )
        ManeuverType.SharpLeft, ManeuverType.SharpRight -> arrow(
            Path().apply { moveTo(p(0.32f, 0.92f)); lineTo(p(0.32f, 0.22f)); lineTo(p(0.66f, 0.62f)) },
            p(0.5f, 0.43f), p(0.76f, 0.74f),
        )
        ManeuverType.KeepLeft, ManeuverType.KeepRight, ManeuverType.RampLeft, ManeuverType.RampRight, is ManeuverType.Exit -> {
            drawPath(Path().apply { moveTo(p(0.4f, 0.92f)); lineTo(p(0.4f, 0.16f)) }, faint, style = stroke)
            arrow(
                Path().apply { moveTo(p(0.4f, 0.92f)); lineTo(p(0.4f, 0.62f)); lineTo(p(0.68f, 0.30f)) },
                p(0.55f, 0.45f), p(0.78f, 0.18f),
            )
        }
        ManeuverType.Merge -> {
            drawPath(Path().apply { moveTo(p(0.76f, 0.92f)); quadraticBezierTo(0.76f * w, 0.62f * w, 0.5f * w, 0.48f * w) }, faint, style = stroke)
            arrow(
                Path().apply { moveTo(p(0.26f, 0.92f)); quadraticBezierTo(0.26f * w, 0.62f * w, 0.5f * w, 0.48f * w); lineTo(p(0.5f, 0.24f)) },
                p(0.5f, 0.4f), p(0.5f, 0.1f),
            )
        }
        ManeuverType.UTurn -> arrow(
            Path().apply {
                moveTo(p(0.66f, 0.92f)); lineTo(p(0.66f, 0.42f))
                arcTo(androidx.compose.ui.geometry.Rect(0.30f * w, 0.20f * w, 0.66f * w, 0.56f * w), 0f, -180f, false)
                lineTo(p(0.30f, 0.62f))
            },
            p(0.30f, 0.5f), p(0.30f, 0.78f),
        )
        is ManeuverType.Roundabout -> {
            drawCircle(color, radius = 0.19f * w, center = p(0.5f, 0.5f), style = stroke)
            drawPath(Path().apply { moveTo(p(0.5f, 0.95f)); lineTo(p(0.5f, 0.69f)) }, color, style = stroke)
            arrow(Path().apply { moveTo(p(0.63f, 0.37f)); lineTo(p(0.76f, 0.24f)) }, p(0.63f, 0.37f), p(0.86f, 0.14f))
        }
        ManeuverType.Arrive -> {
            val pin = Path().apply {
                moveTo(p(0.5f, 0.92f))
                cubicTo(0.36f * w, 0.72f * w, 0.24f * w, 0.56f * w, 0.24f * w, 0.40f * w)
                arcTo(androidx.compose.ui.geometry.Rect(0.24f * w, 0.14f * w, 0.76f * w, 0.66f * w), 180f, 180f, false)
                cubicTo(0.76f * w, 0.56f * w, 0.64f * w, 0.72f * w, 0.5f * w, 0.92f * w)
                close()
            }
            drawPath(pin, color)
            drawCircle(Color.Black.copy(alpha = 0.25f), radius = 0.09f * w, center = p(0.5f, 0.40f))
        }
        ManeuverType.Depart, ManeuverType.Continue, ManeuverType.Unknown -> arrow(
            Path().apply { moveTo(p(0.5f, 0.92f)); lineTo(p(0.5f, 0.26f)) },
            p(0.5f, 0.4f), p(0.5f, 0.1f),
        )
    }
}

private fun Path.moveTo(point: Offset) = moveTo(point.x, point.y)
private fun Path.lineTo(point: Offset) = lineTo(point.x, point.y)

private fun DrawScope.arrowHead(from: Offset, tip: Offset, color: Color, w: Float) {
    val angle = atan2(tip.y - from.y, tip.x - from.x)
    val length = w * 0.26f
    val spread = 0.62f
    val left = Offset(tip.x - length * cos(angle - spread), tip.y - length * sin(angle - spread))
    val right = Offset(tip.x - length * cos(angle + spread), tip.y - length * sin(angle + spread))
    drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(left.x, left.y); lineTo(right.x, right.y); close() }, color)
}

/** Spoken-style name for accessibility services. */
fun ManeuverType.describe(): String = when (this) {
    ManeuverType.Depart -> "Depart"
    ManeuverType.Arrive -> "Arrive"
    ManeuverType.Continue -> "Continue straight"
    ManeuverType.TurnLeft -> "Turn left"
    ManeuverType.TurnRight -> "Turn right"
    ManeuverType.SlightLeft -> "Slight left"
    ManeuverType.SlightRight -> "Slight right"
    ManeuverType.SharpLeft -> "Sharp left"
    ManeuverType.SharpRight -> "Sharp right"
    ManeuverType.UTurn -> "U-turn"
    is ManeuverType.Roundabout -> "Roundabout"
    is ManeuverType.Exit -> "Exit"
    ManeuverType.KeepLeft -> "Keep left"
    ManeuverType.KeepRight -> "Keep right"
    ManeuverType.Merge -> "Merge"
    ManeuverType.RampLeft -> "Ramp on the left"
    ManeuverType.RampRight -> "Ramp on the right"
    ManeuverType.Unknown -> "Continue"
}
