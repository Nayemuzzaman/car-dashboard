package com.csjotlab.cardashboard.nav.routing

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin HTTP transport over an OSRM `route/v1` server. The public demo server is the default
 * (open, no key, no SLA); the base URL is injectable for a self-hosted instance. Parsing is
 * delegated to [OsrmResponseParser], so this class holds the only network code for OSRM.
 */
class OsrmRoutingEngine(
    private val baseUrl: String,
    private val profile: String = "driving",
    private val parse: (String) -> RouteResult = OsrmResponseParser::parse,
) : RoutingEngine {

    override suspend fun route(request: RouteRequest): RouteResult = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url(request)).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                // OSRM answers 4xx with a JSON body that carries the failure code; read whichever
                // stream has it so the reason reaches the UI instead of a bare exception.
                val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
                val body = stream?.bufferedReader()?.use { it.readText() }
                    ?: return@withContext RouteResult.Failure("OSRM returned HTTP ${connection.responseCode}")
                parse(body)
            } finally {
                connection.disconnect()
            }
        } catch (t: Throwable) {
            RouteResult.Failure(t.message ?: "Routing request failed")
        }
    }

    private fun url(request: RouteRequest): String {
        fun lonLat(p: GeoPoint) = "${p.longitude},${p.latitude}"
        return "$baseUrl/route/v1/$profile/${lonLat(request.origin)};${lonLat(request.destination)}" +
            "?overview=full&geometries=geojson&steps=true&alternatives=true"
    }

    private companion object {
        const val USER_AGENT = "CarDashboard/1.0 (com.csjotlab.cardashboard)"
    }
}
