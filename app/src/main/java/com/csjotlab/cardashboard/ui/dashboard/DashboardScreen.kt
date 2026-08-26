package com.csjotlab.cardashboard.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.csjotlab.cardashboard.ui.theme.CarDashboardTheme
import com.csjotlab.cardashboard.ui.theme.DashboardAccent
import com.csjotlab.cardashboard.ui.theme.DashboardSpacing
import com.csjotlab.cardashboard.ui.theme.DashboardSurface
import com.csjotlab.cardashboard.ui.theme.DashboardSurfaceHigh
import com.csjotlab.cardashboard.ui.theme.DashboardTextMuted
import com.csjotlab.cardashboard.ui.theme.DashboardWarning

private enum class DriveMode(
    val label: String,
    val accent: Color
) {
    Eco("Eco", Color(0xFF22C55E)),
    Comfort("Comfort", DashboardAccent),
    Sport("Sport", Color(0xFFF97316))
}

@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    onDriveModeLabelChanged: (String) -> Unit,
    onIssueClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    // The connection banner's action is only ever offered when the formatter supplies a label for
    // it, and the only such state today is "USB permission required" — which cannot occur until
    // the USB source exists in Task 18. Defaulted so the callers that have nothing to do with it
    // (the previews, the layout tests) stay unchanged.
    onConnectionAction: () -> Unit = {},
    // Variant-specific debug controls are measured as dashboard chrome instead of overlaid above
    // it. The release source-set lambda emits no node, so it also consumes no layout space.
    debugContent: @Composable (Modifier) -> Unit = {},
) {
    // Saveable, not remember: rotation recreates MainActivity, and a plain remember drops
    // the selection back to Comfort. DriveMode is an enum and so java.io.Serializable,
    // which the default saver can put in the bundle without a custom Saver.
    var selectedMode by rememberSaveable { mutableStateOf(DriveMode.Comfort) }

    // A one-shot notification of a UI preference, not a data loop: it fires once per selection
    // change so the formatter can phrase the Gear helper, and never polls anything.
    LaunchedEffect(selectedMode) { onDriveModeLabelChanged(selectedMode.label) }

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
                    uiState = uiState,
                    selectedMode = selectedMode,
                    onModeSelected = { selectedMode = it },
                    onIssueClick = onIssueClick,
                    onConnectionAction = onConnectionAction,
                    debugContent = debugContent,
                )
            } else {
                PortraitDashboardLayout(
                    uiState = uiState,
                    selectedMode = selectedMode,
                    onModeSelected = { selectedMode = it },
                    onIssueClick = onIssueClick,
                    onConnectionAction = onConnectionAction,
                    debugContent = debugContent,
                )
            }
        }
    }
}

