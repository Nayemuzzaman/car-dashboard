package com.example.cardashboard.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.cardashboard.R
import com.example.cardashboard.domain.model.ChargingState
import com.example.cardashboard.domain.model.DriveMode
import com.example.cardashboard.domain.model.EnergyState
import com.example.cardashboard.domain.model.Gear
import com.example.cardashboard.domain.model.Indicators
import com.example.cardashboard.domain.model.SpeedUnit
import com.example.cardashboard.domain.model.Temperatures
import com.example.cardashboard.domain.model.TripData
import com.example.cardashboard.domain.model.VehicleDataSourceKind
import com.example.cardashboard.domain.model.VehicleDataState
import com.example.cardashboard.domain.model.VehicleDataUnavailableReason
import com.example.cardashboard.domain.model.VehicleState
import com.example.cardashboard.ui.components.DashboardPanel
import com.example.cardashboard.ui.components.DriveModeSelector
import com.example.cardashboard.ui.components.EnergyPanel
import com.example.cardashboard.ui.components.GearIndicator
import com.example.cardashboard.ui.components.IndicatorCluster
import com.example.cardashboard.ui.components.PanelHeader
import com.example.cardashboard.ui.components.Readout
import com.example.cardashboard.ui.components.RpmGauge
import com.example.cardashboard.ui.components.SpeedGauge
import com.example.cardashboard.ui.components.StatusHeader
import com.example.cardashboard.ui.components.TemperaturePanel
import com.example.cardashboard.ui.components.TripPanel
import com.example.cardashboard.ui.components.distanceLabelRes
import com.example.cardashboard.ui.format.DashboardFormat
import com.example.cardashboard.ui.theme.CarDashboardTheme
import com.example.cardashboard.ui.theme.DashboardSpacing
import com.example.cardashboard.ui.theme.LocalDashboardColors
import java.time.LocalDateTime

const val DASHBOARD_ROOT_TAG = "dashboard_root"
const val ODOMETER_TAG = "odometer_value"
const val TRIP_RESET_CONFIRM_TAG = "trip_reset_confirm"
const val LOADING_MESSAGE_TAG = "loading_message"

/**
 * The instrument cluster.
 *
 * Stateless on purpose: it takes a [DashboardUiState] and reports intents back, so it can be
 * previewed and tested without a ViewModel, a repository or a running simulation.
 */
@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    onToggleSpeedUnit: () -> Unit,
    onDriveModeSelected: (DriveMode) -> Unit,
    onRequestTripReset: () -> Unit,
    onConfirmTripReset: () -> Unit,
    onDismissTripReset: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDashboardColors.current
    val driveMode = uiState.vehicleState?.driveMode ?: DriveMode.NORMAL
    val accent = colors.accentFor(driveMode, MaterialTheme.colorScheme.primary)

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(DASHBOARD_ROOT_TAG),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Nothing important may sit under the status bar, a notch or the gesture bar.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(DashboardSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)
        ) {
            StatusHeader(
                now = uiState.now,
                isDemoData = uiState.isDemoData,
                statusMessage = uiState.statusMessage(),
                onOpenSettings = onOpenSettings
            )

            if (uiState.isLoading) {
                LoadingMessage(modifier = Modifier.fillMaxWidth().weight(1f))
            } else {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (maxWidth >= WIDE_LAYOUT_BREAKPOINT) {
                        WideDashboardLayout(
                            uiState = uiState,
                            accent = accent,
                            // A phone in landscape has barely 250dp of usable height: everything
                            // but the speedometer moves into the scrolling side column so no gauge
                            // ends up clipped.
                            shortScreen = maxHeight < SHORT_SCREEN_BREAKPOINT,
                            onToggleSpeedUnit = onToggleSpeedUnit,
                            onDriveModeSelected = onDriveModeSelected,
                            onRequestTripReset = onRequestTripReset
                        )
                    } else {
                        CompactDashboardLayout(
                            uiState = uiState,
                            accent = accent,
                            onToggleSpeedUnit = onToggleSpeedUnit,
                            onDriveModeSelected = onDriveModeSelected,
                            onRequestTripReset = onRequestTripReset
                        )
                    }
                }
            }
        }
    }

    if (uiState.tripResetConfirmationVisible) {
        TripResetDialog(onConfirm = onConfirmTripReset, onDismiss = onDismissTripReset)
    }
}

