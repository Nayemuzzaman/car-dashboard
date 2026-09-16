package com.csjotlab.cardashboard.nav.geocoding

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotonResponseParserTest {

    // Shape captured from photon.komoot.io/api/?q=dhaka%20airport&lang=en, trimmed.
    private val photonJson = """
        {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "geometry": {"type": "Point", "coordinates": [90.4053032, 23.8431441]},
              "properties": {
                "osm_type": "W", "osm_id": 1, "osm_key": "aeroway", "osm_value": "aerodrome",
                "name": "Hazrat Shahjalal International Airport",
                "city": "Dhaka", "country": "Bangladesh", "countrycode": "BD", "type": "house"
              }
            },
            {
              "type": "Feature",
              "geometry": {"type": "Point", "coordinates": [90.4081911, 23.8523202]},
              "properties": {
                "osm_key": "highway", "osm_value": "residential",
                "street": "Lane 11 East", "housenumber": "7", "city": "Dhaka", "country": "Bangladesh"
              }
            },
            {
              "type": "Feature",
              "geometry": {"type": "Point", "coordinates": [90.1, 23.9]},
              "properties": {"osm_key": "place", "osm_value": "locality", "country": "Bangladesh"}
            },
            {
              "type": "Feature",
              "geometry": {"type": "Point", "coordinates": ["bad"]},
              "properties": {"name": "Broken"}
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses named places with structured address and category`() {
        val places = (PhotonResponseParser.parse(photonJson) as GeocodeResult.Success).places

        assertEquals(3, places.size)
        assertEquals("Hazrat Shahjalal International Airport", places[0].name)
        assertEquals(GeoPoint(23.8431441, 90.4053032), places[0].point)
        assertEquals("Dhaka, Bangladesh", places[0].address)
        assertEquals("aerodrome", places[0].category)
    }

    @Test
    fun `an unnamed street result uses the street as its name and does not repeat it in the address`() {
        val places = (PhotonResponseParser.parse(photonJson) as GeocodeResult.Success).places

        assertEquals("Lane 11 East 7", places[1].name)
        assertEquals("Dhaka, Bangladesh", places[1].address)
        assertEquals("residential", places[1].category)
    }

    @Test
    fun `a result with nothing to name it falls back to its coordinates`() {
        val places = (PhotonResponseParser.parse(photonJson) as GeocodeResult.Success).places

        assertEquals("23.9000, 90.1000", places[2].name)
        assertEquals("Bangladesh", places[2].address)
    }

    @Test
    fun `a feature without usable coordinates is skipped not fatal`() {
        val places = (PhotonResponseParser.parse(photonJson) as GeocodeResult.Success).places
        assertTrue(places.none { it.name == "Broken" })
    }

    @Test
    fun `malformed json and a missing features array are failures`() {
        assertTrue(PhotonResponseParser.parse("nope") is GeocodeResult.Failure)
        assertTrue(PhotonResponseParser.parse("""{"type":"FeatureCollection"}""") is GeocodeResult.Failure)
        assertNull((PhotonResponseParser.parse("""{"features":[]}""") as GeocodeResult.Success).places.firstOrNull())
    }
}
