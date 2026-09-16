package com.csjotlab.cardashboard.nav.engine

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.NavigationPhase
import com.csjotlab.cardashboard.nav.domain.NavigationState
import com.csjotlab.cardashboard.nav.domain.RerouteState
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.vehicle.domain.Clock
import com.csjotlab.cardashboard.vehicle.domain.Signal

data class NavigationUpdate(
    val route: Route?,
    val location: GeoPoint?,
    val speedMps: Float?,
    val gpsCourseDegrees: Float?,
    val compassDegrees: Float?,
    val rerouteState: RerouteState,
)

data class NavigationEngineResult(
    val state: NavigationState,
    val requestReroute: Boolean,
)

/**
 * Folds location, heading and route into one [NavigationState].
 *
 * Pure Kotlin: it owns the smoothing and off-route state, but performs no I/O and knows nothing
 * about Android or the routing backend. The repository drives it by calling [update] on each new
 * location fix, compass reading, route change or reroute-state change.
 */
class NavigationEngine(
    private val clock: Clock,
    private val headingSmoother: HeadingSmoother = HeadingSmoother(),
    private val offRouteDetector: OffRouteDetector = OffRouteDetector(),
) {
    private var current = NavigationState.idle()
    private var lastHeadingMs: Long? = null
    private var lastCompassDegrees: Float? = null
    /** Where the last reroute was requested from; null until one has been. */
    private var lastRerouteOrigin: GeoPoint? = null

    val state: NavigationState get() = current

    fun update(update: NavigationUpdate): NavigationEngineResult {
        val now = clock.nowMs()

        if (update.compassDegrees != null) {
            lastCompassDegrees = update.compassDegrees
        }

        var next = current
        if (update.route !== current.route) {
            next = onRouteChanged(next, update.route)
        }

        val location = update.location
        next = if (location != null) {
            applyLocation(next, location, update, now)
        } else {
            next.copy(location = Signal.Unknown, speedMps = Signal.Unknown, headingDegrees = Signal.Unknown)
        }

        var requestReroute = false
        val route = next.route
        if (route != null) {
            // With no live fix yet, progress is measured from the route's own origin so a planned
            // route still shows its distance, time, ETA and next maneuver as a preview.
            val progressPosition = location ?: route.origin
            val progress = RouteProgressCalculator.progress(route, progressPosition)
            val maneuver = ManeuverProgressTracker.progressAt(route, progress.distanceAlongRouteMeters)

            val offRoute = location != null && offRouteDetector.check(location, route)
            // A reroute from the same spot yields the same route, so a second request is only
            // allowed once the car has moved; this keeps a car parked beside the road from asking
            // the router every few seconds.
            val movedSinceLastRequest = location != null && lastRerouteOrigin?.let {
                GeoMath.distanceMeters(it, location) >= MIN_REROUTE_MOVE_METERS
            } ?: true
            requestReroute = movedSinceLastRequest && ReroutePolicy.shouldRequest(offRoute, update.rerouteState)
            if (requestReroute) lastRerouteOrigin = location

            next = next.copy(
                phase = when {
                    location == null -> NavigationPhase.Previewing
                    progress.remainingDistanceMeters <= ARRIVAL_METERS -> NavigationPhase.Arrived
                    else -> NavigationPhase.Navigating
                },
                nextManeuver = maneuver?.maneuver,
                maneuverPhrase = ManeuverPhrasing.phrase(maneuver),
                remainingDistanceMeters = Signal.Value(progress.remainingDistanceMeters, now),
                remainingTimeSeconds = Signal.Value(progress.remainingTimeSeconds, now),
                etaMs = Signal.Value(RouteProgressCalculator.etaMs(clock, progress.remainingTimeSeconds), now),
                offRoute = offRoute,
            )
        } else {
            next = next.copy(
                phase = NavigationPhase.Idle,
                nextManeuver = null,
                maneuverPhrase = null,
                remainingDistanceMeters = null,
                remainingTimeSeconds = null,
                etaMs = null,
                offRoute = false,
            )
        }

        next = next.copy(rerouteState = update.rerouteState)
        current = next
        return NavigationEngineResult(next, requestReroute)
    }

    private fun onRouteChanged(current: NavigationState, newRoute: Route?): NavigationState {
        if (newRoute == null) {
            offRouteDetector.reset()
            lastRerouteOrigin = null
            return NavigationState.idle()
        }
        offRouteDetector.reset()
        return current.copy(
            route = newRoute,
            phase = NavigationPhase.Previewing,
            nextManeuver = null,
            maneuverPhrase = null,
            remainingDistanceMeters = null,
            remainingTimeSeconds = null,
            etaMs = null,
            offRoute = false,
        )
    }

    private fun applyLocation(next: NavigationState, location: GeoPoint, update: NavigationUpdate, now: Long): NavigationState {
        val speedMps = update.speedMps?.let { Signal.Value(it, now) } ?: Signal.Unknown
        val rawHeading = HeadingSourcePolicy.select(update.speedMps, update.gpsCourseDegrees, lastCompassDegrees)
        val smoothed = rawHeading?.let { raw ->
            val elapsedMs = lastHeadingMs?.let { now - it }
            if (elapsedMs == null) {
                headingSmoother.reset(raw)
                lastHeadingMs = now
                raw
            } else {
                lastHeadingMs = now
                headingSmoother.next(raw, elapsedMs)
            }
        }

        return next.copy(
            location = Signal.Value(location, now),
            speedMps = speedMps,
            headingDegrees = smoothed?.let { Signal.Value(it, now) } ?: Signal.Unknown,
        )
    }

    private companion object {
        const val ARRIVAL_METERS = 25f
        const val MIN_REROUTE_MOVE_METERS = 25.0
    }
}
