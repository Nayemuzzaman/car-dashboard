package com.csjotlab.cardashboard.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.csjotlab.cardashboard.nav.data.NavigationRepository
import com.csjotlab.cardashboard.nav.data.RecentDestinationsStore
import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.NavigationPhase
import com.csjotlab.cardashboard.nav.domain.NavigationState
import com.csjotlab.cardashboard.nav.domain.toLabel
import com.csjotlab.cardashboard.nav.geocoding.GeocodeResult
import com.csjotlab.cardashboard.nav.geocoding.GeocodingEngine
import com.csjotlab.cardashboard.nav.geocoding.Place
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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Session flags (`guidanceStarted`, `followMode`), the search policy, and the destination label
 * live here; route/position/reroute state stays in [NavigationRepository]. The screen only renders
 * what this class exposes.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class NavigationViewModel(
    private val repository: NavigationRepository,
    private val recentStore: RecentDestinationsStore,
    private val geocodingEngine: GeocodingEngine,
) : ViewModel() {

    val uiState: StateFlow<NavigationUiState> = repository.snapshot
        .map { snapshot -> NavigationFormatter.toUiState(snapshot, ::formatEtaTime) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NavigationUiState.idle())

    val navigationState: StateFlow<NavigationState> = repository.snapshot
        .map { it.state }
        .stateIn(viewModelScope, SharingStarted.Eagerly, NavigationState.idle())

    private val _guidanceStarted = MutableStateFlow(false)

    private val _followMode = MutableStateFlow(true)
    val followMode: StateFlow<Boolean> = _followMode.asStateFlow()

    val screenMode: StateFlow<ScreenMode> = combine(repository.snapshot, _guidanceStarted) { snapshot, started ->
        val state = snapshot.state
        when {
            snapshot.destination == null -> ScreenMode.Planning
            !started || state.route == null -> ScreenMode.Overview
            state.phase == NavigationPhase.Arrived -> ScreenMode.Arrived
            else -> ScreenMode.Guidance
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ScreenMode.Planning)

    private val _destination = MutableStateFlow<Place?>(null)
    val destination: StateFlow<Place?> = _destination.asStateFlow()

    /** Explicit start place; null means "your location" (the live GPS fix). */
    private val _origin = MutableStateFlow<Place?>(null)
    val origin: StateFlow<Place?> = _origin.asStateFlow()

    private val _recentDestinations = MutableStateFlow(recentStore.recent())
    val recentDestinations: StateFlow<List<Place>> = _recentDestinations.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val search: StateFlow<SearchUiState> = _searchQuery
        .map { it.trim() }
        .distinctUntilChanged()
        // A cleared or too-short query resets instantly; a real query waits for typing to settle.
        .debounce { query -> if (query.length < MIN_QUERY_LENGTH) 0L else SEARCH_DEBOUNCE_MS }
        .mapLatest { query -> runSearch(query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState.Empty)

    private suspend fun runSearch(query: String): SearchUiState {
        if (query.length < MIN_QUERY_LENGTH) return SearchUiState.Empty
        val near = repository.snapshot.value.state.location.valueOrNull()
        return when (val result = geocodingEngine.search(query, near)) {
            is GeocodeResult.Success -> SearchUiState(result.places.map { NavigationFormatter.toSearchResult(it, near) }, null)
            is GeocodeResult.Failure -> SearchUiState(emptyList(), SEARCH_UNAVAILABLE)
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun clearSearch() {
        _searchQuery.value = ""
    }

    fun selectDestination(place: Place) {
        recentStore.record(place)
        _recentDestinations.value = recentStore.recent()
        _destination.value = place
        _guidanceStarted.value = false
        clearSearch()
        repository.setDestination(place.point)
    }

    /** A tapped point is usable immediately under a coordinate label; the name follows when known. */
    fun selectMapPoint(point: GeoPoint) {
        selectDestination(Place(point.toLabel(), point))
        viewModelScope.launch {
            val named = geocodingEngine.reverse(point) ?: return@launch
            if (_destination.value?.point != point) return@launch
            val place = named.copy(point = point)
            recentStore.record(place)
            _recentDestinations.value = recentStore.recent()
            _destination.value = place
        }
    }

    fun selectOrigin(place: Place) {
        _origin.value = place
        clearSearch()
        repository.setOrigin(place.point)
    }

    fun useCurrentLocationAsOrigin() {
        _origin.value = null
        clearSearch()
        repository.setOrigin(null)
    }

    /**
     * Exchanges start and destination. With "your location" as the start, the destination becomes
     * the current fix — so it needs one; without a destination or a fix nothing changes.
     */
    fun swapEndpoints() {
        val currentDestination = _destination.value ?: return
        val newDestination = _origin.value
            ?: repository.snapshot.value.state.location.valueOrNull()?.let { Place(YOUR_LOCATION_LABEL, it) }
            ?: return
        _origin.value = currentDestination
        _destination.value = newDestination
        _guidanceStarted.value = false
        repository.setOrigin(currentDestination.point)
        repository.setDestination(newDestination.point)
    }

    fun setAvoidTolls(enabled: Boolean) {
        repository.setAvoidTolls(enabled)
    }

    fun startGuidance() {
        _guidanceStarted.value = true
        _followMode.value = true
    }

    fun endNavigation() {
        _guidanceStarted.value = false
        _followMode.value = true
        _destination.value = null
        repository.setDestination(null)
    }

    fun onUserMovedMap() {
        _followMode.value = false
    }

    fun recenter() {
        _followMode.value = true
    }

    class Factory(
        private val repository: NavigationRepository,
        private val recentStore: RecentDestinationsStore,
        private val geocodingEngine: GeocodingEngine,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NavigationViewModel(repository, recentStore, geocodingEngine) as T
    }

    private companion object {
        const val YOUR_LOCATION_LABEL = "Your location"
        const val MIN_QUERY_LENGTH = 2
        const val SEARCH_DEBOUNCE_MS = 400L
    }
}
