package com.csjotlab.cardashboard.nav.routing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin HTTP transport over a Valhalla `/route` server (POST JSON). `avoidTolls` becomes the
 * `auto.use_tolls = 0` costing option — Valhalla treats it as a strong preference, so the parsed
 * route's `hasToll` is still the truth of what came back. Parsing is [ValhallaResponseParser].
 */
class ValhallaRoutingEngine(
    private val baseUrl: String,
    private val parse: (String) -> RouteResult = ValhallaResponseParser::parse,
) : RoutingEngine {

    override suspend fun route(request: RouteRequest): RouteResult = withContext(Dispatchers.IO) {
        try {
            val connection = URL("$baseUrl/route").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.outputStream.bufferedWriter().use { it.write(body(request)) }
                val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
                val body = stream?.bufferedReader()?.use { it.readText() }
                    ?: return@withContext RouteResult.Failure("Valhalla returned HTTP ${connection.responseCode}")
                parse(body)
            } finally {
                connection.disconnect()
            }
        } catch (t: Throwable) {
            RouteResult.Failure(t.message ?: "Routing request failed")
        }
    }

    internal fun body(request: RouteRequest): String = buildJsonObject {
        put(
            "locations",
            buildJsonArray {
                add(buildJsonObject { put("lat", request.origin.latitude); put("lon", request.origin.longitude) })
                add(buildJsonObject { put("lat", request.destination.latitude); put("lon", request.destination.longitude) })
            },
        )
        put("costing", "auto")
        put("units", "kilometers")
        put("language", "en-US")
        if (request.avoidTolls) {
            putJsonObject("costing_options") { putJsonObject("auto") { put("use_tolls", 0) } }
        }
    }.toString()

    private companion object {
        const val USER_AGENT = "CarDashboard/1.0 (com.csjotlab.cardashboard)"
    }
}
