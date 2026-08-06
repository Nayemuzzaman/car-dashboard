package com.example.cardashboard.domain.model

/**
 * Unit in which speed and distance are presented to the driver.
 *
 * All values inside the domain are stored in metric units (km/h, km). Conversion happens only at
 * the presentation boundary so that stored/simulated data never depends on a user preference.
 */
enum class SpeedUnit {
    KILOMETERS_PER_HOUR,
    MILES_PER_HOUR;

    companion object {
        const val KM_PER_MILE = 1.609344
    }
}

/** Converts a metric speed to the given display unit. */
fun Float.kmhToDisplay(unit: SpeedUnit): Float = when (unit) {
    SpeedUnit.KILOMETERS_PER_HOUR -> this
    SpeedUnit.MILES_PER_HOUR -> (this / SpeedUnit.KM_PER_MILE).toFloat()
}

/** Converts a metric distance to the given display unit. */
fun Double.kmToDisplay(unit: SpeedUnit): Double = when (unit) {
    SpeedUnit.KILOMETERS_PER_HOUR -> this
    SpeedUnit.MILES_PER_HOUR -> this / SpeedUnit.KM_PER_MILE
}

/** Unit in which temperatures are presented to the driver. */
enum class TemperatureUnit {
    CELSIUS,
    FAHRENHEIT
}

/** Converts a Celsius reading to the given display unit. */
fun Float.celsiusToDisplay(unit: TemperatureUnit): Float = when (unit) {
    TemperatureUnit.CELSIUS -> this
    TemperatureUnit.FAHRENHEIT -> this * 9f / 5f + 32f
}
