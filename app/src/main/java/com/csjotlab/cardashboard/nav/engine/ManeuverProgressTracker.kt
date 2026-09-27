package com.csjotlab.cardashboard.nav.engine

import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.Route

data class ManeuverProgress(
    val maneuver: Maneuver,
    val distanceToManeuverMeters: Float,
    /** Index of the maneuver's step in [Route.steps]; -1 when unknown. */
    val stepIndex: Int = -1,
    /** The road the maneuver leads onto, when the router named it. */
    val roadName: String? = null,
    /** The road being driven now (the step before the maneuver), when named. */
    val currentRoadName: String? = null,
    /** The maneuver right after this one, when it follows closely enough to announce together. */
    val then: Maneuver? = null,
)

/**
 * Finds the next maneuver still ahead of (or now at) the traveller.
 *
 * A route's steps are ordered; each step's maneuver happens at its start, and the step's distance is
 * how far you travel before the next step's maneuver. The first step is the departure point and is
 * never reported as upcoming. The final step is the arrival point.
 */
object ManeuverProgressTracker {
    /** A following maneuver closer than this to the next one is announced with it ("Then ↰"). */
    const val THEN_ANNOUNCE_METERS = 250.0

    /** Step positions from the router's step distances. */
    fun progressAt(route: Route, distanceAlongRouteMeters: Float): ManeuverProgress? {
        val starts = DoubleArray(route.steps.size)
        var acc = 0.0
        route.steps.forEachIndexed { i, step -> starts[i] = acc; acc += step.distanceMeters }
        return progressAt(route, starts, distanceAlongRouteMeters.toDouble())
    }

    /** Step positions measured on the route geometry — the same metric as the progress projection. */
    fun progressAt(index: RouteIndex, distanceAlongRouteMeters: Double): ManeuverProgress? =
        progressAt(index.route, index.stepStartMeters, distanceAlongRouteMeters)

    private fun progressAt(route: Route, stepStarts: DoubleArray, along: Double): ManeuverProgress? {
        val steps = route.steps
        if (steps.isEmpty()) return null
        val traveled = along.coerceAtLeast(0.0)

        // The departure maneuver is at the origin and already behind the driver. `<=`: a maneuver
        // reached exactly is "now", not already passed. Past every step means the arrival is now.
        val next = (1 until steps.size).firstOrNull { traveled <= stepStarts[it] } ?: steps.lastIndex
        val distance = (stepStarts[next] - traveled).coerceAtLeast(0.0)
        val then = (next + 1).takeIf { it < steps.size && stepStarts[it] - stepStarts[next] <= THEN_ANNOUNCE_METERS }
        return ManeuverProgress(
            maneuver = steps[next].maneuver,
            distanceToManeuverMeters = if (next == steps.lastIndex && traveled > stepStarts[next]) 0f else distance.toFloat(),
            stepIndex = next,
            roadName = steps[next].roadName,
            currentRoadName = steps[next - 1].roadName,
            then = then?.let { steps[it].maneuver },
        )
    }
}
