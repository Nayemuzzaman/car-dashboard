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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.Dp
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
    val accent: Color? = null
)

private data class DashboardWarningState(
    val label: String,
    val active: Boolean,
    val helper: String
)

private enum class DriveMode(
    val label: String,
    val accent: Color
) {
    Eco("Eco", Color(0xFF22C55E)),
    Comfort("Comfort", DashboardAccent),
    Sport("Sport", Color(0xFFF97316))
}

// Built per mode rather than held as a static list: the Gear helper reads off the selected
// DriveMode, so a value captured once at class-init could never follow it.
private fun mockMetrics(mode: DriveMode) = listOf(
    DashboardMetric("RPM", "2,350", "x1000", Color(0xFFA78BFA)),
    DashboardMetric("Fuel", "68%", "Range 420 km", Color(0xFF22C55E)),
    DashboardMetric("Gear", "D", "${mode.label} shift"),
    DashboardMetric("Temp", "91 C", "Engine stable", DashboardWarning)
)

private val mockWarningScenarios = listOf(
    listOf(
        DashboardWarningState("Seatbelt", true, "Driver belt open"),
        DashboardWarningState("Door", false, "All doors closed"),
        DashboardWarningState("Tire Pressure", false, "Nominal"),
        DashboardWarningState("Check Engine", false, "No fault")
    ),
    listOf(
        DashboardWarningState("Seatbelt", false, "Secured"),
        DashboardWarningState("Door", true, "Rear left open"),
        DashboardWarningState("Tire Pressure", true, "Front right low"),
        DashboardWarningState("Check Engine", false, "No fault")
    ),
    listOf(
        DashboardWarningState("Seatbelt", false, "Secured"),
        DashboardWarningState("Door", false, "All doors closed"),
        DashboardWarningState("Tire Pressure", false, "Nominal"),
        DashboardWarningState("Check Engine", true, "Service soon")
    )
)

private val mockSpeedSequence = listOf(0, 18, 42, 67, 86, 112, 98, 124, 76, 54)

@Composable
fun DashboardScreen(modifier: Modifier = Modifier) {
    // Saveable, not remember: rotation recreates MainActivity, and a plain remember drops
    // the selection back to Comfort. DriveMode is an enum and so java.io.Serializable,
    // which the default saver can put in the bundle without a custom Saver.
    var selectedMode by rememberSaveable { mutableStateOf(DriveMode.Comfort) }
    var warnings by remember { mutableStateOf(mockWarningScenarios.first()) }

    LaunchedEffect(Unit) {
        var scenarioIndex = 0
        while (true) {
            delay(3_200)
            scenarioIndex = (scenarioIndex + 1) % mockWarningScenarios.size
            warnings = mockWarningScenarios[scenarioIndex]
        }
    }

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
                            selectedMode.accent.copy(alpha = 0.16f),
                            MaterialTheme.colorScheme.background,
                            Color(0xFF0B1220)
                        )
                    )
                )
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(DashboardSpacing.screenPadding)
        ) {
            // The landscape layout fills a bounded height rather than scrolling, so it is
            // only safe above a floor. Anything shorter falls back to the scrolling
            // portrait layout instead of clipping its columns.
            val wideLayout = maxWidth >= 720.dp && maxHeight >= 300.dp

            if (wideLayout) {
                LandscapeDashboardLayout(
                    selectedMode = selectedMode,
                    warnings = warnings,
                    onModeSelected = { selectedMode = it }
                )
            } else {
                PortraitDashboardLayout(
                    selectedMode = selectedMode,
                    warnings = warnings,
                    onModeSelected = { selectedMode = it }
                )
            }
        }
    }
}

