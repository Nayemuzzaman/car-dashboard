package com.csjotlab.cardashboard.nav.engine

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sun elevation from position and time (the low-precision almanac algorithm, accurate to well under
 * a degree between 1950 and 2050), used for the automatic day/night map. No network, no sensors.
 */
object SolarDayNight {
    /** Upper limb on the horizon, including refraction — the conventional sunrise/sunset altitude. */
    const val SUNSET_ELEVATION_DEGREES = -0.833

    fun isDaylight(latitude: Double, longitude: Double, epochMs: Long): Boolean =
        elevationDegrees(latitude, longitude, epochMs) > SUNSET_ELEVATION_DEGREES

    fun elevationDegrees(latitude: Double, longitude: Double, epochMs: Long): Double {
        val n = epochMs / 86_400_000.0 + 2_440_587.5 - 2_451_545.0 // days since J2000.0
        val meanLongitude = 280.460 + 0.9856474 * n
        val meanAnomaly = Math.toRadians(357.528 + 0.9856003 * n)
        val eclipticLongitude = Math.toRadians(
            meanLongitude + 1.915 * sin(meanAnomaly) + 0.020 * sin(2 * meanAnomaly),
        )
        val obliquity = Math.toRadians(23.439 - 0.0000004 * n)
        val rightAscension = atan2(cos(obliquity) * sin(eclipticLongitude), cos(eclipticLongitude))
        val declination = asin(sin(obliquity) * sin(eclipticLongitude))
        val gmstHours = 18.697374558 + 24.06570982441908 * n
        val hourAngle = Math.toRadians(gmstHours * 15.0 + longitude) - rightAscension
        val lat = Math.toRadians(latitude)
        return Math.toDegrees(asin(sin(lat) * sin(declination) + cos(lat) * cos(declination) * cos(hourAngle)))
    }
}
