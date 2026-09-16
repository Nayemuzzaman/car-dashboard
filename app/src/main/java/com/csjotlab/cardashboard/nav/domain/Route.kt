package com.csjotlab.cardashboard.nav.domain

data class RouteStep(
    val maneuver: Maneuver,
    val distanceMeters: Float,
    val durationSeconds: Long,
    val geometry: List<GeoPoint>,
    /** Road travelled after the maneuver, for "via …" summaries; null when the router gave none. */
    val roadName: String? = null,
    /** True when this step runs on a toll road. False also when the router cannot tell. */
    val toll: Boolean = false,
)

data class Route(
    val origin: GeoPoint,
    val destination: GeoPoint,
    val steps: List<RouteStep>,
    /** The full route polyline, origin to destination. */
    val geometry: List<GeoPoint>,
    val totalDistanceMeters: Float,
    val totalDurationSeconds: Long,
    /** True when this is a straight-line preview, not a road-routed path. */
    val isPreview: Boolean = false,
    /**
     * Whether the route uses a toll road. Null means the routing engine cannot say (OSRM, the
     * straight-line preview) — the UI then says "unavailable", never "free".
     */
    val hasToll: Boolean? = null,
)
