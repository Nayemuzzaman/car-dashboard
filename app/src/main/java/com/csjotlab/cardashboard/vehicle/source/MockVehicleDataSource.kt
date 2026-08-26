package com.csjotlab.cardashboard.vehicle.source

import com.csjotlab.cardashboard.vehicle.domain.AdapterIdentity
import com.csjotlab.cardashboard.vehicle.domain.Clock
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticCode
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticIssue
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticSource
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticStatus
import com.csjotlab.cardashboard.vehicle.domain.DoorPosition
import com.csjotlab.cardashboard.vehicle.domain.DoorState
import com.csjotlab.cardashboard.vehicle.domain.Gear
import com.csjotlab.cardashboard.vehicle.domain.SeatPosition
import com.csjotlab.cardashboard.vehicle.domain.SeatbeltState
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.TirePosition
import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleDiagnosticsState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import com.csjotlab.cardashboard.vehicle.domain.isLiveData
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull
import com.csjotlab.cardashboard.vehicle.protocol.DtcDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MockFrame(
    val state: VehicleState,
    val diagnostics: VehicleDiagnosticsState,
    val connection: VehicleConnectionState,
)

/**
 * One step of the scripted timeline. [apply] is handed the clock reading for that step so every
 * value the mock publishes carries a real timestamp — a constant would make the mock look frozen
 * on a device and would be a placeholder standing in for a reading.
 */
data class MockStep(val delayMs: Long, val apply: (frame: MockFrame, nowMs: Long) -> MockFrame)

/**
 * A scripted, domain-level source. It is the only way to exercise signals no OBD-II adapter
 * provides (doors, tyres, seatbelts), and it drives UI development without a vehicle.
 *
 * It is never selected automatically — see VehicleSourceSelector.
 *
 * There is exactly **one** script run per source, held in a single [MockFrame], and the three
 * exposed flows are views of it. Running the script once per flow would let the three timelines
 * drift apart under a real dispatcher and produce frames that never existed — a DTC visible before
 * the MIL that raised it, telemetry cleared while the connection still reads `Reading`. The frame
 * is what makes the demo scenario coherent enough to walk through on a device.
 *
 * The script only advances between [start] and [stop].
 */
