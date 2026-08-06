package com.example.cardashboard.ui.format

import com.example.cardashboard.domain.model.SpeedUnit
import com.example.cardashboard.domain.model.TemperatureUnit
import com.example.cardashboard.domain.model.celsiusToDisplay
import com.example.cardashboard.domain.model.kmToDisplay
import com.example.cardashboard.domain.model.kmhToDisplay
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Turns domain values into the strings the cluster shows.
 *
 * Unit conversion happens here and nowhere else, so a value is never converted twice. The locale is
 * a parameter rather than a global read so the formatting can be asserted in tests.
 */
object DashboardFormat {

    /** Whole numbers only — a decimal place on a speedometer is noise at a glance. */
    fun speed(speedKmh: Float, unit: SpeedUnit): String =
        speedKmh.kmhToDisplay(unit).roundToInt().toString()

    /** One decimal place, which is what trip distances are quoted to. */
    fun distance(distanceKm: Double, unit: SpeedUnit, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.1f", distanceKm.kmToDisplay(unit))

    /** Whole units with grouping separators, as an odometer reads. */
    fun odometer(distanceKm: Double, unit: SpeedUnit, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%,d", distanceKm.kmToDisplay(unit).roundToLong())

    fun range(rangeKm: Float, unit: SpeedUnit): String =
        rangeKm.toDouble().kmToDisplay(unit).roundToInt().toString()

    fun percent(level: Float): String = level.roundToInt().toString()

    fun temperature(
        celsius: Float,
        unit: TemperatureUnit,
        locale: Locale = Locale.getDefault()
    ): String = String.format(locale, "%.0f", celsius.celsiusToDisplay(unit))

    /**
     * Revs shown as thousands, e.g. 2350 rpm becomes "2.4".
     *
     * Divided as a `Double`: in `Float`, 2350/1000 lands just below 2.35 and rounds down to "2.3".
     */
    fun rpmThousands(rpm: Int, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.1f", rpm / 1000.0)

    /** `h:mm:ss` once an hour has passed, `m:ss` before that. */
    fun duration(millis: Long, locale: Locale = Locale.getDefault()): String {
        val totalSeconds = (millis.coerceAtLeast(0L)) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(locale, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(locale, "%d:%02d", minutes, seconds)
        }
    }

    fun consumption(per100Km: Double, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.1f", per100Km)
}