/** Landscape and tablet: revs on the left, speedometer in the middle, everything else on the right. */
@Composable
private fun WideDashboardLayout(
    uiState: DashboardUiState,
    accent: Color,
    shortScreen: Boolean,
    onToggleSpeedUnit: () -> Unit,
    onDriveModeSelected: (DriveMode) -> Unit,
    onRequestTripReset: () -> Unit
) {
    val vehicleState = uiState.vehicleState
    val settings = uiState.settings

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)
        ) {
            // Left: revs and the gear selector.
            Column(
                modifier = Modifier.weight(0.9f),
                verticalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)
            ) {
                DashboardPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    RpmGauge(
                        rpm = vehicleState?.rpm,
                        vehicleType = uiState.vehicleType,
                        accent = accent,
                        animationsEnabled = settings.animationsEnabled,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                DashboardPanel(modifier = Modifier.fillMaxWidth()) {
                    PanelHeader(title = stringResource(R.string.gear_title))
                    GearIndicator(gear = vehicleState?.gear, accent = accent)
                }
            }

            // Centre: the speedometer, which gets whatever height is left over.
            Column(
                modifier = Modifier.weight(1.3f),
                verticalArrangement = Arrangement.spacedBy(DashboardSpacing.medium),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SpeedGauge(
                    speedKmh = vehicleState?.speedKmh,
                    unit = settings.speedUnit,
                    accent = accent,
                    animationsEnabled = settings.animationsEnabled,
                    onToggleUnit = onToggleSpeedUnit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
                if (!shortScreen) {
                    DistancePanel(
                        vehicleState = vehicleState,
                        speedUnit = settings.speedUnit,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Right: everything that is read rather than glanced at.
            Column(
                modifier = Modifier
                    .weight(1.1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)
            ) {
                if (shortScreen) {
                    DistancePanel(
                        vehicleState = vehicleState,
                        speedUnit = settings.speedUnit,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                EnergyPanel(
                    energy = vehicleState?.energy,
                    vehicleType = uiState.vehicleType,
                    speedUnit = settings.speedUnit,
                    animationsEnabled = settings.animationsEnabled,
                    modifier = Modifier.fillMaxWidth()
                )
                TripPanel(
                    trip = vehicleState?.trip,
                    vehicleType = uiState.vehicleType,
                    speedUnit = settings.speedUnit,
                    onResetTrip = onRequestTripReset,
                    modifier = Modifier.fillMaxWidth()
                )
                TemperaturePanel(
                    temperatures = vehicleState?.temperatures,
                    vehicleType = uiState.vehicleType,
                    unit = settings.temperatureUnit,
                    modifier = Modifier.fillMaxWidth()
                )
                DriveModeSelector(
                    selected = driveModeOf(vehicleState),
                    onModeSelected = onDriveModeSelected,
                    modifier = Modifier.fillMaxWidth()
                )
                if (shortScreen) {
                    IndicatorCluster(
                        indicators = vehicleState?.indicators ?: Indicators.NONE,
                        vehicleType = uiState.vehicleType,
                        animationsEnabled = settings.animationsEnabled,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        if (!shortScreen) {
            IndicatorCluster(
                indicators = vehicleState?.indicators ?: Indicators.NONE,
                vehicleType = uiState.vehicleType,
                animationsEnabled = settings.animationsEnabled,
                singleScrollingRow = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Portrait and small screens: one scrolling column, speed first. */
@Composable
private fun CompactDashboardLayout(
    uiState: DashboardUiState,
    accent: Color,
    onToggleSpeedUnit: () -> Unit,
    onDriveModeSelected: (DriveMode) -> Unit,
    onRequestTripReset: () -> Unit
) {
    val vehicleState = uiState.vehicleState
    val settings = uiState.settings

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(DashboardSpacing.medium),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SpeedGauge(
            speedKmh = vehicleState?.speedKmh,
            unit = settings.speedUnit,
            accent = accent,
            animationsEnabled = settings.animationsEnabled,
            onToggleUnit = onToggleSpeedUnit,
            modifier = Modifier
                .fillMaxWidth(COMPACT_GAUGE_WIDTH_FRACTION)
                .aspectRatio(1f)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)
        ) {
            DashboardPanel(modifier = Modifier.weight(1f)) {
                RpmGauge(
                    rpm = vehicleState?.rpm,
                    vehicleType = uiState.vehicleType,
                    accent = accent,
                    animationsEnabled = settings.animationsEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )
            }
            DashboardPanel(modifier = Modifier.weight(1f)) {
                PanelHeader(title = stringResource(R.string.gear_title))
                GearIndicator(gear = vehicleState?.gear, accent = accent)
            }
        }
        DistancePanel(
            vehicleState = vehicleState,
            speedUnit = settings.speedUnit,
            modifier = Modifier.fillMaxWidth()
        )
        EnergyPanel(
            energy = vehicleState?.energy,
            vehicleType = uiState.vehicleType,
            speedUnit = settings.speedUnit,
            animationsEnabled = settings.animationsEnabled,
            modifier = Modifier.fillMaxWidth()
        )
        TripPanel(
            trip = vehicleState?.trip,
            vehicleType = uiState.vehicleType,
            speedUnit = settings.speedUnit,
            onResetTrip = onRequestTripReset,
            modifier = Modifier.fillMaxWidth()
        )
        TemperaturePanel(
            temperatures = vehicleState?.temperatures,
            vehicleType = uiState.vehicleType,
            unit = settings.temperatureUnit,
            modifier = Modifier.fillMaxWidth()
        )
        DriveModeSelector(
            selected = driveModeOf(vehicleState),
            onModeSelected = onDriveModeSelected,
            modifier = Modifier.fillMaxWidth()
        )
        IndicatorCluster(
            indicators = vehicleState?.indicators ?: Indicators.NONE,
            vehicleType = uiState.vehicleType,
            animationsEnabled = settings.animationsEnabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Odometer and trip distance side by side — the pair a driver reads together. */
@Composable
private fun DistancePanel(
    vehicleState: VehicleState?,
    speedUnit: SpeedUnit,
    modifier: Modifier = Modifier
) {
    val unavailable = stringResource(R.string.value_unavailable)
    val distanceUnit = stringResource(speedUnit.distanceLabelRes)
    val odometerText = if (vehicleState == null) {
        unavailable
    } else {
        "${DashboardFormat.odometer(vehicleState.odometerKm, speedUnit)} $distanceUnit"
    }
    val tripText = if (vehicleState == null) {
        unavailable
    } else {
        "${DashboardFormat.distance(vehicleState.trip.distanceKm, speedUnit)} $distanceUnit"
    }

    DashboardPanel(modifier = modifier) {
        PanelHeader(title = stringResource(R.string.distance_title))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)
        ) {
            Readout(
                label = stringResource(R.string.odometer_label),
                value = odometerText,
                modifier = Modifier
                    .weight(1f)
                    .testTag(ODOMETER_TAG)
            )
            Readout(
                label = stringResource(R.string.trip_title),
                value = tripText,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TripResetDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trip_reset_confirm_title)) },
        text = { Text(stringResource(R.string.trip_reset_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(TRIP_RESET_CONFIRM_TAG)) {
                Text(stringResource(R.string.trip_reset_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun LoadingMessage(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.status_loading),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(LOADING_MESSAGE_TAG)
        )
    }
}

/** Short line explaining why the cluster is not updating, or `null` when all is well. */
@Composable
private fun DashboardUiState.statusMessage(): String? {
    val state = dataState
    return when {
        state !is VehicleDataState.Unavailable -> null
        // Readings are still on screen but frozen; say so rather than let them look live.
        isStale -> stringResource(R.string.status_stale)
        else -> when (state.reason) {
            VehicleDataUnavailableReason.NO_SUPPORTED_INTERFACE ->
                stringResource(R.string.status_no_interface)

            VehicleDataUnavailableReason.NOT_CONNECTED ->
                stringResource(R.string.status_not_connected)

            VehicleDataUnavailableReason.READ_FAILED ->
                stringResource(R.string.status_read_failed)
        }
    }
}

private fun driveModeOf(vehicleState: VehicleState?): DriveMode =
    vehicleState?.driveMode ?: DriveMode.NORMAL

private val WIDE_LAYOUT_BREAKPOINT = 700.dp

/** Below this height the wide layout stops using a bottom telltale strip. */
private val SHORT_SCREEN_BREAKPOINT = 460.dp
private const val COMPACT_GAUGE_WIDTH_FRACTION = 0.8f

@Preview(name = "Landscape", showBackground = true, widthDp = 960, heightDp = 480)
@Composable
private fun DashboardLandscapePreview() {
    CarDashboardTheme {
        DashboardScreen(
            uiState = previewUiState(),
            onToggleSpeedUnit = {},
            onDriveModeSelected = {},
            onRequestTripReset = {},
            onConfirmTripReset = {},
            onDismissTripReset = {},
            onOpenSettings = {}
        )
    }
}

@Preview(name = "Compact", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun DashboardCompactPreview() {
    CarDashboardTheme {
        DashboardScreen(
            uiState = previewUiState(electric = true),
            onToggleSpeedUnit = {},
            onDriveModeSelected = {},
            onRequestTripReset = {},
            onConfirmTripReset = {},
            onDismissTripReset = {},
            onOpenSettings = {}
        )
    }
}

private fun previewUiState(electric: Boolean = false) = DashboardUiState(
    dataState = VehicleDataState.Available(
        vehicleState = VehicleState(
            speedKmh = 86f,
            rpm = if (electric) 6_700 else 2_350,
            gear = Gear.DRIVE,
            driveMode = DriveMode.NORMAL,
            odometerKm = 38_421.0,
            trip = TripData(
                distanceKm = 142.8,
                drivingTimeMillis = 5_400_000,
                energyUsed = if (electric) 24.2 else 10.4
            ),
            energy = if (electric) {
                EnergyState.Battery(
                    levelPercent = 62f,
                    estimatedRangeKm = 236f,
                    chargingState = ChargingState.NOT_CHARGING
                )
            } else {
                EnergyState.Fuel(levelPercent = 68f, estimatedRangeKm = 420f)
            },
            temperatures = Temperatures(outsideCelsius = 18f, powertrainCelsius = 91f),
            indicators = Indicators(lowBeam = true, rightTurnSignal = true)
        ),
        source = VehicleDataSourceKind.DEMO
    ),
    now = LocalDateTime.of(2026, 8, 6, 14, 32)
)
