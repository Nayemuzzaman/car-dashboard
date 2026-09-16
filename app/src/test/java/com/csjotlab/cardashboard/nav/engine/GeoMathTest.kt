package com.csjotlab.cardashboard.nav.engine

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {

    @Test
    fun `identical points are zero metres apart`() {
        assertEquals(0.0, GeoMath.distanceMeters(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0)), 1e-6)
    }

    @Test
    fun `a thousandth of a degree of latitude is about a hundred and eleven metres`() {
        val meters = GeoMath.distanceMeters(GeoPoint(0.0, 0.0), GeoPoint(0.001, 0.0))
        assertTrue("expected ~111 m, got $meters", meters in 100.0..120.0)
    }

    @Test
    fun `a point on a vertex is on the polyline`() {
        // A north-south line along longitude 0.
        val polyline = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.01, 0.0))
        val meters = GeoMath.distanceToPolylineMeters(GeoPoint(0.0, 0.0), polyline)
        assertTrue("expected ~0 m, got $meters", meters < 0.001)
    }

    @Test
    fun `a point beside a vertical line is the perpendicular distance away`() {
        val polyline = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.01, 0.0))
        // 0.001 degrees east of the line, at the equator: ~111 m.
        val meters = GeoMath.distanceToPolylineMeters(GeoPoint(0.005, 0.001), polyline)
        assertTrue("expected ~111 m, got $meters", meters in 100.0..120.0)
    }

    @Test
    fun `an empty polyline has no finite distance`() {
        assertTrue(GeoMath.distanceToPolylineMeters(GeoPoint(0.0, 0.0), emptyList()).isInfinite())
    }
}
