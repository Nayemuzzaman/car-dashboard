package com.csjotlab.cardashboard.nav.map

import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.engine.RouteIndex
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * MapLibre-backed [NavigationMap].
 *
 * It owns the MapLibre-specific rendering (route lines, markers, camera) and nothing else. Route
 * progress, heading and zoom *decisions* are made in `nav.engine`; this class only draws them.
 * Every piece of drawn state is cached so a style switch (day ⇄ night) can redraw it.
 */
class MapLibreNavigationMap(
    private val map: MapLibreMap,
    private val styleProvider: MapStyleProvider,
    private val density: Float,
    /** The map's view, watched for touches so a finger always beats the follow camera. */
    touchView: View? = null,
) : NavigationMap {

    private var onTap: ((GeoPoint) -> Unit)? = null
    private var onUserGesture: (() -> Unit)? = null
    private var onBearing: ((Float) -> Unit)? = null
    private var currentStyle: MapStyle? = null
    private var loadedStyle: Style? = null

    private var route: Route? = null
    private var routeIndex: RouteIndex? = null
    private var alternatives: List<Route> = emptyList()
    private var drawnAlong: Float? = null
    private var pendingAlong: Float? = null
    private var vehicle: VehicleMarker? = null
    private var destination: GeoPoint? = null
    private var searchResults: List<GeoPoint> = emptyList()

    private val controller = VehicleCameraController(map) { position, bearing -> drawVehicle(position, bearing) }

    init {
        map.addOnMapClickListener { latLng ->
            onTap?.invoke(GeoPoint(latLng.latitude, latLng.longitude))
            true
        }
        // One listener covers every gesture (pan, pinch, rotate, tilt, double-tap, fling). Following
        // stops here, synchronously, so the next animation frame never fights the finger.
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) userGesture()
        }
        // While following, the camera is moved every animation frame, and each move cancels a drag
        // MapLibre has not recognised yet — so a finger would never win. Watch the touch stream
        // directly: a finger down pauses the camera at once; moving past the touch slop or a second
        // finger is a gesture and ends following. A plain tap is neither.
        touchView?.let { view ->
            val slop = ViewConfiguration.get(view.context).scaledTouchSlop
            var downX = 0f
            var downY = 0f
            view.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        gestureInThisTouch = false
                        controller.touching = true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> userGesture()
                    MotionEvent.ACTION_MOVE ->
                        if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) userGesture()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> controller.touching = false
                }
                false // MapLibre still handles every touch
            }
        }
        map.addOnCameraMoveListener { onBearing?.invoke(map.cameraPosition.bearing.toFloat()) }
        // ODbL requires the OSM attribution to stay visible: bottom-start, lifted above the bottom
        // overlay by setOverlayInsets.
        map.uiSettings.attributionGravity = Gravity.BOTTOM or Gravity.START
        map.uiSettings.logoGravity = Gravity.BOTTOM or Gravity.START
        setOverlayInsets(MapInsets())
        map.uiSettings.isCompassEnabled = false // the screen draws its own, larger compass
        map.uiSettings.isTiltGesturesEnabled = true
        map.uiSettings.isDoubleTapGesturesEnabled = true
        map.setMinZoomPreference(MIN_ZOOM)
        map.setMaxZoomPreference(MAX_ZOOM)
    }

    private var gestureInThisTouch = false

    private fun userGesture() {
        if (gestureInThisTouch && !controller.following) return
        gestureInThisTouch = true
        controller.stopFollowing()
        onUserGesture?.invoke()
    }

    override fun setStyle(style: MapStyle) {
        if (currentStyle == style) return
        currentStyle = style
        loadedStyle = null
        map.setStyle(styleProvider.style(style)) { loaded ->
            install(loaded, style)
            loadedStyle = loaded
            redrawAll()
        }
    }

    override fun showRoute(route: Route?, alternatives: List<Route>) {
        if (route !== this.route) {
            this.route = route
            routeIndex = route?.let(::RouteIndex)
            drawnAlong = null
            pendingAlong = null
        }
        this.alternatives = alternatives
        drawRoute()
        drawAlternatives()
    }

    override fun setRouteProgress(alongMeters: Float?) {
        // Cut the line where the marker is gliding *from*, so the blue line always starts under or
        // behind the car, never ahead of it while the marker catches up with the newest fix.
        drawnAlong = pendingAlong
        pendingAlong = alongMeters
        if (alongMeters == null) drawnAlong = null
        drawRoute()
    }

    override fun updateVehicle(marker: VehicleMarker?) {
        vehicle = marker
        controller.setVehicle(marker?.position, marker?.bearingDegrees)
        if (marker == null) source(VEHICLE_SOURCE)?.setGeoJson(EMPTY)
    }

    override fun showDestination(point: GeoPoint?) {
        destination = point
        drawDestination()
    }

    override fun showSearchResults(points: List<GeoPoint>) {
        searchResults = points
        drawSearchResults()
    }

    override fun follow(headingUp: Boolean, zoom: Double, insets: MapInsets, vehicleFraction: Double) {
        controller.follow(headingUp, zoom, insets, vehicleFraction)
    }

    override fun stopFollowing() {
        controller.stopFollowing()
    }

    override fun centerOn(point: GeoPoint, zoom: Double, animateMs: Long) {
        controller.stopFollowing()
        val camera = CameraPosition.Builder()
            .target(LatLng(point.latitude, point.longitude))
            .zoom(zoom)
            .bearing(0.0)
            .tilt(0.0)
            .padding(0.0, 0.0, 0.0, 0.0)
            .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(camera), animateMs.toInt())
    }

    override fun fitPoints(points: List<GeoPoint>, insets: MapInsets) {
        controller.stopFollowing()
        val distinct = points.distinct()
        if (distinct.isEmpty()) return
        if (distinct.size == 1) {
            centerOn(distinct.single(), SINGLE_POINT_ZOOM, FIT_ANIMATION_MS.toLong())
            return
        }
        val bounds = LatLngBounds.Builder().apply { distinct.forEach { include(LatLng(it.latitude, it.longitude)) } }.build()
        val margin = (FIT_MARGIN_DP * density).toInt()
        map.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0))
        map.animateCamera(
            CameraUpdateFactory.newLatLngBounds(
                bounds, 0.0, 0.0,
                insets.left + margin, insets.top + margin, insets.right + margin, insets.bottom + margin,
            ),
            FIT_ANIMATION_MS,
        )
    }

    override fun zoomIn() = zoomBy(1.0)

    override fun zoomOut() = zoomBy(-1.0)

    private fun zoomBy(delta: Double) {
        if (controller.following) controller.nudgeZoom(delta)
        else map.animateCamera(if (delta > 0) CameraUpdateFactory.zoomIn() else CameraUpdateFactory.zoomOut(), ZOOM_ANIMATION_MS)
    }

    override fun resetNorth() {
        controller.stopFollowing()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(map.cameraPosition).bearing(0.0).tilt(0.0).build()), RESET_ANIMATION_MS)
    }

    override fun setOverlayInsets(insets: MapInsets) {
        val side = (ATTRIBUTION_MARGIN_DP * density).toInt() + insets.left
        val bottom = (ATTRIBUTION_MARGIN_DP * density).toInt() + insets.bottom
        val logoWidth = (LOGO_WIDTH_DP * density).toInt()
        map.uiSettings.setLogoMargins(side, 0, 0, bottom)
        map.uiSettings.setAttributionMargins(side + logoWidth, 0, 0, bottom)
    }

    override fun setMapTapListener(listener: (GeoPoint) -> Unit) {
        onTap = listener
    }

    override fun setUserGestureListener(listener: () -> Unit) {
        onUserGesture = listener
    }

    override fun setBearingListener(listener: (Float) -> Unit) {
        onBearing = listener
    }

    /** Stops the frame loop; call when the map view is destroyed. */
    fun release() {
        controller.release()
    }

    // ---------------------------------------------------------------- style installation

    private fun install(style: Style, mapStyle: MapStyle) {
        val night = mapStyle == MapStyle.Night
        val palette = if (night) NightPalette else DayPalette
        style.addImage(IMAGE_ARROW, MapMarkerBitmaps.vehicleArrow(density, palette.vehicle))
        style.addImage(IMAGE_ARROW_DEGRADED, MapMarkerBitmaps.vehicleArrow(density, DEGRADED))
        style.addImage(IMAGE_DOT, MapMarkerBitmaps.vehicleDot(density, palette.vehicle))
        style.addImage(IMAGE_DOT_DEGRADED, MapMarkerBitmaps.vehicleDot(density, DEGRADED))
        style.addImage(IMAGE_PIN, MapMarkerBitmaps.destinationPin(density, palette.destination))

        listOf(ALTERNATIVES_SOURCE, TRAVELED_SOURCE, REMAINING_SOURCE, SEARCH_SOURCE, DESTINATION_SOURCE, VEHICLE_SOURCE)
            .forEach { style.addSource(GeoJsonSource(it, EMPTY)) }

        // Route lines go above every road and area layer but under the labels drawn after them, so
        // road fills never cover the route and street names stay readable on top of it. (A style's
        // *first* symbol layer can come before its roads, so that is not a safe anchor.)
        val layers = style.layers
        val lastGeometry = layers.indexOfLast { it !is SymbolLayer }
        val labels = layers.drop(lastGeometry + 1).firstOrNull()?.id
        fun below(layer: Layer) = if (labels != null) style.addLayerBelow(layer, labels) else style.addLayer(layer)
        below(line(ALTERNATIVES_LAYER, ALTERNATIVES_SOURCE, palette.alternative, 6f))
        below(line(CASING_LAYER, REMAINING_SOURCE, palette.casing, 11f))
        below(line(TRAVELED_LAYER, TRAVELED_SOURCE, palette.traveled, 7f))
        below(line(REMAINING_LAYER, REMAINING_SOURCE, palette.route, 7f))

        style.addLayer(
            CircleLayer(SEARCH_LAYER, SEARCH_SOURCE).withProperties(
                PropertyFactory.circleColor(palette.destination),
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleStrokeColor(WHITE),
                PropertyFactory.circleStrokeWidth(2.5f),
            ),
        )
        style.addLayer(
            SymbolLayer(DESTINATION_LAYER, DESTINATION_SOURCE).withProperties(
                PropertyFactory.iconImage(IMAGE_PIN),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
        style.addLayer(
            SymbolLayer(VEHICLE_LAYER, VEHICLE_SOURCE).withProperties(
                PropertyFactory.iconImage(Expression.get(PROP_ICON)),
                PropertyFactory.iconRotate(Expression.get(PROP_BEARING)),
                // Rotate and lie flat with the map, so the arrow points along the road in 3D too.
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
    }

    private fun line(id: String, source: String, color: Int, width: Float) = LineLayer(id, source).withProperties(
        PropertyFactory.lineColor(color),
        PropertyFactory.lineWidth(width),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    )

    // ---------------------------------------------------------------- drawing

    private fun source(id: String): GeoJsonSource? = loadedStyle?.getSourceAs(id)

    private fun redrawAll() {
        drawRoute()
        drawAlternatives()
        drawDestination()
        drawSearchResults()
        controller.shownPosition?.let { drawVehicle(it, vehicle?.bearingDegrees) }
    }

    private fun drawRoute() {
        val index = routeIndex
        if (index == null || index.route.geometry.size < 2) {
            source(TRAVELED_SOURCE)?.setGeoJson(EMPTY)
            source(REMAINING_SOURCE)?.setGeoJson(EMPTY)
            return
        }
        val along = drawnAlong
        if (along == null) {
            source(TRAVELED_SOURCE)?.setGeoJson(EMPTY)
            source(REMAINING_SOURCE)?.setGeoJson(lineFeature(index.route.geometry))
            return
        }
        val (traveled, remaining) = index.split(along.toDouble())
        source(TRAVELED_SOURCE)?.setGeoJson(if (traveled.size >= 2) lineFeature(traveled) else EMPTY)
        source(REMAINING_SOURCE)?.setGeoJson(if (remaining.size >= 2) lineFeature(remaining) else EMPTY)
    }

    private fun drawAlternatives() {
        source(ALTERNATIVES_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(alternatives.filter { it.geometry.size >= 2 }.map { Feature.fromGeometry(lineString(it.geometry)) }),
        )
    }

    private fun drawDestination() {
        source(DESTINATION_SOURCE)?.setGeoJson(destination?.let { FeatureCollection.fromFeature(Feature.fromGeometry(point(it))) } ?: EMPTY)
    }

    private fun drawSearchResults() {
        source(SEARCH_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(searchResults.map { Feature.fromGeometry(point(it)) }))
    }

    private fun drawVehicle(position: GeoPoint, bearing: Float?) {
        val degraded = vehicle?.degraded == true
        val icon = when {
            bearing == null && degraded -> IMAGE_DOT_DEGRADED
            bearing == null -> IMAGE_DOT
            degraded -> IMAGE_ARROW_DEGRADED
            else -> IMAGE_ARROW
        }
        val feature = Feature.fromGeometry(point(position)).apply {
            addStringProperty(PROP_ICON, icon)
            addNumberProperty(PROP_BEARING, bearing ?: 0f)
        }
        source(VEHICLE_SOURCE)?.setGeoJson(feature)
    }

    private fun point(p: GeoPoint) = Point.fromLngLat(p.longitude, p.latitude)
    private fun lineString(points: List<GeoPoint>) = LineString.fromLngLats(points.map(::point))
    private fun lineFeature(points: List<GeoPoint>) = FeatureCollection.fromFeature(Feature.fromGeometry(lineString(points)))

    private class Palette(val route: Int, val casing: Int, val traveled: Int, val alternative: Int, val vehicle: Int, val destination: Int)

    private companion object {
        const val ALTERNATIVES_SOURCE = "nav-alternatives"
        const val TRAVELED_SOURCE = "nav-traveled"
        const val REMAINING_SOURCE = "nav-remaining"
        const val SEARCH_SOURCE = "nav-search"
        const val DESTINATION_SOURCE = "nav-destination"
        const val VEHICLE_SOURCE = "nav-vehicle"
        const val ALTERNATIVES_LAYER = "nav-alternatives-line"
        const val CASING_LAYER = "nav-route-casing"
        const val TRAVELED_LAYER = "nav-traveled-line"
        const val REMAINING_LAYER = "nav-route-line"
        const val SEARCH_LAYER = "nav-search-dots"
        const val DESTINATION_LAYER = "nav-destination-pin"
        const val VEHICLE_LAYER = "nav-vehicle-marker"
        const val IMAGE_ARROW = "nav-arrow"
        const val IMAGE_ARROW_DEGRADED = "nav-arrow-degraded"
        const val IMAGE_DOT = "nav-dot"
        const val IMAGE_DOT_DEGRADED = "nav-dot-degraded"
        const val IMAGE_PIN = "nav-pin"
        const val PROP_ICON = "icon"
        const val PROP_BEARING = "bearing"

        const val MIN_ZOOM = 2.0
        const val MAX_ZOOM = 20.0
        const val SINGLE_POINT_ZOOM = 16.0
        const val FIT_MARGIN_DP = 32f
        const val FIT_ANIMATION_MS = 700
        const val ZOOM_ANIMATION_MS = 250
        const val RESET_ANIMATION_MS = 400
        const val ATTRIBUTION_MARGIN_DP = 8f
        const val LOGO_WIDTH_DP = 90f

        val EMPTY: FeatureCollection = FeatureCollection.fromFeatures(emptyList())

        const val WHITE = 0xFFFFFFFF.toInt()
        const val DEGRADED = 0xFF8A94A6.toInt()

        // Day: saturated blue on the light basemap. Night: lighter blue that stays visible on the
        // dark basemap without glaring.
        val DayPalette = Palette(
            route = 0xFF1D6FE8.toInt(), casing = 0xFF0B3F91.toInt(), traveled = 0xFFA3AAB5.toInt(),
            alternative = 0xFF8E9BB0.toInt(), vehicle = 0xFF1D6FE8.toInt(), destination = 0xFFD93025.toInt(),
        )
        val NightPalette = Palette(
            route = 0xFF60A5FA.toInt(), casing = 0xFF1E3A8A.toInt(), traveled = 0xFF5B6473.toInt(),
            alternative = 0xFF64748B.toInt(), vehicle = 0xFF60A5FA.toInt(), destination = 0xFFF87171.toInt(),
        )
    }
}
