package com.csjotlab.cardashboard.nav.geocoding

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.toLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Thin HTTP transport over Photon (komoot's open OSM geocoder). Photon is built for
 * search-as-you-type and supports a lat/lon bias, which is why it replaced Nominatim as the
 * default: Nominatim's usage policy forbids client-side autocomplete.
 */
class PhotonGeocodingEngine(
    private val baseUrl: String,
    private val userAgent: String = "CarDashboard/1.0 (com.csjotlab.cardashboard)",
    private val language: String = "en",
    private val parse: (String) -> GeocodeResult = PhotonResponseParser::parse,
) : GeocodingEngine {

    override suspend fun search(query: String, near: GeoPoint?): GeocodeResult {
        if (query.isBlank()) return GeocodeResult.Success(emptyList())
        val encoded = URLEncoder.encode(query, "UTF-8")
        val bias = near?.let { "&lat=${it.latitude}&lon=${it.longitude}" }.orEmpty()
        return get("$baseUrl/api/?q=$encoded&limit=$LIMIT&lang=$language$bias")
    }

    override suspend fun reverse(point: GeoPoint): Place? =
        when (val result = get("$baseUrl/reverse?lat=${point.latitude}&lon=${point.longitude}&lang=$language")) {
            is GeocodeResult.Success -> result.places.firstOrNull()
            is GeocodeResult.Failure -> null
        }

    private suspend fun get(url: String): GeocodeResult = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", userAgent)
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parse(body)
            } finally {
                connection.disconnect()
            }
        } catch (t: Throwable) {
            GeocodeResult.Failure(t.message ?: "Search failed")
        }
    }

    private companion object {
        const val LIMIT = 8
    }
}

/** Pure Photon GeoJSON → [Place] mapping; JVM-tested, transport-independent. */
object PhotonResponseParser {

    fun parse(json: String): GeocodeResult {
        return try {
            val features = Json.parseToJsonElement(json).jsonObject["features"]?.jsonArray
                ?: return GeocodeResult.Failure("No features in search response")
            GeocodeResult.Success(features.mapNotNull { runCatching { toPlace(it.jsonObject) }.getOrNull() })
        } catch (t: Throwable) {
            GeocodeResult.Failure(t.message ?: "Unable to parse search response")
        }
    }

    private fun toPlace(feature: JsonObject): Place? {
        val coordinates = feature["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return null
        val longitude = coordinates.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return null
        val latitude = coordinates.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return null
        val point = GeoPoint(latitude, longitude)
        val properties = feature["properties"]?.jsonObject ?: JsonObject(emptyMap())

        fun property(key: String): String? =
            properties[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        val street = listOfNotNull(property("street"), property("housenumber"))
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
        val locality = property("city") ?: property("county") ?: property("state")
        val name = property("name") ?: street ?: locality ?: point.toLabel()
        val address = listOfNotNull(
            street?.takeIf { it != name },
            locality?.takeIf { it != name },
            property("country"),
        ).joinToString(", ").takeIf { it.isNotBlank() }

        return Place(name = name, point = point, address = address, category = property("osm_value"))
    }
}
