package com.csjotlab.cardashboard.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.csjotlab.cardashboard.nav.data.NavigationRepository
import com.csjotlab.cardashboard.nav.data.NavigationSession
import com.csjotlab.cardashboard.nav.data.RecentDestinationsStore
import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.NavigationPhase
import com.csjotlab.cardashboard.nav.domain.NavigationState
import com.csjotlab.cardashboard.nav.domain.toLabel
import com.csjotlab.cardashboard.nav.geocoding.GeocodeResult
import com.csjotlab.cardashboard.nav.geocoding.GeocodingEngine
import com.csjotlab.cardashboard.nav.geocoding.Place
import com.csjotlab.cardashboard.nav.geocoding.PlaceCategory
import com.csjotlab.cardashboard.nav.map.MapThemeMode
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the camera is doing: following the car, left where the user put it, or showing the whole route. */
enum class CameraMode { Follow, Free, Overview }

/**
 * Screen flags (camera mode), the search policy, and the driver's choices. The trip itself
 * (destination point, route, guidance on/off, position) lives in [NavigationRepository]; the labels
 * and view preferences live in [NavigationSession] — both outlive this ViewModel, so leaving the map
 * mid-trip and coming back finds the same trip. The screen only renders what this class exposes.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class NavigationViewModel(
    private val repository: NavigationRepository,
    private val recentStore: RecentDestinationsStore,
    private val geocodingEngine: GeocodingEngine,
    private val session: NavigationSession = NavigationSession(),
) : ViewModel() {

    val uiState: StateFlow<NavigationUiState> = repository.snapshot
        .map { snapshot -> NavigationFormatter.toUiState(snapshot, ::formatEtaTime) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NavigationUiState.idle())

    val navigationState: StateFlow<NavigationState> = repository.snapshot
        .map { it.state }
        .stateIn(viewModelScope, SharingStarted.Eagerly, NavigationState.idle())

    /** The other route options, for drawing them on the map in the overview. */
    val routeAlternatives = repository.snapshot
        .map { snapshot -> snapshot.routeOptions.filterIndexed { i, _ -> i != snapshot.selectedRouteIndex } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _cameraMode = MutableStateFlow(CameraMode.Follow)
    val cameraMode: StateFlow<CameraMode> = _cameraMode.asStateFlow()

    private val _followMode = MutableStateFlow(true)
    val followMode: StateFlow<Boolean> = _followMode.asStateFlow()

    private fun setCamera(mode: CameraMode) {
        _cameraMode.value = mode
        _followMode.value = mode == CameraMode.Follow
    }

    val headingUp: StateFlow<Boolean> = session.headingUp.asStateFlow()
    val themeMode: StateFlow<MapThemeMode> = session.themeMode.asStateFlow()

    val screenMode: StateFlow<ScreenMode> = repository.snapshot.map { snapshot ->
        val state = snapshot.state
        when {
            snapshot.destination == null -> ScreenMode.Planning
            !snapshot.guidanceActive || state.route == null -> ScreenMode.Overview
            state.phase == NavigationPhase.Arrived -> ScreenMode.Arrived
            else -> ScreenMode.Guidance
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, ScreenMode.Planning)

    val destination: StateFlow<Place?> = session.destination.asStateFlow()

    /** Explicit start place; null means "your location" (the live GPS fix). */
    val origin: StateFlow<Place?> = session.origin.asStateFlow()

    private val _recentDestinations = MutableStateFlow(recentStore.recent())
    val recentDestinations: StateFlow<List<Place>> = _recentDestinations.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _activeCategory = MutableStateFlow<PlaceCategory?>(null)
    val activeCategory: StateFlow<PlaceCategory?> = _activeCategory.asStateFlow()

    private sealed interface SearchRequest {
        data class Text(val query: String) : SearchRequest
        data class Category(val category: PlaceCategory) : SearchRequest
        data class AwaitingFix(val category: PlaceCategory) : SearchRequest
    }

    private val searchRequest = MutableStateFlow<SearchRequest>(SearchRequest.Text(""))

    /** Whether a fix exists — a category ("nearby") search waits for one and then runs by itself. */
    private val hasFix = repository.snapshot.map { it.state.location.valueOrNull() != null }.distinctUntilChanged()

    val search: StateFlow<SearchUiState> = combine(searchRequest, hasFix) { request, fix ->
        when (request) {
            is SearchRequest.Text -> SearchRequest.Text(request.query.trim())
            is SearchRequest.Category -> if (fix) request else SearchRequest.AwaitingFix(request.category)
            is SearchRequest.AwaitingFix -> request // never stored in searchRequest; derived here only
        }
    }
        .distinctUntilChanged()
        // A cleared/short query or a category tap resets or searches at once; typing waits to settle.
        .debounce { request ->
            if (request is SearchRequest.Text && request.query.length >= MIN_QUERY_LENGTH) SEARCH_DEBOUNCE_MS else 0L
        }
        .transformLatest { request ->
            if (request is SearchRequest.AwaitingFix) {
                emit(SearchUiState(emptyList(), WAITING_FOR_LOCATION_NEARBY))
                return@transformLatest
            }
            if (request is SearchRequest.Category || (request is SearchRequest.Text && request.query.length >= MIN_QUERY_LENGTH)) {
                emit(SearchUiState.Loading)
            }
            emit(runSearch(request))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState.Empty)

    private suspend fun runSearch(request: SearchRequest): SearchUiState {
        val near = currentFix()
        val result = when (request) {
            is SearchRequest.Text -> {
                if (request.query.length < MIN_QUERY_LENGTH) return SearchUiState.Empty
                geocodingEngine.search(request.query, near)
            }
            is SearchRequest.Category -> geocodingEngine.searchCategory(request.category, near)
            is SearchRequest.AwaitingFix -> return SearchUiState(emptyList(), WAITING_FOR_LOCATION_NEARBY)
        }
        return when (result) {
            is GeocodeResult.Success -> SearchUiState(result.places.map { NavigationFormatter.toSearchResult(it, near) }, null)
            is GeocodeResult.Failure -> SearchUiState(emptyList(), SEARCH_UNAVAILABLE)
        }
    }

    private fun currentFix(): GeoPoint? = repository.snapshot.value.state.location.valueOrNull()

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        _activeCategory.value = null
        searchRequest.value = SearchRequest.Text(query)
    }

    /** One-tap search for a kind of place near the car. */
    fun searchCategory(category: PlaceCategory) {
        _searchQuery.value = category.label
        _activeCategory.value = category
        searchRequest.value = SearchRequest.Category(category)
    }

    fun clearSearch() = onSearchQueryChanged("")

    fun selectDestination(place: Place) {
        recentStore.record(place)
        _recentDestinations.value = recentStore.recent()
        session.destination.value = place
        setCamera(CameraMode.Overview)
        clearSearch()
        repository.setGuidanceActive(false)
        repository.setDestination(place.point)
    }

    /** A tapped point is usable immediately under a coordinate label; the name follows when known. */
    fun selectMapPoint(point: GeoPoint) {
        selectDestination(Place(point.toLabel(), point))
        viewModelScope.launch {
            val named = geocodingEngine.reverse(point) ?: return@launch
            if (session.destination.value?.point != point) return@launch
            val place = named.copy(point = point)
            recentStore.record(place)
            _recentDestinations.value = recentStore.recent()
            session.destination.value = place
        }
    }

    fun selectOrigin(place: Place) {
        session.origin.value = place
        clearSearch()
        repository.setOrigin(place.point)
    }

    fun useCurrentLocationAsOrigin() {
        session.origin.value = null
        clearSearch()
        repository.setOrigin(null)
    }

    /**
     * Exchanges start and destination. With "your location" as the start, the destination becomes
     * the current fix — so it needs one; without a destination or a fix nothing changes.
     */
    fun swapEndpoints() {
        val currentDestination = session.destination.value ?: return
        val newDestination = session.origin.value
            ?: currentFix()?.let { Place(YOUR_LOCATION_LABEL, it) }
            ?: return
        session.origin.value = currentDestination
        session.destination.value = newDestination
        repository.setGuidanceActive(false)
        repository.setOrigin(currentDestination.point)
        repository.setDestination(newDestination.point)
    }

    fun setAvoidTolls(enabled: Boolean) {
        repository.setAvoidTolls(enabled)
    }

    fun selectRoute(index: Int) {
        repository.selectRoute(index)
    }

    fun retryRoute() {
        repository.retryRoute()
    }

    fun startGuidance() {
        setCamera(CameraMode.Follow)
        repository.setGuidanceActive(true)
    }

    /** Ends the trip from any mode: guidance off, route and destination cleared, back to planning. */
    fun endNavigation() {
        repository.setGuidanceActive(false)
        session.destination.value = null
        setCamera(CameraMode.Follow)
        repository.setDestination(null)
    }

    fun onUserMovedMap() {
        setCamera(CameraMode.Free)
    }

    /** Back to the car: follow, restoring the driver's heading-up/north-up choice. */
    fun recenter() {
        setCamera(CameraMode.Follow)
    }

    /** Show the whole remaining route; Recenter returns to following. */
    fun showRouteOverview() {
        setCamera(CameraMode.Overview)
    }

    fun toggleHeadingUp() {
        session.headingUp.value = !session.headingUp.value
        setCamera(CameraMode.Follow)
    }

    fun setThemeMode(mode: MapThemeMode) {
        session.themeMode.value = mode
    }

    class Factory(
        private val repository: NavigationRepository,
        private val recentStore: RecentDestinationsStore,
        private val geocodingEngine: GeocodingEngine,
        private val session: NavigationSession,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NavigationViewModel(repository, recentStore, geocodingEngine, session) as T
    }

    private companion object {
        const val YOUR_LOCATION_LABEL = "Your location"
        const val MIN_QUERY_LENGTH = 2
        const val SEARCH_DEBOUNCE_MS = 400L
    }
}
