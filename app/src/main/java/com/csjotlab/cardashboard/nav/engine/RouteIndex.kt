package com.csjotlab.cardashboard.nav.engine

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Route
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/** Where a position lands on a route. Distances in metres along the route geometry. */
data class RouteProjection(
    val alongMeters: Double,
    val lateralMeters: Double,
    /** The nearest point on the route — the map-matched position. */
    val point: GeoPoint,
    /** Direction of travel of the matched segment, degrees clockwise from north. */
    val bearingDegrees: Float,
)

/**
 * A route's geometry prepared once for the per-fix questions navigation asks: how far along is this
 * position, where does each maneuver sit, and which part of the line is already behind the car.
 *
 * Everything is measured on the route polyline itself. Router step distances are rounded and
 * sometimes measured differently from the polyline, so using them for maneuver positions while
 * projecting fixes onto the polyline makes instructions advance early or late. Step starts come from
 * the steps' own geometry when every step has one; otherwise the router distances are scaled onto the
 * polyline length.
 */
class RouteIndex(val route: Route) {
    private val points: List<GeoPoint> = route.geometry
    private val cumulative: DoubleArray = DoubleArray(points.size).also { acc ->
        for (i in 1 until points.size) acc[i] = acc[i - 1] + GeoMath.distanceMeters(points[i - 1], points[i])
    }

    val lengthMeters: Double = cumulative.lastOrNull() ?: 0.0

    /** Start of each step's maneuver along the route; same size as [Route.steps]. */
    val stepStartMeters: DoubleArray = stepStarts()

    /**
     * The closest point of the route to [point] among the part between [fromMeters] and [toMeters].
     * An empty route projects to its only point (or the query point) at distance zero.
     */
    fun project(point: GeoPoint, fromMeters: Double = 0.0, toMeters: Double = lengthMeters): RouteProjection {
        if (points.size < 2) {
            val only = points.firstOrNull() ?: point
            return RouteProjection(0.0, GeoMath.distanceMeters(point, only), only, 0f)
        }
        val cosLat = cos(Math.toRadians(point.latitude))
        fun x(p: GeoPoint) = (p.longitude - point.longitude) * METERS_PER_DEGREE * cosLat
        fun y(p: GeoPoint) = (p.latitude - point.latitude) * METERS_PER_DEGREE

        var best: RouteProjection? = null
        var bestLateral = Double.POSITIVE_INFINITY
        for (i in 1 until points.size) {
            if (cumulative[i] < fromMeters) continue
            if (cumulative[i - 1] > toMeters) break
            val ax = x(points[i - 1]); val ay = y(points[i - 1])
            val dx = x(points[i]) - ax; val dy = y(points[i]) - ay
            val lengthSq = dx * dx + dy * dy
            val segmentMeters = cumulative[i] - cumulative[i - 1]
            // Restrict t to the window inside this segment so the window edges are respected.
            val tMin = if (segmentMeters > 0) ((fromMeters - cumulative[i - 1]) / segmentMeters).coerceIn(0.0, 1.0) else 0.0
            val tMax = if (segmentMeters > 0) ((toMeters - cumulative[i - 1]) / segmentMeters).coerceIn(0.0, 1.0) else 1.0
            val t = (if (lengthSq == 0.0) 0.0 else (-ax * dx - ay * dy) / lengthSq).coerceIn(tMin, tMax)
            val lateral = hypot(ax + t * dx, ay + t * dy)
            if (lateral < bestLateral) {
                bestLateral = lateral
                best = RouteProjection(
                    alongMeters = cumulative[i - 1] + t * segmentMeters,
                    lateralMeters = lateral,
                    point = interpolate(points[i - 1], points[i], t),
                    bearingDegrees = HeadingMath.normalize(Math.toDegrees(atan2(dx, dy)).toFloat()),
                )
            }
        }
        return best ?: project(point) // an empty window: answer for the whole route
    }

    /** The route position [alongMeters] from the start, clamped to the route. */
    fun pointAt(alongMeters: Double): GeoPoint {
        if (points.isEmpty()) error("empty route geometry")
        if (points.size == 1 || alongMeters <= 0.0) return points.first()
        if (alongMeters >= lengthMeters) return points.last()
        val i = segmentEndingAfter(alongMeters)
        val segment = cumulative[i] - cumulative[i - 1]
        val t = if (segment > 0) (alongMeters - cumulative[i - 1]) / segment else 0.0
        return interpolate(points[i - 1], points[i], t)
    }

