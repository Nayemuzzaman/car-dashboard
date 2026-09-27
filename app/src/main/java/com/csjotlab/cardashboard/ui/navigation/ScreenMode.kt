package com.csjotlab.cardashboard.ui.navigation

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.geocoding.Place
import com.csjotlab.cardashboard.nav.geocoding.PlaceCategory
import com.csjotlab.cardashboard.nav.map.MapThemeMode

/** Which overlay set the full-screen map shows. Derived in [NavigationViewModel], never in the screen. */
enum class ScreenMode { Planning, Overview, Guidance, Arrived }

/** A geocoder hit plus its distance from the current fix, already formatted; null when there is no fix. */
data class SearchResultUi(
    val place: Place,
    val distanceText: String?,
)

data class SearchUiState(
    val results: List<SearchResultUi>,
    val error: String?,
    /** A search is in flight; "no results" must not be claimed yet. */
    val loading: Boolean = false,
) {
    companion object {
        val Empty = SearchUiState(emptyList(), null)
        val Loading = SearchUiState(emptyList(), null, loading = true)
    }
}

/** Every callback the navigation screen can raise, so the screen signature stays readable. */
data class NavigationActions(
    val onSearchQueryChanged: (String) -> Unit = {},
    val onSelectDestination: (Place) -> Unit = {},
    val onSelectOrigin: (Place) -> Unit = {},
    val onUseCurrentLocation: () -> Unit = {},
    val onMapTap: (GeoPoint) -> Unit = {},
    val onStart: () -> Unit = {},
    val onEnd: () -> Unit = {},
    val onRecenter: () -> Unit = {},
    val onUserMovedMap: () -> Unit = {},
    val onSwapEndpoints: () -> Unit = {},
    val onAvoidTollsChanged: (Boolean) -> Unit = {},
    val onBack: () -> Unit = {},
    val onSearchCategory: (PlaceCategory) -> Unit = {},
    val onSelectRoute: (Int) -> Unit = {},
    val onRetryRoute: () -> Unit = {},
    val onShowOverview: () -> Unit = {},
    val onToggleHeadingUp: () -> Unit = {},
    val onThemeModeChanged: (MapThemeMode) -> Unit = {},
    val onRequestLocationPermission: () -> Unit = {},
) {
    companion object {
        val None = NavigationActions()
    }
}
