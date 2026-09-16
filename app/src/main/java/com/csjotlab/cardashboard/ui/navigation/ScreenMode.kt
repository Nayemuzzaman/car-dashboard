package com.csjotlab.cardashboard.ui.navigation

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.geocoding.Place

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
) {
    companion object {
        val Empty = SearchUiState(emptyList(), null)
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
) {
    companion object {
        val None = NavigationActions()
    }
}
