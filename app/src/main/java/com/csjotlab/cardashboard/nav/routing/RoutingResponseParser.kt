package com.csjotlab.cardashboard.nav.routing

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.domain.RouteStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Maps a GraphHopper Directions API response into our domain [Route].
 *
 * The parser is deliberately independent of the transport: it reads a JSON string and returns a
 * [RouteResult], so it can be tested on the JVM and reused by any HTTP client or an on-device
 * engine. A malformed or empty response is a [RouteResult.Failure], never a throw.
 */
object RoutingResponseParser {

    fun parse(json: String): RouteResult {
        return try {
            val root = Json.parseToJsonElement(json).jsonObject
            val paths = root["paths"]?.jsonArray ?: return RouteResult.Failure("No paths in routing response")
            if (paths.isEmpty()) return RouteResult.Failure("No path in routing response")

            val path = paths[0].jsonObject
            val distanceMeters = path["distance"]?.jsonPrimitive?.doubleOrNull
                ?: return RouteResult.Failure("Routing response is missing distance")
            val timeMs = path["time"]?.jsonPrimitive?.longOrNull
                ?: return RouteResult.Failure("Routing response is missing time")

            val coordinates = path["points"]?.jsonObject?.get("coordinates")?.jsonArray
                ?: return RouteResult.Failure("Routing response is missing points")
            val geometry = coordinates.map { toPoint(it.jsonArray) }
            if (geometry.size < 2) return RouteResult.Failure("Routing response has too few points")

            val instructions = path["instructions"]?.jsonArray
                ?: return RouteResult.Failure("Routing response is missing instructions")
            if (instructions.size < 2) return RouteResult.Failure("Routing response has too few instructions")

            val steps = instructions.mapIndexed { index, element ->
                parseStep(element.jsonObject, geometry, index, instructions.lastIndex)
            }

            RouteResult.Success(
                Route(
                    origin = geometry.first(),
                    destination = geometry.last(),
                    steps = steps,
                    geometry = geometry,
                    totalDistanceMeters = distanceMeters.toFloat(),
                    totalDurationSeconds = timeMs / 1_000L,
                ),
            )
        } catch (t: Throwable) {
            RouteResult.Failure(t.message ?: "Unable to parse routing response")
        }
    }

    private fun parseStep(json: JsonObject, geometry: List<GeoPoint>, index: Int, lastIndex: Int): RouteStep {
        val distanceMeters = json["distance"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 0f
        val durationSeconds = (json["time"]?.jsonPrimitive?.longOrNull ?: 0L) / 1_000L
        val sign = json["sign"]?.jsonPrimitive?.intOrNull ?: -99
        val exitNumber = json["exit_number"]?.jsonPrimitive?.intOrNull
        val text = json["text"]?.jsonPrimitive?.contentOrNull

        val maneuver = when (index) {
            0 -> Maneuver(ManeuverType.Depart, text)
            lastIndex -> Maneuver(ManeuverType.Arrive, text)
            else -> Maneuver(maneuverType(sign, exitNumber), text)
        }

        return RouteStep(
            maneuver = maneuver,
            // The final arrival point has no length.
            distanceMeters = if (index == lastIndex) 0f else distanceMeters,
            durationSeconds = if (index == lastIndex) 0L else durationSeconds,
            geometry = intervalGeometry(json["interval"] as? JsonArray, geometry),
            roadName = json["street_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
        )
    }

    private fun toPoint(coordinate: JsonArray): GeoPoint = GeoPoint(
        latitude = coordinate[1].jsonPrimitive.content.toDouble(),
        longitude = coordinate[0].jsonPrimitive.content.toDouble(),
    )

    private fun intervalGeometry(interval: JsonArray?, geometry: List<GeoPoint>): List<GeoPoint> {
        if (interval == null || interval.size < 2) return emptyList()
        val start = interval[0].jsonPrimitive.content.toInt().coerceIn(0, geometry.lastIndex)
        val end = interval[1].jsonPrimitive.content.toInt().coerceIn(start, geometry.lastIndex)
        return geometry.subList(start, end + 1)
    }

    private fun maneuverType(sign: Int, exitNumber: Int?): ManeuverType = when (sign) {
        -8, -9, -98 -> ManeuverType.UTurn
        -7 -> ManeuverType.KeepLeft
        -6 -> ManeuverType.Roundabout(null)
        -3 -> ManeuverType.SharpLeft
        -2 -> ManeuverType.TurnLeft
        -1 -> ManeuverType.SlightLeft
        0 -> ManeuverType.Continue
        1 -> ManeuverType.SlightRight
        2 -> ManeuverType.TurnRight
        3 -> ManeuverType.SharpRight
        4 -> ManeuverType.Arrive
        6 -> ManeuverType.Roundabout(exitNumber)
        else -> ManeuverType.Unknown
    }
}
