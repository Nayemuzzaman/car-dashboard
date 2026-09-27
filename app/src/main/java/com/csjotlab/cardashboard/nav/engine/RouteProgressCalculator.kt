package com.csjotlab.cardashboard.nav.engine

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.vehicle.domain.Clock
import kotlin.math.roundToLong

data class RouteProgress(
    val distanceAlongRouteMeters: Float,
    val remainingDistanceMeters: Float,
    val remainingTimeSeconds: Long,
)

/**
 * Projects the current position onto the route and derives how far and how long is left.
 *
 * Remaining time is the un-elapsed fraction of the current step plus the full durations of the
 * steps after it. It is derived only from a resolved [Route] and a live position; a caller that has
 * neither must not construct a [RouteProgress].
 */
object RouteProgressCalculator {

    fun progress(route: Route, position: GeoPoint): RouteProgress {
        val along = GeoMath.distanceAlongPolylineMeters(position, route.geometry).toFloat()
        val remainingDistance = (route.totalDistanceMeters - along).coerceAtLeast(0f)
        return RouteProgress(
            distanceAlongRouteMeters = along,
            remainingDistanceMeters = remainingDistance,
            remainingTimeSeconds = remainingTimeSeconds(route, along),
        )
    }

    /**
     * Remaining time with step positions measured on the route geometry ([RouteIndex]): the
     * un-driven fraction of the current step plus every later step.
     */
    fun remainingTimeSeconds(index: RouteIndex, distanceAlong: Double): Long {
        val steps = index.route.steps
        if (steps.isEmpty()) {
            val length = index.lengthMeters
            return if (length <= 0.0) index.route.totalDurationSeconds
            else (index.route.totalDurationSeconds * (1.0 - distanceAlong / length)).roundToLong().coerceAtLeast(0L)
        }
        var remaining = 0.0
        for (i in steps.indices) {
            val start = index.stepStartMeters[i]
            val end = if (i < steps.lastIndex) index.stepStartMeters[i + 1] else index.lengthMeters
            val span = end - start
            remaining += when {
                distanceAlong <= start -> steps[i].durationSeconds.toDouble()
                distanceAlong >= end || span <= 0.0 -> 0.0
                else -> steps[i].durationSeconds * (end - distanceAlong) / span
            }
        }
        return remaining.roundToLong().coerceAtLeast(0L)
    }

    fun etaMs(clock: Clock, remainingTimeSeconds: Long): Long =
        clock.nowMs() + remainingTimeSeconds * 1_000L

    private fun remainingTimeSeconds(route: Route, distanceAlong: Float): Long {
        var cumulative = 0f
        var remaining = 0L
        var startedCurrentStep = false

        for (step in route.steps) {
            val stepEnd = cumulative + step.distanceMeters
            if (!startedCurrentStep && distanceAlong < stepEnd) {
                val fractionLeft = if (step.distanceMeters <= 0f) {
                    0f
                } else {
                    ((stepEnd - distanceAlong) / step.distanceMeters).coerceIn(0f, 1f)
                }
                remaining += (step.durationSeconds * fractionLeft).roundToLong()
                startedCurrentStep = true
            } else if (startedCurrentStep) {
                remaining += step.durationSeconds
            }
            cumulative = stepEnd
        }
        return remaining.coerceAtLeast(0L)
    }
}
