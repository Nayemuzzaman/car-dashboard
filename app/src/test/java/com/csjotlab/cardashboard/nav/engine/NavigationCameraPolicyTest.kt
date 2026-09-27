package com.csjotlab.cardashboard.nav.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationCameraPolicyTest {

    @Test
    fun `slow driving is close, motorway speed is wide`() {
        val town = NavigationCameraPolicy.followZoom(speedMps = 3f, distanceToManeuverMeters = null)
        val motorway = NavigationCameraPolicy.followZoom(speedMps = 31f, distanceToManeuverMeters = null)
        assertTrue(town > motorway)
        assertEquals(NavigationCameraPolicy.MAX_FOLLOW_ZOOM, town, 0.01)
        assertEquals(NavigationCameraPolicy.MIN_FOLLOW_ZOOM, motorway, 0.01)
    }

    @Test
    fun `an approaching turn zooms in even at speed`() {
        val far = NavigationCameraPolicy.followZoom(speedMps = 25f, distanceToManeuverMeters = 2_000f)
        val near = NavigationCameraPolicy.followZoom(speedMps = 25f, distanceToManeuverMeters = 150f)
        assertTrue(near > far)
        assertTrue(near >= NavigationCameraPolicy.NEAR_MANEUVER_ZOOM)
    }

    @Test
    fun `unknown speed uses the default close zoom`() {
        assertEquals(NavigationCameraPolicy.DEFAULT_FOLLOW_ZOOM, NavigationCameraPolicy.followZoom(null, null), 0.01)
    }

    @Test
    fun `heading-up is tilted, north-up is flat`() {
        assertTrue(NavigationCameraPolicy.followTilt(headingUp = true) > 0.0)
        assertEquals(0.0, NavigationCameraPolicy.followTilt(headingUp = false), 0.0)
    }

    @Test
    fun `the vehicle sits in the lower part of the map`() {
        val top = NavigationCameraPolicy.vehicleTopPaddingPx(mapHeightPx = 1_000)
        // Centre of the padded area [top, 1000] is where the camera target (the car) is drawn.
        assertEquals(700.0, (top + 1_000) / 2.0, 1.0)
    }

    @Test
    fun `the vehicle sits inside the space the banner and trip bar leave free`() {
        val (top, bottom) = NavigationCameraPolicy.followPadding(heightPx = 1_000.0, topInset = 200.0, bottomInset = 100.0, fraction = 0.7)
        // Free area 200..900; 70 % down it is 690. The padded area's centre must be there.
        assertEquals(690.0, (top + (1_000 - bottom)) / 2.0, 0.5)
    }

    @Test
    fun `a centred target needs no padding`() {
        val (top, bottom) = NavigationCameraPolicy.followPadding(1_000.0, 0.0, 0.0, 0.5)
        assertEquals(0.0, top, 0.001)
        assertEquals(0.0, bottom, 0.001)
    }
}
