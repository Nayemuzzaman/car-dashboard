package com.csjotlab.cardashboard.nav.geocoding

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.engine.GeoMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** "Places of [category] within [radiusMeters] of [near]". */
fun interface NearbyPlaceSearch {
    suspend fun search(category: PlaceCategory, near: GeoPoint, radiusMeters: Int): GeocodeResult
}

/**
 * Nearby places by OSM tag from an Overpass API server (open, no key; the public default is
 * overpass-api.de, overridable for a self-hosted instance).
 *
 * A text geocoder is the wrong tool for "nearest fuel": it ranks by name match, so stations
 * literally *named* "Fuel station" on another continent outrank the ENEOS round the corner. Overpass
 * answers the actual question — every `amenity=fuel` within a radius.
 */
class OverpassCategorySearch(
    private val baseUrl: String,
    private val userAgent: String = "CarDashboard/1.0 (com.csjotlab.cardashboard)",
) : NearbyPlaceSearch {

    override suspend fun search(category: PlaceCategory, near: GeoPoint, radiusMeters: Int): GeocodeResult = withContext(Dispatchers.IO) {
        try {
            val connection = URL("$baseUrl/api/interpreter").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.connectTimeout = 10_000
                connection.readTimeout = 20_000
                connection.outputStream.bufferedWriter().use { it.write("data=" + URLEncoder.encode(query(category, near, radiusMeters), "UTF-8")) }
                if (connection.responseCode >= 400) {
                    Log.w(TAG, "Overpass HTTP ${connection.responseCode} for ${category.name} within $radiusMeters m")
                    return@withContext GeocodeResult.Failure("Overpass returned HTTP ${connection.responseCode}")
                }
                OverpassResponseParser.parse(connection.inputStream.bufferedReader().use { it.readText() }, category)
                    .also { if (it is GeocodeResult.Failure) Log.w(TAG, "Overpass response unusable: ${it.reason}") }
            } finally {
                connection.disconnect()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Overpass request failed: $t")
            GeocodeResult.Failure(t.message ?: "Nearby search failed")
        }
    }

    companion object {
        private const val TAG = "CarDash/Nav"
        const val MAX_RESULTS = 20

        fun query(category: PlaceCategory, near: GeoPoint, radiusMeters: Int): String {
            val (key, value) = category.osmTag.split(':', limit = 2)
            return """[out:json][timeout:15];nwr["$key"="$value"](around:$radiusMeters,${near.latitude},${near.longitude});out center $MAX_RESULTS;"""
        }
    }
}

/** Pure Overpass JSON → [Place] mapping. */
object OverpassResponseParser {

    fun parse(json: String, category: PlaceCategory): GeocodeResult {
        return try {
            val elements = Json.parseToJsonElement(json).jsonObject["elements"]?.jsonArray
                ?: return GeocodeResult.Failure("No elements in Overpass response")
            GeocodeResult.Success(elements.mapNotNull { runCatching { toPlace(it.jsonObject, category) }.getOrNull() })
        } catch (t: Throwable) {
            GeocodeResult.Failure(t.message ?: "Unable to parse Overpass response")
        }
    }

    private fun toPlace(element: JsonObject, category: PlaceCategory): Place? {
        val center = element["center"]?.jsonObject
        val lat = (element["lat"] ?: center?.get("lat"))?.jsonPrimitive?.doubleOrNull ?: return null
        val lon = (element["lon"] ?: center?.get("lon"))?.jsonPrimitive?.doubleOrNull ?: return null
        val tags = element["tags"]?.jsonObject ?: JsonObject(emptyMap())
        fun tag(key: String) = tags[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        // The English brand ("FamilyMart", "Lawson") beats a local-script name the driver may not read;
        // the branch ("八幡本城店") then tells two stores of the same chain apart.
        val name = tag("name:en") ?: tag("brand:en") ?: tag("name") ?: tag("brand") ?: category.singular
        val street = tag("addr:street")?.let { street -> tag("addr:housenumber")?.let { "$street $it" } ?: street }
        val address = listOfNotNull(tag("branch"), street, tag("addr:city"))
            .joinToString(", ").takeIf { it.isNotBlank() }
        return Place(name = name, point = GeoPoint(lat, lon), address = address, category = category.osmTag.substringAfter(':'))
    }
}

/**
 * Asks every server at once and returns the first usable answer. Public Overpass servers are
 * volunteer-run and often slow or overloaded (504); racing them keeps the wait to the fastest one
 * instead of the sum of the timeouts. Fails only when all of them fail.
 */
class FirstSuccessNearbySearch(private val servers: List<NearbyPlaceSearch>) : NearbyPlaceSearch {

    init {
        require(servers.isNotEmpty()) { "at least one nearby-search server is needed" }
    }

    override suspend fun search(category: PlaceCategory, near: GeoPoint, radiusMeters: Int): GeocodeResult = coroutineScope {
        val answers = Channel<GeocodeResult>(servers.size)
        val jobs = servers.map { server -> launch { answers.send(server.search(category, near, radiusMeters)) } }
        var last: GeocodeResult = GeocodeResult.Failure("No nearby-search server answered")
        repeat(servers.size) {
            val answer = answers.receive()
            if (answer is GeocodeResult.Success) {
                jobs.forEach { it.cancel() }
                return@coroutineScope answer
            }
            last = answer
        }
        last
    }
}

/**
 * Category buttons answered by a [NearbyPlaceSearch] around the car (5 km, widened once to 25 km
 * when that finds fewer than three), nearest first. If the nearby search fails, the text geocoder is
 * tried — but only its results within [FALLBACK_MAX_METERS] are kept: a text geocoder ranks by name,
 * and "Fuel station" on another continent is not an answer to "fuel near me". Nothing near is an
 * honest failure. Without a fix there is no "near": that is a failure too, never a world search.
 */
class NearbyCategoryGeocodingEngine(
    private val delegate: GeocodingEngine,
    private val nearby: NearbyPlaceSearch,
) : GeocodingEngine by delegate {

    override suspend fun searchCategory(category: PlaceCategory, near: GeoPoint?): GeocodeResult {
        if (near == null) return GeocodeResult.Failure("Nearby search needs your location")
        var result = nearby.search(category, near, NEAR_RADIUS_METERS)
        if (result is GeocodeResult.Success && result.places.size < MIN_RESULTS) {
            val wider = nearby.search(category, near, WIDE_RADIUS_METERS)
            if (wider is GeocodeResult.Success) result = wider
        }
        return when (result) {
            is GeocodeResult.Success -> GeocodeResult.Success(result.places.sortedBy { GeoMath.distanceMeters(near, it.point) })
            is GeocodeResult.Failure -> when (val text = delegate.searchCategory(category, near)) {
                is GeocodeResult.Success -> text.places
                    .filter { GeoMath.distanceMeters(near, it.point) <= FALLBACK_MAX_METERS }
                    .sortedBy { GeoMath.distanceMeters(near, it.point) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { GeocodeResult.Success(it) }
                    ?: GeocodeResult.Failure(result.reason)
                is GeocodeResult.Failure -> text
            }
        }
    }

    private companion object {
        const val NEAR_RADIUS_METERS = 5_000
        const val WIDE_RADIUS_METERS = 25_000
        const val MIN_RESULTS = 3
        const val FALLBACK_MAX_METERS = 50_000.0
    }
}