@Composable
private fun LandscapeDashboardLayout(
    uiState: DashboardUiState,
    selectedMode: DriveMode,
    onModeSelected: (DriveMode) -> Unit,
    onIssueClick: (String) -> Unit,
    onConnectionAction: () -> Unit,
    debugContent: @Composable (Modifier) -> Unit,
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
            // The banners and debug chrome live inside the first column, not in a full-width row
            // above all three. A row above would be unweighted and would therefore tax every column
            // equally — and columns two and three have nothing to give: measured on a Pixel
            // 7a, that arrangement squeezed the metric tiles' helper text from two lines to
            // one and pushed the fourth warning row out of its panel, which is the same
            // overflow as before commit ecf85cd arriving from above instead of within.
            //
            // Column one is the only elastic one. Its SpeedPanel is a gauge sized
            // minOf(maxWidth, maxHeight, 260.dp), so it absorbs the chrome rows by
            // drawing a slightly smaller circle and clips nothing. Full column width also
            // keeps the simulation wording on one line, which half a row did not.
            Column(
                modifier = Modifier
                    .weight(1.25f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                debugContent(Modifier.align(Alignment.End))
                if (uiState.isSimulated) {
                    SimulationBanner(
                        modifier = Modifier.fillMaxWidth(),
                        compact = compact
                    )
                }
                ConnectionBanner(
                    label = uiState.connectionLabel,
                    actionLabel = uiState.connectionActionLabel,
                    onAction = onConnectionAction,
                    modifier = Modifier.fillMaxWidth()
                )
                SpeedPanel(
                    uiState = uiState,
                    accent = selectedMode.accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    compact = compact
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                MetricGrid(
                    metrics = uiState.metrics,
                    accent = selectedMode.accent,
                    modifier = Modifier.weight(1f),
                    compact = compact,
                    // The column bounds this grid, so its rows can share that height.
                    fillHeight = true
                )
                OdometerPanel(
                    totalText = uiState.odometerText,
                    tripText = uiState.tripText,
                    modifier = Modifier.fillMaxWidth(),
                    compact = compact
                )
            }
            Column(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                // No Vehicle Health panel here on purpose. This column is height-bounded and
                // already holds four warning rows plus the mode chips; a third panel is what
                // overflowed it before commit ecf85cd. The diagnostic count rides in the
                // Warnings header instead, which costs no height at all.
                WarningPanel(
                    warnings = uiState.warnings,
                    onIssueClick = onIssueClick,
                    modifier = Modifier.weight(1f),
                    compact = compact,
                    diagnosticCount = uiState.diagnostics.size
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
    uiState: DashboardUiState,
    selectedMode: DriveMode,
    onModeSelected: (DriveMode) -> Unit,
    onIssueClick: (String) -> Unit,
    onConnectionAction: () -> Unit,
    debugContent: @Composable (Modifier) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)
    ) {
        debugContent(Modifier.align(Alignment.End))
        if (uiState.isSimulated) {
            SimulationBanner(modifier = Modifier.fillMaxWidth())
        }
        ConnectionBanner(
            label = uiState.connectionLabel,
            actionLabel = uiState.connectionActionLabel,
            onAction = onConnectionAction,
            modifier = Modifier.fillMaxWidth()
        )
        SpeedPanel(
            uiState = uiState,
            accent = selectedMode.accent,
            modifier = Modifier
                .fillMaxWidth()
                // A fixed height, not heightIn(min): inside verticalScroll the max height
                // constraint is Infinity, and a weight(1f) child of an unbounded Column
                // measures to zero — which collapsed the gauge entirely.
                .height(360.dp)
        )
        MetricGrid(
            metrics = uiState.metrics,
            accent = selectedMode.accent,
            modifier = Modifier.fillMaxWidth()
        )
        OdometerPanel(
            totalText = uiState.odometerText,
            tripText = uiState.tripText,
            modifier = Modifier.fillMaxWidth()
        )
        WarningPanel(
            warnings = uiState.warnings,
            onIssueClick = onIssueClick,
            modifier = Modifier.fillMaxWidth()
        )
        // Portrait scrolls, so the full list of diagnostics is safe here. Its landscape
        // counterpart is the count in the Warnings header above.
        VehicleHealthPanel(
            diagnostics = uiState.diagnostics,
            isConnected = uiState.isConnected,
            onIssueClick = onIssueClick,
            modifier = Modifier.fillMaxWidth()
        )
        DriveModePanel(
            selectedMode = selectedMode,
            onModeSelected = onModeSelected,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SpeedPanel(
    uiState: DashboardUiState,
    accent: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    DashboardPanel(
        modifier = modifier,
        contentPadding = if (compact) DashboardSpacing.compact else DashboardSpacing.medium
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PanelHeader(title = "Speed", value = uiState.connectionLabel)
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                // Square, and never larger than the space it was given, so the arc stays
                // circular in both the portrait and landscape layouts.
                SpeedGauge(
                    speed = uiState.speedForGauge,
                    speedText = uiState.speedText,
                    maxSpeed = uiState.maxSpeedKph,
                    accent = accent,
                    modifier = Modifier.size(minOf(maxWidth, maxHeight, 260.dp))
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SmallReadout(label = "Trip", value = uiState.tripText)
                SmallReadout(label = "Range", value = uiState.rangeText)
            }
        }
    }
}

@Composable
private fun SpeedGauge(
    speed: Int?,
    speedText: String,
    maxSpeed: Int,
    accent: Color,
    modifier: Modifier = Modifier
) {
    // A missing reading leaves the arc empty rather than pinning it to zero, which would look
    // exactly like a stationary car. Note that a zero *progress* is not enough on its own: with
    // StrokeCap.Round a zero-sweep arc still paints a round cap at the 135 degree origin, which is
    // indistinguishable from a real reading of 0 km/h. The progress arcs are therefore skipped
    // entirely below when there is nothing to show, leaving only the track.
    val hasReading = speed != null
    val targetProgress = speed?.let { it.coerceIn(0, maxSpeed).toFloat() / maxSpeed.toFloat() } ?: 0f
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 900),
        label = "speedProgress"
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
            if (hasReading) {
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
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = speedText,
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
    metrics: List<MetricUi>,
    accent: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    fillHeight: Boolean = false
) {
    // Two fixed rows of two, indexed below. The formatter guarantees four tiles in every state and
    // "the grid must never reflow" depends on it, so state the contract here instead of letting a
    // shorter list surface as an IndexOutOfBoundsException from inside a Compose layout pass.
    require(metrics.size == 4) {
        "MetricGrid renders exactly four tiles in two fixed rows; got ${metrics.size}"
    }

    val gap = if (compact) DashboardSpacing.small else DashboardSpacing.medium

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
    metric: MetricUi,
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
                // The em dash for a missing reading is not a value, so it does not get the
                // accent a reading would: unavailable has to look unavailable.
                color = if (metric.available) accent else DashboardTextMuted
            )
            Text(
                text = metric.helper,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                // Compact is the bounded landscape tile, which has room for one line of helper
                // text and not two. Because the 128dp floor is dropped just above, the overflow
                // there is silent: the second line is simply cut, and "Not reported by vehicle"
                // renders as "Not reported by" with nothing to show it was shortened. An ellipsis
                // says so.
                //
                // Ellipsized, never rephrased. "Not available from this source" and "Not reported
                // by vehicle" are different claims — one is about the active source, the other
                // about whether a reading has arrived — and a shorter paraphrase is a new claim
                // rather than a shorter one. Truncating the glyphs keeps the strings distinct.
                maxLines = if (compact) 1 else Int.MAX_VALUE,
                overflow = if (compact) TextOverflow.Ellipsis else TextOverflow.Clip
            )
        }
    }
}

@Composable
private fun OdometerPanel(
    totalText: String,
    tripText: String,
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
            PanelHeader(title = "Distance", value = "Odometer")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SmallReadout(label = "Total", value = totalText)
                SmallReadout(label = "Trip A", value = tripText)
            }
        }
    }
}

