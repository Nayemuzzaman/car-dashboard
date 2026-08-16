package com.example.cardashboard.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.cardashboard.BuildConfig
import com.example.cardashboard.R
import com.example.cardashboard.domain.model.DashboardSettings
import com.example.cardashboard.domain.model.SpeedUnit
import com.example.cardashboard.domain.model.TemperatureUnit
import com.example.cardashboard.domain.model.ThemePreference
import com.example.cardashboard.domain.model.VehicleType
import com.example.cardashboard.ui.components.DashboardPanel
import com.example.cardashboard.ui.components.PanelHeader
import com.example.cardashboard.ui.theme.CarDashboardTheme
import com.example.cardashboard.ui.theme.DashboardSpacing
import com.example.cardashboard.ui.theme.LocalDashboardColors
import kotlinx.coroutines.launch

const val SETTINGS_ROOT_TAG = "settings_root"
const val DEMO_MODE_SWITCH_TAG = "demo_mode_switch"
const val ANIMATIONS_SWITCH_TAG = "animations_switch"
const val RESET_DEMO_BUTTON_TAG = "reset_demo_button"
const val SETTINGS_OPTION_TAG_PREFIX = "settings_option_"

/**
 * Settings, kept to the handful of choices that actually change the cluster.
 *
 * Every control writes straight through to persistent storage; there is no save button and nothing
 * to lose by backing out.
 */
@Composable
fun SettingsScreen(
    settings: DashboardSettings,
    onSpeedUnitSelected: (SpeedUnit) -> Unit,
    onTemperatureUnitSelected: (TemperatureUnit) -> Unit,
    onDemoModeChanged: (Boolean) -> Unit,
    onDemoVehicleTypeSelected: (VehicleType) -> Unit,
    onThemeSelected: (ThemePreference) -> Unit,
    onAnimationsChanged: (Boolean) -> Unit,
    onResetDemoData: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDashboardColors.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resetDoneMessage = stringResource(R.string.settings_reset_demo_done)

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(SETTINGS_ROOT_TAG),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
        Column(
            modifier = Modifier
                // A settings row stretched across a 900dp landscape screen is hard to scan, so the
                // content keeps a comfortable reading width and centres itself.
                .widthIn(max = CONTENT_MAX_WIDTH)
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(DashboardSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(DashboardSpacing.minTouchTarget)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.settings_back),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() }
                )
            }

            DashboardPanel(modifier = Modifier.fillMaxWidth()) {
                PanelHeader(title = stringResource(R.string.settings_section_units))
                OptionRow(
                    label = stringResource(R.string.settings_speed_unit),
                    options = SpeedUnit.entries,
                    selected = settings.speedUnit,
                    labelFor = { stringResource(it.settingsLabelRes) },
                    tagFor = { "speed_unit_${it.name.lowercase()}" },
                    onSelected = onSpeedUnitSelected
                )
                OptionRow(
                    label = stringResource(R.string.settings_temperature_unit),
                    options = TemperatureUnit.entries,
                    selected = settings.temperatureUnit,
                    labelFor = { stringResource(it.settingsLabelRes) },
                    tagFor = { "temperature_unit_${it.name.lowercase()}" },
                    onSelected = onTemperatureUnitSelected
                )
            }

            DashboardPanel(modifier = Modifier.fillMaxWidth()) {
                PanelHeader(title = stringResource(R.string.settings_section_data))
                SwitchRow(
                    label = stringResource(R.string.settings_demo_mode),
                    summary = stringResource(R.string.settings_demo_mode_summary),
                    checked = settings.demoModeEnabled,
                    onCheckedChange = onDemoModeChanged,
                    tag = DEMO_MODE_SWITCH_TAG
                )
                OptionRow(
                    label = stringResource(R.string.settings_demo_vehicle_type),
                    options = VehicleType.entries,
                    selected = settings.demoVehicleType,
                    labelFor = { stringResource(it.settingsLabelRes) },
                    tagFor = { "vehicle_type_${it.name.lowercase()}" },
                    enabled = settings.demoModeEnabled,
                    onSelected = onDemoVehicleTypeSelected
                )
                Text(
                    text = stringResource(R.string.settings_reset_demo_summary),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textMuted
                )
                OutlinedButton(
                    onClick = {
                        onResetDemoData()
                        // Resetting is invisible from this screen, so confirm that it happened.
                        scope.launch { snackbarHostState.showSnackbar(resetDoneMessage) }
                    },
                    enabled = settings.demoModeEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = DashboardSpacing.minTouchTarget)
                        .testTag(RESET_DEMO_BUTTON_TAG)
                ) {
                    Text(stringResource(R.string.settings_reset_demo))
                }
            }

            DashboardPanel(modifier = Modifier.fillMaxWidth()) {
                PanelHeader(title = stringResource(R.string.settings_section_appearance))
                OptionRow(
                    label = stringResource(R.string.settings_theme),
                    options = ThemePreference.entries,
                    selected = settings.themePreference,
                    labelFor = { stringResource(it.settingsLabelRes) },
                    tagFor = { "theme_${it.name.lowercase()}" },
                    onSelected = onThemeSelected
                )
                SwitchRow(
                    label = stringResource(R.string.settings_animations),
                    summary = stringResource(R.string.settings_animations_summary),
                    checked = settings.animationsEnabled,
                    onCheckedChange = onAnimationsChanged,
                    tag = ANIMATIONS_SWITCH_TAG
                )
            }

            DashboardPanel(modifier = Modifier.fillMaxWidth()) {
                PanelHeader(title = stringResource(R.string.settings_section_about))
                Text(
                    text = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.settings_about_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted
                )
            }
        }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

