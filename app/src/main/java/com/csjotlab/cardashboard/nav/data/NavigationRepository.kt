package com.csjotlab.cardashboard.nav.data

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.GpsQuality
import com.csjotlab.cardashboard.nav.domain.NavigationState
import com.csjotlab.cardashboard.nav.domain.RerouteState
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.engine.LocationFilter
import com.csjotlab.cardashboard.nav.engine.NavigationEngine
import com.csjotlab.cardashboard.nav.engine.NavigationEngineResult
import com.csjotlab.cardashboard.nav.engine.NavigationUpdate
import com.csjotlab.cardashboard.nav.location.HeadingProvider
import com.csjotlab.cardashboard.nav.location.LocationProvider
import com.csjotlab.cardashboard.nav.location.LocationReading
import com.csjotlab.cardashboard.nav.routing.RouteRequest
import com.csjotlab.cardashboard.nav.routing.RouteResult
import com.csjotlab.cardashboard.nav.routing.RoutingEngine
import com.csjotlab.cardashboard.nav.vehicle.NoVehicleDataProvider
import com.csjotlab.cardashboard.nav.vehicle.VehicleDataProvider
import com.csjotlab.cardashboard.nav.vehicle.VehicleMotion
import com.csjotlab.cardashboard.vehicle.domain.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
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
    /** Every route the router offered for this trip, the recommendation first. */
    val routeOptions: List<Route> = emptyList(),
    /** Which of [routeOptions] is the active [NavigationState.route]. */
    val selectedRouteIndex: Int = 0,
    /** True between Start and End/arrival; off-route detection and rerouting only run then. */
    val guidanceActive: Boolean = false,
) {
    companion object {
        val Idle = NavigationSnapshot(NavigationState.idle(), null, null)
    }
}

private sealed interface NavEvent {
    data class Location(val reading: LocationReading) : NavEvent
    data class Heading(val degrees: Float) : NavEvent
    data class Vehicle(val motion: VehicleMotion?) : NavEvent
    data class Destination(val point: GeoPoint?) : NavEvent
    data class Origin(val point: GeoPoint?) : NavEvent
    data class AvoidTolls(val enabled: Boolean) : NavEvent
    data class Guidance(val active: Boolean) : NavEvent
    data class SelectRoute(val index: Int) : NavEvent
    data object RetryRoute : NavEvent
    data class Routing(val outcome: RoutingOutcome) : NavEvent
    data object StaleLocation : NavEvent
    data object RerouteBackoffElapsed : NavEvent
}

private data class RoutingOutcome(
    val destination: GeoPoint,
    val result: RouteResult,
)

/**
 * Owns the navigation session: the selected destination, the route options, and the folding of
 * location + heading + vehicle data + route into one [NavigationSnapshot]. Routing requests (initial,
 * reroute, retry) are orchestrated here; all state mutation happens in a single sequential collector
 * so there is no shared-state race.
 */
