package com.csjotlab.cardashboard.nav.engine

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.GpsQuality
import com.csjotlab.cardashboard.nav.domain.NavigationPhase
import com.csjotlab.cardashboard.nav.domain.NavigationState
import com.csjotlab.cardashboard.nav.domain.RerouteState
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.vehicle.domain.Clock
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull

data class NavigationUpdate(
    val route: Route?,
    val location: GeoPoint?,
    val speedMps: Float?,
    val gpsCourseDegrees: Float?,
    val compassDegrees: Float?,
    val rerouteState: RerouteState,
    val accuracyMeters: Float? = null,
    val courseAccuracyDegrees: Float? = null,
    /**
     * Identifies the fix. Updates repeating the same timestamp (a compass tick, a route arriving)
     * are not new evidence for off-route detection or progress. Null treats every update as new.
     */
    val fixTimestampMs: Long? = null,
    /** Off-route detection, rerouting and arrival only run while the driver is being guided. */
    val guidanceActive: Boolean = true,
    /** Null derives Good/None from [location]. */
    val gpsQuality: GpsQuality? = null,
    /** Vehicle-reported speed, used for the stationary decision only when the GPS gives none. */
    val vehicleSpeedMps: Float? = null,
)

data class NavigationEngineResult(
    val state: NavigationState,
    val requestReroute: Boolean,
)

/**
 * Folds location, heading and route into one [NavigationState].
 *
 * Pure Kotlin: it owns the smoothing, progress and off-route state, but performs no I/O and knows
 * nothing about Android or the routing backend. The repository drives it on each new location fix,
 * compass reading, route change or reroute-state change.
 */
