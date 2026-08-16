package com.example.cardashboard.ui.components

import com.example.cardashboard.R
import com.example.cardashboard.domain.model.SpeedUnit
import com.example.cardashboard.domain.model.TemperatureUnit

/** Unit labels, resolved in one place so no screen invents its own wording. */

val SpeedUnit.speedLabelRes: Int
    get() = when (this) {
        SpeedUnit.KILOMETERS_PER_HOUR -> R.string.unit_kmh
        SpeedUnit.MILES_PER_HOUR -> R.string.unit_mph
    }

/** Distance follows the speed unit: km/h implies kilometres, mph implies miles. */
val SpeedUnit.distanceLabelRes: Int
    get() = when (this) {
        SpeedUnit.KILOMETERS_PER_HOUR -> R.string.unit_km
        SpeedUnit.MILES_PER_HOUR -> R.string.unit_mi
    }

val TemperatureUnit.labelRes: Int
    get() = when (this) {
        TemperatureUnit.CELSIUS -> R.string.unit_celsius
        TemperatureUnit.FAHRENHEIT -> R.string.unit_fahrenheit
    }
