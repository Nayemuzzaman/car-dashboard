package com.csjotlab.cardashboard.nav.domain

/**
 * The maneuver vocabulary a route can express.
 *
 * Sealed so the set is deliberate: a new maneuver requires editing the [ManeuverType] hierarchy and
 * the unit test that pins it, not merely adding a string somewhere.
 */
sealed interface ManeuverType {
    data object Depart : ManeuverType
    data object Arrive : ManeuverType
    data object Continue : ManeuverType
    data object TurnLeft : ManeuverType
    data object TurnRight : ManeuverType
    data object SlightLeft : ManeuverType
    data object SlightRight : ManeuverType
    data object SharpLeft : ManeuverType
    data object SharpRight : ManeuverType
    data object UTurn : ManeuverType
    data class Roundabout(val exitNumber: Int?) : ManeuverType
    data class Exit(val number: String?) : ManeuverType
    data object KeepLeft : ManeuverType
    data object KeepRight : ManeuverType
    data object Merge : ManeuverType
    /** Highway entrance ramp on the left / right. */
    data object RampLeft : ManeuverType
    data object RampRight : ManeuverType
    data object Unknown : ManeuverType
}

data class Maneuver(
    val type: ManeuverType,
    /** Optional routing-engine text; never used verbatim for in-car phrasing. */
    val instruction: String?,
)
