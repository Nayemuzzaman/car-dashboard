package com.csjotlab.cardashboard.ui.navigation

import com.csjotlab.cardashboard.nav.data.NavigationSnapshot
import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.NavigationPhase
import com.csjotlab.cardashboard.nav.domain.RerouteState
import com.csjotlab.cardashboard.nav.engine.GeoMath
import com.csjotlab.cardashboard.nav.geocoding.Place
import com.csjotlab.cardashboard.vehicle.domain.isValue
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Turns a [NavigationSnapshot] into display strings. All formatting lives here, never in the domain
 * or the screen; "no value" becomes [UNAVAILABLE], never a guessed number.
 */
object NavigationFormatter {

    private const val MOTION_SPEED_MPS = 1.5f

    fun toUiState(snapshot: NavigationSnapshot, formatEta: (Long) -> String): NavigationUiState {
        val state = snapshot.state
        val hasRoute = state.route != null
        val hasLocation = state.location.isValue

        val speedMps = state.speedMps.valueOrNull()
        val statusLabel = when {
            state.rerouteState == RerouteState.Failed && hasRoute -> UNABLE_TO_REROUTE
            state.rerouteState == RerouteState.Failed -> UNABLE_TO_FIND_ROUTE
            hasRoute && state.phase == NavigationPhase.Arrived -> ARRIVED
            hasRoute && hasLocation -> NAVIGATING
            hasRoute -> ROUTE_READY
            snapshot.destination != null && snapshot.origin == null && !hasLocation -> WAITING_FOR_GPS
            snapshot.destination != null -> FINDING_ROUTE
            else -> NO_ROUTE
        }

        val motionLabel = when {
            speedMps == null -> null
            speedMps > MOTION_SPEED_MPS -> MOVING
            else -> STOPPED
        }

        val previewLabel = if (state.route?.isPreview == true) STRAIGHT_LINE_PREVIEW else null

        val route = state.route
        val tollLabel = route?.let {
            when {
                it.hasToll == null -> TOLL_UNKNOWN
                it.hasToll && snapshot.avoidTolls -> TOLLS_UNAVOIDABLE
                it.hasToll -> TOLL_ROAD
                else -> TOLL_FREE
            }
        }
        val viaText = route?.steps
            ?.filter { it.roadName != null && it.distanceMeters > 0f }
            ?.maxByOrNull { it.distanceMeters }
            ?.roadName
            ?.let { "via $it" }
        val nextStepToll = state.nextManeuver?.let { next ->
            route?.steps?.firstOrNull { it.maneuver == next }?.toll
        } ?: false

        return NavigationUiState(
            statusLabel = statusLabel,
            maneuverText = state.maneuverPhrase,
            remainingDistanceText = state.remainingDistanceMeters?.valueOrNull()?.let(::formatRemainingDistance),
            remainingTimeText = state.remainingTimeSeconds?.valueOrNull()?.let(::formatDuration),
            etaText = state.etaMs?.valueOrNull()?.let(formatEta),
            speedText = speedMps?.let(::formatSpeed) ?: UNAVAILABLE,
            motionLabel = motionLabel,
            previewLabel = previewLabel,
            hasLocation = hasLocation,
            hasRoute = hasRoute,
            isRerouting = state.rerouteState == RerouteState.InProgress,
            tollLabel = tollLabel,
            viaText = viaText,
            nextStepToll = nextStepToll,
            avoidTolls = snapshot.avoidTolls,
        )
    }

    fun formatRemainingDistance(meters: Float): String {
        if (meters < 1_000f) return "${meters.roundToInt()} m"
        val km = meters / 1_000f
        return if (km >= 10f) "${km.roundToInt()} km" else String.format(Locale.US, "%.1f km", km)
    }

    fun formatDuration(seconds: Long): String {
        if (seconds < 60L) return "less than a minute"
        val minutes = seconds / 60L
        if (minutes < 60L) return "$minutes min"
        val hours = minutes / 60L
        val remainingMinutes = minutes % 60L
        return if (remainingMinutes == 0L) "$hours h" else "$hours h $remainingMinutes min"
    }

    fun formatSpeed(metersPerSecond: Float): String =
        "${(metersPerSecond * 3.6f).roundToInt()} km/h"

    /** A distance is shown only when a live fix exists — never a distance from nowhere. */
    fun toSearchResult(place: Place, near: GeoPoint?): SearchResultUi = SearchResultUi(
        place = place,
        distanceText = near?.let { formatRemainingDistance(GeoMath.distanceMeters(it, place.point).toFloat()) },
    )
}

/** Renders an epoch timestamp as a wall-clock HH:mm on the device's time zone. */
fun formatEtaTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.US).format(Date(epochMs))
