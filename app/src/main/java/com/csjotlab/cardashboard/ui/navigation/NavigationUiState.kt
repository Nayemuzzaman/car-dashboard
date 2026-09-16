package com.csjotlab.cardashboard.ui.navigation

const val NO_ROUTE = "No route"
const val FINDING_ROUTE = "Finding route…"
const val WAITING_FOR_GPS = "Waiting for GPS"
const val ROUTE_READY = "Route ready"
const val NAVIGATING = "Navigating"
const val ARRIVED = "Arrived"
const val UNABLE_TO_REROUTE = "Unable to reroute"
const val UNABLE_TO_FIND_ROUTE = "Unable to find route"
const val STRAIGHT_LINE_PREVIEW = "Straight-line preview"
const val MOVING = "Moving"
const val STOPPED = "Stopped"
const val UNAVAILABLE = "—"
const val SEARCH_UNAVAILABLE = "Search unavailable"
const val TOLL_ROAD = "Toll road"
const val TOLL_FREE = "Toll-free"
const val TOLL_UNKNOWN = "Toll info unavailable"
const val TOLLS_UNAVOIDABLE = "Tolls unavoidable"

/**
 * Everything the navigation screen is allowed to show. Strings are produced by
 * [NavigationFormatter], so the screen adds no data logic of its own.
 */
data class NavigationUiState(
    val statusLabel: String,
    val maneuverText: String?,
    val remainingDistanceText: String?,
    val remainingTimeText: String?,
    val etaText: String?,
    val speedText: String,
    val motionLabel: String?,
    val previewLabel: String?,
    val hasLocation: Boolean,
    val hasRoute: Boolean,
    val isRerouting: Boolean,
    /** [TOLL_ROAD], [TOLL_FREE], [TOLL_UNKNOWN] or [TOLLS_UNAVOIDABLE]; null without a route. */
    val tollLabel: String? = null,
    /** "via <longest road>" or null when the router named no roads. */
    val viaText: String? = null,
    /** True when the road after the next maneuver is tolled. */
    val nextStepToll: Boolean = false,
    /** The driver's preference as sent to the router. */
    val avoidTolls: Boolean = false,
) {
    companion object {
        fun idle(): NavigationUiState = NavigationUiState(
            statusLabel = NO_ROUTE,
            maneuverText = null,
            remainingDistanceText = null,
            remainingTimeText = null,
            etaText = null,
            speedText = UNAVAILABLE,
            motionLabel = null,
            previewLabel = null,
            hasLocation = false,
            hasRoute = false,
            isRerouting = false,
        )
    }
}
