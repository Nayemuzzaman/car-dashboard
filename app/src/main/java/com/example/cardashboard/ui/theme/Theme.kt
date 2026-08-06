package com.example.cardashboard.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.cardashboard.domain.model.ThemePreference

private val DarkColorScheme = darkColorScheme(
    primary = DashboardAccent,
    secondary = DashboardAccentDim,
    tertiary = DashboardWarning,
    error = DashboardCritical,
    background = DashboardBackground,
    surface = DashboardSurface,
    surfaceVariant = DashboardSurfaceHigh,
    onPrimary = DashboardBackground,
    onSecondary = DashboardBackground,
    onTertiary = DashboardBackground,
    onError = DashboardText,
    onBackground = DashboardText,
    onSurface = DashboardText,
    onSurfaceVariant = DashboardTextMuted
)

private val LightColorScheme = lightColorScheme(
    primary = DashboardAccentDim,
    secondary = DashboardAccent,
    tertiary = DashboardWarningLight,
    error = DashboardCriticalLight,
    background = DashboardSurfaceLight,
    surface = DashboardSurfaceLight,
    surfaceVariant = DashboardSurfaceHighLight,
    onPrimary = DashboardText,
    onSecondary = DashboardBackground,
    onTertiary = DashboardText,
    onError = DashboardText,
    onBackground = DashboardBackground,
    onSurface = DashboardBackground,
    onSurfaceVariant = DashboardTextMutedLight
)

/**
 * Applies the cluster palette.
 *
 * Dynamic (wallpaper) colour is deliberately not offered: a telltale that means "engine fault" has
 * to stay the same colour whatever the phone's wallpaper is.
 */
@Composable
fun CarDashboardTheme(
    themePreference: ThemePreference = ThemePreference.DARK,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themePreference) {
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val dashboardColors = if (darkTheme) DarkDashboardColors else LightDashboardColors

    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    if (!view.isInEditMode && activity != null) {
        SideEffect {
            // The activity draws edge to edge; only the icon tint has to follow the theme.
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalDashboardColors provides dashboardColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}

/** Previews and tests render without an Activity, so the window tweaks have to be skippable. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