@Composable
private fun LandscapeDashboardLayout(
    selectedMode: DriveMode,
    warnings: List<DashboardWarningState>,
    onModeSelected: (DriveMode) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // A phone in landscape gives this layout roughly 320dp of height to hold a metric
        // grid plus an odometer, or four warning rows plus the mode chips. At the default
        // density that content intrinsically wants ~370dp, so the panels overflowed their
        // bounded columns and clipped. Below the threshold every panel drops to a tighter
        // padding/spacing scale; above it nothing changes.
        val compact = maxHeight < 480.dp
        val gap = if (compact) DashboardSpacing.small else DashboardSpacing.medium

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpeedPanel(
                accent = selectedMode.accent,
                modifier = Modifier
                    .weight(1.25f)
                    .fillMaxHeight(),
                compact = compact
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                MetricGrid(
                    mode = selectedMode,
                    modifier = Modifier.weight(1f),
                    compact = compact,
                    // The column bounds this grid, so its rows can share that height.
                    fillHeight = true
                )
                OdometerPanel(modifier = Modifier.fillMaxWidth(), compact = compact)
            }
            Column(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                WarningPanel(
                    warnings = warnings,
                    modifier = Modifier.weight(1f),
                    compact = compact
                )
                DriveModePanel(
                    selectedMode = selectedMode,
                    onModeSelected = onModeSelected,
                    modifier = Modifier.fillMaxWidth(),
                    compact = compact
                )
            }
        }
    }
}

