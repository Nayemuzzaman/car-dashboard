package com.csjotlab.cardashboard.nav.routing

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValhallaResponseParserTest {

    // Six points, precision 6, encoded with our own codec so the fixture is self-consistent.
    private val shapePoints = listOf(
        GeoPoint(33.8869, 130.8826), GeoPoint(33.8870, 130.8830), GeoPoint(33.8875, 130.8840),
        GeoPoint(33.8880, 130.8850), GeoPoint(33.8890, 130.8860), GeoPoint(33.8900, 130.8870),
    )
    private val shape = Polyline.encode(shapePoints, precision = 6)

    // Field shapes captured from valhalla1.openstreetmap.de (costing auto, units kilometers).
    private fun response(hasToll: Boolean = true) = """
        {
          "trip": {
            "status": 0, "status_message": "Found route between points", "units": "kilometers", "language": "en-US",
            "summary": {"has_toll": $hasToll, "has_highway": true, "has_ferry": false, "time": 305.5, "length": 3.2004},
            "legs": [
              {
                "summary": {"time": 305.5, "length": 3.2004},
                "shape": "$shape",
                "maneuvers": [
                  {"type": 1, "instruction": "Drive east.", "street_names": ["市道浅野31号線"], "time": 10.2, "length": 0.25,
                   "begin_shape_index": 0, "end_shape_index": 1, "travel_mode": "drive"},
                  {"type": 19, "instruction": "Take the ramp.", "street_names": ["Kitakyusyu Expressway Route 2"], "time": 92.2, "length": 0.501,
                   "begin_shape_index": 1, "end_shape_index": 2, "toll": true},
                  {"type": 26, "instruction": "Enter the roundabout and take the 3rd exit.", "street_names": ["North Saxon Roundabout"], "time": 16.9, "length": 0.091,
                   "begin_shape_index": 2, "end_shape_index": 3, "roundabout_exit_count": 3},
                  {"type": 20, "instruction": "Take exit 201 on the right.", "street_names": ["呉服町ランプ出口"], "time": 206.0, "length": 1.309,
                   "begin_shape_index": 3, "end_shape_index": 4, "toll": true,
                   "sign": {"exit_number_elements": [{"text": "201"}], "exit_name_elements": [{"text": "Gofukumachi Exit"}]}},
                  {"type": 4, "instruction": "You have arrived at your destination.", "time": 0.0, "length": 0.0,
                   "begin_shape_index": 5, "end_shape_index": 5}
                ]
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `parses a valhalla trip into a route with toll flags, road names and decoded geometry`() {
        val route = (ValhallaResponseParser.parse(response()) as RouteResult.Success).route

        assertEquals(6, route.geometry.size)
        assertEquals(33.8869, route.origin.latitude, 1e-6)
        assertEquals(130.8870, route.destination.longitude, 1e-6)
        assertEquals(3200.4f, route.totalDistanceMeters, 0.1f)
        assertEquals(306L, route.totalDurationSeconds)
        assertEquals(true, route.hasToll)
        assertFalse(route.isPreview)

        assertEquals(5, route.steps.size)
        assertEquals(ManeuverType.Depart, route.steps[0].maneuver.type)
        assertEquals("市道浅野31号線", route.steps[0].roadName)
        assertEquals(250f, route.steps[0].distanceMeters, 0.01f)
        assertEquals(10L, route.steps[0].durationSeconds)
        assertEquals(listOf(shapePoints[0], shapePoints[1]).map { it.latitude }, route.steps[0].geometry.map { it.latitude })
        assertFalse(route.steps[0].toll)

        assertEquals(ManeuverType.RampLeft, route.steps[1].maneuver.type)
        assertTrue(route.steps[1].toll)
        assertEquals("Kitakyusyu Expressway Route 2", route.steps[1].roadName)

        assertEquals(ManeuverType.Roundabout(3), route.steps[2].maneuver.type)
        assertEquals(ManeuverType.Exit("201"), route.steps[3].maneuver.type)
        assertTrue(route.steps[3].toll)

        assertEquals(ManeuverType.Arrive, route.steps[4].maneuver.type)
        assertEquals(0f, route.steps[4].distanceMeters)
        assertEquals("Take the ramp.", route.steps[1].maneuver.instruction)
    }

    @Test
    fun `a toll-free trip reports hasToll false and no tolled steps`() {
        val route = (ValhallaResponseParser.parse(response(hasToll = false)) as RouteResult.Success).route
        assertEquals(false, route.hasToll)
    }

    @Test
    fun `maps every valhalla maneuver type onto the domain vocabulary`() {
        val map = { type: Int -> ValhallaResponseParser.maneuverType(type, exitCount = null, exitNumber = null) }

        assertEquals(ManeuverType.Depart, map(1)); assertEquals(ManeuverType.Depart, map(2)); assertEquals(ManeuverType.Depart, map(3))
        assertEquals(ManeuverType.Arrive, map(4)); assertEquals(ManeuverType.Arrive, map(5)); assertEquals(ManeuverType.Arrive, map(6))
        assertEquals(ManeuverType.Continue, map(7)); assertEquals(ManeuverType.Continue, map(8)); assertEquals(ManeuverType.Continue, map(17)); assertEquals(ManeuverType.Continue, map(22))
        assertEquals(ManeuverType.SlightRight, map(9)); assertEquals(ManeuverType.TurnRight, map(10)); assertEquals(ManeuverType.SharpRight, map(11))
        assertEquals(ManeuverType.UTurn, map(12)); assertEquals(ManeuverType.UTurn, map(13))
        assertEquals(ManeuverType.SharpLeft, map(14)); assertEquals(ManeuverType.TurnLeft, map(15)); assertEquals(ManeuverType.SlightLeft, map(16))
        assertEquals(ManeuverType.RampRight, map(18)); assertEquals(ManeuverType.RampLeft, map(19))
        assertEquals(ManeuverType.Exit(null), map(20)); assertEquals(ManeuverType.Exit(null), map(21))
        assertEquals(ManeuverType.KeepRight, map(23)); assertEquals(ManeuverType.KeepLeft, map(24))
        assertEquals(ManeuverType.Merge, map(25)); assertEquals(ManeuverType.Merge, map(37)); assertEquals(ManeuverType.Merge, map(38))
        assertEquals(ManeuverType.Roundabout(null), map(26)); assertEquals(ManeuverType.Continue, map(27))
        assertEquals(ManeuverType.Continue, map(28)); assertEquals(ManeuverType.Continue, map(29))
        assertEquals(ManeuverType.Unknown, map(30)); assertEquals(ManeuverType.Unknown, map(0)); assertEquals(ManeuverType.Unknown, map(99))
        assertEquals(ManeuverType.Roundabout(2), ValhallaResponseParser.maneuverType(26, exitCount = 2, exitNumber = null))
        assertEquals(ManeuverType.Exit("7"), ValhallaResponseParser.maneuverType(21, exitCount = null, exitNumber = "7"))
    }

    @Test
    fun `errors and malformed responses are failures not throws`() {
        val error = ValhallaResponseParser.parse("""{"error_code":442,"error":"No path could be found for input","status_code":400,"status":"Bad Request"}""")
        assertTrue(error is RouteResult.Failure)
        assertTrue((error as RouteResult.Failure).reason.contains("No path could be found"))

        assertTrue(ValhallaResponseParser.parse("nope") is RouteResult.Failure)
        assertTrue(ValhallaResponseParser.parse("""{"trip":{"legs":[]}}""") is RouteResult.Failure)
        assertNull((ValhallaResponseParser.parse("""{"trip":{"summary":{"length":1,"time":1},"legs":[{"shape":"","maneuvers":[]}]}}""") as? RouteResult.Success))
    }
}

class ValhallaRoutingEngineBodyTest {
    private val request = RouteRequest(GeoPoint(33.8869, 130.8826), GeoPoint(33.5904, 130.4017))

    @Test
    fun `request body carries both points and only adds the toll option when asked`() {
        val engine = ValhallaRoutingEngine(baseUrl = "https://example")

        val plain = engine.body(request)
        val avoiding = engine.body(request.copy(avoidTolls = true))

        assertTrue(plain.contains(""""lat":33.8869""") && plain.contains(""""lon":130.4017"""))
        assertTrue(plain.contains(""""costing":"auto""""))
        assertFalse(plain.contains("use_tolls"))
        assertTrue(avoiding.contains(""""use_tolls":0"""))
    }
}
