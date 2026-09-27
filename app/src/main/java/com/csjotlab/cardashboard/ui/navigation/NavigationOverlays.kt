package com.csjotlab.cardashboard.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.geocoding.Place
import com.csjotlab.cardashboard.nav.geocoding.PlaceCategory
import com.csjotlab.cardashboard.nav.map.MapThemeMode

// ---------------------------------------------------------------- Type scale (in-car sizes)

private val BannerDistance = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 44.sp)
private val BannerInstruction = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 27.sp)
private val Title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp)
private val Body = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp)
private val BodyStrong = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
private val Secondary = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp)
private val ButtonLabel = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)
private val Big = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)

/** Minimum touch target for anything the driver may press: larger than the 48 dp phone norm. */
private val TouchTarget = 56.dp
private val PanelShape = RoundedCornerShape(20.dp)

internal const val YOUR_LOCATION = "Your location"
internal const val CHOOSE_DESTINATION = "Choose destination"
internal const val NEXT_STEP_TOLL = "Toll ahead"
internal const val AVOID_TOLLS = "Avoid tolls"
internal const val END = "End"
internal const val START = "Start"
internal const val DONE = "Done"
internal const val RETRY = "Retry"
internal const val RECENTER = "Recenter"

// ---------------------------------------------------------------- Shared pieces

@Composable
internal fun NavPanel(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), content: @Composable () -> Unit) {
    val colors = LocalNavColors.current
    Surface(
        modifier = modifier,
        shape = PanelShape,
        color = colors.panel,
        contentColor = colors.onPanel,
        shadowElevation = 8.dp,
        border = if (colors.isNight) BorderStroke(1.dp, colors.outline) else null,
    ) {
        Box(modifier = Modifier.padding(padding)) { content() }
    }
}

internal enum class NavButtonStyle { Primary, Danger, Secondary }

@Composable
internal fun NavButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: NavButtonStyle = NavButtonStyle.Primary,
    enabled: Boolean = true,
    leading: (@Composable (Color) -> Unit)? = null,
) {
    val colors = LocalNavColors.current
    val (container, content) = when {
        !enabled -> colors.panelVariant to colors.onPanelMuted
        style == NavButtonStyle.Primary -> colors.accent to colors.onAccent
        style == NavButtonStyle.Danger -> colors.danger to colors.onDanger
        else -> colors.panelVariant to colors.onPanel
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = TouchTarget),
        shape = RoundedCornerShape(16.dp),
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.let { it(content); Spacer(Modifier.width(8.dp)) }
            Text(text = text, style = ButtonLabel, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Round floating control on the map edge. */
@Composable
internal fun RoundControl(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable (Color) -> Unit,
) {
    val colors = LocalNavColors.current
    val tint = if (highlighted) colors.onAccent else colors.onPanel
    Surface(
        onClick = onClick,
        modifier = modifier.size(TouchTarget).semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        color = if (highlighted) colors.accent else colors.panel,
        shadowElevation = 6.dp,
        border = if (colors.isNight && !highlighted) BorderStroke(1.dp, colors.outline) else null,
    ) {
        Box(contentAlignment = Alignment.Center) { content(tint) }
    }
}

@Composable
internal fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = color) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = BodyStrong,
            color = if (color.luminance() > 0.5f) Color(0xFF111111) else Color.White,
            maxLines = 1,
        )
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

// ---------------------------------------------------------------- Planning: top of the map

enum class PlannerField { Start, Destination }