class MockVehicleDataSource(
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val script: List<MockStep> = DEMO_SCRIPT,
) : VehicleDataSource {

    override val id: VehicleSourceId = VehicleSourceId.MOCK

    private val frames = MutableStateFlow(initialFrame())
    private val beats = MutableSharedFlow<Long>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var driver: Job? = null
    private var preparedActivation: VehicleSourceActivation? = null
    private var activated = false
    private val lifecycleLock = Mutex()

    override val vehicleState: Flow<VehicleState> = frames.map { it.state }.distinctUntilChanged()
    override val diagnostics: Flow<VehicleDiagnosticsState> =
        frames.map { it.diagnostics }.distinctUntilChanged()
    override val connectionState: Flow<VehicleConnectionState> =
        frames.map { it.connection }.distinctUntilChanged()
    override val liveness: Flow<Long> = beats.asSharedFlow()

    override suspend fun start(): VehicleSourceActivation = lifecycleLock.withLock {
        preparedActivation?.let { return@withLock it }
        frames.value = initialFrame()
        lateinit var handle: VehicleSourceActivation
        handle = VehicleSourceActivation {
            lifecycleLock.withLock activation@{
                if (preparedActivation !== handle || activated) return@activation
                activated = true
                driver = scope.launch { runScript() }
            }
        }
        preparedActivation = handle
        handle
    }

    override suspend fun stop() = lifecycleLock.withLock {
        driver?.cancelAndJoin()
        driver = null
        preparedActivation = null
        activated = false
        frames.value = initialFrame()
    }

    private suspend fun runScript() {
        for (step in script) {
            delay(step.delayMs)
            val nowMs = clock.nowMs()
            val frame = step.apply(frames.value, nowMs)
            frames.value = frame
            // A beat means the vehicle answered — see the liveness contract on VehicleDataSource.
            // Nothing can have answered while this is disconnected, connecting or negotiating with
            // the adapter, and nothing answers once the cable is pulled. This source is the only
            // in-tree reference implementation, so it has to model the contract it documents.
            if (frame.connection.isLiveData) beats.emit(nowMs)
        }
    }

    private fun initialFrame() = MockFrame(
        state = VehicleState.unavailable(VehicleSourceId.MOCK),
        diagnostics = VehicleDiagnosticsState.empty(),
        connection = VehicleConnectionState.Disconnected,
    )

    companion object {
        /** The scenario named in the spec, in order. */
        val DEMO_SCRIPT: List<MockStep> = listOf(
            MockStep(400) { frame, _ -> frame.copy(connection = VehicleConnectionState.Connecting) },
            MockStep(600) { frame, _ ->
                frame.copy(
                    connection = VehicleConnectionState.Connected(
                        AdapterIdentity("MOCK SOURCE", elmCompatible = false),
                        protocol = "Simulated",
                    ),
                )
            },
            MockStep(400) { frame, _ -> frame.copy(connection = VehicleConnectionState.Reading) },
            MockStep(600) { frame, nowMs -> frame.copy(state = drivingState(frame.state, nowMs)) },
            MockStep(1_500) { frame, nowMs ->
                frame.copy(state = withRearLeftDoorOpen(frame.state, nowMs))
            },
            MockStep(1_500) { frame, nowMs ->
                frame.copy(state = withFrontRightTyreLow(frame.state, nowMs))
            },
            MockStep(1_500) { frame, nowMs ->
                frame.copy(state = withPartialCoverage(frame.state, nowMs))
            },
            MockStep(1_500) { frame, nowMs ->
                frame.copy(state = withCoverageRestored(frame.state, nowMs))
            },
            MockStep(1_500) { frame, nowMs ->
                frame.copy(
                    state = frame.state.copy(
                        malfunctionIndicatorLampOn = Signal.Value(true, nowMs),
                        lastUpdatedMs = nowMs,
                    ),
                    diagnostics = withEngineDtc(nowMs),
                )
            },
            MockStep(2_000) { frame, _ ->
                // Simulated cable removal: every value is cleared in one step.
                frame.copy(
                    state = VehicleState.unavailable(VehicleSourceId.MOCK),
                    diagnostics = VehicleDiagnosticsState.empty(),
                    connection = VehicleConnectionState.ConnectionLost("Simulated cable removal"),
                )
            },
        )

        private fun drivingState(previous: VehicleState, nowMs: Long) = previous.copy(
            speedKph = Signal.Value(60f, nowMs),
            engineRpm = Signal.Value(2100, nowMs),
            fuelLevelPercent = Signal.Value(62f, nowMs),
            coolantTemperatureCelsius = Signal.Value(91, nowMs),
            gear = Signal.Value(Gear.Drive, nowMs),
            // A simulator has full authority over the vehicle it simulates, so the healthy window
            // models a fully instrumented car: every seat, every door, every tyre. That is not the
            // same as inventing a reading — the forbidden thing is *inferring* an unreported belt
            // from something else, and no real source may do what this line does.
            doors = Signal.Value(DoorPosition.entries.associateWith { DoorState.Closed }, nowMs),
            seatbelts = Signal.Value(SeatPosition.entries.associateWith { SeatbeltState.Buckled }, nowMs),
            tirePressuresKpa = Signal.Value(TirePosition.entries.associateWith { 230f }, nowMs),
            malfunctionIndicatorLampOn = Signal.Value(false, nowMs),
            lastUpdatedMs = nowMs,
        )

        /**
         * A deliberate window of partial coverage: the simulated body-control module drops off the
         * bus, so the boot, bonnet and rear belts stop being reported at all. Their positions leave
         * the map rather than acquiring a placeholder value, which is what makes the dashboard's
         * "not reported" path reachable on a physical device.
         */
        private fun withPartialCoverage(previous: VehicleState, nowMs: Long): VehicleState {
            val reportedDoors = setOf(
                DoorPosition.FrontLeft, DoorPosition.FrontRight,
                DoorPosition.RearLeft, DoorPosition.RearRight,
            )
            val reportedSeats = setOf(SeatPosition.Driver, SeatPosition.FrontPassenger)
            return previous.copy(
                doors = Signal.Value(
                    previous.doors.valueOrNull().orEmpty().filterKeys { it in reportedDoors },
                    nowMs,
                ),
                seatbelts = Signal.Value(
                    previous.seatbelts.valueOrNull().orEmpty().filterKeys { it in reportedSeats },
                    nowMs,
                ),
                lastUpdatedMs = nowMs,
            )
        }

        /** The module comes back. The scripted anomalies (open door, low tyre) are preserved. */
        private fun withCoverageRestored(previous: VehicleState, nowMs: Long) = previous.copy(
            doors = Signal.Value(
                DoorPosition.entries.associateWith { DoorState.Closed } +
                    (DoorPosition.RearLeft to DoorState.Open),
                nowMs,
            ),
            seatbelts = Signal.Value(
                SeatPosition.entries.associateWith { SeatbeltState.Buckled },
                nowMs,
            ),
            lastUpdatedMs = nowMs,
        )

        private fun withRearLeftDoorOpen(previous: VehicleState, nowMs: Long): VehicleState {
            val doors = previous.doors.let { signal ->
                when (signal) {
                    is Signal.Value -> signal.value + (DoorPosition.RearLeft to DoorState.Open)
                    else -> mapOf(DoorPosition.RearLeft to DoorState.Open)
                }
            }
            return previous.copy(doors = Signal.Value(doors, nowMs), lastUpdatedMs = nowMs)
        }

        private fun withFrontRightTyreLow(previous: VehicleState, nowMs: Long): VehicleState {
            val tyres = previous.tirePressuresKpa.let { signal ->
                when (signal) {
                    is Signal.Value -> signal.value + (TirePosition.FrontRight to 150f)
                    else -> mapOf(TirePosition.FrontRight to 150f)
                }
            }
            return previous.copy(
                tirePressuresKpa = Signal.Value(tyres, nowMs),
                lastUpdatedMs = nowMs,
            )
        }

        private fun withEngineDtc(timestampMs: Long): VehicleDiagnosticsState {
            val code = "P0301"
            return VehicleDiagnosticsState(
                issues = listOf(
                    DiagnosticIssue(
                        id = "dtc:$code",
                        code = DiagnosticCode.Dtc(code),
                        title = null,
                        description = null,
                        classification = DtcDecoder.classify(code),
                        severity = DtcDecoder.severityFor(DiagnosticStatus.Stored, milOn = true),
                        status = DiagnosticStatus.Stored,
                        source = DiagnosticSource.Mock,
                        firstSeenMs = timestampMs,
                        lastSeenMs = timestampMs,
                    ),
                ),
                malfunctionIndicatorLampOn = Signal.Value(true, timestampMs),
                storedDtcCount = Signal.Value(1, timestampMs),
                lastScanMs = timestampMs,
            )
        }
    }
}
