package com.csjotlab.cardashboard.ui.navigation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.geocoding.Place
import com.csjotlab.cardashboard.ui.dashboard.DashboardPanel
import com.csjotlab.cardashboard.ui.theme.DashboardAccent
import com.csjotlab.cardashboard.ui.theme.DashboardSpacing
import com.csjotlab.cardashboard.ui.theme.DashboardSurface
import com.csjotlab.cardashboard.ui.theme.DashboardSurfaceHigh
import com.csjotlab.cardashboard.ui.theme.DashboardTextMuted
import com.csjotlab.cardashboard.ui.theme.DashboardWarning

private val OnAccent = Color(0xFF03111D)

// ---------------------------------------------------------------- Planning: top of the map

enum class PlannerField { Start, Destination }

/**
 * Google-Maps-style route planner: two stacked endpoint rows and a swap button. The active row is
 * the live text field; the other shows what was chosen. Start defaults to "Your location".
 */
@Composable
internal fun PlannerCard(
    originName: String?,
    destinationName: String?,
    activeField: PlannerField,
    query: String,
    onQueryChange: (String) -> Unit,
    onFieldSelected: (PlannerField) -> Unit,
    onSwap: () -> Unit,
    onUseCurrentLocation: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small),
        verticalAlignment = Alignment.Top,
    ) {
        PillButton(text = "Back", accent = true, onClick = onBack)
        DashboardPanel(modifier = Modifier.weight(1f), contentPadding = DashboardSpacing.small) {
            Column(verticalArrangement = Arrangement.spacedBy(DashboardSpacing.tight)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DashboardSpacing.tight)) {
                        PlannerRow(
                            glyph = "●",
                            glyphColor = DashboardAccent,
                            label = originName ?: YOUR_LOCATION,
                            placeholder = "Search start",
                            active = activeField == PlannerField.Start,
                            query = query,
                            onQueryChange = onQueryChange,
                            onClick = { onFieldSelected(PlannerField.Start) },
                        )
                        PlannerRow(
                            glyph = "◉",
                            glyphColor = DashboardWarning,
                            label = destinationName ?: CHOOSE_DESTINATION,
                            placeholder = "Search destination",
                            active = activeField == PlannerField.Destination,
                            query = query,
                            onQueryChange = onQueryChange,
                            onClick = { onFieldSelected(PlannerField.Destination) },
                        )
                    }
                    SquareButton(text = "⇅", contentDescription = "Swap start and destination", onClick = onSwap)
                }
                if (activeField == PlannerField.Start && originName != null) {
                    PillButton(text = "Use your location", accent = false, onClick = onUseCurrentLocation)
                }
            }
        }
    }
}

