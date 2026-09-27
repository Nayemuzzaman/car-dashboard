package com.csjotlab.cardashboard.nav.domain

import com.csjotlab.cardashboard.vehicle.domain.Signal

enum class NavigationPhase { Idle, Previewing, Navigating, Arrived }

enum class RerouteState { Idle, InProgress, Failed }

/**
 * Everything the navigation screen is allowed to show about one moment of a session.
 *
 * The same honesty rule as the vehicle domain applies: there is no numeric default. Position, speed
 * and heading are [Signal]s that only hold a value because a provider reported one; the route and
 * its derived values are null until a routing engine actually returned a route.
 */
data class NavigationState(
    val phase: NavigationPhase,
    val location: Signal<GeoPoint>,
    val speedMps: Signal<Float>,
    /** Smoothed heading-up target bearing, degrees 0..360. */
    val headingDegrees: Signal<Float>,
    val route: Route?,
    val nextManeuver: Maneuver?,
    val maneuverPhrase: String?,
    val remainingDistanceMeters: Signal<Float>?,
    val remainingTimeSeconds: Signal<Long>?,
    val etaMs: Signal<Long>?,
    val offRoute: Boolean,
    val rerouteState: RerouteState,
    /** Distance to [nextManeuver] in metres, shown large on the banner. */
    val distanceToManeuverMeters: Float? = null,
    /** The banner instruction without distance, e.g. "Turn right onto Route 3". */
    val maneuverInstruction: String? = null,
    /** The road currently driven, when the router named it. */
    val currentRoadName: String? = null,
    /** The maneuver right after [nextManeuver] when it follows closely ("Then ↰"). */
    val thenManeuver: Maneuver? = null,
    /** Progress along the route geometry; splits the drawn route into driven and ahead. */
    val distanceAlongRouteMeters: Float? = null,
    /**
     * Where to draw the vehicle: the fix matched onto the route when it is within a few metres of
     * it, otherwise the fix itself; the last known position while the signal is lost. Rendering
     * only — [location] remains the unmodified filtered fix.
     */
    val displayLocation: GeoPoint? = null,
    val gpsQuality: GpsQuality = GpsQuality.None,
    val accuracyMeters: Float? = null,
) {
    companion object {
        fun idle(): NavigationState = NavigationState(
            phase = NavigationPhase.Idle,
            location = Signal.Unknown,
            speedMps = Signal.Unknown,
            headingDegrees = Signal.Unknown,
            route = null,
            nextManeuver = null,
            maneuverPhrase = null,
            remainingDistanceMeters = null,
            remainingTimeSeconds = null,
            etaMs = null,
            offRoute = false,
            rerouteState = RerouteState.Idle,
        )
    }
}