class NavigationEngine(
    private val clock: Clock,
    private val headingSmoother: HeadingSmoother = HeadingSmoother(),
    private val offRouteDetector: OffRouteDetector = OffRouteDetector(),
    private val progressTracker: RouteProgressTracker = RouteProgressTracker(),
) {
    private var current = NavigationState.idle()
    private var index: RouteIndex? = null
    private var lastHeadingMs: Long? = null
    private var lastCompassDegrees: Float? = null
    private var lastReliableCourse: Float? = null
    private var lastFixTimestampMs: Long? = null
    private var lastFixClockMs: Long? = null
    /** The latest projection of a fix onto the current route; null until a fix has been matched. */
    private var projection: RouteProjection? = null
    /** Where the last reroute was requested from; null until one has been. */
    private var lastRerouteOrigin: GeoPoint? = null

    val state: NavigationState get() = current

    fun update(update: NavigationUpdate): NavigationEngineResult {
        val now = clock.nowMs()
        update.compassDegrees?.let { lastCompassDegrees = it }

        var next = current
        if (update.route !== current.route) next = onRouteChanged(next, update.route)

        val location = update.location
        val isNewFix = location != null && (update.fixTimestampMs == null || update.fixTimestampMs != lastFixTimestampMs)
        val elapsedMs = if (isNewFix) lastFixClockMs?.let { now - it } ?: 0L else 0L
        if (isNewFix) {
            lastFixTimestampMs = update.fixTimestampMs
            lastFixClockMs = now
        }
        val gpsQuality = update.gpsQuality ?: if (location != null) GpsQuality.Good else GpsQuality.None
        val signalLost = location == null && gpsQuality == GpsQuality.Lost

        next = if (location != null) {
            applyLocation(next, location, update, isNewFix, now)
        } else {
            next.copy(
                location = Signal.Unknown,
                speedMps = Signal.Unknown,
                // A lost signal keeps the map's orientation; nothing known at all clears it.
                headingDegrees = if (signalLost) next.headingDegrees else Signal.Unknown,
                displayLocation = if (signalLost) next.displayLocation else null,
                accuracyMeters = null,
            )
        }
        next = next.copy(gpsQuality = gpsQuality)

        var requestReroute = false
        val route = next.route
        val routeIndex = index
        if (route != null && routeIndex != null) {
            val guiding = update.guidanceActive
            var offRoute = false
            if (guiding && location != null) {
                if (isNewFix || projection == null) {
                    val speed = update.speedMps ?: update.vehicleSpeedMps
                    val p = progressTracker.update(location, speed, elapsedMs)
                    projection = p
                    offRoute = offRouteDetector.checkLateral(p.lateralMeters, update.accuracyMeters, update.fixTimestampMs)
                    // A reroute from the same spot yields the same route, so a second request is
                    // only allowed once the car has moved; a car parked beside the road must not
                    // poll the router.
                    val moved = lastRerouteOrigin?.let { GeoMath.distanceMeters(it, location) >= MIN_REROUTE_MOVE_METERS } ?: true
                    requestReroute = moved && ReroutePolicy.shouldRequest(offRoute, update.rerouteState)
                    if (requestReroute) lastRerouteOrigin = location
                } else {
                    offRoute = current.offRoute
                }
            }

            // Guidance measures from the matched fix; a preview (or guidance before its first fix)
            // measures from the route origin. A lost signal keeps the last matched progress.
            val matched = projection.takeIf { guiding }
            val along = matched?.alongMeters ?: 0.0
            val maneuver = ManeuverProgressTracker.progressAt(routeIndex, along)
            val remainingDistance = remainingDistance(route, routeIndex, along)
            val remainingTime = RouteProgressCalculator.remainingTimeSeconds(routeIndex, along)
            val holding = signalLost && matched != null

            if (location != null && matched != null && isNewFix) {
                val onRoad = matched.lateralMeters <= SNAP_METERS && !offRoute
                next = next.copy(displayLocation = if (onRoad) matched.point else location)
            }

            next = next.copy(
                phase = when {
                    !guiding || matched == null -> NavigationPhase.Previewing
                    remainingDistance <= ARRIVAL_METERS -> NavigationPhase.Arrived
                    else -> NavigationPhase.Navigating
                },
                nextManeuver = maneuver?.maneuver,
                maneuverPhrase = ManeuverPhrasing.phrase(maneuver),
                maneuverInstruction = maneuver?.let { ManeuverPhrasing.instruction(it.maneuver.type, it.roadName) },
                distanceToManeuverMeters = maneuver?.distanceToManeuverMeters,
                currentRoadName = maneuver?.currentRoadName,
                thenManeuver = maneuver?.then,
                distanceAlongRouteMeters = along.toFloat(),
                remainingDistanceMeters = Signal.Value(remainingDistance, now),
                remainingTimeSeconds = Signal.Value(remainingTime, now),
                // In a tunnel the arrival time is not known to be slipping: keep the last estimate.
                etaMs = if (holding) current.etaMs else Signal.Value(RouteProgressCalculator.etaMs(clock, remainingTime), now),
                offRoute = offRoute,
            )
        } else {
            next = next.copy(
                phase = NavigationPhase.Idle,
                nextManeuver = null,
                maneuverPhrase = null,
                maneuverInstruction = null,
                distanceToManeuverMeters = null,
                currentRoadName = null,
                thenManeuver = null,
                distanceAlongRouteMeters = null,
                remainingDistanceMeters = null,
                remainingTimeSeconds = null,
                etaMs = null,
                offRoute = false,
                displayLocation = next.displayLocation ?: location,
            )
        }

        next = next.copy(rerouteState = update.rerouteState)
        current = next
        return NavigationEngineResult(next, requestReroute)
    }

    /** The router's total scaled by the share of the geometry still ahead. */
    private fun remainingDistance(route: Route, index: RouteIndex, along: Double): Float {
        val length = index.lengthMeters
        if (length <= 0.0) return route.totalDistanceMeters
        return (route.totalDistanceMeters * (1.0 - along / length)).toFloat().coerceAtLeast(0f)
    }

    private fun onRouteChanged(current: NavigationState, newRoute: Route?): NavigationState {
        offRouteDetector.reset()
        projection = null
        index = newRoute?.let(::RouteIndex)
        progressTracker.reset(index)
        if (newRoute == null) {
            lastRerouteOrigin = null
            return NavigationState.idle().copy(
                location = current.location,
                speedMps = current.speedMps,
                headingDegrees = current.headingDegrees,
                displayLocation = current.displayLocation,
                gpsQuality = current.gpsQuality,
                accuracyMeters = current.accuracyMeters,
            )
        }
        return current.copy(
            route = newRoute,
            phase = NavigationPhase.Previewing,
            nextManeuver = null,
            maneuverPhrase = null,
            maneuverInstruction = null,
            distanceToManeuverMeters = null,
            thenManeuver = null,
            remainingDistanceMeters = null,
            remainingTimeSeconds = null,
            etaMs = null,
            offRoute = false,
        )
    }

    private fun applyLocation(
        next: NavigationState,
        location: GeoPoint,
        update: NavigationUpdate,
        isNewFix: Boolean,
        now: Long,
    ): NavigationState {
        val speed = update.speedMps ?: update.vehicleSpeedMps
        if (isNewFix && HeadingSourcePolicy.isCourseReliable(speed, update.gpsCourseDegrees, update.courseAccuracyDegrees)) {
            lastReliableCourse = update.gpsCourseDegrees
        }
        val rawHeading = HeadingSourcePolicy.select(
            speedMps = speed,
            gpsCourseDegrees = update.gpsCourseDegrees,
            courseAccuracyDegrees = update.courseAccuracyDegrees,
            lastReliableCourseDegrees = lastReliableCourse,
            compassDegrees = lastCompassDegrees,
        )
        val smoothed = rawHeading?.let { raw ->
            val elapsedMs = lastHeadingMs?.let { now - it }
            lastHeadingMs = now
            if (elapsedMs == null) {
                headingSmoother.reset(raw)
                raw
            } else {
                headingSmoother.next(raw, elapsedMs)
            }
        }

        return next.copy(
            location = Signal.Value(location, now),
            speedMps = update.speedMps?.let { Signal.Value(it, now) } ?: Signal.Unknown,
            headingDegrees = smoothed?.let { Signal.Value(it, now) } ?: Signal.Unknown,
            displayLocation = if (next.route == null || !update.guidanceActive) location else next.displayLocation ?: location,
            accuracyMeters = update.accuracyMeters,
        )
    }

    private companion object {
        const val ARRIVAL_METERS = 25f
        const val MIN_REROUTE_MOVE_METERS = 25.0
        /** A fix this close to the route is drawn on it. Beyond, the raw fix is drawn. */
        const val SNAP_METERS = 20.0
    }
}

/** The display value for the snapped position when a caller holds only a state. */
val NavigationState.vehiclePosition: GeoPoint? get() = displayLocation ?: location.valueOrNull()
