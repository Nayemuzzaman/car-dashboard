package com.csjotlab.cardashboard.nav.engine

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProgressTrackerTest {

    private val m = 1.0 / 111_194.9

    /** Out 500 m north on one side of a street, then back south 1 m to the east — an out-and-back. */
    private val outAndBack = Route(
        origin = GeoPoint(0.0, 0.0),
        destination = GeoPoint(0.0, 1 * m),
        steps = emptyList(),
        geometry = listOf(GeoPoint(0.0, 0.0), GeoPoint(500 * m, 0.0), GeoPoint(500 * m, 1 * m), GeoPoint(0.0, 1 * m)),
        totalDistanceMeters = 1_001f,
        totalDurationSeconds = 100L,
    )

    @Test
    fun `progress on an out-and-back never jumps to the return leg on the way out`() {
        val tracker = RouteProgressTracker()
        tracker.reset(RouteIndex(outAndBack))

        // Driving north, slightly east of the outbound line — geometrically closer to the return leg.
        var last = -1.0
        for (north in listOf(0, 50, 100, 150, 200, 250)) {
            val p = tracker.update(GeoPoint(north * m, 0.6 * m), speedMps = 12f, elapsedMs = 1_000L)
            assertTrue("jumped to ${p.alongMeters} at $north m", p.alongMeters < 400.0)
            assertTrue(p.alongMeters >= last)
            last = p.alongMeters
        }
    }

    @Test
    fun `after the turnaround the return leg is followed`() {
        val tracker = RouteProgressTracker()
        tracker.reset(RouteIndex(outAndBack))
        for (north in 0..500 step 50) tracker.update(GeoPoint(north * m, 0.0), 12f, 4_000L)

        val p = tracker.update(GeoPoint(450 * m, 1 * m), 12f, 4_000L)
        assertEquals(551.0, p.alongMeters, 3.0)
    }

    @Test
    fun `a position far outside the window falls back to a global search`() {
        val tracker = RouteProgressTracker()
        tracker.reset(RouteIndex(outAndBack))
        tracker.update(GeoPoint(0.0, 0.0), 0f, 1_000L)

        // GPS recovering after a tunnel, 480 m further on: well beyond the forward window.
        val p = tracker.update(GeoPoint(480 * m, 0.0), speedMps = null, elapsedMs = 1_000L)
        assertEquals(480.0, p.alongMeters, 3.0)
    }

    @Test
    fun `reset forgets the previous progress`() {
        val tracker = RouteProgressTracker()
        tracker.reset(RouteIndex(outAndBack))
        tracker.update(GeoPoint(400 * m, 0.0), 12f, 1_000L)

        tracker.reset(RouteIndex(outAndBack))
        assertEquals(0.0, tracker.update(GeoPoint(0.0, 0.0), 0f, 1_000L).alongMeters, 1.0)
    }
}
