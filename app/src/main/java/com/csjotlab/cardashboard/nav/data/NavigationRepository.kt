package com.csjotlab.cardashboard.nav.data

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.NavigationState
import com.csjotlab.cardashboard.nav.domain.RerouteState
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.engine.NavigationEngine
import com.csjotlab.cardashboard.nav.engine.NavigationEngineResult
import com.csjotlab.cardashboard.nav.engine.NavigationUpdate
import com.csjotlab.cardashboard.nav.location.HeadingProvider
import com.csjotlab.cardashboard.nav.location.LocationProvider
import com.csjotlab.cardashboard.nav.location.LocationReading
import com.csjotlab.cardashboard.nav.routing.RouteRequest
import com.csjotlab.cardashboard.nav.routing.RouteResult
import com.csjotlab.cardashboard.nav.routing.RoutingEngine
import com.csjotlab.cardashboard.vehicle.domain.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NavigationSnapshot(
    val state: NavigationState,
    val destination: GeoPoint?,
    /** Explicit start point; null means "use the current GPS fix as the origin". */
    val origin: GeoPoint?,
    /** The driver's toll preference as sent to the router; see [Route.hasToll] for the outcome. */
    val avoidTolls: Boolean = false,
) {
    companion object {
        val Idle = NavigationSnapshot(NavigationState.idle(), null, null)
    }
}

private sealed interface NavEvent {
    data class Location(val reading: LocationReading) : NavEvent
    data class Heading(val degrees: Float) : NavEvent
    data class Destination(val point: GeoPoint?) : NavEvent
    data class Origin(val point: GeoPoint?) : NavEvent
    data class AvoidTolls(val enabled: Boolean) : NavEvent
    data class Routing(val outcome: RoutingOutcome) : NavEvent
    data object StaleLocation : NavEvent
}

private data class RoutingOutcome(
    val destination: GeoPoint,
    val result: RouteResult,
)

/**
 * Owns the navigation session: the selected destination, the current route, and the folding of
 * location + heading + route into one [NavigationSnapshot]. Routing requests (initial and reroute)
 * are orchestrated here; all state mutation happens in a single sequential collector so there is
 * no shared-state race.
 */
class NavigationRepository(
    private val routingEngine: RoutingEngine,
    private val locationProvider: LocationProvider,
    private val headingProvider: HeadingProvider,
    private val clock: Clock,
    scope: CoroutineScope,
    private val staleTimeoutMs: Long = 5_000L,
) {
    private val engine = NavigationEngine(clock)
    private val _destination = MutableStateFlow<GeoPoint?>(null)
    private val _origin = MutableStateFlow<GeoPoint?>(null)
    private val _avoidTolls = MutableStateFlow(false)

    val snapshot: StateFlow<NavigationSnapshot> = channelFlow {
        var destination: GeoPoint? = null
        var explicitOrigin: GeoPoint? = null
        var avoidTolls = false
        var currentRoute: Route? = null
        var rerouteState = RerouteState.Idle
        var awaitingRoute = false
        var lastLocation: LocationReading? = null
        var lastCompass: Float? = null
        var staleJob: Job? = null

        val routingOutcomes = Channel<RoutingOutcome>(Channel.BUFFERED)
        val staleEvents = Channel<Unit>(Channel.CONFLATED)

        fun armStaleness() {
            staleJob?.cancel()
            staleJob = launch {
                delay(staleTimeoutMs)
                staleEvents.send(Unit)
            }
        }

        suspend fun computeAndSend(): NavigationEngineResult {
            val location = lastLocation
            val result = engine.update(
                NavigationUpdate(
                    route = currentRoute,
                    location = location?.point,
                    speedMps = location?.speedMps,
                    gpsCourseDegrees = location?.courseDegrees,
                    compassDegrees = lastCompass,
                    rerouteState = rerouteState,
                ),
            )
            send(NavigationSnapshot(result.state, destination, explicitOrigin, avoidTolls))
            return result
        }

        suspend fun requestRoute(origin: GeoPoint, dest: GeoPoint) {
            awaitingRoute = false
            rerouteState = RerouteState.InProgress
            launch {
                routingOutcomes.send(RoutingOutcome(dest, routingEngine.route(RouteRequest(origin, dest, avoidTolls))))
            }
            computeAndSend()
        }

        suspend fun maybeRequestRoute(result: NavigationEngineResult) {
            val dest = destination ?: return
            val origin = explicitOrigin ?: lastLocation?.point ?: return
            if (result.requestReroute || awaitingRoute) {
                requestRoute(origin, dest)
            }
        }

        merge(
            locationProvider.readings.map { NavEvent.Location(it) },
            headingProvider.readings.map { NavEvent.Heading(it) },
            _destination.map { NavEvent.Destination(it) },
            _origin.map { NavEvent.Origin(it) },
            _avoidTolls.map { NavEvent.AvoidTolls(it) },
            routingOutcomes.receiveAsFlow().map { NavEvent.Routing(it) },
            staleEvents.receiveAsFlow().map { NavEvent.StaleLocation },
        ).collect { event ->
            when (event) {
                is NavEvent.Location -> {
                    lastLocation = event.reading
                    armStaleness()
                    maybeRequestRoute(computeAndSend())
                }

                is NavEvent.Heading -> {
                    lastCompass = event.degrees
                    maybeRequestRoute(computeAndSend())
                }

                is NavEvent.Destination -> {
                    if (event.point == destination) return@collect
                    destination = event.point
                    currentRoute = null
                    rerouteState = RerouteState.Idle
                    awaitingRoute = event.point != null
                    maybeRequestRoute(computeAndSend())
                }

                is NavEvent.Origin -> {
                    if (event.point == explicitOrigin) return@collect
                    explicitOrigin = event.point
                    currentRoute = null
                    rerouteState = RerouteState.Idle
                    awaitingRoute = destination != null
                    maybeRequestRoute(computeAndSend())
                }

                is NavEvent.AvoidTolls -> {
                    if (event.enabled == avoidTolls) return@collect
                    avoidTolls = event.enabled
                    // Keep the current route on screen while the replacement is fetched; the
                    // snapshot's rerouteState tells the UI it is being updated.
                    awaitingRoute = destination != null
                    maybeRequestRoute(computeAndSend())
                }

                is NavEvent.Routing -> {
                    if (event.outcome.destination != destination) return@collect
                    when (val result = event.outcome.result) {
                        is RouteResult.Success -> {
                            currentRoute = result.route
                            rerouteState = RerouteState.Idle
                        }

                        is RouteResult.Failure -> {
                            currentRoute = null
                            rerouteState = RerouteState.Failed
                        }
                    }
                    computeAndSend()
                }

                is NavEvent.StaleLocation -> {
                    lastLocation = null
                    computeAndSend()
                }
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, NavigationSnapshot.Idle)

    fun setDestination(point: GeoPoint?) {
        _destination.value = point
    }

    fun setOrigin(point: GeoPoint?) {
        _origin.value = point
    }

    fun setAvoidTolls(enabled: Boolean) {
        _avoidTolls.value = enabled
    }

    suspend fun start() {
        locationProvider.start()
        headingProvider.start()
    }

    suspend fun stop() {
        locationProvider.stop()
        headingProvider.stop()
        _destination.value = null
        _origin.value = null
    }
}