@Composable
private fun PortraitDashboardLayout(
    selectedMode: DriveMode,
    warnings: List<DashboardWarningState>,
    onModeSelected: (DriveMode) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)
    ) {
        SpeedPanel(
            accent = selectedMode.accent,
            modifier = Modifier
                .fillMaxWidth()
                // A fixed height, not heightIn(min): inside verticalScroll the max height
                // constraint is Infinity, and a weight(1f) child of an unbounded Column
                // measures to zero — which collapsed the gauge entirely.
                .height(360.dp)
        )
        MetricGrid(mode = selectedMode, modifier = Modifier.fillMaxWidth())
        OdometerPanel(modifier = Modifier.fillMaxWidth())
        WarningPanel(warnings = warnings, modifier = Modifier.fillMaxWidth())
        DriveModePanel(
            selectedMode = selectedMode,
            onModeSelected = onModeSelected,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SpeedPanel(
    accent: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    var speed by remember { mutableIntStateOf(mockSpeedSequence.first()) }

    LaunchedEffect(Unit) {
        var speedIndex = 0
        while (true) {
            delay(1_600)
            speedIndex = (speedIndex + 1) % mockSpeedSequence.size
            speed = mockSpeedSequence[speedIndex]
        }
    }

    DashboardPanel(
        modifier = modifier,
        contentPadding = if (compact) DashboardSpacing.compact else DashboardSpacing.medium
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PanelHeader(title = "Speed", value = "Live mock")
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                // Square, and never larger than the space it was given, so the arc stays
                // circular in both the portrait and landscape layouts.
                SpeedGauge(
                    speed = speed,
                    maxSpeed = 220,
                    accent = accent,
                    modifier = Modifier.size(minOf(maxWidth, maxHeight, 260.dp))
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
    accent: Color,
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
                color = accent,
                startAngle = startAngle,
                sweepAngle = gaugeSweep * animatedProgress,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            drawArc(
                color = accent.copy(alpha = 0.22f),
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
private fun MetricGrid(
    mode: DriveMode,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    fillHeight: Boolean = false
) {
    val gap = if (compact) DashboardSpacing.small else DashboardSpacing.medium
    // The mode is the single source of truth for both the accent and the Gear helper.
    val accent = mode.accent
    val metrics = remember(mode) { mockMetrics(mode) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(gap)
    ) {
        // Only take a weight when the caller bounded our height. In portrait this grid sits
        // inside a verticalScroll, where a weighted child measures to zero and both rows
        // would vanish; there the rows stay wrap-content as before.
        val rowModifier = if (fillHeight) {
            Modifier
                .fillMaxWidth()
                .weight(1f)
        } else {
            Modifier.fillMaxWidth()
        }
        val tileModifier = if (fillHeight) Modifier.fillMaxHeight() else Modifier

        listOf(
            metrics[0] to metrics[1],
            metrics[2] to metrics[3]
        ).forEach { (left, right) ->
            Row(
                modifier = rowModifier,
                horizontalArrangement = Arrangement.spacedBy(gap)
            ) {
                MetricTile(
                    metric = left,
                    accent = accent,
                    modifier = tileModifier.weight(1f),
                    compact = compact
                )
                MetricTile(
                    metric = right,
                    accent = accent,
                    modifier = tileModifier.weight(1f),
                    compact = compact
                )
            }
        }
    }
}

@Composable
private fun MetricTile(
    metric: DashboardMetric,
    accent: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    DashboardPanel(
        // The 128dp floor is what overflowed the bounded landscape column: two rows of it
        // plus spacing demanded more height than the column had. Where the parent already
        // sizes the tile, the floor is redundant.
        modifier = if (compact) modifier else modifier.heightIn(min = 128.dp),
        contentPadding = if (compact) DashboardSpacing.compact else DashboardSpacing.medium
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(
                if (compact) DashboardSpacing.tight else DashboardSpacing.small
            )
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
                color = metric.accent ?: accent
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
private fun OdometerPanel(modifier: Modifier = Modifier, compact: Boolean = false) {
    DashboardPanel(
        modifier = modifier,
        contentPadding = if (compact) DashboardSpacing.compact else DashboardSpacing.medium
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(
                if (compact) DashboardSpacing.small else DashboardSpacing.medium
            )
        ) {
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
private fun WarningPanel(
    warnings: List<DashboardWarningState>,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val activeWarningCount = warnings.count { it.active }

    DashboardPanel(
        modifier = modifier,
        contentPadding = if (compact) DashboardSpacing.compact else DashboardSpacing.medium
    ) {
        Column(
            modifier = if (compact) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(
                if (compact) DashboardSpacing.small else DashboardSpacing.medium
            )
        ) {
            PanelHeader(
                title = "Warnings",
                value = if (activeWarningCount == 0) "All clear" else "$activeWarningCount active"
            )
            // The rows share whatever height is left after the header rather than each
            // claiming an intrinsic height and pushing the last one past the panel edge.
            // SpaceEvenly keeps them centred in the leftover space when there is slack.
            Column(
                modifier = if (compact) Modifier.weight(1f) else Modifier,
                verticalArrangement = if (compact) {
                    Arrangement.SpaceEvenly
                } else {
                    Arrangement.spacedBy(DashboardSpacing.medium)
                }
            ) {
                warnings.forEach { warning ->
                    WarningRow(warning)
                }
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (warning.active) DashboardWarning else Color(0xFF22C55E))
            )
            Column {
                Text(
                    text = warning.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = warning.helper,
                    style = MaterialTheme.typography.labelMedium,
                    color = DashboardTextMuted
                )
            }
        }
        Text(
            text = if (warning.active) "ON" else "OK",
            style = MaterialTheme.typography.labelMedium,
            color = if (warning.active) DashboardWarning else Color(0xFF22C55E)
        )
    }
}

@Composable
private fun DriveModePanel(
    selectedMode: DriveMode,
    onModeSelected: (DriveMode) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    DashboardPanel(
        modifier = modifier,
        contentPadding = if (compact) DashboardSpacing.compact else DashboardSpacing.medium
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(
                if (compact) DashboardSpacing.small else DashboardSpacing.medium
            )
        ) {
            PanelHeader(title = "Driving Mode", value = selectedMode.label)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small)
            ) {
                DriveMode.entries.forEach { mode ->
                    ModeChip(
                        label = mode.label,
                        selected = mode == selectedMode,
                        accent = mode.accent,
                        onClick = { onModeSelected(mode) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) accent else DashboardSurfaceHigh),
        color = if (selected) accent else DashboardSurfaceHigh,
        shape = RoundedCornerShape(8.dp),
        onClick = onClick
    ) {
        Box(
            modifier = Modifier.padding(vertical = DashboardSpacing.small),
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
}

@Composable
private fun DashboardPanel(
    modifier: Modifier = Modifier,
    contentPadding: Dp = DashboardSpacing.medium,
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
                .padding(contentPadding)
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
