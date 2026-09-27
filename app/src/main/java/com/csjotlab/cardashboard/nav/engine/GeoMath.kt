package com.csjotlab.cardashboard.nav.engine

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure geographic arithmetic used by off-route detection. No Android imports. */
object GeoMath {
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val METERS_PER_DEGREE = 111_320.0

    /** Great-circle (Haversine) distance in metres. */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)

        val sinLat = sin(dLat / 2)
        val sinLon = sin(dLon / 2)
        val h = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLon * sinLon
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * Minimum perpendicular distance in metres from [point] to [polyline]. Returns positive
     * infinity for an empty polyline. Uses an equirectangular projection centred on the query point,
     * which is accurate at the tens-of-metres scale off-route detection cares about.
     */
    fun distanceToPolylineMeters(point: GeoPoint, polyline: List<GeoPoint>): Double {
        if (polyline.isEmpty()) return Double.POSITIVE_INFINITY
        if (polyline.size == 1) return distanceMeters(point, polyline[0])

        val cosLat = cos(Math.toRadians(point.latitude))
        fun x(p: GeoPoint) = (p.longitude - point.longitude) * METERS_PER_DEGREE * cosLat
        fun y(p: GeoPoint) = (p.latitude - point.latitude) * METERS_PER_DEGREE

        var best = Double.POSITIVE_INFINITY
        var ax = x(polyline[0])
        var ay = y(polyline[0])
        for (i in 1 until polyline.size) {
            val bx = x(polyline[i])
            val by = y(polyline[i])
            best = min(best, pointToSegmentDistance(ax, ay, bx, by))
            ax = bx
            ay = by
        }
        return best
    }

    /**
     * Distance travelled along [polyline] to the point nearest to [point], in metres. This is the
     * projection used to place the current position onto a route geometry. Empty or single-point
     * polylines project to the start.
     */
    fun distanceAlongPolylineMeters(point: GeoPoint, polyline: List<GeoPoint>): Double {
        if (polyline.size < 2) return 0.0

        val cosLat = cos(Math.toRadians(point.latitude))
        fun x(p: GeoPoint) = (p.longitude - point.longitude) * METERS_PER_DEGREE * cosLat
        fun y(p: GeoPoint) = (p.latitude - point.latitude) * METERS_PER_DEGREE

        var bestDistanceAlong = 0.0
        var bestLateralSq = Double.POSITIVE_INFINITY
        var cumulative = 0.0

        var ax = x(polyline[0])
        var ay = y(polyline[0])
        for (i in 1 until polyline.size) {
            val bx = x(polyline[i])
            val by = y(polyline[i])
            val dx = bx - ax
            val dy = by - ay
            val segmentLength = hypot(dx, dy)
            val t = if (segmentLength == 0.0) 0.0 else ((-ax * dx - ay * dy) / (segmentLength * segmentLength)).coerceIn(0.0, 1.0)
            val cx = ax + t * dx
            val cy = ay + t * dy
            val lateralSq = cx * cx + cy * cy
            if (lateralSq < bestLateralSq) {
                bestLateralSq = lateralSq
                bestDistanceAlong = cumulative + t * segmentLength
            }
            cumulative += segmentLength
            ax = bx
            ay = by
        }
        return bestDistanceAlong
    }

    /** Initial great-circle bearing from [a] to [b], degrees clockwise from north, 0..360. */
    fun bearingDegrees(a: GeoPoint, b: GeoPoint): Float {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return HeadingMath.normalize(Math.toDegrees(atan2(y, x)).toFloat())
    }

    /** Distance from the origin (the query point) to segment `a-b`, in projected metres. */
    private fun pointToSegmentDistance(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val lengthSq = dx * dx + dy * dy
        val t = if (lengthSq == 0.0) 0.0 else (-ax * dx - ay * dy) / lengthSq
        val clamped = t.coerceIn(0.0, 1.0)
        val cx = ax + clamped * dx
        val cy = ay + clamped * dy
        return hypot(cx, cy)
    }
}
