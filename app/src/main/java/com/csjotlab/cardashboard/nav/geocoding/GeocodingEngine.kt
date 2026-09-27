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
 * Quick-search categories. [query] is the text sent to a free-text geocoder; [osmTag] is the
 * OpenStreetMap tag a nearby search looks for; [singular] names a place that has no name of its own.
 */
enum class PlaceCategory(val label: String, val query: String, val osmTag: String, val singular: String) {
    Fuel("Fuel", "fuel station", "amenity:fuel", "Fuel station"),
    Convenience("Convenience", "convenience store", "shop:convenience", "Convenience store"),
    Parking("Parking", "parking", "amenity:parking", "Parking"),
    Food("Food", "restaurant", "amenity:restaurant", "Restaurant"),
    Hospital("Hospital", "hospital", "amenity:hospital", "Hospital"),
    Charging("Charging", "charging station", "amenity:charging_station", "Charging station"),
    Hotel("Hotel", "hotel", "tourism:hotel", "Hotel"),
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

    /** Places of one kind near [near]. The default is a plain text search for the category. */
    suspend fun searchCategory(category: PlaceCategory, near: GeoPoint?): GeocodeResult = search(category.query, near)
}
