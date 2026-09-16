package com.csjotlab.cardashboard.nav.routing

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.domain.RouteStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToLong

/**
 * Maps a Valhalla `/route` response (costing `auto`, units kilometers) into our domain [Route].
 *
 * Valhalla is the one open router that says whether a route is tolled (`summary.has_toll`,
 * per-maneuver `toll`) and can be asked to avoid tolls, which is why it sits first in the public
 * chain. Its shape is a precision-6 encoded polyline; each maneuver spans
 * `begin_shape_index..end_shape_index` of it. Errors and malformed JSON become
 * [RouteResult.Failure], never a throw.
 */
object ValhallaResponseParser {

    fun parse(json: String): RouteResult {
        return try {
            val root = Json.parseToJsonElement(json).jsonObject
            root["error"]?.jsonPrimitive?.contentOrNull?.let { return RouteResult.Failure("Valhalla: $it") }
            val trip = root["trip"]?.jsonObject ?: return RouteResult.Failure("No trip in Valhalla response")
            val summary = trip["summary"]?.jsonObject ?: return RouteResult.Failure("Valhalla response is missing summary")
            val lengthKm = summary["length"]?.jsonPrimitive?.doubleOrNull
                ?: return RouteResult.Failure("Valhalla response is missing length")
            val timeSeconds = summary["time"]?.jsonPrimitive?.doubleOrNull
                ?: return RouteResult.Failure("Valhalla response is missing time")
            val hasToll = summary["has_toll"]?.jsonPrimitive?.booleanOrNull

            val legs = trip["legs"]?.jsonArray ?: return RouteResult.Failure("Valhalla response is missing legs")
            if (legs.isEmpty()) return RouteResult.Failure("No leg in Valhalla response")

            val geometry = mutableListOf<GeoPoint>()
            val steps = mutableListOf<RouteStep>()
            legs.forEach { legElement ->
                val leg = legElement.jsonObject
                val shape = Polyline.decode(leg["shape"]?.jsonPrimitive?.contentOrNull.orEmpty(), precision = 6)
                geometry += shape
                leg["maneuvers"]?.jsonArray?.forEach { steps += parseManeuver(it.jsonObject, shape) }
            }
            if (geometry.size < 2) return RouteResult.Failure("Valhalla response has too few points")
            if (steps.size < 2) return RouteResult.Failure("Valhalla response has too few maneuvers")

            RouteResult.Success(
                Route(
                    origin = geometry.first(),
                    destination = geometry.last(),
                    steps = steps,
                    geometry = geometry,
                    totalDistanceMeters = (lengthKm * 1_000.0).toFloat(),
                    totalDurationSeconds = timeSeconds.roundToLong(),
                    hasToll = hasToll,
                ),
            )
        } catch (t: Throwable) {
            RouteResult.Failure(t.message ?: "Unable to parse Valhalla response")
        }
    }

    private fun parseManeuver(json: JsonObject, shape: List<GeoPoint>): RouteStep {
        val type = json["type"]?.jsonPrimitive?.intOrNull ?: 0
        val exitCount = json["roundabout_exit_count"]?.jsonPrimitive?.intOrNull
        val exitNumber = json["sign"]?.jsonObject
            ?.get("exit_number_elements")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
        val begin = (json["begin_shape_index"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, shape.lastIndex.coerceAtLeast(0))
        val end = (json["end_shape_index"]?.jsonPrimitive?.intOrNull ?: begin).coerceIn(begin, shape.lastIndex.coerceAtLeast(0))
        return RouteStep(
            maneuver = Maneuver(
                type = maneuverType(type, exitCount, exitNumber),
                instruction = json["instruction"]?.jsonPrimitive?.contentOrNull,
            ),
            distanceMeters = ((json["length"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1_000.0).toFloat(),
            durationSeconds = (json["time"]?.jsonPrimitive?.doubleOrNull ?: 0.0).roundToLong(),
            geometry = if (shape.isEmpty()) emptyList() else shape.subList(begin, end + 1),
            roadName = json["street_names"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull,
            toll = json["toll"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    /** Valhalla maneuver type codes → domain vocabulary. */
    internal fun maneuverType(type: Int, exitCount: Int?, exitNumber: String?): ManeuverType = when (type) {
        1, 2, 3 -> ManeuverType.Depart
        4, 5, 6 -> ManeuverType.Arrive
        7, 8, 17, 22, 27, 28, 29 -> ManeuverType.Continue
        9 -> ManeuverType.SlightRight
        10 -> ManeuverType.TurnRight
        11 -> ManeuverType.SharpRight
        12, 13 -> ManeuverType.UTurn
        14 -> ManeuverType.SharpLeft
        15 -> ManeuverType.TurnLeft
        16 -> ManeuverType.SlightLeft
        18, 23 -> ManeuverType.KeepRight
        19, 24 -> ManeuverType.KeepLeft
        20, 21 -> ManeuverType.Exit(exitNumber)
        25, 37, 38 -> ManeuverType.Merge
        26 -> ManeuverType.Roundabout(exitCount)
        else -> ManeuverType.Unknown
    }
}
