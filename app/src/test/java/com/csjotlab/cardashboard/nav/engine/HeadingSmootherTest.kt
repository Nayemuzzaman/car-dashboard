package com.csjotlab.cardashboard.nav.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingSmootherTest {

    @Test
    fun `normalise keeps bearings in the zero to three sixty range`() {
        assertEquals(0f, HeadingMath.normalize(0f))
        assertEquals(0f, HeadingMath.normalize(360f))
        assertEquals(350f, HeadingMath.normalize(-10f))
        assertEquals(10f, HeadingMath.normalize(370f))
    }

    @Test
    fun `shortest delta turns the short way across north`() {
        assertEquals(20f, HeadingMath.shortestDelta(350f, 10f))
        assertEquals(-20f, HeadingMath.shortestDelta(10f, 350f))
    }

    @Test
    fun `shortest delta never exceeds one hundred eighty degrees`() {
        listOf(
            0f to 180f,
            0f to -180f,
            350f to 10f,
            10f to 350f,
            90f to 90f,
        ).forEach { (from, to) ->
            val delta = HeadingMath.shortestDelta(from, to)
            assertTrue("delta $delta out of range for $from -> $to", delta > -180f && delta <= 180f)
        }
    }

    @Test
    fun `smoother turns the short way across north`() {
        val smoother = HeadingSmoother(maxTurnRateDegPerSec = 180f, initialBearingDegrees = 350f)

        // 350 -> 10 must go +20, not -340.
        assertEquals(10f, smoother.next(targetDegrees = 10f, elapsedMs = 1_000L), 0.001f)
    }

    @Test
    fun `smoother clamps its turn rate`() {
        val smoother = HeadingSmoother(maxTurnRateDegPerSec = 90f, initialBearingDegrees = 0f)

        // A full second allows at most 90 degrees of the 180-degree turn.
        assertEquals(90f, smoother.next(targetDegrees = 180f, elapsedMs = 1_000L), 0.001f)
        assertEquals(180f, smoother.next(targetDegrees = 180f, elapsedMs = 1_000L), 0.001f)
    }

    @Test
    fun `smoother output stays normalised`() {
        val smoother = HeadingSmoother(maxTurnRateDegPerSec = 200f, initialBearingDegrees = 350f)
        repeat(20) {
            val out = smoother.next(targetDegrees = 10f, elapsedMs = 500L)
            assertTrue("bearing $out outside 0..360", out in 0f..360f)
        }
    }

    @Test
    fun `source policy prefers course when moving and compass when stopped`() {
        assertEquals(
            55f,
            HeadingSourcePolicy.select(speedMps = 3f, gpsCourseDegrees = 55f, compassDegrees = 200f),
        )
        assertEquals(
            200f,
            HeadingSourcePolicy.select(speedMps = 1f, gpsCourseDegrees = 55f, compassDegrees = 200f),
        )
    }

    @Test
    fun `source policy falls back to compass when a moving fix has no course`() {
        assertEquals(
            200f,
            HeadingSourcePolicy.select(speedMps = 3f, gpsCourseDegrees = null, compassDegrees = 200f),
        )
    }

    @Test
    fun `source policy returns null when nothing can supply a heading`() {
        assertNull(HeadingSourcePolicy.select(speedMps = 3f, gpsCourseDegrees = null, compassDegrees = null))
        assertNull(HeadingSourcePolicy.select(speedMps = null, gpsCourseDegrees = null, compassDegrees = null))
    }
}
