package com.csjotlab.cardashboard.nav.routing

import com.csjotlab.cardashboard.nav.domain.GeoPoint

data class RouteRequest(
    val origin: GeoPoint,
    val destination: GeoPoint,
    /** A preference, not a guarantee: engines that support it steer away from tolls. */
    val avoidTolls: Boolean = false,
)

/**
 * One route-computation provider. The navigation layer never knows whether the engine is a
 * self-hosted HTTP service, an on-device GraphHopper instance, or a test fake.
 */
interface RoutingEngine {
    suspend fun route(request: RouteRequest): RouteResult
}