private val CONTENT_MAX_WIDTH = 760.dp

/** A labelled row of mutually exclusive choices. */
@Composable
private fun <T> OptionRow(
    label: String,
    options: List<T>,
    selected: T,
    labelFor: @Composable (T) -> String,
    tagFor: (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = LocalDashboardColors.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DashboardSpacing.small)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else colors.textMuted
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small)
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = DashboardSpacing.minTouchTarget)
                        .heightIn(min = DashboardSpacing.minTouchTarget)
                        .clip(RoundedCornerShape(DashboardSpacing.small))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary else colors.gaugeTrack
                        )
                        .selectable(
                            selected = isSelected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onSelected(option) }
                        )
                        .padding(DashboardSpacing.small)
                        .testTag(SETTINGS_OPTION_TAG_PREFIX + tagFor(option)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = labelFor(option),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            isSelected -> MaterialTheme.colorScheme.onPrimary
                            enabled -> MaterialTheme.colorScheme.onSurface
                            else -> colors.textMuted
                        },
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
    modifier: Modifier = Modifier
) {
    val colors = LocalDashboardColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DashboardSpacing.minTouchTarget),
        horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.medium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textMuted
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
    }
}

private val SpeedUnit.settingsLabelRes: Int
    get() = when (this) {
        SpeedUnit.KILOMETERS_PER_HOUR -> R.string.unit_kmh
        SpeedUnit.MILES_PER_HOUR -> R.string.unit_mph
    }

private val TemperatureUnit.settingsLabelRes: Int
    get() = when (this) {
        TemperatureUnit.CELSIUS -> R.string.unit_celsius
        TemperatureUnit.FAHRENHEIT -> R.string.unit_fahrenheit
    }

private val VehicleType.settingsLabelRes: Int
    get() = when (this) {
        VehicleType.COMBUSTION -> R.string.settings_vehicle_combustion
        VehicleType.ELECTRIC -> R.string.settings_vehicle_electric
    }

private val ThemePreference.settingsLabelRes: Int
    get() = when (this) {
        ThemePreference.DARK -> R.string.settings_theme_dark
        ThemePreference.LIGHT -> R.string.settings_theme_light
        ThemePreference.SYSTEM -> R.string.settings_theme_system
    }

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 900, heightDp = 600)
@Composable
private fun SettingsPreview() {
    CarDashboardTheme {
        SettingsScreen(
            settings = DashboardSettings.DEFAULT,
            onSpeedUnitSelected = {},
            onTemperatureUnitSelected = {},
            onDemoModeChanged = {},
            onDemoVehicleTypeSelected = {},
            onThemeSelected = {},
            onAnimationsChanged = {},
            onResetDemoData = {},
            onBack = {}
        )
    }
}