/**
 * Route planner: two stacked endpoint rows and a swap button. The active row is the live text
 * field; the other shows what was chosen. Start defaults to "Your location".
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
    val colors = LocalNavColors.current
    NavPanel(modifier = modifier.fillMaxWidth(), padding = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundControl(contentDescription = "Back", onClick = onBack) { tint ->
                    Icon(Icons.Filled.ArrowBack, contentDescription = null, tint = tint)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlannerRow(
                        dotColor = colors.accent,
                        label = originName ?: YOUR_LOCATION,
                        placeholder = "Search start",
                        active = activeField == PlannerField.Start,
                        query = query,
                        onQueryChange = onQueryChange,
                        onClick = { onFieldSelected(PlannerField.Start) },
                    )
                    PlannerRow(
                        dotColor = colors.danger,
                        label = destinationName ?: CHOOSE_DESTINATION,
                        placeholder = "Where to?",
                        active = activeField == PlannerField.Destination,
                        query = query,
                        onQueryChange = onQueryChange,
                        onClick = { onFieldSelected(PlannerField.Destination) },
                    )
                }
                RoundControl(contentDescription = "Swap start and destination", onClick = onSwap) { tint ->
                    Text("⇅", style = Title, color = tint)
                }
            }
            if (activeField == PlannerField.Start && originName != null) {
                NavButton(text = "Use your location", onClick = onUseCurrentLocation, style = NavButtonStyle.Secondary)
            }
        }
    }
}

@Composable
private fun PlannerRow(
    dotColor: Color,
    label: String,
    placeholder: String,
    active: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onClick: () -> Unit,
) {
    val colors = LocalNavColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(modifier = Modifier.size(12.dp)) { drawCircle(dotColor) }
        if (active) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).semantics { contentDescription = placeholder },
                singleLine = true,
                placeholder = {
                    Text(if (label == YOUR_LOCATION || label == CHOOSE_DESTINATION) placeholder else label, style = Body, color = colors.onPanelMuted)
                },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = colors.onPanelMuted) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = colors.onPanelMuted)
                        }
                    }
                } else null,
                textStyle = Body.copy(color = colors.onPanel),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.onPanel,
                    unfocusedTextColor = colors.onPanel,
                    focusedContainerColor = colors.panelVariant,
                    unfocusedContainerColor = colors.panelVariant,
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.outline,
                    cursorColor = colors.accent,
                ),
            )
        } else {
            Surface(
                onClick = onClick,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp),
                color = colors.panelVariant,
            ) {
                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(text = label, style = Body, color = colors.onPanel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun CategoryChips(active: PlaceCategory?, onSelect: (PlaceCategory) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalNavColors.current
    LazyRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
        items(PlaceCategory.entries) { category ->
            val selected = category == active
            Surface(
                onClick = { onSelect(category) },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(24.dp),
                color = if (selected) colors.accent else colors.panel,
                shadowElevation = 4.dp,
                border = if (selected) null else BorderStroke(1.dp, colors.outline),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = category.glyph(), style = BodyStrong)
                    Text(text = category.label, style = BodyStrong, color = if (selected) colors.onAccent else colors.onPanel)
                }
            }
        }
    }
}

internal fun PlaceCategory.glyph(): String = when (this) {
    PlaceCategory.Fuel -> "⛽"
    PlaceCategory.Parking -> "🅿"
    PlaceCategory.Food -> "🍴"
    PlaceCategory.Hospital -> "🏥"
    PlaceCategory.Charging -> "🔌"
    PlaceCategory.Hotel -> "🛏"
}

@Composable
internal fun SearchResultsList(
    search: SearchUiState,
    maxHeight: Dp,
    onSelect: (Place) -> Unit,
) {
    val colors = LocalNavColors.current
    NavPanel(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 6.dp)) {
        when {
            search.loading -> Text(
                text = "Searching…",
                modifier = Modifier.padding(16.dp).semantics { contentDescription = "Searching" },
                style = Body,
                color = colors.onPanelMuted,
            )
            search.error != null -> Text(
                text = search.error,
                modifier = Modifier.padding(16.dp),
                style = BodyStrong,
                color = if (search.error == WAITING_FOR_LOCATION_NEARBY) colors.onPanelMuted else colors.warning,
            )
            search.results.isEmpty() -> Text(
                text = "No places found",
                modifier = Modifier.padding(16.dp),
                style = Body,
                color = colors.onPanelMuted,
            )
            else -> LazyColumn(modifier = Modifier.heightIn(max = maxHeight)) {
                items(search.results) { result ->
                    PlaceRow(
                        glyph = placeGlyph(result.place.category),
                        title = result.place.name,
                        subtitle = listOfNotNull(result.place.category?.replace('_', ' '), result.place.address).joinToString(" · "),
                        trailing = result.distanceText,
                        onClick = { onSelect(result.place) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun RecentDestinationsList(recent: List<Place>, maxHeight: Dp, onSelect: (Place) -> Unit) {
    val colors = LocalNavColors.current
    NavPanel(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 6.dp)) {
        Column {
            Text(text = "Recent", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = Secondary, color = colors.onPanelMuted)
            LazyColumn(modifier = Modifier.heightIn(max = maxHeight)) {
                items(recent) { place ->
                    PlaceRow(glyph = RECENT_GLYPH, title = place.name, subtitle = place.address.orEmpty(), trailing = null, onClick = { onSelect(place) })
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(glyph: String, title: String, subtitle: String, trailing: String?, onClick: () -> Unit) {
    val colors = LocalNavColors.current
    Surface(onClick = onClick, color = colors.panel, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = glyph, style = Title)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = BodyStrong, color = colors.onPanel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) {
                    Text(text = subtitle, style = Secondary, color = colors.onPanelMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            trailing?.let { Text(text = it, style = BodyStrong, color = colors.accent) }
        }
    }
}

@Composable
internal fun LocationPermissionBanner(onAllow: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalNavColors.current
    NavPanel(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Location is off", style = BodyStrong, color = colors.onPanel)
                Text("Navigation needs your position to route and guide you.", style = Secondary, color = colors.onPanelMuted)
            }
            NavButton(text = "Allow", onClick = onAllow)
        }
    }
}

// ---------------------------------------------------------------- Overview: bottom panel

@Composable
internal fun OverviewPanel(
    uiState: NavigationUiState,
    destinationName: String?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onAvoidTollsChanged: (Boolean) -> Unit,
    destinationAddress: String? = null,
    onSelectRoute: (Int) -> Unit = {},
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalNavColors.current
    NavPanel(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = destinationName ?: "Destination", style = Title, color = colors.onPanel, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    destinationAddress?.let { Text(text = it, style = Secondary, color = colors.onPanelMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                RoundControl(contentDescription = "Cancel", onClick = onCancel) { tint -> Icon(Icons.Filled.Close, contentDescription = null, tint = tint) }
            }

            if (uiState.hasRoute) {
                if (uiState.routeOptions.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(uiState.routeOptions) { index, option ->
                            RouteOptionCard(option, selected = index == uiState.selectedRouteIndex, onClick = { onSelectRoute(index) })
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(text = uiState.remainingTimeText ?: UNAVAILABLE, style = Big, color = colors.success)
                        Text(text = uiState.remainingDistanceText ?: UNAVAILABLE, style = BodyStrong, color = colors.onPanelMuted)
                        uiState.etaText?.let { Text(text = "Arrive $it", style = BodyStrong, color = colors.onPanelMuted) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    uiState.tollLabel?.let { TollBadge(it) }
                    uiState.viaText?.let { Text(text = it, style = Secondary, color = colors.onPanelMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (uiState.isRerouting) Text(text = "Updating route…", style = Secondary, color = colors.onPanelMuted)
                AvoidTollsToggle(checked = uiState.avoidTolls, onCheckedChange = onAvoidTollsChanged)
            } else {
                Text(
                    text = uiState.statusLabel,
                    style = BodyStrong,
                    color = if (uiState.statusLabel == UNABLE_TO_FIND_ROUTE) colors.warning else colors.onPanelMuted,
                )
            }
            uiState.previewLabel?.let { Text(text = it, style = BodyStrong, color = colors.warning) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (uiState.canRetry) {
                    NavButton(text = RETRY, onClick = onRetry, modifier = Modifier.weight(1f), leading = { Icon(Icons.Filled.Refresh, null, tint = it) })
                } else {
                    NavButton(text = START, onClick = onStart, enabled = uiState.hasRoute, modifier = Modifier.weight(1f), leading = { tint -> ManeuverIcon(ManeuverType.Continue, tint, Modifier.size(22.dp)) })
                }
            }
        }
    }
}

@Composable
private fun RouteOptionCard(option: RouteOptionUi, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalNavColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) colors.accent.copy(alpha = if (colors.isNight) 0.22f else 0.10f) else colors.panelVariant,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.outline),
        modifier = Modifier.width(168.dp).semantics { contentDescription = "Route ${option.durationText}${if (selected) ", selected" else ""}" },
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = option.durationText, style = Title, color = if (selected) colors.onPanel else colors.onPanel)
            Text(text = option.distanceText, style = Secondary, color = colors.onPanelMuted)
            option.viaText?.let { Text(text = it, style = Secondary, color = colors.onPanelMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(text = option.tollLabel, style = Secondary, color = if (option.tollLabel == TOLL_ROAD) colors.warning else colors.onPanelMuted, maxLines = 1)
        }
    }
}

@Composable
internal fun TollBadge(label: String) {
    val colors = LocalNavColors.current
    val warning = label == TOLL_ROAD || label == TOLLS_UNAVOIDABLE || label == NEXT_STEP_TOLL
    val tint = when {
        warning -> colors.warning
        label == TOLL_FREE -> colors.success
        else -> colors.onPanelMuted
    }
    Surface(shape = RoundedCornerShape(10.dp), color = tint.copy(alpha = 0.14f), border = BorderStroke(1.dp, tint.copy(alpha = 0.5f))) {
        Text(text = label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = BodyStrong.copy(fontSize = 14.sp), color = tint, maxLines = 1)
    }
}

@Composable
internal fun AvoidTollsToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = LocalNavColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = AVOID_TOLLS },
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.accent,
                checkedThumbColor = colors.onAccent,
                uncheckedTrackColor = colors.panelVariant,
                uncheckedThumbColor = colors.onPanelMuted,
                uncheckedBorderColor = colors.outline,
            ),
        )
        Text(text = AVOID_TOLLS, style = Body, color = colors.onPanel)
    }
}

// ---------------------------------------------------------------- Guidance: top banner, bottom trip bar

/**
 * The next maneuver: arrow, distance, and where it leads — readable at a glance. A maneuver that
 * follows closely is previewed underneath ("Then ↰").
 */
