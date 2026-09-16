package com.csjotlab.cardashboard.ui.navigation

import com.csjotlab.cardashboard.nav.data.NavigationRepository
import com.csjotlab.cardashboard.nav.data.PersistentRecentDestinationsStore
import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.domain.RouteStep
import com.csjotlab.cardashboard.nav.fakes.ControllableHeadingProvider
import com.csjotlab.cardashboard.nav.fakes.ControllableLocationProvider
import com.csjotlab.cardashboard.nav.fakes.FakeGeocodingEngine
import com.csjotlab.cardashboard.nav.fakes.InMemoryStringStorage
import com.csjotlab.cardashboard.nav.geocoding.GeocodeResult
import com.csjotlab.cardashboard.nav.geocoding.Place
import com.csjotlab.cardashboard.nav.location.LocationReading
import com.csjotlab.cardashboard.nav.routing.FakeRoutingEngine
import com.csjotlab.cardashboard.nav.routing.RouteRequest
import com.csjotlab.cardashboard.nav.routing.RouteResult
import com.csjotlab.cardashboard.vehicle.domain.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val here = GeoPoint(0.0, 0.0)
    private val airport = Place("Airport", GeoPoint(0.0, 0.0090), address = "Dhaka", category = "aerodrome")

    private val route = Route(
        origin = here,
        destination = airport.point,
        steps = listOf(
            RouteStep(Maneuver(ManeuverType.Depart, null), 1000f, 90L, emptyList()),
            RouteStep(Maneuver(ManeuverType.Arrive, null), 0f, 0L, emptyList()),
        ),
        geometry = listOf(here, airport.point),
        totalDistanceMeters = 1000f,
        totalDurationSeconds = 90L,
    )

    private fun fix(point: GeoPoint) = LocationReading(point, speedMps = 3f, courseDegrees = 90f, accuracyMeters = null, timestampMs = 0L)

    private class Harness(
        val viewModel: NavigationViewModel,
        val location: ControllableLocationProvider,
        val geocoder: FakeGeocodingEngine,
        val routing: FakeRoutingEngine,
    )

    private fun TestScope.harness(
        geocoder: FakeGeocodingEngine = FakeGeocodingEngine(),
        routing: FakeRoutingEngine = FakeRoutingEngine { RouteResult.Success(route) },
    ): Harness {
        val location = ControllableLocationProvider()
        val repository = NavigationRepository(
            routingEngine = routing,
            locationProvider = location,
            headingProvider = ControllableHeadingProvider(),
            clock = Clock { 0L },
            scope = backgroundScope,
        )
        val viewModel = NavigationViewModel(repository, PersistentRecentDestinationsStore(InMemoryStringStorage()), geocoder)
        // WhileSubscribed flows only run with a collector; keep the ones under test alive.
        backgroundScope.launch { viewModel.search.collect {} }
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return Harness(viewModel, location, geocoder, routing)
    }

    @Test
    fun `queries shorter than two characters never reach the geocoder`() = runTest(dispatcher.scheduler) {
        val h = harness()

        h.viewModel.onSearchQueryChanged("a")
        advanceTimeBy(1_000L); runCurrent()

        assertTrue(h.geocoder.searches.isEmpty())
        assertEquals(SearchUiState.Empty, h.viewModel.search.value)
    }

    @Test
    fun `search is debounced, biased to the current fix, and results carry a distance`() = runTest(dispatcher.scheduler) {
        val h = harness(FakeGeocodingEngine(searchResult = GeocodeResult.Success(listOf(airport))))
        h.location.emit(fix(here)); runCurrent()

        h.viewModel.onSearchQueryChanged("air")
        h.viewModel.onSearchQueryChanged("airp")
        advanceTimeBy(200L); runCurrent()
        assertTrue("still inside the debounce window", h.geocoder.searches.isEmpty())

        advanceTimeBy(300L); runCurrent()

        assertEquals(listOf("airp" to here), h.geocoder.searches)
        val result = h.viewModel.search.value.results.single()
        assertEquals(airport, result.place)
        assertEquals("1.0 km", result.distanceText)
        assertNull(h.viewModel.search.value.error)
    }

    @Test
    fun `without a fix the search is unbiased and results have no distance`() = runTest(dispatcher.scheduler) {
        val h = harness(FakeGeocodingEngine(searchResult = GeocodeResult.Success(listOf(airport))))

        h.viewModel.onSearchQueryChanged("airport")
        advanceTimeBy(500L); runCurrent()

        assertEquals(listOf("airport" to null), h.geocoder.searches)
        assertNull(h.viewModel.search.value.results.single().distanceText)
    }

    @Test
    fun `a geocoder failure is a visible error not an empty list`() = runTest(dispatcher.scheduler) {
        val h = harness(FakeGeocodingEngine(searchResult = GeocodeResult.Failure("boom")))

        h.viewModel.onSearchQueryChanged("airport")
        advanceTimeBy(500L); runCurrent()

        assertEquals(SEARCH_UNAVAILABLE, h.viewModel.search.value.error)
        assertTrue(h.viewModel.search.value.results.isEmpty())
    }

    @Test
    fun `clearing the query clears results immediately`() = runTest(dispatcher.scheduler) {
        val h = harness(FakeGeocodingEngine(searchResult = GeocodeResult.Success(listOf(airport))))
        h.viewModel.onSearchQueryChanged("airport")
        advanceTimeBy(500L); runCurrent()
        assertEquals(1, h.viewModel.search.value.results.size)

        h.viewModel.onSearchQueryChanged("")
        runCurrent()

        assertEquals(SearchUiState.Empty, h.viewModel.search.value)
    }

    @Test
    fun `choosing a destination enters overview, start enters guidance, end returns to planning`() = runTest(dispatcher.scheduler) {
        val h = harness()
        h.location.emit(fix(here)); runCurrent()
        assertEquals(ScreenMode.Planning, h.viewModel.screenMode.value)

        h.viewModel.selectDestination(airport)
        runCurrent()
        assertEquals(ScreenMode.Overview, h.viewModel.screenMode.value)
        assertEquals(airport, h.viewModel.destination.value)
        assertEquals(1, h.routing.requests.size)

        h.viewModel.startGuidance()
        runCurrent()
        assertEquals(ScreenMode.Guidance, h.viewModel.screenMode.value)
        assertTrue(h.viewModel.followMode.value)

        h.viewModel.endNavigation()
        runCurrent()
        assertEquals(ScreenMode.Planning, h.viewModel.screenMode.value)
        assertNull(h.viewModel.destination.value)
    }

    @Test
    fun `guidance ends in arrived when the fix reaches the destination`() = runTest(dispatcher.scheduler) {
        val h = harness()
        h.location.emit(fix(here)); runCurrent()
        h.viewModel.selectDestination(airport); runCurrent()
        h.viewModel.startGuidance(); runCurrent()

        h.location.emit(fix(airport.point)); runCurrent()

        assertEquals(ScreenMode.Arrived, h.viewModel.screenMode.value)
    }

    @Test
    fun `a new destination while guiding drops back to overview`() = runTest(dispatcher.scheduler) {
        val h = harness()
        h.location.emit(fix(here)); runCurrent()
        h.viewModel.selectDestination(airport); runCurrent()
        h.viewModel.startGuidance(); runCurrent()

        h.viewModel.selectDestination(Place("Station", GeoPoint(0.0, 0.02))); runCurrent()

        assertEquals(ScreenMode.Overview, h.viewModel.screenMode.value)
    }

    @Test
    fun `a user gesture breaks follow mode and recenter restores it`() = runTest(dispatcher.scheduler) {
        val h = harness()

        h.viewModel.onUserMovedMap()
        assertFalse(h.viewModel.followMode.value)

        h.viewModel.recenter()
        assertTrue(h.viewModel.followMode.value)
    }

    @Test
    fun `a map tap is labelled by its coordinates and then renamed by reverse geocoding`() = runTest(dispatcher.scheduler) {
        val tapped = GeoPoint(0.0, 0.0090)
        val h = harness(FakeGeocodingEngine(reverseResult = Place("Terminal 1", tapped, address = "Dhaka")))
        h.location.emit(fix(here)); runCurrent()

        h.viewModel.selectMapPoint(tapped)
        assertEquals("0.0000, 0.0090", h.viewModel.destination.value?.name)
        runCurrent()

        assertEquals(listOf(tapped), h.geocoder.reverses)
        assertEquals("Terminal 1", h.viewModel.destination.value?.name)
        assertEquals(ScreenMode.Overview, h.viewModel.screenMode.value)
        assertEquals(listOf("Terminal 1"), h.viewModel.recentDestinations.value.map { it.name })
    }

    @Test
    fun `a reverse geocode that fails leaves the coordinate label in place`() = runTest(dispatcher.scheduler) {
        val tapped = GeoPoint(0.0, 0.0090)
        val h = harness(FakeGeocodingEngine(reverseResult = null))

        h.viewModel.selectMapPoint(tapped); runCurrent()

        assertEquals("0.0000, 0.0090", h.viewModel.destination.value?.name)
    }

    @Test
    fun `selecting a destination records it in recents and clears the search`() = runTest(dispatcher.scheduler) {
        val h = harness()
        h.viewModel.onSearchQueryChanged("airport")

        h.viewModel.selectDestination(airport); runCurrent()

        assertEquals(listOf(airport), h.viewModel.recentDestinations.value)
        assertEquals("", h.viewModel.searchQuery.value)
    }

    @Test
    fun `avoid tolls reaches the router and the ui state`() = runTest(dispatcher.scheduler) {
        val h = harness()
        h.location.emit(fix(here)); runCurrent()
        h.viewModel.selectDestination(airport); runCurrent()
        assertFalse(h.viewModel.uiState.value.avoidTolls)

        h.viewModel.setAvoidTolls(true); runCurrent()

        assertEquals(listOf(false, true), h.routing.requests.map { it.avoidTolls })
        assertTrue(h.viewModel.uiState.value.avoidTolls)
    }

    @Test
    fun `swapping endpoints with your location as start routes from the destination back to the fix`() = runTest(dispatcher.scheduler) {
        val h = harness()
        h.location.emit(fix(here)); runCurrent()
        h.viewModel.selectDestination(airport); runCurrent()
        assertNull(h.viewModel.origin.value)

        h.viewModel.swapEndpoints(); runCurrent()

        assertEquals(airport, h.viewModel.origin.value)
        assertEquals(here, h.viewModel.destination.value?.point)
        assertEquals("Your location", h.viewModel.destination.value?.name)
        val last = h.routing.requests.last()
        assertEquals(airport.point, last.origin)
        assertEquals(here, last.destination)
    }

    @Test
    fun `swapping two explicit places exchanges them`() = runTest(dispatcher.scheduler) {
        val station = Place("Station", GeoPoint(0.0, 0.02))
        val h = harness()
        h.location.emit(fix(here)); runCurrent()
        h.viewModel.selectOrigin(station); runCurrent()
        h.viewModel.selectDestination(airport); runCurrent()

        h.viewModel.swapEndpoints(); runCurrent()

        assertEquals(airport, h.viewModel.origin.value)
        assertEquals(station, h.viewModel.destination.value)
        assertEquals(RouteRequest(airport.point, station.point), h.routing.requests.last().copy(avoidTolls = false))
    }

    @Test
    fun `swap without a destination or without a fix does nothing`() = runTest(dispatcher.scheduler) {
        val h = harness()
        h.viewModel.swapEndpoints(); runCurrent()
        assertNull(h.viewModel.origin.value)
        assertNull(h.viewModel.destination.value)

        h.viewModel.selectDestination(airport); runCurrent() // no fix yet
        h.viewModel.swapEndpoints(); runCurrent()
        assertNull(h.viewModel.origin.value)
        assertEquals(airport, h.viewModel.destination.value)
    }

    @Test
    fun `use current location clears an explicit origin`() = runTest(dispatcher.scheduler) {
        val h = harness()
        h.viewModel.selectOrigin(Place("Station", GeoPoint(0.0, 0.02))); runCurrent()
        assertEquals("Station", h.viewModel.origin.value?.name)

        h.viewModel.useCurrentLocationAsOrigin(); runCurrent()

        assertNull(h.viewModel.origin.value)
    }
}
