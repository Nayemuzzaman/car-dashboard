package com.csjotlab.cardashboard.nav.geocoding

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.fakes.FakeGeocodingEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateQueryParserTest {

    @Test
    fun `decimal pairs in the common notations parse`() {
        assertEquals(GeoPoint(23.8103, 90.4125), CoordinateQueryParser.parse("23.8103, 90.4125"))
        assertEquals(GeoPoint(23.8103, 90.4125), CoordinateQueryParser.parse("  23.8103 90.4125 "))
        assertEquals(GeoPoint(-33.8688, 151.2093), CoordinateQueryParser.parse("-33.8688,151.2093"))
        assertEquals(GeoPoint(33.59, 130.4), CoordinateQueryParser.parse("33.59°N 130.4°E"))
        assertEquals(GeoPoint(-22.9, -43.2), CoordinateQueryParser.parse("22.9 S, 43.2 W"))
    }

    @Test
    fun `text, out-of-range values and single numbers are not coordinates`() {
        assertNull(CoordinateQueryParser.parse("Route 3"))
        assertNull(CoordinateQueryParser.parse("91.0, 10.0"))
        assertNull(CoordinateQueryParser.parse("10.0, 181.0"))
        assertNull(CoordinateQueryParser.parse("221B"))
        assertNull(CoordinateQueryParser.parse("12 34"), ) // two bare integers read as an address, not a place on earth
    }
}

class CoordinateGeocodingEngineTest {

    @Test
    fun `a coordinate query is answered locally without the network`() = runTest {
        val delegate = FakeGeocodingEngine()
        val engine = CoordinateGeocodingEngine(delegate)

        val result = engine.search("23.8103, 90.4125", near = null) as GeocodeResult.Success

        assertEquals(GeoPoint(23.8103, 90.4125), result.places.single().point)
        assertEquals("coordinates", result.places.single().category)
        assertTrue(delegate.searches.isEmpty())
    }

    @Test
    fun `anything else goes to the real geocoder`() = runTest {
        val delegate = FakeGeocodingEngine()
        CoordinateGeocodingEngine(delegate).search("hospital", near = null)
        assertEquals(listOf("hospital" to null), delegate.searches)
    }

    @Test
    fun `category searches are sorted nearest first when there is a fix`() = runTest {
        val near = GeoPoint(0.0, 0.0)
        val far = Place("Far", GeoPoint(0.05, 0.0))
        val close = Place("Close", GeoPoint(0.001, 0.0))
        val delegate = FakeGeocodingEngine(searchResult = GeocodeResult.Success(listOf(far, close)))

        val result = CoordinateGeocodingEngine(delegate).searchCategory(PlaceCategory.Fuel, near) as GeocodeResult.Success

        assertEquals(listOf("Close", "Far"), result.places.map { it.name })
    }
}