@Composable
internal fun ManeuverBanner(
    uiState: NavigationUiState,
    maneuverType: ManeuverType?,
    thenType: ManeuverType?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNavColors.current
    Surface(modifier = modifier.fillMaxWidth(), shape = PanelShape, color = colors.banner, shadowElevation = 8.dp) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                maneuverType?.let { ManeuverIcon(it, colors.onBanner, Modifier.size(64.dp)) }
                Column(modifier = Modifier.weight(1f)) {
                    uiState.maneuverDistanceText?.let { Text(text = it, style = BannerDistance, color = colors.onBanner) }
                    val instruction = uiState.instructionText ?: uiState.maneuverText
                    instruction?.let {
                        Text(text = it, style = BannerInstruction, color = colors.onBanner, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (instruction == null && uiState.maneuverDistanceText == null) {
                        Text(text = uiState.statusLabel, style = BannerInstruction, color = colors.onBanner)
                    }
                }
            }
            if (thenType != null) {
                Surface(color = colors.bannerVariant, shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = "Then", style = BodyStrong, color = colors.onBannerMuted)
                        ManeuverIcon(thenType, colors.onBanner, Modifier.size(26.dp))
                    }
                }
            }
        }
    }
}

/** Reroute, GPS, preview and toll notices under the banner. Nothing is shown when all is well. */
@Composable
internal fun GuidanceStatusRow(uiState: NavigationUiState, modifier: Modifier = Modifier) {
    val colors = LocalNavColors.current
    val chips = buildList {
        uiState.rerouteText?.let { add(it to if (it == REROUTING) colors.accent else colors.warning) }
        uiState.gpsText?.let { add(it to colors.warning) }
        uiState.previewLabel?.let { add(it to colors.warning) }
        if (uiState.nextStepToll) add(NEXT_STEP_TOLL to colors.warning)
    }
    if (chips.isEmpty()) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { (text, color) -> StatusChip(text, color) }
    }
}

