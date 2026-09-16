package com.csjotlab.cardashboard.nav.routing

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingResponseParserTest {

    private val graphHopperJson = """
        {
          "paths": [
            {
              "distance": 700.0,
              "time": 90000,
              "points": {
                "type": "LineString",
                "coordinates": [[0.0, 0.0], [0.0, 0.0045], [0.0, 0.0063]]
              },
              "instructions": [
                { "distance": 500.0, "time": 60000, "sign": 0, "text": "Continue", "interval": [0, 1] },
                { "distance": 200.0, "time": 30000, "sign": -2, "text": "Turn left", "interval": [1, 2] },
                { "distance": 0.0, "time": 0, "sign": 4, "text": "Finish", "interval": [2, 2] }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses a graphhopper route into domain steps with departure and arrival`() {
        val result = RoutingResponseParser.parse(graphHopperJson) as RouteResult.Success
        val route = result.route

        assertEquals(GeoPoint(0.0, 0.0), route.origin)
        assertEquals(GeoPoint(0.0063, 0.0), route.destination)
        assertEquals(700f, route.totalDistanceMeters)
        assertEquals(90L, route.totalDurationSeconds)
        assertEquals(3, route.steps.size)

        assertEquals(ManeuverType.Depart, route.steps[0].maneuver.type)
        assertEquals(500f, route.steps[0].distanceMeters)
        assertEquals(60L, route.steps[0].durationSeconds)

        assertEquals(ManeuverType.TurnLeft, route.steps[1].maneuver.type)
        assertEquals(200f, route.steps[1].distanceMeters)

        assertEquals(ManeuverType.Arrive, route.steps[2].maneuver.type)
        assertEquals(0f, route.steps[2].distanceMeters)
    }

    @Test
    fun `maps roundabout and u-turn maneuver codes`() {
        val json = """
            {
              "paths": [
                {
                  "distance": 1000.0,
                  "time": 120000,
                  "points": {
                    "type": "LineString",
                    "coordinates": [[0.0, 0.0], [0.003, 0.0], [0.006, 0.0], [0.009, 0.0]]
                  },
                  "instructions": [
                    { "distance": 300.0, "time": 40000, "sign": 0, "interval": [0, 1] },
                    { "distance": 300.0, "time": 40000, "sign": 6, "exit_number": 2, "interval": [1, 2] },
                    { "distance": 400.0, "time": 40000, "sign": -8, "interval": [2, 3] },
                    { "distance": 0.0, "time": 0, "sign": 4, "interval": [3, 3] }
                  ]
                }
              ]
            }
        """.trimIndent()

        val route = (RoutingResponseParser.parse(json) as RouteResult.Success).route

        assertEquals(ManeuverType.Roundabout(2), route.steps[1].maneuver.type)
        assertEquals(ManeuverType.UTurn, route.steps[2].maneuver.type)
        assertEquals(ManeuverType.Arrive, route.steps[3].maneuver.type)
    }

    @Test
    fun `malformed json is a failure not a throw`() {
        assertTrue(RoutingResponseParser.parse("not json") is RouteResult.Failure)
    }

    @Test
    fun `an empty paths list is a failure`() {
        assertTrue(RoutingResponseParser.parse("""{"paths":[]}""") is RouteResult.Failure)
    }
}
