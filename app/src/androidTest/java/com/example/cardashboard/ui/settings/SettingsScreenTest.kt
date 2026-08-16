package com.example.cardashboard.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.cardashboard.domain.model.DashboardSettings
import com.example.cardashboard.domain.model.SpeedUnit
import com.example.cardashboard.domain.model.TemperatureUnit
import com.example.cardashboard.domain.model.ThemePreference
import com.example.cardashboard.domain.model.VehicleType
import com.example.cardashboard.ui.theme.CarDashboardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var settings = DashboardSettings.DEFAULT
    private var speedUnitSelections = mutableListOf<SpeedUnit>()
    private var temperatureUnitSelections = mutableListOf<TemperatureUnit>()
    private var vehicleTypeSelections = mutableListOf<VehicleType>()
    private var themeSelections = mutableListOf<ThemePreference>()
    private var demoModeChanges = mutableListOf<Boolean>()
    private var animationChanges = mutableListOf<Boolean>()
    private var demoResets = 0
    private var backPresses = 0

    private fun setSettings(initial: DashboardSettings = DashboardSettings.DEFAULT) {
        settings = initial
        composeRule.setContent {
            CarDashboardTheme {
                SettingsScreen(
                    settings = settings,
                    onSpeedUnitSelected = { speedUnitSelections += it },
                    onTemperatureUnitSelected = { temperatureUnitSelections += it },
                    onDemoModeChanged = { demoModeChanges += it },
                    onDemoVehicleTypeSelected = { vehicleTypeSelections += it },
                    onThemeSelected = { themeSelections += it },
                    onAnimationsChanged = { animationChanges += it },
                    onResetDemoData = { demoResets++ },
                    onBack = { backPresses++ }
                )
            }
        }
    }

    private fun option(tag: String) =
        composeRule.onNodeWithTag(SETTINGS_OPTION_TAG_PREFIX + tag)

    @Test
    fun settingsScreenRenders() {
        setSettings()

        composeRule.onNodeWithTag(SETTINGS_ROOT_TAG).assertIsDisplayed()
    }

    @Test
    fun choosingMilesReportsTheNewSpeedUnit() {
        setSettings()

        option("speed_unit_miles_per_hour").performScrollTo().performClick()

        assertEquals(listOf(SpeedUnit.MILES_PER_HOUR), speedUnitSelections)
    }

    @Test
    fun choosingFahrenheitReportsTheNewTemperatureUnit() {
        setSettings()

        option("temperature_unit_fahrenheit").performScrollTo().performClick()

        assertEquals(listOf(TemperatureUnit.FAHRENHEIT), temperatureUnitSelections)
    }

    @Test
    fun demoModeReflectsTheStoredValueAndCanBeTurnedOff() {
        setSettings()

        composeRule.onNodeWithTag(DEMO_MODE_SWITCH_TAG).performScrollTo().assertIsOn().performClick()

        assertEquals(listOf(false), demoModeChanges)
    }

    @Test
    fun demoModeOffIsReflectedInTheSwitch() {
        setSettings(DashboardSettings.DEFAULT.copy(demoModeEnabled = false))

        composeRule.onNodeWithTag(DEMO_MODE_SWITCH_TAG).performScrollTo().assertIsOff()
    }

    @Test
    fun theElectricDemoProfileCanBeSelected() {
        setSettings()

        option("vehicle_type_electric").performScrollTo().performClick()

        assertEquals(listOf(VehicleType.ELECTRIC), vehicleTypeSelections)
    }

    @Test
    fun resettingDemoDataIsRequestedOnce() {
        setSettings()

        composeRule.onNodeWithTag(RESET_DEMO_BUTTON_TAG).performScrollTo().performClick()

        assertEquals(1, demoResets)
    }

    @Test
    fun theThemeCanBeChanged() {
        setSettings()

        option("theme_light").performScrollTo().performClick()

        assertEquals(listOf(ThemePreference.LIGHT), themeSelections)
    }

    @Test
    fun animationsCanBeTurnedOff() {
        setSettings()

        composeRule.onNodeWithTag(ANIMATIONS_SWITCH_TAG).performScrollTo().assertIsOn().performClick()

        assertEquals(listOf(false), animationChanges)
    }

    @Test
    fun demoOptionsAreDisabledWhenDemoModeIsOff() {
        setSettings(DashboardSettings.DEFAULT.copy(demoModeEnabled = false))

        option("vehicle_type_electric").performScrollTo().performClick()

        assertFalse(
            "a disabled option must not report a selection",
            vehicleTypeSelections.isNotEmpty()
        )
    }
}
