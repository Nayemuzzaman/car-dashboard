package com.csjotlab.cardashboard.nav.geocoding

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.fakes.FakeGeocodingEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverpassResponseParserTest {

    // Shape captured from overpass-api.de (`out center`): nodes carry lat/lon, ways a center.
    private val json = """
        {"version": 0.6, "elements": [
          {"type": "node", "id": 1, "lat": 33.8701, "lon": 130.7512,
           "tags": {"amenity": "fuel", "name": "ENEOS 本城", "name:en": "ENEOS Honjo", "brand": "ENEOS",
                    "addr:street": "本城", "addr:city": "北九州市"}},
          {"type": "way", "id": 2, "center": {"lat": 33.8610, "lon": 130.7400},
           "tags": {"amenity": "fuel", "brand": "コスモ石油"}},
          {"type": "node", "id": 3, "lat": 33.8650, "lon": 130.7600, "tags": {"amenity": "fuel"}},
          {"type": "node", "id": 4, "tags": {"amenity": "fuel"}}
        ]}
    """.trimIndent()

    @Test
    fun `elements become places named in english, then locally, then by brand, then by kind`() {
        val places = (OverpassResponseParser.parse(json, PlaceCategory.Fuel) as GeocodeResult.Success).places

        assertEquals(listOf("ENEOS Honjo", "コスモ石油", "Fuel station"), places.map { it.name })
        assertEquals(GeoPoint(33.8610, 130.7400), places[1].point)
        assertEquals("本城, 北九州市", places[0].address)
        assertTrue(places.all { it.category == "fuel" })
    }

    @Test
    fun `malformed json is a failure not a throw`() {
        assertTrue(OverpassResponseParser.parse("<html>busy</html>", PlaceCategory.Fuel) is GeocodeResult.Failure)
    }

    @Test
    fun `the query asks for the tag around the point`() {
        val q = OverpassCategorySearch.query(PlaceCategory.Fuel, GeoPoint(33.8661, 130.7509), radiusMeters = 5_000)
        assertTrue(q, q.contains("""nwr["amenity"="fuel"](around:5000,33.8661,130.7509)"""))
        assertTrue(q.contains("out center"))
    }
}

class NearbyCategoryGeocodingEngineTest {

    private val near = GeoPoint(33.8661, 130.7509)
    private fun place(name: String, north: Double) = Place(name, GeoPoint(near.latitude + north, near.longitude))

    @Test
    fun `with a fix, categories come from the nearby search, nearest first`() = runTest {
        val text = FakeGeocodingEngine()
        val engine = NearbyCategoryGeocodingEngine(text) { _, _, _ -> GeocodeResult.Success(listOf(place("Far", 0.03), place("Near", 0.001), place("Mid", 0.01))) }

        val result = engine.searchCategory(PlaceCategory.Fuel, near) as GeocodeResult.Success

        assertEquals(listOf("Near", "Mid", "Far"), result.places.map { it.name })
        assertTrue("the text geocoder is not asked", text.searches.isEmpty())
    }

    @Test
    fun `too few results widen the radius once`() = runTest {
        val radii = mutableListOf<Int>()
        val engine = NearbyCategoryGeocodingEngine(FakeGeocodingEngine()) { _, _, radius ->
            radii += radius
            GeocodeResult.Success(if (radius > 5_000) listOf(place("A", 0.1), place("B", 0.12), place("C", 0.15)) else listOf(place("A", 0.1)))
        }

        val result = engine.searchCategory(PlaceCategory.Hospital, near) as GeocodeResult.Success

        assertEquals(listOf(5_000, 25_000), radii)
        assertEquals(3, result.places.size)
    }

    @Test
    fun `a failing nearby search falls back to the text geocoder`() = runTest {
        val text = FakeGeocodingEngine(searchResult = GeocodeResult.Success(listOf(place("Fallback", 0.0))))
        val engine = NearbyCategoryGeocodingEngine(text) { _, _, _ -> GeocodeResult.Failure("busy") }

        val result = engine.searchCategory(PlaceCategory.Parking, near) as GeocodeResult.Success

        assertEquals("Fallback", result.places.single().name)
    }

    @Test
    fun `far-away text results are not offered as nearby`() = runTest {
        val tripoli = Place("Fuel station", GeoPoint(32.88, 13.19))
        val text = FakeGeocodingEngine(searchResult = GeocodeResult.Success(listOf(tripoli)))
        val engine = NearbyCategoryGeocodingEngine(text) { _, _, _ -> GeocodeResult.Failure("HTTP 504") }

        assertTrue(engine.searchCategory(PlaceCategory.Fuel, near) is GeocodeResult.Failure)
    }

    @Test
    fun `without a fix there is no 'nearby', so nothing is searched`() = runTest {
        var called = false
        val text = FakeGeocodingEngine()
        val result = NearbyCategoryGeocodingEngine(text) { _, _, _ -> called = true; GeocodeResult.Success(emptyList()) }
            .searchCategory(PlaceCategory.Food, null)
        assertTrue(result is GeocodeResult.Failure)
        assertTrue(!called)
        assertTrue(text.searches.isEmpty())
    }
}

class FirstSuccessNearbySearchTest {

    private val near = GeoPoint(33.8661, 130.7509)
    private val found = GeocodeResult.Success(listOf(Place("ENEOS", near)))

    @Test
    fun `the first usable answer wins over overloaded servers`() = runTest {
        val race = FirstSuccessNearbySearch(
            listOf(
                NearbyPlaceSearch { _, _, _ -> GeocodeResult.Failure("HTTP 504") },
                NearbyPlaceSearch { _, _, _ -> kotlinx.coroutines.delay(10_000); found },
                NearbyPlaceSearch { _, _, _ -> kotlinx.coroutines.delay(1_000); found },
            ),
        )
        assertEquals(found, race.search(PlaceCategory.Fuel, near, 5_000))
        assertEquals("did not wait for the slow server", 1_000L, testScheduler.currentTime)
    }

    @Test
    fun `it fails only when every server fails`() = runTest {
        val race = FirstSuccessNearbySearch(listOf(NearbyPlaceSearch { _, _, _ -> GeocodeResult.Failure("a") }, NearbyPlaceSearch { _, _, _ -> GeocodeResult.Failure("b") }))
        assertTrue(race.search(PlaceCategory.Fuel, near, 5_000) is GeocodeResult.Failure)
    }
}
