package com.csjotlab.cardashboard.nav.routing

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OsrmResponseParserTest {

    // Shape captured from router.project-osrm.org (route/v1/driving, geometries=geojson, steps=true),
    // trimmed to the fields we read plus the ones we must ignore.
    private val osrmJson = """
        {
          "code": "Ok",
          "waypoints": [{"name": "Midsummer Boulevard", "location": [-0.759418, 52.040553]}],
          "routes": [
            {
              "distance": 1200.5,
              "duration": 95.4,
              "weight_name": "routability",
              "geometry": {
                "type": "LineString",
                "coordinates": [[-0.7594, 52.0405], [-0.7590, 52.0410], [-0.7580, 52.0420], [-0.7570, 52.0430], [-0.7560, 52.0440]]
              },
              "legs": [
                {
                  "steps": [
                    { "distance": 300.0, "duration": 20.0, "name": "Midsummer Boulevard", "mode": "driving",
                      "geometry": {"type": "LineString", "coordinates": [[-0.7594, 52.0405], [-0.7590, 52.0410]]},
                      "maneuver": {"type": "depart", "bearing_after": 326, "bearing_before": 0, "location": [-0.7594, 52.0405]} },
                    { "distance": 400.0, "duration": 30.0, "name": "V7 Saxon Gate", "mode": "driving",
                      "geometry": {"type": "LineString", "coordinates": [[-0.7590, 52.0410], [-0.7580, 52.0420]]},
                      "maneuver": {"type": "turn", "modifier": "right", "location": [-0.7590, 52.0410]} },
                    { "distance": 500.0, "duration": 45.0, "name": "H5 Portway", "mode": "driving",
                      "geometry": {"type": "LineString", "coordinates": [[-0.7580, 52.0420], [-0.7570, 52.0430]]},
                      "maneuver": {"type": "rotary", "modifier": "slight left", "exit": 3, "location": [-0.7580, 52.0420]} },
                    { "distance": 0.5, "duration": 0.4, "name": "H5 Portway", "mode": "driving",
                      "geometry": {"type": "LineString", "coordinates": [[-0.7570, 52.0430], [-0.7560, 52.0440]]},
                      "maneuver": {"type": "exit rotary", "modifier": "slight left", "exit": 3, "location": [-0.7570, 52.0430]} },
                    { "distance": 0.0, "duration": 0.0, "name": "Peterborough Gate", "mode": "driving",
                      "geometry": {"type": "LineString", "coordinates": [[-0.7560, 52.0440], [-0.7560, 52.0440]]},
                      "maneuver": {"type": "arrive", "location": [-0.7560, 52.0440]} }
                  ]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses an osrm route into domain steps with geometry in lat-lon order`() {
        val route = (OsrmResponseParser.parse(osrmJson) as RouteResult.Success).route

        assertEquals(GeoPoint(52.0405, -0.7594), route.origin)
        assertEquals(GeoPoint(52.0440, -0.7560), route.destination)
        assertEquals(5, route.geometry.size)
        assertEquals(1200.5f, route.totalDistanceMeters)
        assertEquals(95L, route.totalDurationSeconds)
        assertFalse(route.isPreview)

        assertEquals(5, route.steps.size)
        assertEquals(ManeuverType.Depart, route.steps[0].maneuver.type)
        assertEquals(300f, route.steps[0].distanceMeters)
        assertEquals(20L, route.steps[0].durationSeconds)
        assertEquals(listOf(GeoPoint(52.0405, -0.7594), GeoPoint(52.0410, -0.7590)), route.steps[0].geometry)

        assertEquals(ManeuverType.TurnRight, route.steps[1].maneuver.type)
        assertEquals("V7 Saxon Gate", route.steps[1].maneuver.instruction)
        assertEquals(ManeuverType.Roundabout(3), route.steps[2].maneuver.type)
        assertEquals(ManeuverType.Continue, route.steps[3].maneuver.type)
        assertEquals(ManeuverType.Arrive, route.steps[4].maneuver.type)
        assertEquals(0f, route.steps[4].distanceMeters)
    }

    @Test
    fun `maps every osrm maneuver type and modifier onto the domain vocabulary`() {
        val map = OsrmResponseParser::maneuverType

        assertEquals(ManeuverType.Depart, map("depart", null, null))
        assertEquals(ManeuverType.Arrive, map("arrive", null, null))
        assertEquals(ManeuverType.TurnLeft, map("turn", "left", null))
        assertEquals(ManeuverType.TurnRight, map("end of road", "right", null))
        assertEquals(ManeuverType.SlightLeft, map("turn", "slight left", null))
        assertEquals(ManeuverType.SlightRight, map("new name", "slight right", null))
        assertEquals(ManeuverType.SharpLeft, map("turn", "sharp left", null))
        assertEquals(ManeuverType.SharpRight, map("turn", "sharp right", null))
        assertEquals(ManeuverType.UTurn, map("turn", "uturn", null))
        assertEquals(ManeuverType.UTurn, map("continue", "uturn", null))
        assertEquals(ManeuverType.Continue, map("continue", "straight", null))
        assertEquals(ManeuverType.Continue, map("new name", null, null))
        assertEquals(ManeuverType.Continue, map("notification", "straight", null))
        assertEquals(ManeuverType.Roundabout(2), map("roundabout", "slight left", 2))
        assertEquals(ManeuverType.Roundabout(1), map("rotary", "straight", 1))
        assertEquals(ManeuverType.Roundabout(null), map("roundabout turn", "left", null))
        assertEquals(ManeuverType.Continue, map("exit roundabout", "slight left", 2))
        assertEquals(ManeuverType.Continue, map("exit rotary", "straight", 1))
        assertEquals(ManeuverType.KeepLeft, map("fork", "slight left", null))
        assertEquals(ManeuverType.KeepRight, map("fork", "right", null))
        assertEquals(ManeuverType.RampRight, map("on ramp", "slight right", null))
        assertEquals(ManeuverType.RampLeft, map("on ramp", "left", null))
        assertEquals(ManeuverType.Exit(null), map("off ramp", "slight right", null))
        assertEquals(ManeuverType.Merge, map("merge", "slight left", null))
        assertEquals(ManeuverType.Unknown, map("something new", "left", null))
        assertEquals(ManeuverType.Unknown, map(null, null, null))
    }

    @Test
    fun `a non-ok code is a failure carrying the server message`() {
        val json = """{"code":"NoRoute","message":"Impossible route between points"}"""

        val result = OsrmResponseParser.parse(json)

        assertTrue(result is RouteResult.Failure)
        assertTrue((result as RouteResult.Failure).reason.contains("NoRoute"))
        assertTrue(result.reason.contains("Impossible route"))
    }

    @Test
    fun `an empty routes array and malformed json are failures not throws`() {
        assertTrue(OsrmResponseParser.parse("""{"code":"Ok","routes":[]}""") is RouteResult.Failure)
        assertTrue(OsrmResponseParser.parse("not json") is RouteResult.Failure)
        assertTrue(OsrmResponseParser.parse("""{"code":"Ok","routes":[{"distance":1,"duration":1,"geometry":{"coordinates":[[0,0]]},"legs":[]}]}""") is RouteResult.Failure)
    }
}
