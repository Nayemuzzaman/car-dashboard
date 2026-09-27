package com.csjotlab.cardashboard.ui.navigation

import com.csjotlab.cardashboard.nav.data.NavigationRepository
import com.csjotlab.cardashboard.nav.data.NavigationSession
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
import com.csjotlab.cardashboard.nav.geocoding.Place
import com.csjotlab.cardashboard.nav.geocoding.PlaceCategory
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

/** The trip controls the field report said were missing or broken. */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationViewModelGuidanceTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val here = GeoPoint(0.0, 0.0)
    private val airport = Place("Airport", GeoPoint(0.009, 0.0), address = "Terminal 1")
    private val route = Route(
        origin = here,
        destination = airport.point,
        steps = listOf(
            RouteStep(Maneuver(ManeuverType.Depart, null), 1_000f, 90L, emptyList()),
            RouteStep(Maneuver(ManeuverType.Arrive, null), 0f, 0L, emptyList()),
        ),
        geometry = listOf(here, airport.point),
        totalDistanceMeters = 1_000f,
        totalDurationSeconds = 90L,
    )
    private var fixTime = 0L
    private fun fix(point: GeoPoint) = LocationReading(point, 10f, 0f, 5f, 60_000L * ++fixTime)

    private class Harness(
        val viewModel: NavigationViewModel,
        val repository: NavigationRepository,
        val session: NavigationSession,
        val location: ControllableLocationProvider,
        val geocoder: FakeGeocodingEngine,
        val routing: FakeRoutingEngine,
    )

    private fun TestScope.harness(result: (RouteRequest) -> RouteResult = { RouteResult.Success(route) }): Harness {
        val location = ControllableLocationProvider()
        val routing = FakeRoutingEngine(result)
        val repository = NavigationRepository(routing, location, ControllableHeadingProvider(), Clock { 0L }, backgroundScope)
        val geocoder = FakeGeocodingEngine()
        val session = NavigationSession()
        val viewModel = NavigationViewModel(repository, PersistentRecentDestinationsStore(InMemoryStringStorage()), geocoder, session)
        backgroundScope.launch { viewModel.search.collect {} }
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return Harness(viewModel, repository, session, location, geocoder, routing)
    }

    private fun TestScope.guiding(h: Harness) {
        h.location.emit(fix(here)); runCurrent()
        h.viewModel.selectDestination(airport); runCurrent()
        h.viewModel.startGuidance(); runCurrent()
        assertEquals(ScreenMode.Guidance, h.viewModel.screenMode.value)
    }

    @Test
    fun `end during guidance stops guidance and returns to planning`() = runTest(dispatcher.scheduler) {
        val h = harness()
        guiding(h)

        h.viewModel.endNavigation(); runCurrent()

        assertEquals(ScreenMode.Planning, h.viewModel.screenMode.value)
        assertFalse(h.repository.snapshot.value.guidanceActive)
        assertNull(h.repository.snapshot.value.state.route)
        assertNull(h.viewModel.destination.value)
    }

    @Test
    fun `leaving the map mid-trip and coming back finds the same trip`() = runTest(dispatcher.scheduler) {
        val h = harness()
        guiding(h)

        // Back to the dashboard destroys the ViewModel; reopening the map builds a new one.
        val reopened = NavigationViewModel(h.repository, PersistentRecentDestinationsStore(InMemoryStringStorage()), h.geocoder, h.session)
        runCurrent()

        assertEquals(ScreenMode.Guidance, reopened.screenMode.value)
        assertEquals(airport, reopened.destination.value)

        reopened.endNavigation(); runCurrent()
        assertEquals(ScreenMode.Planning, reopened.screenMode.value)
    }

    @Test
    fun `the orientation toggle flips heading-up and resumes following`() = runTest(dispatcher.scheduler) {
        val h = harness()
        guiding(h)
        h.viewModel.onUserMovedMap()
        assertTrue(h.viewModel.headingUp.value)

        h.viewModel.toggleHeadingUp()

        assertFalse(h.viewModel.headingUp.value)
        assertEquals(CameraMode.Follow, h.viewModel.cameraMode.value)
    }

    @Test
    fun `route overview frees the camera and recenter returns to following`() = runTest(dispatcher.scheduler) {
        val h = harness()
        guiding(h)

        h.viewModel.showRouteOverview()
        assertEquals(CameraMode.Overview, h.viewModel.cameraMode.value)
        assertFalse(h.viewModel.followMode.value)

        h.viewModel.recenter()
        assertEquals(CameraMode.Follow, h.viewModel.cameraMode.value)
        assertTrue(h.viewModel.followMode.value)
    }

    @Test
    fun `selecting a destination shows it in overview before guidance`() = runTest(dispatcher.scheduler) {
        val h = harness()
        h.location.emit(fix(here)); runCurrent()

        h.viewModel.selectDestination(airport); runCurrent()

        assertEquals(ScreenMode.Overview, h.viewModel.screenMode.value)
        assertEquals(CameraMode.Overview, h.viewModel.cameraMode.value)
        assertFalse(h.repository.snapshot.value.guidanceActive)
    }

    @Test
    fun `alternatives are offered and choosing one makes it the route`() = runTest(dispatcher.scheduler) {
        val slower = route.copy(totalDurationSeconds = 150L)
        val h = harness { RouteResult.Success(route, listOf(slower)) }
        h.location.emit(fix(here)); runCurrent()
        h.viewModel.selectDestination(airport); runCurrent()
        assertEquals(2, h.viewModel.uiState.value.routeOptions.size)
        assertEquals(listOf(slower), h.viewModel.routeAlternatives.value)

        h.viewModel.selectRoute(1); runCurrent()

        assertEquals(1, h.viewModel.uiState.value.selectedRouteIndex)
        assertEquals(slower, h.viewModel.navigationState.value.route)
        assertEquals(listOf(route), h.viewModel.routeAlternatives.value)
    }

    @Test
    fun `a failed route can be retried from the overview`() = runTest(dispatcher.scheduler) {
        var fail = true
        val h = harness { if (fail) RouteResult.Failure("offline") else RouteResult.Success(route) }
        h.location.emit(fix(here)); runCurrent()
        h.viewModel.selectDestination(airport); runCurrent()
        assertTrue(h.viewModel.uiState.value.canRetry)

        fail = false
        h.viewModel.retryRoute(); runCurrent()

        assertFalse(h.viewModel.uiState.value.canRetry)
        assertTrue(h.viewModel.uiState.value.hasRoute)
    }

    @Test
    fun `a category chip searches that kind of place at once`() = runTest(dispatcher.scheduler) {
        val h = harness()
        h.location.emit(fix(here)); runCurrent()

        h.viewModel.searchCategory(PlaceCategory.Fuel); runCurrent()

        assertEquals(PlaceCategory.Fuel, h.viewModel.activeCategory.value)
        assertEquals(listOf(PlaceCategory.Fuel.query to here), h.geocoder.searches)

        h.viewModel.onSearchQueryChanged("pa"); runCurrent()
        assertNull("typing leaves the category", h.viewModel.activeCategory.value)
    }

    @Test
    fun `a category search waits for a fix instead of searching the whole world`() = runTest(dispatcher.scheduler) {
        val h = harness()

        h.viewModel.searchCategory(PlaceCategory.Fuel); runCurrent()
        assertEquals(WAITING_FOR_LOCATION_NEARBY, h.viewModel.search.value.error)
        assertTrue(h.geocoder.searches.isEmpty())

        h.location.emit(fix(here)); runCurrent()
        assertEquals("runs by itself once the car is located", listOf(PlaceCategory.Fuel.query to here), h.geocoder.searches)
    }
}
