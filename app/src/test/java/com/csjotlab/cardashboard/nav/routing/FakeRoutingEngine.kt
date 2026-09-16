package com.csjotlab.cardashboard.nav.routing

class FakeRoutingEngine(
    private val result: (RouteRequest) -> RouteResult,
) : RoutingEngine {
    val requests = mutableListOf<RouteRequest>()

    override suspend fun route(request: RouteRequest): RouteResult {
        requests += request
        return result(request)
    }
}
