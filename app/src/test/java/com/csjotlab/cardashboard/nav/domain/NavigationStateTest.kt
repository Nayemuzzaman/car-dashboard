package com.csjotlab.cardashboard.nav.domain

import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationStateTest {

    @Test
    fun `idle state reports nothing known and no route`() {
        val state = NavigationState.idle()

        assertEquals(NavigationPhase.Idle, state.phase)
        assertEquals(Signal.Unknown, state.location)
        assertEquals(Signal.Unknown, state.speedMps)
        assertEquals(Signal.Unknown, state.headingDegrees)
        assertNull(state.route)
        assertNull(state.nextManeuver)
        assertNull(state.maneuverPhrase)
        assertNull(state.remainingDistanceMeters)
        assertNull(state.remainingTimeSeconds)
        assertNull(state.etaMs)
        assertFalse(state.offRoute)
        assertEquals(RerouteState.Idle, state.rerouteState)
    }

    @Test
    fun `a populated location keeps its value and timestamp`() {
        val located = NavigationState.idle().copy(
            location = Signal.Value(GeoPoint(latitude = 1.0, longitude = 2.0), timestampMs = 123L),
        )

        assertEquals(GeoPoint(1.0, 2.0), located.location.valueOrNull())
        assertEquals(123L, (located.location as Signal.Value).timestampMs)
    }
}
