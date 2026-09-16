package com.csjotlab.cardashboard.nav.routing

import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.domain.RouteStep
import com.csjotlab.cardashboard.nav.engine.GeoMath
import kotlin.math.roundToLong

/**
 * Produces a straight-line preview between two points when no road-routing server is available.
 *
 * The resulting [Route.isPreview] is true and the UI labels it as a preview, so a straight line is
 * never presented as real road routing.
 */
class StraightLineRoutingEngine(
    private val averageSpeedMps: Float = 11.1f, // ~40 km/h
) : RoutingEngine {

    override suspend fun route(request: RouteRequest): RouteResult {
        val distance = GeoMath.distanceMeters(request.origin, request.destination).toFloat()
        val durationSeconds = (distance / averageSpeedMps).roundToLong()
        val geometry = listOf(request.origin, request.destination)

        return RouteResult.Success(
            Route(
                origin = request.origin,
                destination = request.destination,
                steps = listOf(
                    RouteStep(
                        maneuver = Maneuver(ManeuverType.Depart, null),
                        distanceMeters = distance,
                        durationSeconds = durationSeconds,
                        geometry = geometry,
                    ),
                    RouteStep(
                        maneuver = Maneuver(ManeuverType.Arrive, null),
                        distanceMeters = 0f,
                        durationSeconds = 0L,
                        geometry = listOf(request.destination),
                    ),
                ),
                geometry = geometry,
                totalDistanceMeters = distance,
                totalDurationSeconds = durationSeconds,
                isPreview = true,
            ),
        )
    }
}
