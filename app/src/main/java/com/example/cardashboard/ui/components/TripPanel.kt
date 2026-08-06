package com.example.cardashboard.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.example.cardashboard.R
import com.example.cardashboard.domain.model.SpeedUnit
import com.example.cardashboard.domain.model.TripData
import com.example.cardashboard.domain.model.VehicleType
import com.example.cardashboard.domain.model.kmToDisplay
import com.example.cardashboard.ui.format.DashboardFormat
import com.example.cardashboard.ui.theme.DashboardSpacing
import kotlin.math.roundToInt

const val TRIP_DISTANCE_TAG = "trip_distance"
const val TRIP_RESET_BUTTON_TAG = "trip_reset_button"

/**
 * Trip computer.
 *
 * The reset button only asks the ViewModel to open a confirmation; nothing is cleared until the
 * driver confirms, and the odometer is never part of it.
 */
@Composable
fun TripPanel(
    trip: TripData?,
    vehicleType: VehicleType,
    speedUnit: SpeedUnit,
    onResetTrip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val unavailable = stringResource(R.string.value_unavailable)
    val distanceUnit = stringResource(speedUnit.distanceLabelRes)
    val speedUnitLabel = stringResource(speedUnit.speedLabelRes)
    val consumptionUnit = stringResource(
        if (vehicleType == VehicleType.ELECTRIC) {
            R.string.trip_consumption_unit_electric
        } else {
            R.string.trip_consumption_unit_fuel
        }
    )

    val distanceText = if (trip == null) {
        unavailable
    } else {
        "${DashboardFormat.distance(trip.distanceKm, speedUnit)} $distanceUnit"
    }
    val averageSpeedText = if (trip == null) {
        unavailable
    } else {
        "${trip.averageSpeedKmh.kmToDisplay(speedUnit).roundToInt()} $speedUnitLabel"
    }
    val drivingTimeText = if (trip == null) unavailable else DashboardFormat.duration(trip.drivingTimeMillis)
    val consumption = trip?.consumptionPer100Km
    val consumptionText = if (consumption == null) {
        unavailable
    } else {
        "${DashboardFormat.consumption(consumption)} $consumptionUnit"
    }

    DashboardPanel(modifier = modifier) {
        PanelHeader(title = stringResource(R.string.trip_title))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)
        ) {
            Readout(
                label = stringResource(R.string.trip_distance),
                value = distanceText,
                modifier = Modifier
                    .weight(1f)
                    .testTag(TRIP_DISTANCE_TAG)
            )
            Readout(
                label = stringResource(R.string.trip_average_speed),
                value = averageSpeedText,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.medium)
        ) {
            Readout(
                label = stringResource(R.string.trip_driving_time),
                value = drivingTimeText,
                modifier = Modifier.weight(1f)
            )
            Readout(
                label = stringResource(R.string.trip_consumption),
                value = consumptionText,
                modifier = Modifier.weight(1f)
            )
        }
        OutlinedButton(
            onClick = onResetTrip,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DashboardSpacing.minTouchTarget)
                .testTag(TRIP_RESET_BUTTON_TAG)
        ) {
            Text(
                text = stringResource(R.string.trip_reset),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
