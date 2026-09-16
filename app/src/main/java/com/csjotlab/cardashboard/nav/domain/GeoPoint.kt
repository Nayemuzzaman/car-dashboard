package com.csjotlab.cardashboard.nav.domain

import java.util.Locale

/** A WGS-84 coordinate. Degrees, not radians; latitude first. */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

/** The label of last resort when nothing named a place: "23.8103, 90.4125". */
fun GeoPoint.toLabel(): String = String.format(Locale.US, "%.4f, %.4f", latitude, longitude)
