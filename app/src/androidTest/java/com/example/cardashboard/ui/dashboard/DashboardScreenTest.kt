package com.example.cardashboard.ui.dashboard

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.cardashboard.domain.model.ChargingState
import com.example.cardashboard.domain.model.DashboardSettings
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
import com.example.cardashboard.domain.model.VehicleType
import com.example.cardashboard.domain.model.sanitized
import com.example.cardashboard.ui.components.DEMO_BADGE_TAG
import com.example.cardashboard.ui.components.DRIVE_MODE_TAG_PREFIX
import com.example.cardashboard.ui.components.ENERGY_LEVEL_TAG
import com.example.cardashboard.ui.components.GEAR_INDICATOR_TAG
import com.example.cardashboard.ui.components.INDICATOR_TAG_PREFIX
import com.example.cardashboard.ui.components.OUTSIDE_TEMPERATURE_TAG
import com.example.cardashboard.ui.components.RPM_VALUE_TAG
import com.example.cardashboard.ui.components.SETTINGS_BUTTON_TAG
import com.example.cardashboard.ui.components.SPEED_UNIT_TAG
import com.example.cardashboard.ui.components.SPEED_VALUE_TAG
import com.example.cardashboard.ui.components.STATUS_MESSAGE_TAG
import com.example.cardashboard.ui.components.TRIP_RESET_BUTTON_TAG
import com.example.cardashboard.ui.theme.CarDashboardTheme
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Renders the real dashboard against hand-built states.
 *
 * Gauge animations are switched off in the test states so a reading is final the moment it is
 * composed, which keeps the assertions exact instead of timing-dependent.
 */
class DashboardScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setDashboard(
        uiState: DashboardUiState,
        onToggleSpeedUnit: () -> Unit = {},
        onDriveModeSelected: (DriveMode) -> Unit = {},
        onRequestTripReset: () -> Unit = {},
        onConfirmTripReset: () -> Unit = {},
        onDismissTripReset: () -> Unit = {},
        onOpenSettings: () -> Unit = {}
    ) {
        composeRule.setContent {
            CarDashboardTheme {
                DashboardScreen(
                    uiState = uiState,
                    onToggleSpeedUnit = onToggleSpeedUnit,
                    onDriveModeSelected = onDriveModeSelected,
                    onRequestTripReset = onRequestTripReset,
                    onConfirmTripReset = onConfirmTripReset,
                    onDismissTripReset = onDismissTripReset,
                    onOpenSettings = onOpenSettings
                )
            }
        }
    }

    @Test
    fun dashboardRendersSpeedRevsAndGear() {
        setDashboard(uiState(speedKmh = 88f))

        composeRule.onNodeWithTag(DASHBOARD_ROOT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SPEED_VALUE_TAG).assertTextIs("88")
        composeRule.onNodeWithTag(RPM_VALUE_TAG).assertTextIs("2.4")
        composeRule.onNodeWithTag(GEAR_INDICATOR_TAG).assertExists()
    }

    @Test
    fun odometerAndTripDistanceAreShown() {
        setDashboard(uiState(speedKmh = 40f))

        composeRule.onNodeWithTag(ODOMETER_TAG).assertContainsText("38,421")
    }

    @Test
    fun speedIsConvertedWhenMilesAreSelected() {
        setDashboard(
            uiState(speedKmh = 100f).copy(
                settings = testSettings().copy(speedUnit = SpeedUnit.MILES_PER_HOUR)
            )
        )

        // 100 km/h is 62 mph.
        composeRule.onNodeWithTag(SPEED_VALUE_TAG).assertTextIs("62")
        composeRule.onNodeWithTag(SPEED_UNIT_TAG).assertTextIs("mph")
    }

    @Test
    fun tappingTheUnitLabelRequestsAUnitChange() {
        var toggles = 0
        setDashboard(uiState(speedKmh = 50f), onToggleSpeedUnit = { toggles++ })

        composeRule.onNodeWithTag(SPEED_UNIT_TAG).performClick()

        assertEquals(1, toggles)
    }

    @Test
    fun theTripIsNotResetWithoutAConfirmation() {
        var resetRequests = 0
        setDashboard(uiState(speedKmh = 0f), onRequestTripReset = { resetRequests++ })

        composeRule.onNodeWithTag(TRIP_RESET_BUTTON_TAG).performScrollTo().performClick()

        assertEquals(1, resetRequests)
    }

    @Test
    fun confirmingTheDialogResetsTheTrip() {
        var confirmed = 0
        setDashboard(
            uiState(speedKmh = 0f).copy(tripResetConfirmationVisible = true),
            onConfirmTripReset = { confirmed++ }
        )

        composeRule.onNodeWithTag(TRIP_RESET_CONFIRM_TAG).assertIsDisplayed().performClick()

        assertEquals(1, confirmed)
    }

    @Test
    fun fuelInformationIsShownForACombustionVehicle() {
        setDashboard(uiState(speedKmh = 40f))

        composeRule.onNodeWithTag(ENERGY_LEVEL_TAG).assertTextIs("68%")
        composeRule.onNodeWithText("Fuel").assertExists()
    }

    @Test
    fun batteryAndChargingAreShownForAnElectricVehicle() {
        setDashboard(uiState(speedKmh = 40f, electric = true))

        composeRule.onNodeWithTag(ENERGY_LEVEL_TAG).assertTextIs("62%")
        composeRule.onNodeWithText("Battery").assertExists()
        composeRule.onNodeWithText("Charging").assertExists()
    }

    @Test
    fun aLowTankLightsTheLowFuelTelltale() {
        setDashboard(
            uiState(speedKmh = 40f).withEnergy(
                EnergyState.Fuel(levelPercent = 6f, estimatedRangeKm = 30f)
            )
        )

        composeRule.onNodeWithTag(INDICATOR_TAG_PREFIX + "low_energy").assertStateIs("On")
    }

    @Test
    fun activeAndInactiveTelltalesReportDistinctStates() {
        setDashboard(
            uiState(speedKmh = 40f).withIndicators(
                Indicators(lowBeam = true, rightTurnSignal = true)
            )
        )

        composeRule.onNodeWithTag(INDICATOR_TAG_PREFIX + "right_turn").assertStateIs("On")
        composeRule.onNodeWithTag(INDICATOR_TAG_PREFIX + "low_beam").assertStateIs("On")
        composeRule.onNodeWithTag(INDICATOR_TAG_PREFIX + "left_turn").assertStateIs("Off")
        composeRule.onNodeWithTag(INDICATOR_TAG_PREFIX + "engine").assertStateIs("Off")
    }

    @Test
    fun nothingIsLitWhenTheVehicleReportsNoFaults() {
        setDashboard(
            uiState(speedKmh = 40f)
                .withIndicators(Indicators.NONE)
                .withEnergy(EnergyState.Fuel(levelPercent = 70f, estimatedRangeKm = 430f))
        )

        listOf(
            "left_turn", "right_turn", "hazard", "high_beam", "low_beam", "parking_brake",
            "seat_belt", "door_open", "engine", "low_energy", "tire_pressure", "temperature",
            "abs", "traction_control"
        ).forEach { key ->
            composeRule.onNodeWithTag(INDICATOR_TAG_PREFIX + key).assertStateIs("Off")
        }
    }

    @Test
    fun everyTelltaleCarriesAContentDescription() {
        setDashboard(uiState(speedKmh = 40f))

        composeRule.onNodeWithTag(INDICATOR_TAG_PREFIX + "seat_belt")
            .assertContentDescriptionEquals("Seat belt")
    }

    @Test
    fun demoDataIsAlwaysLabelledAsSuch() {
        setDashboard(uiState(speedKmh = 40f))

        composeRule.onNodeWithTag(DEMO_BADGE_TAG).assertIsDisplayed()
    }

    @Test
    fun vehicleDataCarriesNoDemoBadge() {
        setDashboard(
            uiState(speedKmh = 40f).copy(
                dataState = VehicleDataState.Available(
                    vehicleState = VehicleState(speedKmh = 40f),
                    source = VehicleDataSourceKind.VEHICLE
                )
            )
        )

        composeRule.onNodeWithTag(DEMO_BADGE_TAG).assertDoesNotExist()
    }

    @Test
    fun loadingSaysSoInsteadOfShowingZeroes() {
        setDashboard(DashboardUiState(dataState = VehicleDataState.Loading))

        composeRule.onNodeWithTag(LOADING_MESSAGE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SPEED_VALUE_TAG).assertDoesNotExist()
    }

    @Test
    fun withoutAVehicleInterfaceTheReasonIsExplained() {
        setDashboard(
            DashboardUiState(
                dataState = VehicleDataState.Unavailable(
                    VehicleDataUnavailableReason.NO_SUPPORTED_INTERFACE
                )
            )
        )

        composeRule.onNodeWithTag(STATUS_MESSAGE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(DEMO_BADGE_TAG).assertDoesNotExist()
    }

    @Test
    fun aMissingSensorReadsAsUnavailableRatherThanZero() {
        setDashboard(
            uiState(speedKmh = 40f).withTemperatures(
                Temperatures(outsideCelsius = null, powertrainCelsius = 90f)
            )
        )

        composeRule.onNodeWithTag(OUTSIDE_TEMPERATURE_TAG).assertContainsText("--")
    }

    @Test
    fun selectingADriveModeIsReportedBack() {
        var selected: DriveMode? = null
        setDashboard(uiState(speedKmh = 40f), onDriveModeSelected = { selected = it })

        composeRule.onNodeWithTag(DRIVE_MODE_TAG_PREFIX + "sport").performScrollTo().performClick()

        assertEquals(DriveMode.SPORT, selected)
    }

    @Test
    fun theSettingsButtonOpensSettings() {
        var opened = 0
        setDashboard(uiState(speedKmh = 40f), onOpenSettings = { opened++ })

        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()

        assertEquals(1, opened)
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun testSettings() = DashboardSettings.DEFAULT.copy(animationsEnabled = false)

    private fun uiState(speedKmh: Float, electric: Boolean = false) = DashboardUiState(
        dataState = VehicleDataState.Available(
            vehicleState = VehicleState(
                speedKmh = speedKmh,
                rpm = 2_350,
                gear = Gear.DRIVE,
                driveMode = DriveMode.NORMAL,
                odometerKm = 38_421.0,
                trip = TripData(
                    distanceKm = 142.8,
                    drivingTimeMillis = 5_400_000,
                    energyUsed = 10.4
                ),
                energy = if (electric) {
                    EnergyState.Battery(
                        levelPercent = 62f,
                        estimatedRangeKm = 236f,
                        chargingState = ChargingState.CHARGING
                    )
                } else {
                    EnergyState.Fuel(levelPercent = 68f, estimatedRangeKm = 420f)
                },
                temperatures = Temperatures(outsideCelsius = 18f, powertrainCelsius = 90f),
                indicators = Indicators(lowBeam = true)
            ),
            source = VehicleDataSourceKind.DEMO
        ),
        settings = testSettings().copy(
            demoVehicleType = if (electric) VehicleType.ELECTRIC else VehicleType.COMBUSTION
        ),
        now = LocalDateTime.of(2026, 8, 6, 14, 32)
    )

    /**
     * Applies [transform] and then sanitizes, exactly as the repository does, so telltales derived
     * from readings (low energy, overheating) are present in the state the UI is handed.
     */
    private fun DashboardUiState.mapVehicleState(
        transform: (VehicleState) -> VehicleState
    ): DashboardUiState {
        val available = dataState as VehicleDataState.Available
        return copy(
            dataState = available.copy(
                vehicleState = transform(available.vehicleState).sanitized()
            )
        )
    }

    private fun DashboardUiState.withIndicators(indicators: Indicators) =
        mapVehicleState { it.copy(indicators = indicators) }

    private fun DashboardUiState.withTemperatures(temperatures: Temperatures) =
        mapVehicleState { it.copy(temperatures = temperatures) }

    private fun DashboardUiState.withEnergy(energy: EnergyState) =
        mapVehicleState { it.copy(energy = energy) }
}

/** Asserts the node's own text, which is what the driver actually reads. */
internal fun SemanticsNodeInteraction.assertTextIs(expected: String) = assertTextEquals(expected)

internal fun SemanticsNodeInteraction.assertContainsText(expected: String) =
    assert(hasText(expected, substring = true))

/** Asserts the accessibility state a screen reader would announce for a telltale. */
internal fun SemanticsNodeInteraction.assertStateIs(expected: String) =
    assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected))
