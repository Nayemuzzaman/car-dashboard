package com.csjotlab.cardashboard.nav.geocoding

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.toLabel
import com.csjotlab.cardashboard.nav.engine.GeoMath

/**
 * Parses a typed coordinate pair: `23.8103, 90.4125`, `23.8103 90.4125`, `33.59°N 130.4°E`,
 * `22.9 S, 43.2 W`. Latitude first, as every map app and GPS readout writes it. At least one of the
 * numbers must have a decimal point or a hemisphere letter, so "12 34" stays an address query.
 */
object CoordinateQueryParser {
    private val pattern = Regex(
        """^\s*(-?\d{1,2}(?:\.\d+)?)\s*°?\s*([NSns])?\s*[,;\s]\s*(-?\d{1,3}(?:\.\d+)?)\s*°?\s*([EWew])?\s*$""",
    )

    fun parse(query: String): GeoPoint? {
        val match = pattern.matchEntire(query) ?: return null
        val (latText, latHemisphere, lonText, lonHemisphere) = match.destructured
        val explicit = latText.contains('.') || lonText.contains('.') || latHemisphere.isNotEmpty() || lonHemisphere.isNotEmpty()
        if (!explicit) return null
        var lat = latText.toDoubleOrNull() ?: return null
        var lon = lonText.toDoubleOrNull() ?: return null
        if (latHemisphere.equals("S", ignoreCase = true)) lat = -kotlin.math.abs(lat)
        if (lonHemisphere.equals("W", ignoreCase = true)) lon = -kotlin.math.abs(lon)
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return GeoPoint(lat, lon)
    }
}

/**
 * Wraps the real geocoder with the answers that need no network: typed coordinates become a place
 * directly, and category results are ordered nearest-first when the driver's position is known.
 */
class CoordinateGeocodingEngine(private val delegate: GeocodingEngine) : GeocodingEngine {

    override suspend fun search(query: String, near: GeoPoint?): GeocodeResult {
        CoordinateQueryParser.parse(query)?.let { point ->
            return GeocodeResult.Success(listOf(Place(point.toLabel(), point, address = "Coordinates", category = COORDINATES)))
        }
        return delegate.search(query, near)
    }

    override suspend fun reverse(point: GeoPoint): Place? = delegate.reverse(point)

    override suspend fun searchCategory(category: PlaceCategory, near: GeoPoint?): GeocodeResult =
        when (val result = delegate.searchCategory(category, near)) {
            is GeocodeResult.Success -> if (near == null) result else {
                GeocodeResult.Success(result.places.sortedBy { GeoMath.distanceMeters(near, it.point) })
            }
            is GeocodeResult.Failure -> result
        }

    companion object {
        const val COORDINATES = "coordinates"
    }
}