/** Arrival time first (what drivers ask), then time and distance left, and the controls. */
@Composable
internal fun TripBar(
    uiState: NavigationUiState,
    onEnd: () -> Unit,
    onOverview: () -> Unit,
    showOverview: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNavColors.current
    NavPanel(modifier = modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = uiState.etaText ?: UNAVAILABLE, style = Big, color = colors.success, modifier = Modifier.semantics { contentDescription = "Arrival ${uiState.etaText ?: "unknown"}" })
                Text(
                    text = listOfNotNull(uiState.remainingTimeText, uiState.remainingDistanceText).joinToString(" · ").ifBlank { UNAVAILABLE },
                    style = BodyStrong,
                    color = colors.onPanelMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showOverview) {
                RoundControl(contentDescription = "Route overview", onClick = onOverview) { tint -> RouteOverviewGlyph(tint) }
            }
            NavButton(text = END, onClick = onEnd, style = NavButtonStyle.Danger, leading = { Icon(Icons.Filled.Close, null, tint = it) })
        }
    }
}

@Composable
internal fun SpeedBubble(speedText: String, modifier: Modifier = Modifier) {
    val colors = LocalNavColors.current
    val number = speedText.substringBefore(' ')
    val unit = speedText.substringAfter(' ', "")
    Surface(modifier = modifier.size(72.dp).semantics { contentDescription = "Speed $speedText" }, shape = CircleShape, color = colors.panel, shadowElevation = 6.dp,
        border = BorderStroke(2.dp, colors.outline)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(text = number, style = Big, color = colors.onPanel)
            if (unit.isNotEmpty()) Text(text = unit, style = Secondary.copy(fontSize = 12.sp), color = colors.onPanelMuted)
        }
    }
}

