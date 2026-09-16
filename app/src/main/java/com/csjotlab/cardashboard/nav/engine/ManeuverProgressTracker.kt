package com.csjotlab.cardashboard.nav.engine

import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.Route

data class ManeuverProgress(
    val maneuver: Maneuver,
    val distanceToManeuverMeters: Float,
)

/**
 * Finds the next maneuver still ahead of (or now at) the traveller.
 *
 * A route's steps are ordered; each step's maneuver happens at its start (cumulative distance), and
 * its [com.csjotlab.cardashboard.nav.domain.RouteStep.distanceMeters] is how far you travel before
 * the next step's maneuver. The first step is the departure point and is never reported as upcoming.
 * The final step is the arrival point with distance 0.
 */
object ManeuverProgressTracker {

    fun progressAt(route: Route, distanceAlongRouteMeters: Float): ManeuverProgress? {
        if (route.steps.isEmpty()) return null

        val traveled = distanceAlongRouteMeters.coerceAtLeast(0f)

        // The departure maneuver is at the origin; it is already behind the driver, so only the
        // steps after it are ever "next".
        var cumulative = route.steps.first().distanceMeters

        for (step in route.steps.drop(1)) {
            // `<=` rather than `<`: a maneuver reached exactly is "now", not already passed.
            if (traveled <= cumulative) {
                return ManeuverProgress(
                    maneuver = step.maneuver,
                    distanceToManeuverMeters = (cumulative - traveled).coerceAtLeast(0f),
                )
            }
            cumulative += step.distanceMeters
        }

        // Past the final step means the destination; the arrival maneuver is now, not ahead.
        return ManeuverProgress(
            maneuver = route.steps.last().maneuver,
            distanceToManeuverMeters = 0f,
        )
    }
}
