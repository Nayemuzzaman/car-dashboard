package com.csjotlab.cardashboard.nav.engine

import com.csjotlab.cardashboard.nav.domain.ManeuverType
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Turns a maneuver and its distance into the single large instruction shown to the driver.
 *
 * All formatting lives here, never in the domain. Roundabouts and arrival are complete sentences on
 * their own; every other maneuver is `"<action> in <distance>"` or `"<action> now"` inside the
 * imminent threshold.
 */
object ManeuverPhrasing {
    const val IMMINENT_METERS = 20f

    fun phrase(progress: ManeuverProgress?): String? {
        val p = progress ?: return null

        return when (val type = p.maneuver.type) {
            ManeuverType.Arrive -> "Arrive at destination"
            is ManeuverType.Roundabout -> roundabout(type)
            else -> {
                val action = actionFor(type) ?: return null
                if (p.distanceToManeuverMeters <= IMMINENT_METERS) "$action now"
                else "$action in ${formatDistance(p.distanceToManeuverMeters)}"
            }
        }
    }

    fun formatDistance(meters: Float): String =
        if (meters < 1_000f) "${meters.roundToInt()} m"
        else String.format(Locale.US, "%.1f km", meters / 1_000f)

    /**
     * The banner instruction without a distance — the distance is shown separately and larger.
     * [roadName] is the road the maneuver leads onto; it is only ever used when the router named it.
     */
    fun instruction(type: ManeuverType, roadName: String?): String? {
        val onto = roadName?.takeIf { it.isNotBlank() }?.let { " onto $it" }.orEmpty()
        return when (type) {
            ManeuverType.Arrive -> "Arrive at your destination"
            ManeuverType.Unknown -> null
            ManeuverType.Continue -> roadName?.takeIf { it.isNotBlank() }?.let { "Continue on $it" } ?: "Continue straight"
            ManeuverType.Depart -> roadName?.takeIf { it.isNotBlank() }?.let { "Head out on $it" } ?: "Depart"
            ManeuverType.SlightLeft -> "Slight left$onto"
            ManeuverType.SlightRight -> "Slight right$onto"
            is ManeuverType.Exit -> (type.number?.let { "Take exit $it" } ?: "Take the exit") + if (type.number == null) onto else ""
            is ManeuverType.Roundabout -> roundabout(type) + onto
            else -> actionFor(type)?.let { it + onto }
        }
    }

    private fun roundabout(type: ManeuverType.Roundabout): String =
        type.exitNumber?.let { "At the roundabout, take the ${ordinal(it)} exit" }
            ?: "Enter the roundabout"

    private fun actionFor(type: ManeuverType): String? = when (type) {
        ManeuverType.Depart -> "Depart"
        ManeuverType.Continue -> "Continue straight"
        ManeuverType.TurnLeft -> "Turn left"
        ManeuverType.TurnRight -> "Turn right"
        ManeuverType.SlightLeft -> "Keep left"
        ManeuverType.SlightRight -> "Keep right"
        ManeuverType.SharpLeft -> "Turn sharp left"
        ManeuverType.SharpRight -> "Turn sharp right"
        ManeuverType.UTurn -> "Make a U-turn"
        ManeuverType.KeepLeft -> "Keep left"
        ManeuverType.KeepRight -> "Keep right"
        ManeuverType.Merge -> "Merge"
        ManeuverType.RampLeft -> "Take the ramp on the left"
        ManeuverType.RampRight -> "Take the ramp on the right"
        is ManeuverType.Exit -> type.number?.let { "Take exit $it" } ?: "Take the exit"
        ManeuverType.Unknown -> null
        is ManeuverType.Roundabout, ManeuverType.Arrive -> null // handled above
    }

    private fun ordinal(n: Int): String = when {
        n % 100 in 11..13 -> "${n}th"
        n % 10 == 1 -> "${n}st"
        n % 10 == 2 -> "${n}nd"
        n % 10 == 3 -> "${n}rd"
        else -> "${n}th"
    }
}
