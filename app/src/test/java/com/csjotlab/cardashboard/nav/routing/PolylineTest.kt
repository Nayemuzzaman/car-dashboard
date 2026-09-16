package com.csjotlab.cardashboard.nav.routing

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineTest {

    @Test
    fun `decodes the google reference polyline at precision 5`() {
        // Example from the Google encoded-polyline spec.
        val points = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision = 5)

        assertEquals(3, points.size)
        assertEquals(GeoPoint(38.5, -120.2), points[0])
        assertEquals(GeoPoint(40.7, -120.95), points[1])
        assertEquals(GeoPoint(43.252, -126.453), points[2])
    }

    @Test
    fun `decodes a valhalla precision-6 shape`() {
        // Two points 0.000001° apart in both axes, starting at (33.886900, 130.882600).
        val encoded = Polyline.encode(listOf(GeoPoint(33.8869, 130.8826), GeoPoint(33.886901, 130.882601)), precision = 6)

        val points = Polyline.decode(encoded, precision = 6)

        assertEquals(2, points.size)
        assertEquals(33.8869, points[0].latitude, 1e-9)
        assertEquals(130.8826, points[0].longitude, 1e-9)
        assertEquals(33.886901, points[1].latitude, 1e-9)
        assertEquals(130.882601, points[1].longitude, 1e-9)
    }

    @Test
    fun `an empty or truncated string decodes to what it can without throwing`() {
        assertTrue(Polyline.decode("", precision = 6).isEmpty())
        // A dangling continuation chunk is dropped rather than thrown on.
        assertTrue(Polyline.decode("_", precision = 6).isEmpty())
    }
}
