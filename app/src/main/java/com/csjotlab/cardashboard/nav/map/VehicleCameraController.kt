package com.csjotlab.cardashboard.nav.map

import android.animation.TimeAnimator
import android.os.SystemClock
import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.engine.GeoMath
import com.csjotlab.cardashboard.nav.engine.HeadingMath
import com.csjotlab.cardashboard.nav.engine.NavigationCameraPolicy
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.abs
import kotlin.math.exp

/**
 * Glides the vehicle marker between GPS fixes and, while following, moves the camera with it on the
 * same frame — so the marker and the camera can never drift apart, and the map never steps once a
 * second.
 *
 * Each new fix starts an interpolation from where the marker is *drawn* to the fix, lasting as long
 * as the gap between fixes (so at 1 Hz the marker is always moving, arriving just as the next fix
 * lands). Position is linear, bearing takes the shortest arc. The camera eases toward the marker:
 * position and bearing quickly, zoom and tilt slowly, so a zoom change from the camera policy reads
 * as a gentle dolly rather than a jump. The frame loop stops itself once everything has settled —
 * nothing runs while the car is parked.
 */
internal class VehicleCameraController(
    private val map: MapLibreMap,
    private val render: (GeoPoint, Float?) -> Unit,
) {
    private var fromPosition: GeoPoint? = null
    private var toPosition: GeoPoint? = null
    private var fromBearing: Float? = null
    private var toBearing: Float? = null
    private var animationStartMs = 0L
    private var animationDurationMs = 0L
    private var lastFixAtMs: Long? = null

    var shownPosition: GeoPoint? = null
        private set
    private var shownBearing: Float? = null

    var following = false
        private set

    /** A finger is on the map: the camera must not move under it. */
    var touching = false
    private var headingUp = true
    private var targetZoom = NavigationCameraPolicy.DEFAULT_FOLLOW_ZOOM
    private var targetTilt = 0.0
    private var insets = MapInsets()
    private var vehicleFraction = 0.5

    private var cameraTarget: LatLng? = null
    private var cameraBearing = 0.0
    private var cameraZoom = 0.0
    private var cameraTilt = 0.0

    private val animator = TimeAnimator().apply {
        setTimeListener { _, _, deltaMs -> onFrame(deltaMs.coerceIn(0L, 100L)) }
    }

    fun setVehicle(position: GeoPoint?, bearing: Float?) {
        val now = SystemClock.uptimeMillis()
        if (position == null) {
            toPosition = null
            shownPosition = null
            lastFixAtMs = null
            return
        }
        val shown = shownPosition
        // A parked car's compass wobbles a degree or two many times a second: not worth a frame.
        val shownB = shownBearing
        if (shown != null && GeoMath.distanceMeters(shown, position) < STILL_METERS &&
            (bearing == null || shownB == null || abs(HeadingMath.shortestDelta(shownB, bearing)) < MIN_BEARING_CHANGE_DEGREES)
        ) return
        val jump = shown == null || GeoMath.distanceMeters(shown, position) > MAX_GLIDE_METERS
        fromPosition = if (jump) position else shown
        toPosition = position
        fromBearing = shownBearing ?: bearing
        toBearing = bearing
        animationStartMs = now
        animationDurationMs = if (jump) 0L else lastFixAtMs?.let { (now - it).coerceIn(MIN_GLIDE_MS, MAX_GLIDE_MS) } ?: 0L
        lastFixAtMs = now
        kick()
    }

    fun follow(headingUp: Boolean, zoom: Double, insets: MapInsets, vehicleFraction: Double) {
        if (!following) {
            val camera = map.cameraPosition
            cameraTarget = camera.target
            cameraBearing = camera.bearing
            cameraZoom = camera.zoom
            cameraTilt = camera.tilt
        }
        following = true
        this.headingUp = headingUp
        this.insets = insets
        this.vehicleFraction = vehicleFraction
        targetZoom = zoom
        targetTilt = NavigationCameraPolicy.followTilt(headingUp)
        kick()
    }

    /** A user zoom while following changes the follow zoom instead of breaking follow. */
    fun nudgeZoom(delta: Double) {
        targetZoom = (targetZoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        kick()
    }

    fun stopFollowing() {
        following = false
    }

    fun release() {
        animator.cancel()
    }

    private fun kick() {
        if (!animator.isStarted) animator.start()
    }

    private fun onFrame(deltaMs: Long) {
        val now = SystemClock.uptimeMillis()
        val target = toPosition
        val t = if (animationDurationMs <= 0L) 1.0 else ((now - animationStartMs).toDouble() / animationDurationMs).coerceIn(0.0, 1.0)
        if (target != null) {
            val from = fromPosition ?: target
            shownPosition = GeoPoint(
                from.latitude + (target.latitude - from.latitude) * t,
                from.longitude + (target.longitude - from.longitude) * t,
            )
            val fb = fromBearing
            val tb = toBearing
            shownBearing = when {
                tb == null -> fb
                fb == null -> tb
                else -> HeadingMath.normalize(fb + HeadingMath.shortestDelta(fb, tb) * t.toFloat())
            }
            render(shownPosition!!, shownBearing)
        }

        val settledCamera = if (following && !touching) moveCamera(deltaMs) else true
        if (t >= 1.0 && settledCamera) animator.end()
    }

    /** Eases the camera toward the marker; returns true once it has caught up. */
    private fun moveCamera(deltaMs: Long): Boolean {
        val position = shownPosition ?: return true
        val desired = LatLng(position.latitude, position.longitude)
        val current = cameraTarget ?: desired
        val kPosition = ease(deltaMs, POSITION_TAU_MS)
        val next = LatLng(
            current.latitude + (desired.latitude - current.latitude) * kPosition,
            current.longitude + (desired.longitude - current.longitude) * kPosition,
        )
        cameraTarget = next

        val desiredBearing = if (headingUp) (shownBearing?.toDouble() ?: cameraBearing) else 0.0
        val bearingDelta = HeadingMath.shortestDelta(cameraBearing.toFloat(), desiredBearing.toFloat()).toDouble()
        cameraBearing = HeadingMath.normalize((cameraBearing + bearingDelta * ease(deltaMs, BEARING_TAU_MS)).toFloat()).toDouble()
        cameraZoom += (targetZoom - cameraZoom) * ease(deltaMs, ZOOM_TAU_MS)
        cameraTilt += (targetTilt - cameraTilt) * ease(deltaMs, TILT_TAU_MS)

        val (topPadding, bottomPadding) = NavigationCameraPolicy.followPadding(
            heightPx = map.height.toDouble(),
            topInset = insets.top.toDouble(),
            bottomInset = insets.bottom.toDouble(),
            fraction = vehicleFraction,
        )
        map.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(next)
                    .bearing(cameraBearing)
                    .zoom(cameraZoom)
                    .tilt(cameraTilt)
                    .padding(insets.left.toDouble(), topPadding, insets.right.toDouble(), bottomPadding)
                    .build(),
            ),
        )
        val lagMeters = GeoMath.distanceMeters(GeoPoint(next.latitude, next.longitude), position)
        return lagMeters < 0.3 && abs(bearingDelta) < 0.2 && abs(targetZoom - cameraZoom) < 0.01 && abs(targetTilt - cameraTilt) < 0.1
    }

    private fun ease(deltaMs: Long, tauMs: Double) = 1.0 - exp(-deltaMs / tauMs)

    private companion object {
        const val MIN_GLIDE_MS = 300L
        const val STILL_METERS = 0.5
        const val MIN_BEARING_CHANGE_DEGREES = 2f
        const val MAX_GLIDE_MS = 1_500L
        /** Farther than this between fixes (GPS re-acquired, reroute teleport) is shown as a jump. */
        const val MAX_GLIDE_METERS = 300.0
        const val POSITION_TAU_MS = 120.0
        const val BEARING_TAU_MS = 250.0
        const val ZOOM_TAU_MS = 1_200.0
        const val TILT_TAU_MS = 600.0
        const val MIN_ZOOM = 3.0
        const val MAX_ZOOM = 20.0
    }
}
