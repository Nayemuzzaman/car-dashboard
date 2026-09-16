package com.csjotlab.cardashboard.nav.geocoding

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Thin HTTP transport over the OpenStreetMap Nominatim geocoder. Nominatim is open (ODbL) but its
 * public endpoint is usage-limited, requires attribution and forbids client-side autocomplete, so
 * it is no longer the default ([PhotonGeocodingEngine] is). Kept as an alternative behind the same
 * seam; the base URL is injectable so a self-hosted instance can be used.
 */
class NominatimGeocodingEngine(
    private val baseUrl: String,
    private val userAgent: String = "CarDashboard/1.0 (com.csjotlab.cardashboard)",
    private val parse: (String) -> GeocodeResult = NominatimResponseParser::parse,
) : GeocodingEngine {

    override suspend fun search(query: String, near: GeoPoint?): GeocodeResult {
        if (query.isBlank()) return GeocodeResult.Success(emptyList())
        val encoded = URLEncoder.encode(query, "UTF-8")
        // Nominatim has no proximity parameter; an unbounded viewbox around the fix ranks results
        // inside it first without excluding the rest of the world.
        val bias = near?.let {
            "&viewbox=${it.longitude - 0.5},${it.latitude + 0.5},${it.longitude + 0.5},${it.latitude - 0.5}&bounded=0"
        }.orEmpty()
        return get("$baseUrl/search?format=json&limit=5&q=$encoded$bias")
    }

    override suspend fun reverse(point: GeoPoint): Place? {
        val body = getBody("$baseUrl/reverse?format=json&lat=${point.latitude}&lon=${point.longitude}") ?: return null
        return NominatimResponseParser.parseReverse(body)
    }

    private suspend fun get(url: String): GeocodeResult {
        val body = getBody(url) ?: return GeocodeResult.Failure("Search failed")
        return parse(body)
    }

    private suspend fun getBody(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", userAgent)
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        } catch (t: Throwable) {
            null
        }
    }
}

object NominatimResponseParser {

    fun parse(json: String): GeocodeResult = try {
        val root = Json.parseToJsonElement(json).jsonArray
        val places = root.mapNotNull { element ->
            val obj = element.jsonObject
            val lat = obj["lat"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = obj["lon"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            val name = obj["display_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Place(name = name, point = GeoPoint(lat, lon))
        }
        GeocodeResult.Success(places)
    } catch (t: Throwable) {
        GeocodeResult.Failure(t.message ?: "Unable to parse search response")
    }

    /** `/reverse?format=json` answers with one object, not an array. */
    fun parseReverse(json: String): Place? = try {
        val obj = Json.parseToJsonElement(json).jsonObject
        val lat = obj["lat"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        val lon = obj["lon"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        val name = obj["display_name"]?.jsonPrimitive?.contentOrNull
        if (lat == null || lon == null || name == null) null else Place(name, GeoPoint(lat, lon))
    } catch (t: Throwable) {
        null
    }
}
