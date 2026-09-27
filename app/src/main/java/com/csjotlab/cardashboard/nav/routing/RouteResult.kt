package com.csjotlab.cardashboard.nav.routing

import com.csjotlab.cardashboard.nav.domain.Route

sealed interface RouteResult {
    /** [route] is the engine's recommendation; [alternatives] are other options it returned, if any. */
    data class Success(val route: Route, val alternatives: List<Route> = emptyList()) : RouteResult
    data class Failure(val reason: String) : RouteResult
}
