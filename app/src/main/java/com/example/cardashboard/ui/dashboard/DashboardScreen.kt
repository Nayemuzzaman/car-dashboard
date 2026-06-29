package com.example.cardashboard.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.cardashboard.ui.theme.CarDashboardTheme
import com.example.cardashboard.ui.theme.DashboardAccent
import com.example.cardashboard.ui.theme.DashboardSpacing
import com.example.cardashboard.ui.theme.DashboardSurface
import com.example.cardashboard.ui.theme.DashboardSurfaceHigh
import com.example.cardashboard.ui.theme.DashboardTextMuted
import com.example.cardashboard.ui.theme.DashboardWarning
import kotlinx.coroutines.delay

private data class DashboardMetric(
    val label: String,
    val value: String,
    val helper: String,
    val accent: Color = DashboardAccent
)

private data class DashboardWarningState(
    val label: String,
    val active: Boolean
)

private val mockMetrics = listOf(
    DashboardMetric("RPM", "2,350", "x1000", Color(0xFFA78BFA)),
    DashboardMetric("Fuel", "68%", "Range 420 km", Color(0xFF22C55E)),
    DashboardMetric("Gear", "D", "Comfort shift", DashboardAccent),
    DashboardMetric("Temp", "91 C", "Engine stable", DashboardWarning)
)

private val mockWarnings = listOf(
    DashboardWarningState("Seatbelt", true),
    DashboardWarningState("Door", false),
    DashboardWarningState("Tire", false),
    DashboardWarningState("Engine", false)
)

private val mockSpeedSequence = listOf(0, 18, 42, 67, 86, 112, 98, 124, 76, 54)

@Composable
fun DashboardScreen(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            Color(0xFF0B1220)
                        )
                    )
                )
                .padding(DashboardSpacing.screenPadding)
        ) {
            val wideLayout = maxWidth >= 720.dp

            if (wideLayout) {
                LandscapeDashboardLayout()
            } else {
                PortraitDashboardLayout()
            }
        }
    }
}