@Composable
internal fun RoadNamePill(name: String, modifier: Modifier = Modifier) {
    val colors = LocalNavColors.current
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = colors.panel, shadowElevation = 4.dp) {
        Text(text = name, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = BodyStrong, color = colors.onPanel, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- Arrived

@Composable
internal fun ArrivedPanel(destinationName: String?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalNavColors.current
    NavPanel(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ManeuverIcon(ManeuverType.Arrive, colors.danger, Modifier.size(48.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = ARRIVED, style = Title, color = colors.onPanel)
                destinationName?.let { Text(text = it, style = Body, color = colors.onPanelMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            NavButton(text = DONE, onClick = onDone)
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
    compass: (@Composable () -> Unit)? = null,
    themeMode: MapThemeMode? = null,
    onThemeModeChanged: (MapThemeMode) -> Unit = {},
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.End) {
        compass?.invoke()
        themeMode?.let { mode ->
            RoundControl(contentDescription = "Map theme: ${mode.name}", onClick = { onThemeModeChanged(mode.next()) }) { tint ->
                Text(text = mode.glyph(), style = BodyStrong, color = tint)
            }
        }
        RoundControl(contentDescription = "Zoom in", onClick = onZoomIn) { tint -> Text("+", style = Big, color = tint) }
        RoundControl(contentDescription = "Zoom out", onClick = onZoomOut) { tint -> Text("−", style = Big, color = tint) }
        if (showRecenter) {
            RoundControl(contentDescription = RECENTER, onClick = onRecenter, highlighted = true) { tint -> MyLocationGlyph(tint) }
        }
    }
}

private fun MapThemeMode.next(): MapThemeMode = when (this) {
    MapThemeMode.Auto -> MapThemeMode.Day
    MapThemeMode.Day -> MapThemeMode.Night
    MapThemeMode.Night -> MapThemeMode.Auto
}

private fun MapThemeMode.glyph(): String = when (this) {
    MapThemeMode.Auto -> "A"
    MapThemeMode.Day -> "☀"
    MapThemeMode.Night -> "☾"
}

/**
 * Compass needle; red points north. It rotates in the draw phase ([bearing] is read inside
 * graphicsLayer), so a turning map does not recompose the screen. In guidance it also shows and
 * toggles heading-up (arrow up) versus north-up.
 */
@Composable
internal fun CompassControl(bearing: () -> Float, headingUp: Boolean?, onClick: () -> Unit) {
    val description = when (headingUp) {
        true -> "Compass, heading up. Switch to north up"
        false -> "Compass, north up. Switch to heading up"
        null -> "Compass. Reset to north"
    }
    RoundControl(contentDescription = description, onClick = onClick) { tint ->
        Canvas(modifier = Modifier.size(30.dp).graphicsLayer { rotationZ = -bearing() }) {
            val w = size.width
            val north = Path().apply { moveTo(w / 2, 0f); lineTo(w * 0.72f, w / 2); lineTo(w * 0.28f, w / 2); close() }
            val south = Path().apply { moveTo(w / 2, w); lineTo(w * 0.72f, w / 2); lineTo(w * 0.28f, w / 2); close() }
            drawPath(north, Color(0xFFE53935))
            drawPath(south, tint.copy(alpha = 0.55f))
        }
    }
}

@Composable
private fun MyLocationGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(26.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension * 0.30f
        drawCircle(tint, radius = r, center = c, style = Stroke(width = size.minDimension * 0.09f))
        drawCircle(tint, radius = r * 0.45f, center = c)
        val s = size.minDimension
        listOf(Offset(0f, -1f), Offset(0f, 1f), Offset(-1f, 0f), Offset(1f, 0f)).forEach { d ->
            drawLine(tint, Offset(c.x + d.x * r, c.y + d.y * r), Offset(c.x + d.x * s * 0.48f, c.y + d.y * s * 0.48f), strokeWidth = s * 0.09f)
        }
    }
}

@Composable
private fun RouteOverviewGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(26.dp)) {
        val s = size.minDimension
        val path = Path().apply {
            moveTo(s * 0.2f, s * 0.85f)
            cubicTo(s * 0.2f, s * 0.45f, s * 0.8f, s * 0.6f, s * 0.8f, s * 0.2f)
        }
        drawPath(path, tint, style = Stroke(width = s * 0.1f))
        drawCircle(tint, radius = s * 0.11f, center = Offset(s * 0.2f, s * 0.85f))
        drawCircle(tint, radius = s * 0.11f, center = Offset(s * 0.8f, s * 0.2f))
    }
}

// ---------------------------------------------------------------- Dialog

@Composable
internal fun EndNavigationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalNavColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.panel,
        titleContentColor = colors.onPanel,
        textContentColor = colors.onPanelMuted,
        title = { Text("End navigation?", style = Title) },
        text = { Text("Guidance to your destination will stop.", style = Body) },
        confirmButton = { NavButton(text = END, onClick = onConfirm, style = NavButtonStyle.Danger) },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = TouchTarget)) {
                Text("Keep navigating", style = ButtonLabel, color = colors.accent)
            }
        },
    )
}
