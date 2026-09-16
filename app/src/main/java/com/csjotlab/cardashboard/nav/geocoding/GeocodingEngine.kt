package com.csjotlab.cardashboard.nav.geocoding

import com.csjotlab.cardashboard.nav.domain.GeoPoint

data class Place(
    val name: String,
    val point: GeoPoint,
    /** Display-only address line, e.g. "Lane 11 East, Dhaka, Bangladesh". */
    val address: String? = null,
    /** OSM feature value, e.g. "aerodrome", "station", "restaurant". */
    val category: String? = null,
)

sealed interface GeocodeResult {
    data class Success(val places: List<Place>) : GeocodeResult
    data class Failure(val reason: String) : GeocodeResult
}

/**
 * Turns free-text place queries into coordinates and coordinates back into names. Photon is the
 * open default; Nominatim is an alternative behind the same seam.
 */
interface GeocodingEngine {
    /** [near] biases ranking toward the driver's position; null when there is no fix. */
    suspend fun search(query: String, near: GeoPoint?): GeocodeResult

    /** Null when nothing is known about the point or the request failed — never a throw. */
    suspend fun reverse(point: GeoPoint): Place?
}
