package com.csjotlab.cardashboard.nav.routing

import com.csjotlab.cardashboard.nav.domain.Route

sealed interface RouteResult {
    data class Success(val route: Route) : RouteResult
    data class Failure(val reason: String) : RouteResult
}
