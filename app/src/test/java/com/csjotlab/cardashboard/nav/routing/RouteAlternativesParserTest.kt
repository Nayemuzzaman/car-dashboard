package com.csjotlab.cardashboard.nav.routing

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteAlternativesParserTest {

    private fun JsonObject.with(key: String, value: kotlinx.serialization.json.JsonElement) = JsonObject(this + (key to value))

    private val osrm = """
        {"code": "Ok", "routes": [{
          "distance": 1000.0, "duration": 90.0,
          "geometry": {"type": "LineString", "coordinates": [[0.0, 0.0], [0.0, 0.009]]},
          "legs": [{"steps": [
            {"distance": 1000.0, "duration": 90.0, "name": "A", "geometry": {"type": "LineString", "coordinates": [[0.0, 0.0], [0.0, 0.009]]},
             "maneuver": {"type": "depart"}},
            {"distance": 0.0, "duration": 0.0, "name": "A", "geometry": {"type": "LineString", "coordinates": [[0.0, 0.009], [0.0, 0.009]]},
             "maneuver": {"type": "arrive"}}
          ]}]
        }]}
    """.trimIndent()

    @Test
    fun `osrm alternatives follow the recommended route`() {
        val root = Json.parseToJsonElement(osrm).jsonObject
        val first = root["routes"]!!.jsonArray[0].jsonObject
        val json = root.with("routes", JsonArray(listOf(first, first.with("distance", JsonPrimitive(1_400.0))))).toString()

        val result = OsrmResponseParser.parse(json) as RouteResult.Success

        assertEquals(1_000f, result.route.totalDistanceMeters)
        assertEquals(listOf(1_400f), result.alternatives.map { it.totalDistanceMeters })
    }

    @Test
    fun `a malformed alternative is dropped without losing the main route`() {
        val root = Json.parseToJsonElement(osrm).jsonObject
        val first = root["routes"]!!.jsonArray[0].jsonObject
        val json = root.with("routes", JsonArray(listOf(first, JsonObject(emptyMap())))).toString()

        val result = OsrmResponseParser.parse(json) as RouteResult.Success

        assertTrue(result.alternatives.isEmpty())
    }

    @Test
    fun `valhalla alternates become alternatives`() {
        val shape = Polyline.encode(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.009, 0.0)), precision = 6)
        fun trip(km: Double) = """
            {"summary": {"has_toll": false, "time": 90.0, "length": $km},
             "legs": [{"shape": "$shape", "maneuvers": [
               {"type": 1, "time": 90.0, "length": $km, "begin_shape_index": 0, "end_shape_index": 1},
               {"type": 4, "time": 0.0, "length": 0.0, "begin_shape_index": 1, "end_shape_index": 1}
             ]}]}
        """.trimIndent()
        val json = """{"trip": ${trip(1.0)}, "alternates": [{"trip": ${trip(1.3)}}, {"trip": {"broken": true}}]}"""

        val result = ValhallaResponseParser.parse(json) as RouteResult.Success

        assertEquals(1_000f, result.route.totalDistanceMeters, 0.1f)
        assertEquals(listOf(1_300f), result.alternatives.map { it.totalDistanceMeters })
    }

    @Test
    fun `the valhalla request asks for alternates`() {
        val body = ValhallaRoutingEngine(baseUrl = "https://example").body(RouteRequest(GeoPoint(0.0, 0.0), GeoPoint(1.0, 1.0)))
        assertTrue(body.contains(""""alternates":2"""))
    }
}