@Composable
private fun LandscapeDashboardLayout() {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.medium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SpeedPanel(
            modifier = Modifier
                .weight(1.25f)
                .fillMaxHeight()
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)
        ) {
            MetricGrid(modifier = Modifier.weight(1f))
            OdometerPanel(modifier = Modifier.fillMaxWidth())
        }
        Column(
            modifier = Modifier
                .weight(0.9f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)
        ) {
            WarningPanel(modifier = Modifier.weight(1f))
            DriveModePanel(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PortraitDashboardLayout() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)
    ) {
        SpeedPanel(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp)
        )
        MetricGrid(modifier = Modifier.fillMaxWidth())
        OdometerPanel(modifier = Modifier.fillMaxWidth())
        WarningPanel(modifier = Modifier.fillMaxWidth())
        DriveModePanel(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SpeedPanel(modifier: Modifier = Modifier) {
    var speed by remember { mutableIntStateOf(mockSpeedSequence.first()) }

    LaunchedEffect(Unit) {
        var speedIndex = 0
        while (true) {
            delay(1_600)
            speedIndex = (speedIndex + 1) % mockSpeedSequence.size
            speed = mockSpeedSequence[speedIndex]
        }
    }

    DashboardPanel(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PanelHeader(title = "Speed", value = "Live mock")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                SpeedGauge(
                    speed = speed,
                    maxSpeed = 220,
                    modifier = Modifier.size(260.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SmallReadout(label = "Trip", value = "142.8 km")
                SmallReadout(label = "Range", value = "420 km")
            }
        }
    }
}

@Composable
private fun SpeedGauge(
    speed: Int,
    maxSpeed: Int,
    modifier: Modifier = Modifier
) {
    val targetProgress = (speed.coerceIn(0, maxSpeed).toFloat() / maxSpeed.toFloat())
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 900),
        label = "speedProgress"
    )
    val animatedSpeed by animateFloatAsState(
        targetValue = speed.toFloat(),
        animationSpec = tween(durationMillis = 900),
        label = "speedNumber"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 18.dp.toPx()
            val gaugeSweep = 270f
            val startAngle = 135f

            drawArc(
                color = DashboardSurfaceHigh,
                startAngle = startAngle,
                sweepAngle = gaugeSweep,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            drawArc(
                color = DashboardAccent,
                startAngle = startAngle,
                sweepAngle = gaugeSweep * animatedProgress,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            drawArc(
                color = DashboardAccent.copy(alpha = 0.22f),
                startAngle = startAngle,
                sweepAngle = gaugeSweep * animatedProgress,
                useCenter = false,
                style = Stroke(width = 34.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = animatedSpeed.toInt().toString(),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "km/h",
                style = MaterialTheme.typography.titleMedium,
                color = DashboardTextMuted
            )
            Text(
                text = "max $maxSpeed",
                style = MaterialTheme.typography.labelMedium,
                color = DashboardTextMuted
            )
        }
    }
}

@Composable
private fun MetricGrid(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)
        ) {
            MetricTile(metric = mockMetrics[0], modifier = Modifier.weight(1f))
            MetricTile(metric = mockMetrics[1], modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)
        ) {
            MetricTile(metric = mockMetrics[2], modifier = Modifier.weight(1f))
            MetricTile(metric = mockMetrics[3], modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricTile(metric: DashboardMetric, modifier: Modifier = Modifier) {
    DashboardPanel(modifier = modifier.heightIn(min = 128.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(DashboardSpacing.small)
        ) {
            Text(
                text = metric.label,
                style = MaterialTheme.typography.labelMedium,
                color = DashboardTextMuted
            )
            Text(
                text = metric.value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = metric.accent
            )
            Text(
                text = metric.helper,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun OdometerPanel(modifier: Modifier = Modifier) {
    DashboardPanel(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)) {
            PanelHeader(title = "Distance", value = "Odometer")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SmallReadout(label = "Total", value = "38,421 km")
                SmallReadout(label = "Trip A", value = "142.8 km")
            }
        }
    }
}

@Composable
private fun WarningPanel(modifier: Modifier = Modifier) {
    DashboardPanel(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)
        ) {
            PanelHeader(title = "Warnings", value = "4 systems")
            mockWarnings.forEach { warning ->
                WarningRow(warning)
            }
        }
    }
}

@Composable
private fun WarningRow(warning: DashboardWarningState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = warning.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = if (warning.active) "ON" else "OK",
            style = MaterialTheme.typography.labelMedium,
            color = if (warning.active) DashboardWarning else Color(0xFF22C55E)
        )
    }
}

@Composable
private fun DriveModePanel(modifier: Modifier = Modifier) {
    DashboardPanel(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)) {
            PanelHeader(title = "Driving Mode", value = "Comfort")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small)
            ) {
                ModeChip(label = "Eco", selected = false, modifier = Modifier.weight(1f))
                ModeChip(label = "Comfort", selected = true, modifier = Modifier.weight(1f))
                ModeChip(label = "Sport", selected = false, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) DashboardAccent else DashboardSurfaceHigh)
            .padding(vertical = DashboardSpacing.small),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color(0xFF03111D) else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DashboardPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = DashboardSurface.copy(alpha = 0.92f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .border(
                    width = 1.dp,
                    color = DashboardSurfaceHigh,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(DashboardSpacing.medium)
        ) {
            content()
        }
    }
}

@Composable
private fun PanelHeader(
    title: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = DashboardTextMuted
        )
    }
}

@Composable
private fun SmallReadout(
    label: String,
    value: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = DashboardTextMuted
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Preview(showBackground = true, widthDp = 900, heightDp = 480)
@Composable
private fun DashboardScreenLandscapePreview() {
    CarDashboardTheme {
        DashboardScreen()
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun DashboardScreenPortraitPreview() {
    CarDashboardTheme {
        DashboardScreen()
    }
}