    /**
     * The route cut at [alongMeters]: the part already driven and the part still ahead. The cut
     * point is the last point of the first list and the first point of the second.
     */
    fun split(alongMeters: Double): Pair<List<GeoPoint>, List<GeoPoint>> {
        if (points.size < 2) return points to points
        if (alongMeters <= 0.0) return listOf(points.first()) to points
        if (alongMeters >= lengthMeters) return points to listOf(points.last())
        val i = segmentEndingAfter(alongMeters)
        val cut = pointAt(alongMeters)
        return (points.subList(0, i) + cut) to (listOf(cut) + points.subList(i, points.size))
    }

    /** Index `i` of the first vertex whose cumulative distance exceeds [alongMeters]. */
    private fun segmentEndingAfter(alongMeters: Double): Int {
        var lo = 1
        var hi = points.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] > alongMeters) hi = mid else lo = mid + 1
        }
        return lo
    }

    private fun stepStarts(): DoubleArray {
        val steps = route.steps
        val starts = DoubleArray(steps.size)
        if (steps.isEmpty()) return starts
        val lengths: List<Double> = if (steps.dropLast(1).all { it.geometry.size >= 2 }) {
            steps.map { step -> step.geometry.zipWithNext { a, b -> GeoMath.distanceMeters(a, b) }.sum() }
        } else {
            val routerTotal = steps.sumOf { it.distanceMeters.toDouble() }
            val scale = if (routerTotal > 0) lengthMeters / routerTotal else 0.0
            steps.map { it.distanceMeters * scale }
        }
        var acc = 0.0
        for (i in steps.indices) {
            starts[i] = acc.coerceAtMost(lengthMeters)
            acc += lengths[i]
        }
        // The arrival maneuver is at the end of the line, whatever rounding the steps carried.
        if (steps.size > 1) starts[steps.lastIndex] = lengthMeters
        return starts
    }

    private fun interpolate(a: GeoPoint, b: GeoPoint, t: Double) = GeoPoint(
        a.latitude + (b.latitude - a.latitude) * t,
        a.longitude + (b.longitude - a.longitude) * t,
    )

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0
    }
}

/**
 * Keeps the car's progress along one route moving forward.
 *
 * A pure nearest-segment search jumps whenever the route passes near itself — an out-and-back, a
 * cloverleaf, a street driven in both directions — and then the maneuver list jumps with it. The
 * tracker searches a window around the last progress (a little behind, as far ahead as the car could
 * have plausibly driven) and only falls back to the whole route when nothing in the window is close.
 */
class RouteProgressTracker(
    private val behindMeters: Double = 40.0,
    private val minAheadMeters: Double = 300.0,
    private val fallbackLateralMeters: Double = 50.0,
) {
    private var index: RouteIndex? = null
    private var lastAlong: Double? = null

    fun reset(index: RouteIndex?) {
        this.index = index
        lastAlong = null
    }

    fun update(point: GeoPoint, speedMps: Float?, elapsedMs: Long): RouteProjection {
        val index = checkNotNull(index) { "reset(index) before update" }
        // A fresh route starts where the car is, so the first search is anchored at the start too.
        val previous = lastAlong ?: 0.0
        // Unknown speed: assume up to motorway speed so a missing speed never strands progress.
        val speed = (speedMps ?: ASSUMED_MAX_SPEED_MPS).toDouble().coerceAtLeast(0.0)
        val ahead = maxOf(minAheadMeters, speed * elapsedMs.coerceAtLeast(0L) / 1_000.0 * 2 + 100.0)
        val windowed = index.project(point, previous - behindMeters, previous + ahead)
        val projection = if (windowed.lateralMeters <= fallbackLateralMeters) {
            windowed
        } else {
            val global = index.project(point)
            if (global.lateralMeters + MIN_IMPROVEMENT_METERS < windowed.lateralMeters) global else windowed
        }
        lastAlong = projection.alongMeters
        return projection
    }

    private companion object {
        const val ASSUMED_MAX_SPEED_MPS = 35f
        const val MIN_IMPROVEMENT_METERS = 20.0
    }
}
