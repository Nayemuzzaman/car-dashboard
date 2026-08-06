package com.example.cardashboard.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.cardashboard.domain.model.DriveMode

/**
 * Cluster colours that Material's scheme has no slot for.
 *
 * Composables read these through [LocalDashboardColors] rather than hardcoding hex values, so the
 * light theme stays readable without every component knowing which theme is active.
 */
@Immutable
data class DashboardColors(
    val ok: Color,
    val caution: Color,
    val critical: Color,
    val beam: Color,
    val gaugeTrack: Color,
    val textMuted: Color,
    val panel: Color,
    val panelBorder: Color,
    val inactiveIndicator: Color
) {
    /** Accent used for the selected drive mode. */
    fun accentFor(mode: DriveMode, default: Color): Color = when (mode) {
        DriveMode.ECO -> ok
        DriveMode.NORMAL -> default
        DriveMode.SPORT -> caution
    }
}

internal val DarkDashboardColors = DashboardColors(
    ok = DashboardOk,
    caution = DashboardWarning,
    critical = DashboardCritical,
    beam = DashboardBeam,
    gaugeTrack = DashboardSurfaceHigh,
    textMuted = DashboardTextMuted,
    panel = DashboardSurface,
    panelBorder = DashboardSurfaceHigh,
    inactiveIndicator = Color(0xFF334155)
)

internal val LightDashboardColors = DashboardColors(
    ok = DashboardOkLight,
    caution = DashboardWarningLight,
    critical = DashboardCriticalLight,
    beam = DashboardBeamLight,
    gaugeTrack = DashboardSurfaceHighLight,
    textMuted = DashboardTextMutedLight,
    panel = Color(0xFFFFFFFF),
    panelBorder = DashboardSurfaceHighLight,
    inactiveIndicator = Color(0xFFCBD5E1)
)

val LocalDashboardColors = staticCompositionLocalOf { DarkDashboardColors }
