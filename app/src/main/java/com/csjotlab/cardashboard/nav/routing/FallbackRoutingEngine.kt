package com.csjotlab.cardashboard.nav.routing

/**
 * Asks each engine in order and returns the first [RouteResult.Success]. The last engine in the
 * chain is expected to be [StraightLineRoutingEngine], which never fails and marks its result as a
 * preview, so the UI can always show *something* honest.
 */
class FallbackRoutingEngine(
    private val engines: List<RoutingEngine>,
) : RoutingEngine {

    init {
        require(engines.isNotEmpty()) { "FallbackRoutingEngine needs at least one engine" }
    }

    override suspend fun route(request: RouteRequest): RouteResult {
        var last: RouteResult = RouteResult.Failure("No routing engine configured")
        for (engine in engines) {
            last = engine.route(request)
            if (last is RouteResult.Success) return last
        }
        return last
    }
}