@Composable
private fun WarningPanel(
    warnings: List<WarningUi>,
    onIssueClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /**
     * Landscape only. Portrait shows the diagnostics in their own [VehicleHealthPanel]; the
     * landscape third column has no room for one, so the count is folded into this header.
     */
    diagnosticCount: Int = 0
) {
    // The grid must never reflow between states, and the compact SpaceEvenly branch below shares
    // the leftover height between a fixed number of rows. Say so rather than discovering it as a
    // misaligned panel.
    require(warnings.size == 4) {
        "WarningPanel renders exactly four rows; got ${warnings.size}"
    }

    // Counting is not enough: zero Active rows can mean "all four reported and are safe" or
    // "nothing has been read", and only the first of those is "All clear". The distinction lives in
    // the formatter next to the row-level rule it mirrors, and warningsHeaderValue delegates to it
    // rather than re-deriving a second summary alongside the diagnostic count.
    val summary = warningsHeaderValue(warnings, diagnosticCount)

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
                value = summary
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
                    WarningRow(warning, onIssueClick)
                }
            }
        }
    }
}

@Composable
private fun WarningRow(warning: WarningUi, onIssueClick: (String) -> Unit) {
    val (dotColor, statusLabel, statusColor) = when (warning.level) {
        WarningLevel.Active -> Triple(DashboardWarning, "ON", DashboardWarning)
        WarningLevel.Ok -> Triple(Color(0xFF22C55E), "OK", Color(0xFF22C55E))
        // Neutral, never green: with no data, "OK" would be a safety claim we cannot make.
        WarningLevel.NotReported -> Triple(DashboardTextMuted, UNAVAILABLE, DashboardTextMuted)
    }

    val rowModifier = if (warning.issueId != null) {
        Modifier
            .fillMaxWidth()
            .clickable { onIssueClick(warning.issueId) }
    } else {
        Modifier.fillMaxWidth()
    }

    Row(
        modifier = rowModifier,
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
                    .background(dotColor)
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
            text = statusLabel,
            style = MaterialTheme.typography.labelMedium,
            color = statusColor
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
internal fun DashboardPanel(
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
internal fun PanelHeader(
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
        DashboardScreen(
            uiState = DashboardUiState.disconnected("Comfort"),
            onDriveModeLabelChanged = {},
            onIssueClick = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun DashboardScreenPortraitPreview() {
    CarDashboardTheme {
        DashboardScreen(
            uiState = DashboardUiState.disconnected("Comfort"),
            onDriveModeLabelChanged = {},
            onIssueClick = {}
        )
    }
}
