package com.csjotlab.cardashboard.nav.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingPolicyTest {

    @Test
    fun `a course is reliable only when moving and, if reported, accurate`() {
        assertTrue(HeadingSourcePolicy.isCourseReliable(speedMps = 5f, courseDegrees = 90f, courseAccuracyDegrees = null))
        assertTrue(HeadingSourcePolicy.isCourseReliable(5f, 90f, 20f))
        assertFalse(HeadingSourcePolicy.isCourseReliable(5f, 90f, 60f))
        assertFalse(HeadingSourcePolicy.isCourseReliable(1f, 90f, 5f))
        assertFalse(HeadingSourcePolicy.isCourseReliable(null, 90f, 5f))
        assertFalse(HeadingSourcePolicy.isCourseReliable(5f, null, null))
    }

    @Test
    fun `stopping holds the last reliable course instead of the compass`() {
        val heading = HeadingSourcePolicy.select(
            speedMps = 0f,
            gpsCourseDegrees = 250f, // stationary GPS bearing: noise
            courseAccuracyDegrees = null,
            lastReliableCourseDegrees = 90f,
            compassDegrees = 180f,
        )
        assertEquals(90f, heading!!, 0.001f)
    }

    @Test
    fun `the compass is used only before any reliable course exists`() {
        assertEquals(180f, HeadingSourcePolicy.select(0f, null, null, null, 180f)!!, 0.001f)
        assertNull(HeadingSourcePolicy.select(0f, null, null, null, null))
    }
}