class NavigationRepository(
    private val routingEngine: RoutingEngine,
    private val locationProvider: LocationProvider,
    private val headingProvider: HeadingProvider,
    private val clock: Clock,
    scope: CoroutineScope,
    private val staleTimeoutMs: Long = 5_000L,
    private val vehicleDataProvider: VehicleDataProvider = NoVehicleDataProvider,
    private val rerouteBackoffMs: Long = 15_000L,
) {
    private val engine = NavigationEngine(clock)
    private val filter = LocationFilter()
    private val _destination = MutableStateFlow<GeoPoint?>(null)
    private val _origin = MutableStateFlow<GeoPoint?>(null)
    private val _avoidTolls = MutableStateFlow(false)
    private val _guidance = MutableStateFlow(false)
    // One-shot commands: a SharedFlow so selecting the same index twice or retrying twice both count.
    private val commands = MutableSharedFlow<NavEvent>(extraBufferCapacity = 8)

    val snapshot: StateFlow<NavigationSnapshot> = channelFlow {
        var destination: GeoPoint? = null
        var explicitOrigin: GeoPoint? = null
        var avoidTolls = false
        var guidanceActive = false
        var routeOptions: List<Route> = emptyList()
        var selectedIndex = 0
        var rerouteState = RerouteState.Idle
        var awaitingRoute = false
        var lastLocation: LocationReading? = null
        var hadFix = false
        var gpsQuality = GpsQuality.None
        var lastCompass: Float? = null
        var vehicle: VehicleMotion? = null
        var staleJob: Job? = null

        val routingOutcomes = Channel<RoutingOutcome>(Channel.BUFFERED)
        val timerEvents = Channel<NavEvent>(Channel.BUFFERED)

        fun currentRoute(): Route? = routeOptions.getOrNull(selectedIndex)

        fun armStaleness() {
            staleJob?.cancel()
            staleJob = launch {
                delay(staleTimeoutMs)
                timerEvents.send(NavEvent.StaleLocation)
            }
        }

        suspend fun computeAndSend(): NavigationEngineResult {
            val location = lastLocation
            // Vehicle speed only counts while fresh; a stale OBD reading says nothing about now.
            val vehicleSpeed = vehicle?.takeIf { clock.nowMs() - it.timestampMs <= VEHICLE_FRESH_MS }?.speedMps
            val result = engine.update(
                NavigationUpdate(
                    route = currentRoute(),
                    location = location?.point,
                    speedMps = location?.speedMps,
                    gpsCourseDegrees = location?.courseDegrees,
                    compassDegrees = lastCompass,
                    rerouteState = rerouteState,
                    accuracyMeters = location?.accuracyMeters,
                    courseAccuracyDegrees = location?.courseAccuracyDegrees,
                    fixTimestampMs = location?.timestampMs,
                    guidanceActive = guidanceActive,
                    gpsQuality = gpsQuality,
                    vehicleSpeedMps = vehicleSpeed,
                ),
            )
            send(
                NavigationSnapshot(
                    state = result.state,
                    destination = destination,
                    origin = explicitOrigin,
                    avoidTolls = avoidTolls,
                    routeOptions = routeOptions,
                    selectedRouteIndex = selectedIndex,
                    guidanceActive = guidanceActive,
                ),
            )
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
            // While guiding, a reroute starts where the car is; otherwise from the planned start.
            val origin = (if (guidanceActive) lastLocation?.point else null)
                ?: explicitOrigin
                ?: lastLocation?.point
                ?: return
            if (result.requestReroute || awaitingRoute) requestRoute(origin, dest)
        }

        fun resetRoute() {
            routeOptions = emptyList()
            selectedIndex = 0
            rerouteState = RerouteState.Idle
        }

        merge(
            locationProvider.readings.map { NavEvent.Location(it) },
            headingProvider.readings.map { NavEvent.Heading(it) },
            vehicleDataProvider.motion.map { NavEvent.Vehicle(it) },
            _destination.map { NavEvent.Destination(it) },
            _origin.map { NavEvent.Origin(it) },
            _avoidTolls.map { NavEvent.AvoidTolls(it) },
            _guidance.map { NavEvent.Guidance(it) },
            commands,
            routingOutcomes.receiveAsFlow().map { NavEvent.Routing(it) },
            timerEvents.receiveAsFlow(),
        ).collect { event ->
            when (event) {
                is NavEvent.Location -> {
                    val filtered = filter.process(event.reading)
                    gpsQuality = filtered.quality
                    val fix = filtered.fix
                    if (fix == null) {
                        // A rejected fix is not a sign of life: the staleness timer keeps running.
                        if (lastLocation == null) gpsQuality = if (hadFix) GpsQuality.Lost else GpsQuality.Degraded
                        computeAndSend()
                        return@collect
                    }
                    lastLocation = fix
                    hadFix = true
                    armStaleness()
                    maybeRequestRoute(computeAndSend())
                }

                is NavEvent.Heading -> {
                    lastCompass = event.degrees
                    computeAndSend()
                }

                is NavEvent.Vehicle -> {
                    vehicle = event.motion
                    computeAndSend()
                }

                is NavEvent.Destination -> {
                    if (event.point == destination) return@collect
                    destination = event.point
                    resetRoute()
                    awaitingRoute = event.point != null
                    if (event.point == null) guidanceActive = false
                    maybeRequestRoute(computeAndSend())
                }

                is NavEvent.Origin -> {
                    if (event.point == explicitOrigin) return@collect
                    explicitOrigin = event.point
                    resetRoute()
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

                is NavEvent.Guidance -> {
                    if (event.active == guidanceActive) return@collect
                    guidanceActive = event.active
                    // The alternatives were for choosing; once driving, only the chosen one matters.
                    if (guidanceActive) currentRoute()?.let { routeOptions = listOf(it); selectedIndex = 0 }
                    computeAndSend()
                }

                is NavEvent.SelectRoute -> {
                    if (event.index !in routeOptions.indices || event.index == selectedIndex) return@collect
                    selectedIndex = event.index
                    computeAndSend()
                }

                NavEvent.RetryRoute -> {
                    if (destination == null || rerouteState == RerouteState.InProgress) return@collect
                    rerouteState = RerouteState.Idle
                    awaitingRoute = true
                    maybeRequestRoute(computeAndSend())
                }

                is NavEvent.Routing -> {
                    if (event.outcome.destination != destination) return@collect
                    when (val result = event.outcome.result) {
                        is RouteResult.Success -> {
                            routeOptions = if (guidanceActive) listOf(result.route) else listOf(result.route) + result.alternatives
                            selectedIndex = 0
                            rerouteState = RerouteState.Idle
                        }

                        is RouteResult.Failure -> {
                            rerouteState = RerouteState.Failed
                            if (currentRoute() != null) {
                                // A failed *re*route keeps the driver on the route they have, and
                                // tries again later (once they have also moved on, see the engine).
                                launch {
                                    delay(rerouteBackoffMs)
                                    timerEvents.send(NavEvent.RerouteBackoffElapsed)
                                }
                            } else {
                                routeOptions = emptyList()
                            }
                        }
                    }
                    computeAndSend()
                }

                NavEvent.RerouteBackoffElapsed -> {
                    if (rerouteState != RerouteState.Failed || currentRoute() == null) return@collect
                    rerouteState = RerouteState.Idle
                    computeAndSend()
                }

                NavEvent.StaleLocation -> {
                    lastLocation = null
                    gpsQuality = GpsQuality.Lost
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

    /** Start (true) or end (false) turn-by-turn guidance for the current route. */
    fun setGuidanceActive(active: Boolean) {
        _guidance.value = active
    }

    /** Make [index] of [NavigationSnapshot.routeOptions] the active route. */
    fun selectRoute(index: Int) {
        commands.tryEmit(NavEvent.SelectRoute(index))
    }

    /** Ask for a route again after a failure. */
    fun retryRoute() {
        commands.tryEmit(NavEvent.RetryRoute)
    }

    /** Starts the sensors. Safe to call again after [pauseSensors] or a permission grant. */
    suspend fun start() {
        locationProvider.start()
        headingProvider.start()
    }

    /** Stops the sensors without touching the session (screen hidden and no guidance running). */
    suspend fun pauseSensors() {
        locationProvider.stop()
        headingProvider.stop()
    }

    suspend fun stop() {
        pauseSensors()
        _guidance.value = false
        _destination.value = null
        _origin.value = null
    }

    private companion object {
        const val VEHICLE_FRESH_MS = 2_000L
    }
}
