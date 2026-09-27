package com.csjotlab.cardashboard.nav.engine

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.GpsQuality
import com.csjotlab.cardashboard.nav.location.LocationReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocationFilterTest {

    private val m = 1.0 / 111_194.9

    private fun reading(
        north: Double,
        east: Double = 0.0,
        t: Long,
        speed: Float? = 10f,
        course: Float? = 0f,
        accuracy: Float? = 5f,
    ) = LocationReading(GeoPoint(north * m, east * m), speed, course, accuracy, t)

    @Test
    fun `an accurate fix passes unchanged and is good`() {
        val r = LocationFilter().process(reading(0.0, t = 0))
        assertEquals(GpsQuality.Good, r.quality)
        assertEquals(GeoPoint(0.0, 0.0), r.fix!!.point)
    }

    @Test
    fun `a wildly inaccurate fix is rejected and marks the signal degraded`() {
        val r = LocationFilter().process(reading(0.0, t = 0, accuracy = 150f))
        assertNull(r.fix)
        assertEquals(GpsQuality.Degraded, r.quality)
    }

    @Test
    fun `a usable but coarse fix is accepted as degraded`() {
        val r = LocationFilter().process(reading(0.0, t = 0, accuracy = 40f))
        assertNotNull(r.fix)
        assertEquals(GpsQuality.Degraded, r.quality)
    }

    @Test
    fun `an impossible jump is rejected until it is confirmed by further fixes`() {
        val filter = LocationFilter()
        filter.process(reading(0.0, t = 0))

        // 500 m in one second is 1 800 km/h.
        assertNull(filter.process(reading(500.0, t = 1_000)).fix)
        assertNull(filter.process(reading(510.0, t = 2_000)).fix)
        // Three consistent fixes in a row: the earlier position was the wrong one.
        assertEquals(520 * m, filter.process(reading(520.0, t = 3_000)).fix!!.point.latitude, 1e-9)
    }

    @Test
    fun `after a long gap a distant fix is accepted at once`() {
        val filter = LocationFilter()
        filter.process(reading(0.0, t = 0))
        assertNotNull(filter.process(reading(2_000.0, t = 60_000)).fix)
    }

    @Test
    fun `a stationary car does not drift with gps jitter`() {
        val filter = LocationFilter()
        filter.process(reading(0.0, t = 0, speed = 0f, accuracy = 10f))

        val jittered = filter.process(reading(4.0, 3.0, t = 1_000, speed = 0.1f, accuracy = 10f))

        assertEquals(GeoPoint(0.0, 0.0), jittered.fix!!.point)
    }

    @Test
    fun `a fix without course or speed gets both from the previous position`() {
        val filter = LocationFilter()
        filter.process(reading(0.0, t = 0, speed = null, course = null))

        val moved = filter.process(reading(0.0, 20.0, t = 1_000, speed = null, course = null)).fix!!

        assertEquals(90f, moved.courseDegrees!!, 1f) // due east
        assertEquals(20f, moved.speedMps!!, 0.5f)
    }

    @Test
    fun `a course is not derived from a few metres of jitter`() {
        val filter = LocationFilter()
        filter.process(reading(0.0, t = 0, speed = null, course = null))

        val crept = filter.process(reading(3.0, t = 1_000, speed = null, course = null)).fix!!

        assertNull(crept.courseDegrees)
    }

    @Test
    fun `a reported course is never overwritten`() {
        val filter = LocationFilter()
        filter.process(reading(0.0, t = 0, course = 10f))
        assertEquals(10f, filter.process(reading(0.0, 20.0, t = 1_000, course = 10f)).fix!!.courseDegrees)
    }
}
