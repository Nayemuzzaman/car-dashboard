package com.csjotlab.cardashboard.nav.routing

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import kotlin.math.roundToLong

/**
 * Google encoded-polyline codec. Valhalla shapes use precision 6 (1e-6°), Google/OSRM use 5.
 * Pure Kotlin so the routing parsers stay JVM-testable.
 */
object Polyline {

    fun decode(encoded: String, precision: Int): List<GeoPoint> {
        val factor = pow10(precision)
        val points = mutableListOf<GeoPoint>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            val (dLat, afterLat) = readDelta(encoded, index) ?: break
            val (dLon, afterLon) = readDelta(encoded, afterLat) ?: break
            index = afterLon
            lat += dLat
            lon += dLon
            points += GeoPoint(lat / factor, lon / factor)
        }
        return points
    }

    fun encode(points: List<GeoPoint>, precision: Int): String {
        val factor = pow10(precision)
        val out = StringBuilder()
        var lastLat = 0L
        var lastLon = 0L
        points.forEach { point ->
            val lat = (point.latitude * factor).roundToLong()
            val lon = (point.longitude * factor).roundToLong()
            writeDelta(out, lat - lastLat)
            writeDelta(out, lon - lastLon)
            lastLat = lat
            lastLon = lon
        }
        return out.toString()
    }

    /** Returns the delta and the index after it, or null if the string ends mid-chunk. */
    private fun readDelta(encoded: String, start: Int): Pair<Long, Int>? {
        var index = start
        var result = 0L
        var shift = 0
        while (true) {
            if (index >= encoded.length) return null
            val chunk = encoded[index++].code - 63
            result = result or ((chunk and 0x1F).toLong() shl shift)
            shift += 5
            if (chunk < 0x20) break
        }
        val delta = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        return delta to index
    }

    private fun writeDelta(out: StringBuilder, delta: Long) {
        var value = if (delta < 0) (delta shl 1).inv() else delta shl 1
        while (value >= 0x20) {
            out.append(((0x20 or (value and 0x1F).toInt()) + 63).toChar())
            value = value shr 5
        }
        out.append((value.toInt() + 63).toChar())
    }

    private fun pow10(precision: Int): Double {
        var factor = 1.0
        repeat(precision) { factor *= 10.0 }
        return factor
    }
}
