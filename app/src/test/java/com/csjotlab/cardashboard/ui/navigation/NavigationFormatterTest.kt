package com.csjotlab.cardashboard.ui.navigation

import com.csjotlab.cardashboard.nav.data.NavigationSnapshot
import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.domain.NavigationPhase
import com.csjotlab.cardashboard.nav.domain.NavigationState
import com.csjotlab.cardashboard.nav.domain.RerouteState
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.domain.RouteStep
import com.csjotlab.cardashboard.nav.geocoding.Place
import com.csjotlab.cardashboard.vehicle.domain.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationFormatterTest {

    private val route = Route(
        origin = GeoPoint(0.0, 0.0),
        destination = GeoPoint(0.0063, 0.0),
        steps = listOf(
            RouteStep(Maneuver(ManeuverType.Depart, null), 500f, 60L, emptyList()),
            RouteStep(Maneuver(ManeuverType.TurnLeft, null), 200f, 30L, emptyList()),
            RouteStep(Maneuver(ManeuverType.Arrive, null), 0f, 0L, emptyList()),
        ),
        geometry = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0063, 0.0)),
        totalDistanceMeters = 700f,
        totalDurationSeconds = 90L,
    )

    @Test
    fun `idle snapshot says no route and nothing known`() {
        val ui = NavigationFormatter.toUiState(NavigationSnapshot.Idle) { "ignored" }

        assertEquals(NO_ROUTE, ui.statusLabel)
        assertNull(ui.maneuverText)
        assertEquals(UNAVAILABLE, ui.speedText)
        assertFalse(ui.hasRoute)
        assertFalse(ui.hasLocation)
    }

    @Test
    fun `a destination with no fix and no explicit start says waiting for gps`() {
        val snapshot = NavigationSnapshot(NavigationState.idle(), GeoPoint(1.0, 2.0), origin = null)
        val ui = NavigationFormatter.toUiState(snapshot) { "ignored" }

        assertEquals(WAITING_FOR_GPS, ui.statusLabel)
        assertFalse(ui.hasRoute)
    }

    @Test
    fun `a destination with an explicit start says finding route`() {
        val snapshot = NavigationSnapshot(
            NavigationState.idle(),
            GeoPoint(1.0, 2.0),
            origin = GeoPoint(0.0, 0.0),
        )
        val ui = NavigationFormatter.toUiState(snapshot) { "ignored" }

        assertEquals(FINDING_ROUTE, ui.statusLabel)
    }

    @Test
    fun `a route plus a live fix is navigating with maneuver and eta`() {
        val state = NavigationState.idle().copy(
            phase = NavigationPhase.Navigating,
            route = route,
            location = Signal.Value(GeoPoint(0.0, 0.0), 1_000L),
            speedMps = Signal.Value(10f, 1_000L),
            maneuverPhrase = "Turn left in 300 m",
            remainingDistanceMeters = Signal.Value(300f, 1_000L),
            remainingTimeSeconds = Signal.Value(45L, 1_000L),
            etaMs = Signal.Value(1_000_000L, 1_000L),
        )
        val ui = NavigationFormatter.toUiState(NavigationSnapshot(state, route.destination, origin = null)) { "14:32" }

        assertEquals(NAVIGATING, ui.statusLabel)
        assertEquals("Turn left in 300 m", ui.maneuverText)
        assertEquals("300 m", ui.remainingDistanceText)
        assertEquals("less than a minute", ui.remainingTimeText)
        assertEquals("14:32", ui.etaText)
        assertEquals("36 km/h", ui.speedText)
        assertTrue(ui.hasRoute)
        assertTrue(ui.hasLocation)
    }

    @Test
    fun `a failed route with no route yet says unable to find route`() {
        val state = NavigationState.idle().copy(rerouteState = RerouteState.Failed)
        val ui = NavigationFormatter.toUiState(NavigationSnapshot(state, route.destination, origin = null)) { "ignored" }

        assertEquals(UNABLE_TO_FIND_ROUTE, ui.statusLabel)
    }

    @Test
    fun `a failed reroute with a route still says unable to reroute`() {
        val state = NavigationState.idle().copy(route = route, rerouteState = RerouteState.Failed)
        val ui = NavigationFormatter.toUiState(NavigationSnapshot(state, route.destination, origin = null)) { "ignored" }

        assertEquals(UNABLE_TO_REROUTE, ui.statusLabel)
    }

    @Test
    fun `a preview route is labelled as a straight-line preview`() {
        val state = NavigationState.idle().copy(route = route.copy(isPreview = true))
        val ui = NavigationFormatter.toUiState(NavigationSnapshot(state, route.destination, origin = null)) { "ignored" }

        assertEquals(STRAIGHT_LINE_PREVIEW, ui.previewLabel)
    }

    @Test
    fun `arrival is shown as arrived`() {
        val state = NavigationState.idle().copy(
            phase = NavigationPhase.Arrived,
            route = route,
            location = Signal.Value(route.destination, 1_000L),
        )
        val ui = NavigationFormatter.toUiState(NavigationSnapshot(state, route.destination, origin = null)) { "ignored" }

        assertEquals(ARRIVED, ui.statusLabel)
    }

    @Test
    fun `distance and duration formatting keep their units`() {
        assertEquals("850 m", NavigationFormatter.formatRemainingDistance(850f))
        assertEquals("1.2 km", NavigationFormatter.formatRemainingDistance(1_200f))
        assertEquals("12 km", NavigationFormatter.formatRemainingDistance(12_000f))
        assertEquals("45 min", NavigationFormatter.formatDuration(2_700L))
        assertEquals("2 h 5 min", NavigationFormatter.formatDuration(7_500L))
        assertEquals("36 km/h", NavigationFormatter.formatSpeed(10f))
    }

    @Test
    fun `a search result carries a formatted distance only when a fix exists`() {
        val place = Place("Airport", GeoPoint(0.0, 0.0090))

        val withFix = NavigationFormatter.toSearchResult(place, near = GeoPoint(0.0, 0.0))
        val withoutFix = NavigationFormatter.toSearchResult(place, near = null)

        assertEquals("1.0 km", withFix.distanceText)
        assertEquals(place, withFix.place)
        assertNull(withoutFix.distanceText)
    }

    private fun navigating(route: Route) = NavigationState.idle().copy(
        phase = NavigationPhase.Navigating,
        location = Signal.Value(GeoPoint(0.0, 0.0), 0L),
        route = route,
        nextManeuver = route.steps[1].maneuver,
    )

    @Test
    fun `toll label states the truth of the route and never guesses`() {
        fun label(hasToll: Boolean?, avoid: Boolean = false): String? {
            val snapshot = NavigationSnapshot(navigating(route.copy(hasToll = hasToll)), route.destination, null, avoidTolls = avoid)
            return NavigationFormatter.toUiState(snapshot) { "" }.tollLabel
        }

        assertEquals(TOLL_ROAD, label(true))
        assertEquals(TOLL_FREE, label(false))
        assertEquals(TOLL_UNKNOWN, label(null))
        assertEquals(TOLLS_UNAVOIDABLE, label(true, avoid = true))
        assertEquals(TOLL_FREE, label(false, avoid = true))
        assertNull(NavigationFormatter.toUiState(NavigationSnapshot.Idle) { "" }.tollLabel)
    }

    @Test
    fun `via text names the longest road and the next step carries its toll flag`() {
        val tolled = route.copy(
            steps = listOf(
                RouteStep(Maneuver(ManeuverType.Depart, null), 500f, 60L, emptyList(), roadName = "Lane 11"),
                RouteStep(Maneuver(ManeuverType.KeepLeft, "Take the ramp."), 4_000f, 30L, emptyList(), roadName = "Kitakyushu Expressway", toll = true),
                RouteStep(Maneuver(ManeuverType.Arrive, null), 0f, 0L, emptyList()),
            ),
            hasToll = true,
        )
        val snapshot = NavigationSnapshot(navigating(tolled), tolled.destination, null)

        val ui = NavigationFormatter.toUiState(snapshot) { "" }

        assertEquals("via Kitakyushu Expressway", ui.viaText)
        assertTrue(ui.nextStepToll)
        assertFalse(ui.avoidTolls)
    }

    @Test
    fun `no via text without road names and no next-step toll on a free road`() {
        val snapshot = NavigationSnapshot(navigating(route), route.destination, null)
        val ui = NavigationFormatter.toUiState(snapshot) { "" }
        assertNull(ui.viaText)
        assertFalse(ui.nextStepToll)
    }
}
