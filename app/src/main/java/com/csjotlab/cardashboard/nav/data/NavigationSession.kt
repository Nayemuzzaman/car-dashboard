package com.csjotlab.cardashboard.nav.data

import com.csjotlab.cardashboard.nav.geocoding.Place
import com.csjotlab.cardashboard.nav.map.MapThemeMode
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the driver chose that must outlive the navigation screen: leaving the map for the dashboard
 * mid-trip and coming back must find the same trip, labelled the same way, with the same view.
 * Process-scoped alongside [NavigationRepository], which holds the trip itself.
 */
class NavigationSession {
    /** The destination as the driver picked it (name, address); the repository has its point. */
    val destination = MutableStateFlow<Place?>(null)

    /** Explicit start place; null means "your location". */
    val origin = MutableStateFlow<Place?>(null)

    /** Heading-up (true) or north-up while following. */
    val headingUp = MutableStateFlow(true)

    val themeMode = MutableStateFlow(MapThemeMode.Auto)
}
