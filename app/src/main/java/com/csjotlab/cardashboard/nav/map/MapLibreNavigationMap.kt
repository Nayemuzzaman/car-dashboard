package com.csjotlab.cardashboard.nav.map

import android.view.Gravity
import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Route
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.RotateGestureDetector
import org.maplibre.android.gestures.StandardScaleGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * MapLibre-backed [NavigationMap].
 *
 * It owns the small amount of MapLibre-specific rendering (a route line, a position dot, a
 * destination pin, and the camera) and nothing else. Heading smoothing and route/math decisions
 * live in `nav.engine`, not here.
 */
class MapLibreNavigationMap(
    private val map: MapLibreMap,
    private val styleProvider: MapStyleProvider,
    attributionTopMarginPx: Int,
) : NavigationMap {

    private var onTap: ((GeoPoint) -> Unit)? = null
    private var onUserGesture: (() -> Unit)? = null
    private var routeSource: GeoJsonSource? = null
    private var positionSource: GeoJsonSource? = null
    private var destinationSource: GeoJsonSource? = null
    private var routeToShow: Route? = null
    private var positionToShow: GeoPoint? = null
    private var destinationToShow: GeoPoint? = null
    private var currentStyle: MapStyle? = null
    private var followZoom = DEFAULT_FOLLOW_ZOOM

    init {
        map.addOnMapClickListener { latLng ->
            onTap?.invoke(GeoPoint(latLng.latitude, latLng.longitude))
            true
        }
        map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
            override fun onMoveBegin(detector: MoveGestureDetector) { onUserGesture?.invoke() }
            override fun onMove(detector: MoveGestureDetector) = Unit
            override fun onMoveEnd(detector: MoveGestureDetector) = Unit
        })
        map.addOnScaleListener(object : MapLibreMap.OnScaleListener {
            override fun onScaleBegin(detector: StandardScaleGestureDetector) { onUserGesture?.invoke() }
            override fun onScale(detector: StandardScaleGestureDetector) = Unit
            override fun onScaleEnd(detector: StandardScaleGestureDetector) = Unit
        })
        map.addOnRotateListener(object : MapLibreMap.OnRotateListener {
            override fun onRotateBegin(detector: RotateGestureDetector) { onUserGesture?.invoke() }
            override fun onRotate(detector: RotateGestureDetector) = Unit
            override fun onRotateEnd(detector: RotateGestureDetector) = Unit
        })
        // ODbL requires the OSM attribution to stay visible; the search bar covers the top-left
        // corner and the panel the bottom, so park it just under the search bar.
        map.uiSettings.attributionGravity = Gravity.TOP or Gravity.START
        map.uiSettings.setAttributionMargins(ATTRIBUTION_SIDE_MARGIN_PX, attributionTopMarginPx, 0, 0)
        map.uiSettings.logoGravity = Gravity.TOP or Gravity.START
        map.uiSettings.setLogoMargins(ATTRIBUTION_SIDE_MARGIN_PX, attributionTopMarginPx + LOGO_OFFSET_PX, 0, 0)
        map.uiSettings.isCompassEnabled = false
    }

    override fun setStyle(style: MapStyle) {
        if (currentStyle == style) return
        currentStyle = style
        map.setStyle(styleProvider.style(style)) { loadedStyle ->
            routeSource = null
            positionSource = null
            destinationSource = null
            ensureRouteLayer(loadedStyle)
            ensureDestinationLayer(loadedStyle)
            ensurePositionLayer(loadedStyle)
            routeToShow?.let { drawRoute(it) }
            destinationToShow?.let { drawDestination(it) }
            positionToShow?.let { drawPosition(it) }
        }
    }

    override fun showRoute(route: Route) {
        routeToShow = route
        map.getStyle { style ->
            ensureRouteLayer(style)
            drawRoute(route)
        }
    }

    override fun clearRoute() {
        routeToShow = null
        map.getStyle { style ->
            if (routeSource != null) {
                style.removeLayer(ROUTE_LAYER_ID)
                style.removeSource(ROUTE_SOURCE_ID)
                routeSource = null
            }
        }
    }

    override fun showPosition(point: GeoPoint) {
        positionToShow = point
        map.getStyle { style ->
            ensurePositionLayer(style)
            drawPosition(point)
        }
    }

    override fun showDestination(point: GeoPoint) {
        destinationToShow = point
        map.getStyle { style ->
            ensureDestinationLayer(style)
            drawDestination(point)
        }
    }

    override fun clearDestination() {
        destinationToShow = null
        map.getStyle { style ->
            if (destinationSource != null) {
                style.removeLayer(DESTINATION_LAYER_ID)
                style.removeSource(DESTINATION_SOURCE_ID)
                destinationSource = null
            }
        }
    }

    override fun centerOn(point: GeoPoint, zoom: Double, animateMs: Long) {
        val camera = CameraPosition.Builder()
            .target(LatLng(point.latitude, point.longitude))
            .zoom(zoom)
            .bearing(0.0)
            .tilt(0.0)
            .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(camera), animateMs.toInt())
    }

    override fun followHeading(bearingDegrees: Float, point: GeoPoint, animateMs: Long) {
        showPosition(point)
        val camera = CameraPosition.Builder()
            .target(LatLng(point.latitude, point.longitude))
            .zoom(followZoom)
            .bearing(bearingDegrees.toDouble())
            .tilt(FOLLOW_TILT)
            .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(camera), animateMs.toInt())
    }

    override fun fitRoute(route: Route, paddingPx: Int, bottomPaddingPx: Int) {
        if (route.geometry.size < 2) return
        val bounds = LatLngBounds.Builder()
            .apply { route.geometry.forEach { include(LatLng(it.latitude, it.longitude)) } }
            .build()
        map.animateCamera(
            CameraUpdateFactory.newLatLngBounds(bounds, 0.0, 0.0, paddingPx, paddingPx, paddingPx, bottomPaddingPx),
            FIT_ANIMATION_MS,
        )
    }

    override fun zoomIn() {
        followZoom = (followZoom + 1.0).coerceAtMost(MAX_ZOOM)
        map.animateCamera(CameraUpdateFactory.zoomIn(), ZOOM_ANIMATION_MS)
    }

    override fun zoomOut() {
        followZoom = (followZoom - 1.0).coerceAtLeast(MIN_ZOOM)
        map.animateCamera(CameraUpdateFactory.zoomOut(), ZOOM_ANIMATION_MS)
    }

    override fun setInteractionEnabled(enabled: Boolean) {
        map.uiSettings.isRotateGesturesEnabled = enabled
        map.uiSettings.isScrollGesturesEnabled = enabled
        map.uiSettings.isZoomGesturesEnabled = enabled
    }

    override fun setMapTapListener(listener: (GeoPoint) -> Unit) {
        onTap = listener
    }

    override fun setUserGestureListener(listener: () -> Unit) {
        onUserGesture = listener
    }

    private fun ensureRouteLayer(style: Style) {
        if (routeSource != null) return
        val source = GeoJsonSource(ROUTE_SOURCE_ID, EMPTY_FEATURE_COLLECTION)
        style.addSource(source)
        val layer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID)
        layer.withProperties(
            PropertyFactory.lineColor(ROUTE_COLOR),
            PropertyFactory.lineWidth(ROUTE_WIDTH),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
        )
        style.addLayer(layer)
        routeSource = source
    }

    private fun drawRoute(route: Route) {
        routeSource?.setGeoJson(routeGeoJson(route))
    }

    private fun ensureDestinationLayer(style: Style) {
        if (destinationSource != null) return
        val source = GeoJsonSource(DESTINATION_SOURCE_ID, EMPTY_FEATURE_COLLECTION)
        style.addSource(source)
        val layer = CircleLayer(DESTINATION_LAYER_ID, DESTINATION_SOURCE_ID)
        layer.withProperties(
            PropertyFactory.circleColor(DESTINATION_COLOR),
            PropertyFactory.circleRadius(DESTINATION_RADIUS),
            PropertyFactory.circleStrokeWidth(DESTINATION_STROKE_WIDTH),
            PropertyFactory.circleStrokeColor(POSITION_STROKE_COLOR),
        )
        style.addLayer(layer)
        destinationSource = source
    }

    private fun drawDestination(point: GeoPoint) {
        destinationSource?.setGeoJson(pointGeoJson(point))
    }

    private fun ensurePositionLayer(style: Style) {
        if (positionSource != null) return
        val source = GeoJsonSource(POSITION_SOURCE_ID, EMPTY_FEATURE_COLLECTION)
        style.addSource(source)
        val layer = CircleLayer(POSITION_LAYER_ID, POSITION_SOURCE_ID)
        layer.withProperties(
            PropertyFactory.circleColor(POSITION_COLOR),
            PropertyFactory.circleRadius(POSITION_RADIUS),
            PropertyFactory.circleStrokeWidth(POSITION_STROKE_WIDTH),
            PropertyFactory.circleStrokeColor(POSITION_STROKE_COLOR),
        )
        style.addLayer(layer)
        positionSource = source
    }

    private fun drawPosition(point: GeoPoint) {
        positionSource?.setGeoJson(pointGeoJson(point))
    }

    private fun routeGeoJson(route: Route): String {
        val coordinates = route.geometry.joinToString(",") { "[${it.longitude},${it.latitude}]" }
        return """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coordinates]},"properties":{}}]}"""
    }

    private fun pointGeoJson(point: GeoPoint): String =
        """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[${point.longitude},${point.latitude}]},"properties":{}}]}"""

    private companion object {
        const val ROUTE_SOURCE_ID = "nav-route-source"
        const val ROUTE_LAYER_ID = "nav-route-layer"
        const val POSITION_SOURCE_ID = "nav-position-source"
        const val POSITION_LAYER_ID = "nav-position-layer"
        const val DESTINATION_SOURCE_ID = "nav-destination-source"
        const val DESTINATION_LAYER_ID = "nav-destination-layer"
        const val EMPTY_FEATURE_COLLECTION = """{"type":"FeatureCollection","features":[]}"""
        const val ROUTE_WIDTH = 6f
        const val POSITION_RADIUS = 10f
        const val POSITION_STROKE_WIDTH = 2f
        const val DESTINATION_RADIUS = 9f
        const val DESTINATION_STROKE_WIDTH = 3f
        const val DEFAULT_FOLLOW_ZOOM = 17.0
        const val MIN_ZOOM = 3.0
        const val MAX_ZOOM = 20.0
        const val FOLLOW_TILT = 0.0
        const val FIT_ANIMATION_MS = 600
        const val ZOOM_ANIMATION_MS = 200
        const val ATTRIBUTION_SIDE_MARGIN_PX = 16
        const val LOGO_OFFSET_PX = 40

        val ROUTE_COLOR = 0xFF38BDF8.toInt() // DashboardAccent
        val POSITION_COLOR = 0xFFF97316.toInt() // DashboardWarning
        val DESTINATION_COLOR = 0xFF38BDF8.toInt() // DashboardAccent
        val POSITION_STROKE_COLOR = 0xFFFFFFFF.toInt()
    }
}
