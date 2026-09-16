package com.csjotlab.cardashboard.nav.map

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Route

enum class MapStyle { Day, Night }

/**
 * The map-rendering seam. MapLibre is wrapped behind this the same way `usb-serial` is wrapped
 * behind `VehicleTransport`, so the rendering library stays replaceable and the navigation logic
 * stays testable without a map.
 */
interface NavigationMap {
    fun setStyle(style: MapStyle)
    fun showRoute(route: Route)
    fun clearRoute()
    fun showPosition(point: GeoPoint)
    fun showDestination(point: GeoPoint)
    fun clearDestination()
    /** North-up camera on [point]; used while planning so the driver sees where they are. */
    fun centerOn(point: GeoPoint, zoom: Double, animateMs: Long)
    /** Heading-up camera on [point] at the current follow zoom. */
    fun followHeading(bearingDegrees: Float, point: GeoPoint, animateMs: Long)
    /** North-up camera fitting the whole route inside [paddingPx], with [bottomPaddingPx] clearing the overview panel. */
    fun fitRoute(route: Route, paddingPx: Int, bottomPaddingPx: Int)
    fun zoomIn()
    fun zoomOut()
    fun setInteractionEnabled(enabled: Boolean)
    fun setMapTapListener(listener: (GeoPoint) -> Unit)
    /** Fires at the start of any user pan / pinch / rotate — never for programmatic camera moves. */
    fun setUserGestureListener(listener: () -> Unit)
}
