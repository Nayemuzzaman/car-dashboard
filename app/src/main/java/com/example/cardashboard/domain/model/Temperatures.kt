package com.example.cardashboard.domain.model

/**
 * Temperature sensors, all in Celsius.
 *
 * Every reading is nullable because not all vehicles expose all sensors, and a sensor can drop out
 * at runtime. A missing reading is rendered as "--" rather than as a fake zero.
 */
data class Temperatures(
    val outsideCelsius: Float? = null,
    /** Coolant temperature on combustion vehicles, pack temperature on electric ones. */
    val powertrainCelsius: Float? = null
) {
    companion object {
        val UNKNOWN = Temperatures()

        /** Readings outside this window are treated as a faulty sensor and discarded. */
        val PLAUSIBLE_RANGE_CELSIUS = -60f..250f

        /** Powertrain temperature at or above which the overheating telltale lights up. */
        const val POWERTRAIN_WARNING_CELSIUS = 110f
    }
}
