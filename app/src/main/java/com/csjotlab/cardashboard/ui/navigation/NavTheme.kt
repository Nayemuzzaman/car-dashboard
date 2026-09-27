package com.csjotlab.cardashboard.ui.navigation

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.engine.SolarDayNight
import com.csjotlab.cardashboard.nav.map.MapStyle
import com.csjotlab.cardashboard.nav.map.MapThemeMode

/**
 * The navigation screen's own colours, paired day/night.
 *
 * The screen must not mix the app theme with the dashboard's hard-coded dark panels: in day mode that
 * produced near-black text on a dark panel (the unreadable "End" button). Every overlay colours its
 * panel *and* its text from one [NavColors], so they always come from the same pair. Primary text is at
 * least 7:1 against its panel; secondary text and button labels at least 4.5:1 (WCAG AA).
 */
@Immutable
data class NavColors(
    val isNight: Boolean,
    val panel: Color,
    val panelVariant: Color,
    val onPanel: Color,
    val onPanelMuted: Color,
    val outline: Color,
    val accent: Color,
    val onAccent: Color,
    val danger: Color,
    val onDanger: Color,
    val warning: Color,
    val success: Color,
    /** The maneuver banner: the most important text on screen, so the strongest contrast. */
    val banner: Color,
    val bannerVariant: Color,
    val onBanner: Color,
    val onBannerMuted: Color,
    val scrim: Color,
)

val DayNavColors = NavColors(
    isNight = false,
    panel = Color(0xFFFFFFFF),
    panelVariant = Color(0xFFF1F5F9),
    onPanel = Color(0xFF0F172A), // 17.9:1 on panel
    onPanelMuted = Color(0xFF475569), // 7.6:1
    outline = Color(0xFFCBD5E1),
    accent = Color(0xFF1D4ED8), // white label 6.7:1
    onAccent = Color(0xFFFFFFFF),
    danger = Color(0xFFC62828), // white label 5.6:1
    onDanger = Color(0xFFFFFFFF),
    warning = Color(0xFFB45309), // 5.0:1 on panel
    success = Color(0xFF15803D), // 5.0:1 on panel
    banner = Color(0xFF0D652D), // white 7.2:1
    bannerVariant = Color(0xFF0A4F23),
    onBanner = Color(0xFFFFFFFF),
    onBannerMuted = Color(0xFFD1FAE5), // 6.3:1 on banner
    scrim = Color(0x66000000),
)

val NightNavColors = NavColors(
    isNight = true,
    panel = Color(0xFF161B22),
    panelVariant = Color(0xFF222A35),
    onPanel = Color(0xFFF1F5F9), // 15.8:1 on panel
    onPanelMuted = Color(0xFFA7B0BE), // 8.0:1
    outline = Color(0xFF334155),
    accent = Color(0xFF8AB4F8), // dark label 8.2:1
    onAccent = Color(0xFF0B1B33),
    danger = Color(0xFFD93025), // white label 4.8:1
    onDanger = Color(0xFFFFFFFF),
    warning = Color(0xFFFBBF24), // 10.4:1 on panel
    success = Color(0xFF4ADE80),
    banner = Color(0xFF0F3D2A), // near-white 11.7:1, dim enough for night driving
    bannerVariant = Color(0xFF0A2C1E),
    onBanner = Color(0xFFF0FDF4),
    onBannerMuted = Color(0xFFA7F3D0),
    scrim = Color(0x99000000),
)

val LocalNavColors = staticCompositionLocalOf { DayNavColors }

/**
 * Which map style to show. [MapThemeMode.Auto] follows the sun at the car's position (so a tunnel at
 * noon stays day and dusk switches on its own), falling back to the system dark setting before the
 * first fix.
 */
fun resolveMapStyle(mode: MapThemeMode, systemDark: Boolean, position: GeoPoint?, nowMs: Long): MapStyle = when (mode) {
    MapThemeMode.Day -> MapStyle.Day
    MapThemeMode.Night -> MapStyle.Night
    MapThemeMode.Auto -> when {
        position != null -> if (SolarDayNight.isDaylight(position.latitude, position.longitude, nowMs)) MapStyle.Day else MapStyle.Night
        systemDark -> MapStyle.Night
        else -> MapStyle.Day
    }
}
