package com.csjotlab.cardashboard.nav.fakes

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.geocoding.GeocodeResult
import com.csjotlab.cardashboard.nav.geocoding.GeocodingEngine
import com.csjotlab.cardashboard.nav.geocoding.Place

class FakeGeocodingEngine(
    var searchResult: GeocodeResult = GeocodeResult.Success(emptyList()),
    var reverseResult: Place? = null,
) : GeocodingEngine {
    val searches = mutableListOf<Pair<String, GeoPoint?>>()
    val reverses = mutableListOf<GeoPoint>()

    override suspend fun search(query: String, near: GeoPoint?): GeocodeResult {
        searches += query to near
        return searchResult
    }

    override suspend fun reverse(point: GeoPoint): Place? {
        reverses += point
        return reverseResult
    }
}
