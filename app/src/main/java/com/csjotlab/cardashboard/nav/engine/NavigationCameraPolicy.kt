package com.csjotlab.cardashboard.nav.engine

/**
 * What the car-following camera should do, as pure numbers the map layer eases toward.
 *
 * Zoom widens with speed (at motorway speed the next kilometre matters, not the next junction) and
 * closes in near a maneuver so the junction is legible. Heading-up is tilted so more road ahead fits
 * on screen; north-up is flat because a tilted north-up map reads as a rotated one. The car is drawn
 * at [VEHICLE_SCREEN_FRACTION] of the map height, leaving most of the view for the road ahead.
 */
object NavigationCameraPolicy {
    const val MAX_FOLLOW_ZOOM = 17.5
    const val MIN_FOLLOW_ZOOM = 15.0
    const val DEFAULT_FOLLOW_ZOOM = 17.0
    const val NEAR_MANEUVER_ZOOM = 17.0
    const val NEAR_MANEUVER_METERS = 250f
    const val HEADING_UP_TILT = 45.0
    const val VEHICLE_SCREEN_FRACTION = 0.70

    private const val SLOW_MPS = 4.2f // 15 km/h
    private const val FAST_MPS = 30.6f // 110 km/h

    fun followZoom(speedMps: Float?, distanceToManeuverMeters: Float?): Double {
        val bySpeed = speedMps?.let {
            val t = ((it - SLOW_MPS) / (FAST_MPS - SLOW_MPS)).coerceIn(0f, 1f)
            MAX_FOLLOW_ZOOM - t * (MAX_FOLLOW_ZOOM - MIN_FOLLOW_ZOOM)
        } ?: DEFAULT_FOLLOW_ZOOM
        val nearManeuver = distanceToManeuverMeters != null && distanceToManeuverMeters <= NEAR_MANEUVER_METERS
        return if (nearManeuver) maxOf(bySpeed, NEAR_MANEUVER_ZOOM) else bySpeed
    }

    fun followTilt(headingUp: Boolean): Double = if (headingUp) HEADING_UP_TILT else 0.0

    /**
     * Top camera padding that puts the camera target at [VEHICLE_SCREEN_FRACTION] of a map
     * [mapHeightPx] tall (MapLibre centres the target inside the padded area).
     */
    fun vehicleTopPaddingPx(mapHeightPx: Int): Double = followPadding(mapHeightPx.toDouble(), 0.0, 0.0, VEHICLE_SCREEN_FRACTION).first

    /**
     * Vertical camera padding (top, bottom) that draws the camera target at [fraction] of the free
     * map area between [topInset] and [bottomInset] — the space not covered by the maneuver banner
     * and the trip bar. MapLibre centres the target inside the padded area, so the padding is
     * chosen to put that centre where the car should be.
     */
    fun followPadding(heightPx: Double, topInset: Double, bottomInset: Double, fraction: Double): Pair<Double, Double> {
        val free = (heightPx - topInset - bottomInset).coerceAtLeast(1.0)
        val y = topInset + fraction * free
        val top = 2 * y - heightPx + bottomInset
        return if (top >= 0) top to bottomInset else 0.0 to (heightPx - 2 * y).coerceAtLeast(0.0)
    }
}
