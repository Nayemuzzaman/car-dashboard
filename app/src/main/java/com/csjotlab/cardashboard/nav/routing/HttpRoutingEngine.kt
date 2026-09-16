package com.csjotlab.cardashboard.nav.routing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin HTTP transport over a self-hosted GraphHopper Directions API.
 *
 * It performs no parsing and no navigation logic; it fetches a response and delegates to
 * [RoutingResponseParser]. Kept deliberately small so it is the only network-dependent piece of the
 * routing layer.
 */
class HttpRoutingEngine(
    private val baseUrl: String,
    private val profile: String = "car",
    private val parse: (String) -> RouteResult = RoutingResponseParser::parse,
) : RoutingEngine {

    override suspend fun route(request: RouteRequest): RouteResult = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url(request)).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parse(body)
            } finally {
                connection.disconnect()
            }
        } catch (t: Throwable) {
            RouteResult.Failure(t.message ?: "Routing request failed")
        }
    }

    private fun url(request: RouteRequest): String {
        val point = { p: com.csjotlab.cardashboard.nav.domain.GeoPoint -> "${p.latitude},${p.longitude}" }
        return "$baseUrl/route" +
            "?point=${point(request.origin)}" +
            "&point=${point(request.destination)}" +
            "&profile=$profile" +
            "&locale=en" +
            "&instructions=true" +
            "&points_encoded=false"
    }
}