@Composable
private fun PlannerRow(
    glyph: String,
    glyphColor: Color,
    label: String,
    placeholder: String,
    active: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = glyph, style = MaterialTheme.typography.titleMedium, color = glyphColor)
        if (active) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(if (label == YOUR_LOCATION || label == CHOOSE_DESTINATION) placeholder else label, color = DashboardTextMuted) },
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = RoundedCornerShape(8.dp),
            )
        } else {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                color = DashboardSurfaceHigh,
                onClick = onClick,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(vertical = DashboardSpacing.compact, horizontal = DashboardSpacing.medium),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal const val YOUR_LOCATION = "Your location"
internal const val CHOOSE_DESTINATION = "Choose destination"

@Composable
internal fun SearchResultsList(
    search: SearchUiState,
    maxHeight: Dp,
    onSelect: (Place) -> Unit,
) {
    DashboardPanel(modifier = Modifier.fillMaxWidth(), contentPadding = DashboardSpacing.small) {
        when {
            search.error != null -> Text(
                text = search.error,
                modifier = Modifier.padding(DashboardSpacing.small),
                style = MaterialTheme.typography.bodyMedium,
                color = DashboardWarning,
            )
            search.results.isEmpty() -> Text(
                text = "No places found",
                modifier = Modifier.padding(DashboardSpacing.small),
                style = MaterialTheme.typography.bodyMedium,
                color = DashboardTextMuted,
            )
            else -> LazyColumn(
                modifier = Modifier.heightIn(max = maxHeight),
                verticalArrangement = Arrangement.spacedBy(DashboardSpacing.tight),
            ) {
                items(search.results) { result ->
                    SearchResultRow(result = result, onClick = { onSelect(result.place) })
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(result: SearchResultUi, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = DashboardSurfaceHigh,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(vertical = DashboardSpacing.small, horizontal = DashboardSpacing.medium),
            horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = placeGlyph(result.place.category), style = MaterialTheme.typography.titleMedium)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DashboardSpacing.tight)) {
                Text(
                    text = result.place.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val secondary = listOfNotNull(result.place.category?.replace('_', ' '), result.place.address).joinToString(" · ")
                if (secondary.isNotBlank()) {
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.labelMedium,
                        color = DashboardTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            result.distanceText?.let { distance ->
                Text(
                    text = distance,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = DashboardAccent,
                )
            }
        }
    }
}

@Composable
internal fun RecentDestinationsList(recent: List<Place>, maxHeight: Dp, onSelect: (Place) -> Unit) {
    DashboardPanel(modifier = Modifier.fillMaxWidth(), contentPadding = DashboardSpacing.small) {
        Column(verticalArrangement = Arrangement.spacedBy(DashboardSpacing.tight)) {
            Text(
                text = "Recent",
                modifier = Modifier.padding(horizontal = DashboardSpacing.small),
                style = MaterialTheme.typography.labelMedium,
                color = DashboardTextMuted,
            )
            LazyColumn(
                modifier = Modifier.heightIn(max = maxHeight),
                verticalArrangement = Arrangement.spacedBy(DashboardSpacing.tight),
            ) {
                items(recent) { place ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = DashboardSurfaceHigh,
                        onClick = { onSelect(place) },
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = DashboardSpacing.small, horizontal = DashboardSpacing.medium),
                            horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = RECENT_GLYPH, style = MaterialTheme.typography.titleMedium)
                            Column(verticalArrangement = Arrangement.spacedBy(DashboardSpacing.tight)) {
                                Text(
                                    text = place.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                place.address?.let {
                                    Text(text = it, style = MaterialTheme.typography.labelMedium, color = DashboardTextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Overview / Guidance / Arrived: bottom panels

@Composable
internal fun OverviewPanel(
    uiState: NavigationUiState,
    destinationName: String?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onAvoidTollsChanged: (Boolean) -> Unit,
) {
    DashboardPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(DashboardSpacing.small)) {
            Text(
                text = destinationName ?: "Destination",
                style = MaterialTheme.typography.titleMedium,
                color = DashboardAccent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (uiState.hasRoute) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Readout(label = "Distance", value = uiState.remainingDistanceText)
                    Readout(label = "Time", value = uiState.remainingTimeText)
                    Readout(label = "ETA", value = uiState.etaText)
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    uiState.tollLabel?.let { TollBadge(it) }
                    uiState.viaText?.let {
                        Text(text = it, style = MaterialTheme.typography.labelMedium, color = DashboardTextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (uiState.isRerouting) {
                        Text(text = "Updating route…", style = MaterialTheme.typography.labelMedium, color = DashboardTextMuted)
                    }
                }
                AvoidTollsToggle(checked = uiState.avoidTolls, onCheckedChange = onAvoidTollsChanged)
            } else {
                Text(
                    text = uiState.statusLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (uiState.statusLabel == UNABLE_TO_FIND_ROUTE) DashboardWarning else DashboardTextMuted,
                )
            }
            uiState.previewLabel?.let { PreviewWarning(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small)) {
                PillButton(text = "Start", accent = true, enabled = uiState.hasRoute, onClick = onStart)
                PillButton(text = "Cancel", accent = false, onClick = onCancel)
            }
        }
    }
}

@Composable
internal fun GuidancePanel(
    uiState: NavigationUiState,
    maneuverType: ManeuverType?,
    onEnd: () -> Unit,
) {
    DashboardPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(DashboardSpacing.small)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = uiState.statusLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (uiState.statusLabel == UNABLE_TO_REROUTE) DashboardWarning else DashboardAccent,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small), verticalAlignment = Alignment.CenterVertically) {
                    uiState.tollLabel?.takeIf { it != TOLL_FREE }?.let { TollBadge(it) }
                    PillButton(text = "End", accent = false, onClick = onEnd)
                }
            }
            uiState.previewLabel?.let { PreviewWarning(it) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                maneuverType?.let { type ->
                    Text(
                        text = type.glyph(),
                        style = MaterialTheme.typography.displayLarge,
                        color = DashboardAccent,
                    )
                }
                uiState.maneuverText?.let { maneuver ->
                    Text(
                        text = maneuver,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (uiState.nextStepToll) TollBadge(NEXT_STEP_TOLL)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Readout(label = "Distance", value = uiState.remainingDistanceText)
                Readout(label = "Time", value = uiState.remainingTimeText)
                Readout(label = "ETA", value = uiState.etaText)
            }
            Text(
                text = listOfNotNull(uiState.motionLabel, uiState.speedText).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = if (uiState.motionLabel == MOVING) DashboardAccent else DashboardTextMuted,
            )
        }
    }
}

@Composable
internal fun ArrivedPanel(destinationName: String?, onDone: () -> Unit) {
    DashboardPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(DashboardSpacing.small)) {
            Text(text = ARRIVED, style = MaterialTheme.typography.titleMedium, color = DashboardAccent)
            destinationName?.let {
                Text(text = it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            PillButton(text = "Done", accent = true, onClick = onDone)
        }
    }
}

// ---------------------------------------------------------------- Right edge: map controls

@Composable
internal fun MapControls(
    showRecenter: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onRecenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(DashboardSpacing.small)) {
        SquareButton(text = "+", contentDescription = "Zoom in", onClick = onZoomIn)
        SquareButton(text = "−", contentDescription = "Zoom out", onClick = onZoomOut)
        if (showRecenter) {
            SquareButton(text = "◎", contentDescription = "Recenter", accent = true, onClick = onRecenter)
        }
    }
}

// ---------------------------------------------------------------- Shared pieces

@Composable
private fun Readout(label: String, value: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(DashboardSpacing.tight)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = DashboardTextMuted)
        Text(
            text = value ?: UNAVAILABLE,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

internal const val NEXT_STEP_TOLL = "Toll ahead"

@Composable
internal fun TollBadge(label: String) {
    val warning = label == TOLL_ROAD || label == TOLLS_UNAVOIDABLE || label == NEXT_STEP_TOLL
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = when {
            warning -> DashboardWarning.copy(alpha = 0.18f)
            label == TOLL_FREE -> DashboardAccent.copy(alpha = 0.14f)
            else -> DashboardSurfaceHigh
        },
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(vertical = DashboardSpacing.tight, horizontal = DashboardSpacing.small),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = when {
                warning -> DashboardWarning
                label == TOLL_FREE -> DashboardAccent
                else -> DashboardTextMuted
            },
            maxLines = 1,
        )
    }
}

@Composable
internal fun AvoidTollsToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.semantics { contentDescription = AVOID_TOLLS })
        Text(text = AVOID_TOLLS, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

internal const val AVOID_TOLLS = "Avoid tolls"

@Composable
private fun PreviewWarning(label: String) {
    Text(text = label, style = MaterialTheme.typography.labelMedium, color = DashboardWarning)
}

@Composable
internal fun PillButton(
    text: String,
    accent: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = when {
            !enabled -> DashboardSurface
            accent -> DashboardAccent
            else -> DashboardSurfaceHigh
        },
        enabled = enabled,
        onClick = onClick,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(vertical = DashboardSpacing.small, horizontal = DashboardSpacing.medium),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = when {
                !enabled -> DashboardTextMuted
                accent -> OnAccent
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SquareButton(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
    accent: Boolean = false,
) {
    Surface(
        modifier = Modifier
            .size(44.dp)
            .semantics { this.contentDescription = contentDescription },
        shape = RoundedCornerShape(8.dp),
        color = if (accent) DashboardAccent else DashboardSurface.copy(alpha = 0.92f),
        onClick = onClick,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(DashboardSpacing.small),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = if (accent) OnAccent else MaterialTheme.colorScheme.onSurface,
        )
    }
}
