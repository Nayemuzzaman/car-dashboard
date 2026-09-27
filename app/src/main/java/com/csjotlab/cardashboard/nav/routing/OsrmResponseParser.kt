package com.csjotlab.cardashboard.nav.routing

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.domain.RouteStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToLong

/**
 * Maps an OSRM `route/v1` response (`geometries=geojson&steps=true`) into our domain [Route].
 *
 * Pure and transport-independent, like [RoutingResponseParser] for GraphHopper. OSRM step semantics
 * are the ones [com.csjotlab.cardashboard.nav.engine.ManeuverProgressTracker] expects: the maneuver
 * happens at the start of the step and the step's distance is the travel until the next one. A
 * non-`Ok` code, an empty route list or malformed JSON is a [RouteResult.Failure], never a throw.
 */
object OsrmResponseParser {

    fun parse(json: String): RouteResult {
        return try {
            val root = Json.parseToJsonElement(json).jsonObject
            val code = root["code"]?.jsonPrimitive?.contentOrNull
            if (code != "Ok") {
                val message = root["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
                return RouteResult.Failure("OSRM returned ${code ?: "no code"}: $message".trimEnd(':', ' '))
            }
            val routes = root["routes"]?.jsonArray ?: return RouteResult.Failure("No routes in OSRM response")
            if (routes.isEmpty()) return RouteResult.Failure("No route in OSRM response")

            when (val primary = parseRoute(routes[0].jsonObject)) {
                is RouteResult.Success -> RouteResult.Success(
                    route = primary.route,
                    // An alternative that does not parse is simply not offered.
                    alternatives = routes.drop(1).mapNotNull {
                        runCatching { (parseRoute(it.jsonObject) as? RouteResult.Success)?.route }.getOrNull()
                    },
                )
                is RouteResult.Failure -> primary
            }
        } catch (t: Throwable) {
            RouteResult.Failure(t.message ?: "Unable to parse OSRM response")
        }
    }

    private fun parseRoute(route: JsonObject): RouteResult {
        return try {
            val distanceMeters = route["distance"]?.jsonPrimitive?.doubleOrNull
                ?: return RouteResult.Failure("OSRM response is missing distance")
            val durationSeconds = route["duration"]?.jsonPrimitive?.doubleOrNull
                ?: return RouteResult.Failure("OSRM response is missing duration")

            val geometry = lineString(route["geometry"])
                ?: return RouteResult.Failure("OSRM response is missing geometry")
            if (geometry.size < 2) return RouteResult.Failure("OSRM response has too few points")

            val steps = route["legs"]?.jsonArray
                ?.flatMap { leg -> leg.jsonObject["steps"]?.jsonArray ?: JsonArray(emptyList()) }
                ?.map { parseStep(it.jsonObject) }
                ?: return RouteResult.Failure("OSRM response is missing steps")
            if (steps.size < 2) return RouteResult.Failure("OSRM response has too few steps")

            RouteResult.Success(
                Route(
                    origin = geometry.first(),
                    destination = geometry.last(),
                    steps = steps,
                    geometry = geometry,
                    totalDistanceMeters = distanceMeters.toFloat(),
                    totalDurationSeconds = durationSeconds.roundToLong(),
                ),
            )
        } catch (t: Throwable) {
            RouteResult.Failure(t.message ?: "Unable to parse OSRM route")
        }
    }

    private fun parseStep(json: JsonObject): RouteStep {
        val maneuver = json["maneuver"]?.jsonObject
        val type = maneuver?.get("type")?.jsonPrimitive?.contentOrNull
        val modifier = maneuver?.get("modifier")?.jsonPrimitive?.contentOrNull
        val exit = maneuver?.get("exit")?.jsonPrimitive?.intOrNull
        val roadName = json["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return RouteStep(
            maneuver = Maneuver(maneuverType(type, modifier, exit), roadName),
            distanceMeters = json["distance"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 0f,
            durationSeconds = json["duration"]?.jsonPrimitive?.doubleOrNull?.roundToLong() ?: 0L,
            geometry = lineString(json["geometry"]) ?: emptyList(),
            roadName = roadName,
        )
    }

    private fun lineString(element: JsonElement?): List<GeoPoint>? =
        element?.jsonObject?.get("coordinates")?.jsonArray?.map { toPoint(it.jsonArray) }

    private fun toPoint(coordinate: JsonArray): GeoPoint = GeoPoint(
        latitude = coordinate[1].jsonPrimitive.content.toDouble(),
        longitude = coordinate[0].jsonPrimitive.content.toDouble(),
    )

    internal fun maneuverType(type: String?, modifier: String?, exit: Int?): ManeuverType = when (type) {
        "depart" -> ManeuverType.Depart
        "arrive" -> ManeuverType.Arrive
        "roundabout", "rotary", "roundabout turn" -> ManeuverType.Roundabout(exit)
        "exit roundabout", "exit rotary" -> ManeuverType.Continue
        "on ramp" -> when (side(modifier)) {
            Side.Left -> ManeuverType.RampLeft
            Side.Right -> ManeuverType.RampRight
            Side.Straight -> ManeuverType.Continue
        }
        "fork" -> when (side(modifier)) {
            Side.Left -> ManeuverType.KeepLeft
            Side.Right -> ManeuverType.KeepRight
            Side.Straight -> ManeuverType.Continue
        }
        "off ramp" -> ManeuverType.Exit(null)
        "merge" -> ManeuverType.Merge
        "turn", "end of road", "continue", "new name", "notification", "use lane" -> fromModifier(modifier)
        else -> ManeuverType.Unknown
    }

    private enum class Side { Left, Right, Straight }

    private fun side(modifier: String?): Side = when {
        modifier == null -> Side.Straight
        modifier.endsWith("left") -> Side.Left
        modifier.endsWith("right") -> Side.Right
        else -> Side.Straight
    }

    private fun fromModifier(modifier: String?): ManeuverType = when (modifier) {
        null, "straight" -> ManeuverType.Continue
        "uturn" -> ManeuverType.UTurn
        "sharp right" -> ManeuverType.SharpRight
        "right" -> ManeuverType.TurnRight
        "slight right" -> ManeuverType.SlightRight
        "slight left" -> ManeuverType.SlightLeft
        "left" -> ManeuverType.TurnLeft
        "sharp left" -> ManeuverType.SharpLeft
        else -> ManeuverType.Unknown
    }
}
