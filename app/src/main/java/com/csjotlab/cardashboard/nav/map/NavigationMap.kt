package com.csjotlab.cardashboard.nav.map

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Route

enum class MapStyle { Day, Night }

/** The driver's day/night choice; [Auto] follows the sun at the car's position. */
enum class MapThemeMode { Auto, Day, Night }

/** The vehicle as the map should draw it. */
data class VehicleMarker(
    val position: GeoPoint,
    /** Direction of travel; null draws a direction-less dot. */
    val bearingDegrees: Float?,
    /** Weak or lost GPS: drawn greyed out, never moved by guesswork. */
    val degraded: Boolean,
)

/** Space kept clear of overlays, in pixels. */
data class MapInsets(val top: Int = 0, val bottom: Int = 0, val left: Int = 0, val right: Int = 0)

/**
 * The map-rendering seam. MapLibre is wrapped behind this the same way `usb-serial` is wrapped
 * behind `VehicleTransport`, so the rendering library stays replaceable and the navigation logic
 * stays testable without a map.
 */
interface NavigationMap {
    fun setStyle(style: MapStyle)

    /** The active route and the other options; null clears the route. */
    fun showRoute(route: Route?, alternatives: List<Route> = emptyList())

    /** Split the drawn route into driven (grey) and ahead (blue) at [alongMeters]; null = all ahead. */
    fun setRouteProgress(alongMeters: Float?)

    /** Move the vehicle marker; it glides to each new position rather than jumping. Null hides it. */
    fun updateVehicle(marker: VehicleMarker?)

    fun showDestination(point: GeoPoint?)
    fun showSearchResults(points: List<GeoPoint>)

    /**
     * Keep the camera on the vehicle — heading-up (tilted, road ahead at the top) or north-up —
     * at [zoom], drawing the vehicle at [vehicleFraction] of the height left free by [insets]
     * (0.5 centred; higher values leave more road ahead). Calling it again updates the targets;
     * the camera eases, never jumps.
     */
    fun follow(headingUp: Boolean, zoom: Double, insets: MapInsets = MapInsets(), vehicleFraction: Double = 0.5)

    /** Stop following; the camera stays where it is. */
    fun stopFollowing()

    /** North-up, flat camera on [point]. */
    fun centerOn(point: GeoPoint, zoom: Double, animateMs: Long)

    /** North-up camera fitting [points] inside [insets] plus a margin. */
    fun fitPoints(points: List<GeoPoint>, insets: MapInsets)

    fun zoomIn()
    fun zoomOut()

    /** Rotate back to north (and flatten), keeping the centre. */
    fun resetNorth()

    /**
     * Space covered by the screen's overlays. The map keeps its required attribution (OSM data is
     * ODbL: it must stay visible) just above the bottom overlay and beside a side panel.
     */
    fun setOverlayInsets(insets: MapInsets)

    fun setMapTapListener(listener: (GeoPoint) -> Unit)

    /** Fires when the user starts any pan / pinch / rotate / tilt / double-tap — never for app camera moves. */
    fun setUserGestureListener(listener: () -> Unit)

    /** The map's bearing whenever the camera moves, for the compass. */
    fun setBearingListener(listener: (Float) -> Unit)
}
