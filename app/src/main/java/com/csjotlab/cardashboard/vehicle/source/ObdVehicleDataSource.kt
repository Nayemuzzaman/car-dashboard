package com.csjotlab.cardashboard.vehicle.source

import com.csjotlab.cardashboard.vehicle.data.VehicleLog
import com.csjotlab.cardashboard.vehicle.domain.Clock
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticCode
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticIssue
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticSource
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticStatus
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.UsbDeviceDescriptor
import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleDiagnosticsState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull
import com.csjotlab.cardashboard.vehicle.protocol.DtcDecoder
import com.csjotlab.cardashboard.vehicle.protocol.DtcReadResult
import com.csjotlab.cardashboard.vehicle.protocol.Elm327Session
import com.csjotlab.cardashboard.vehicle.protocol.HandshakeResult
import com.csjotlab.cardashboard.vehicle.protocol.ObdCommand
import com.csjotlab.cardashboard.vehicle.protocol.ObdPid
import com.csjotlab.cardashboard.vehicle.protocol.ObdResult
import com.csjotlab.cardashboard.vehicle.transport.TransportEvent
import com.csjotlab.cardashboard.vehicle.transport.VehicleTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * A read-only OBD-II vehicle source: talks ELM327 over a [VehicleTransport] and publishes only what
 * the vehicle itself reported.
 *
 * ## Capability discovery is authoritative
 *
 * The mode-01 capability bitmask read during the handshake decides, per field, whether this source
 * can ever supply it. Nothing else does. Three rules follow, and they are the whole point of this
 * class:
 *
 *  1. A field carries a [Signal.Value] only when its own PID is in the discovered capability set
 *     **and** the value was decoded from the reply to that PID's own request. There is no
 *     cross-signal inference anywhere below: gear is never read off speed or RPM, range is never
 *     computed from fuel level, the odometer is never integrated from speed, and trip distance is
 *     never substituted from PID 0x31 ("distance since codes cleared"), which is a different
 *     quantity. Those fields are [Signal.Unsupported] because generic SAE J1979 has no PID for
 *     them, not because this vehicle lacks them.
 *  2. A field is [Signal.Unsupported] only when the bank that would have advertised its PID was
 *     itself decoded and the PID was absent. A bank listed in [HandshakeResult.Ready.undiscoveredBanks],
 *     or a whole handshake that ended in [HandshakeResult.CapabilityDiscoveryFailed], yields
 *     [Signal.Unknown] — "we do not know", never "the source cannot". Discovery failure is not
 *     capability.
 *  3. A PID that discovery did not license is never even requested. A reply we would not be allowed
 *     to publish is bus traffic with no purpose.
 *
 * A decoder returning null (a short or malformed frame) leaves the previous reading in place. No
 * zero, no last-known value from a neighbouring PID, no placeholder of any kind is ever written.
 *
 * ## Recovery, and why the session is rebuilt rather than reused
 *
 * [Elm327Session] tracks how many commands the adapter still owes a reply to, deliberately
 * over-counting on the safe side. Over-counting is absorbing: once the count exceeds what the
 * adapter really owes, every later exchange discards a reply that will never arrive and reports a
 * timeout, and only a genuinely received reply resets it — which by then can no longer happen. A
 * cancelled polling job landing between the write flag and the bytes leaving is enough to trigger
 * it, and this class cancels polling jobs routinely.
 *
 * So a run of [maxUnansweredExchanges] consecutive unanswered exchanges is treated as a lost
 * connection, never as "the vehicle is quiet": the polling loops are cancelled, telemetry is
 * cleared, and the next attempt constructs a **new** [Elm327Session]. The same applies after a
 * transport failure and after any reconnect. No session ever survives a failure.
 *
 * ## Safety
 *
 * Everything that reaches the wire goes through [ObdCommand] and [Elm327Session]'s fixed `AT`
 * configuration set: services 01, 03, 07 and 0A only. Mode 04 (clear DTCs), the VIN read (mode 09
 * PID 02), UDS and raw CAN transmission are not expressible.
 */
class ObdVehicleDataSource(
    private val transport: VehicleTransport,
    private val clock: Clock,
    private val fastIntervalMs: Long = 500L,
    private val slowIntervalMs: Long = 5_000L,
    private val diagnosticsIntervalMs: Long = 15_000L,
    private val commandTimeoutMs: Long = 1_000L,
    private val reconnectDelayMs: Long = 2_000L,
    /** How many consecutive unanswered exchanges mean the link is gone rather than merely quiet. */
    private val maxUnansweredExchanges: Int = 3,
    /**
     * The attached USB device, when the caller knows it. This layer talks to a [VehicleTransport]
     * and has no USB visibility of its own, so the USB layer supplies this; see [descriptorFor].
     */
    private val device: UsbDeviceDescriptor? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val vehicleLog: VehicleLog = VehicleLog(clock),
) : VehicleDataSource {

    override val id: VehicleSourceId = VehicleSourceId.OBD_USB

    private val states = MutableStateFlow(VehicleState.unavailable(VehicleSourceId.OBD_USB))
    private val diagnosticsStates = MutableStateFlow(VehicleDiagnosticsState.empty())
    private val connections =
        MutableStateFlow<VehicleConnectionState>(VehicleConnectionState.Disconnected)

    /**
     * `replay = 0` with [BufferOverflow.DROP_OLDEST]: a beat produced with nothing collecting is
     * dropped rather than queued, and a late subscriber never receives a response dated to a moment
     * before it subscribed. See the liveness contract on [VehicleDataSource].
     */
    private val beats = MutableSharedFlow<Long>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val vehicleState: Flow<VehicleState> = states.asStateFlow()
    override val diagnostics: Flow<VehicleDiagnosticsState> = diagnosticsStates.asStateFlow()
    override val connectionState: Flow<VehicleConnectionState> = connections.asStateFlow()
    override val liveness: Flow<Long> = beats.asSharedFlow()

    private var scope: CoroutineScope? = null
    private var preparedActivation: VehicleSourceActivation? = null
    private var generation = 0L
    private var activated = false
    private val lifecycleLock = Mutex()

    override suspend fun start(): VehicleSourceActivation = lifecycleLock.withLock {
        preparedActivation?.let { return@withLock it }
        val started = CoroutineScope(SupervisorJob() + dispatcher)
        scope = started
        val preparedGeneration = ++generation
        activated = false
        vehicleLog.sourceSelected(id)
        VehicleSourceActivation {
            lifecycleLock.withLock activation@{
                if (scope !== started || generation != preparedGeneration || activated) {
                    return@activation
                }
                activated = true
                started.launch { supervise() }
            }
        }.also { preparedActivation = it }
    }

    override suspend fun stop() = lifecycleLock.withLock {
        val running = scope ?: return@withLock
        val wasActivated = activated
        running.cancel()
        // Await every polling/session finally block before closing the shared transport or allowing
        // start() to create another scope. Otherwise old and new sessions can overlap after stop().
        running.coroutineContext[Job]?.join()
        if (wasActivated) {
            runCatching { transport.close() }
                .onFailure { failure ->
                    vehicleLog.closeFailure(failure.message ?: "unknown failure")
                }
        }
        states.value = VehicleState.unavailable(id)
        diagnosticsStates.value = VehicleDiagnosticsState.empty()
        connections.value = VehicleConnectionState.Disconnected
        scope = null
        preparedActivation = null
        activated = false
        generation++
    }

    // -- lifecycle -----------------------------------------------------------------------------

    private suspend fun supervise() = coroutineScope {
        // Started undispatched so the subscription is in place before open(): TransportEvent is a
        // replay = 0 stream, and an event raised with nobody listening is gone.
        val fatal = CompletableDeferred<VehicleConnectionState>()
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            transport.events.collect { event ->
                when (event) {
                    TransportEvent.Opened -> Unit
                    TransportEvent.Detached -> {
                        vehicleLog.deviceDetached()
                        fatal.complete(VehicleConnectionState.ConnectionLost("USB device detached"))
                    }
                    is TransportEvent.Failed ->
                        fatal.complete(VehicleConnectionState.Error(event.reason))
                }
            }
        }

        setConnection(VehicleConnectionState.Connecting)
        try {
            transport.open()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            clearTelemetry()
            setConnection(VehicleConnectionState.Error(failure.message ?: "the transport could not be opened"))
            watcher.cancel()
            return@coroutineScope
        }

        val worker = launch { sessionLoop() }

        // Suspends until the cable is pulled or the transport fails. A source whose worker has
        // stopped for a terminal reason (an unsupported device) simply parks here holding that
        // state until stop() cancels the scope.
        val terminal = fatal.await()
        worker.cancel()
        worker.join()
        clearTelemetry()
        setConnection(terminal)
        watcher.cancel()
    }

    private suspend fun sessionLoop() {
        while (currentCoroutineContext().isActive) {
            val retry = try {
                runOneSession()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // VehicleTransport does not require a thrown write/read failure to also emit a
                // TransportEvent.Failed. Keep that interface-valid path inside supervision: the
                // failed session is abandoned and the next loop iteration constructs a fresh one.
                clearTelemetry()
                setConnection(
                    VehicleConnectionState.ConnectionLost(
                        "vehicle transport exchange failed: ${failure.message ?: failure::class.simpleName}",
                    ),
                )
                true
            }
            if (!retry) return
            delay(reconnectDelayMs)
        }
    }

    /**
     * Runs one connection attempt end to end. Returns true when another attempt should follow.
     *
     * A new [Elm327Session] per attempt is not tidiness — see the class KDoc: reusing one across a
     * failure permanently poisons its reply bookkeeping.
     */
    private suspend fun runOneSession(): Boolean {
        val session = Elm327Session(
            transport,
            commandTimeoutMs = commandTimeoutMs,
            onSuccessfulVehicleResponse = { beat(clock.nowMs()) },
        )

        return when (val result = session.handshake()) {
            is HandshakeResult.NotElmCompatible -> {
                clearTelemetry()
                setConnection(
                    VehicleConnectionState.UnsupportedDevice(
                        device = descriptorFor(result.identity),
                        reason = "the attached device did not identify as an ELM327: ${result.identity}",
                    ),
                )
                false
            }

            is HandshakeResult.BusUnavailable -> {
                clearTelemetry()
                setConnection(VehicleConnectionState.VehicleCommunicationUnavailable(result.reason))
                true
            }

            is HandshakeResult.CapabilityDiscoveryFailed -> {
                // "We do not know" — not "the vehicle supports nothing". clearTelemetry() leaves
                // every field Signal.Unknown, which is exactly the required outcome; marking them
                // Unsupported off one garbled frame would be a permanent claim from one bad byte.
                clearTelemetry()
                setConnection(
                    VehicleConnectionState.VehicleCommunicationUnavailable(
                        "capability discovery did not decode: ${result.reason}",
                    ),
                )
                true
            }

            is HandshakeResult.Ready -> {
                val capability = Capability(result.supportedPids, result.undiscoveredBanks)
                states.value = initialState(capability)
                diagnosticsStates.value = VehicleDiagnosticsState.empty()
                vehicleLog.adapterIdentity(result.identity)
                setConnection(VehicleConnectionState.Connected(result.identity, result.protocol))
                setConnection(VehicleConnectionState.Reading)
                poll(session, capability)
            }
        }
    }

    // -- capability ----------------------------------------------------------------------------

    private class Capability(val supported: Set<Int>, val undiscoveredBanks: Set<Int>) {

        fun canSupply(pid: Int): Boolean = pid in supported

        /**
         * The signal a field starts life as. [Signal.Unsupported] is only reachable when the bank
         * that would have advertised [pid] was itself decoded; otherwise nothing is known.
         */
        fun initialSignal(pid: Int): Signal<Nothing> = when {
            pid in supported -> Signal.Unknown
            bankOf(pid) in undiscoveredBanks -> Signal.Unknown
            else -> Signal.Unsupported
        }

        /** Bank 0x00 advertises PIDs 0x01..0x20, bank 0x20 advertises 0x21..0x40, and so on. */
        private fun bankOf(pid: Int): Int = ((pid - 1) / BANK_SIZE) * BANK_SIZE

        private companion object {
            const val BANK_SIZE = 0x20
        }
    }

    /**
     * Each field is mapped independently from its own PID's capability. The five fields with no
     * generic J1979 PID at all are [Signal.Unsupported] unconditionally — this source cannot supply
     * them, which is a statement about the source and not about the vehicle.
     */
    private fun initialState(capability: Capability) = VehicleState(
        speedKph = capability.initialSignal(ObdPid.SPEED),
        engineRpm = capability.initialSignal(ObdPid.RPM),
        fuelLevelPercent = capability.initialSignal(ObdPid.FUEL_LEVEL),
        coolantTemperatureCelsius = capability.initialSignal(ObdPid.COOLANT),
        gear = Signal.Unsupported,
        odometerKm = capability.initialSignal(ObdPid.ODOMETER),
        // PID 0x31 is distance since codes were cleared, which is not trip distance; substituting
        // it is explicitly prohibited, and no other generic PID reports a trip.
        tripDistanceKm = Signal.Unsupported,
        estimatedRangeKm = Signal.Unsupported,
        seatbelts = Signal.Unsupported,
        doors = Signal.Unsupported,
        tirePressuresKpa = Signal.Unsupported,
        malfunctionIndicatorLampOn = capability.initialSignal(ObdPid.MONITOR_STATUS),
        source = id,
        lastUpdatedMs = null,
    )

    // -- polling -------------------------------------------------------------------------------

    private class PollSession(
        val session: Elm327Session,
        val capability: Capability,
        val lost: CompletableDeferred<String>,
    ) {
        val unanswered = AtomicInteger(0)
    }

    /** Returns true: a lost connection is always followed by another attempt. */
    private suspend fun poll(session: Elm327Session, capability: Capability): Boolean =
        coroutineScope {
            val context = PollSession(session, capability, CompletableDeferred())
            val jobs: List<Job> = listOf(
                launch { fastLoop(context) },
                launch { slowLoop(context) },
                launch { diagnosticsLoop(context) },
            )

            val reason = context.lost.await()
            jobs.forEach { it.cancel() }
            jobs.joinAll()

            clearTelemetry()
            setConnection(VehicleConnectionState.ConnectionLost(reason))
            true
        }

    private suspend fun fastLoop(context: PollSession) =
        pollLoop(context, FAST_PIDS, fastIntervalMs)

    private suspend fun slowLoop(context: PollSession) =
        pollLoop(context, SLOW_PIDS, slowIntervalMs)

    private suspend fun pollLoop(context: PollSession, pids: List<Int>, intervalMs: Long) {
        val licensed = pids.filter { context.capability.canSupply(it) }
        // Nothing discovery licensed, so nothing to ask for. Spinning a timer to request PIDs whose
        // answers could not legally be published would be pure bus traffic.
        if (licensed.isEmpty()) return

        while (currentCoroutineContext().isActive) {
            for (pid in licensed) {
                if (context.lost.isCompleted) return
                readCurrentData(context, pid)
            }
            delay(intervalMs)
        }
    }

    private suspend fun readCurrentData(context: PollSession, pid: Int) {
        val result = context.session.request(ObdCommand.CurrentData(pid))
        record(context, result, "PID %02X".format(pid))
        if (result !is ObdResult.Data) return

        val nowMs = clock.nowMs()
        applyCurrentData(pid, result.bytes, nowMs)
    }

    /**
     * Writes exactly the one field [pid] was requested for. A null decode is a short or malformed
     * frame: the previous reading stands, and no timestamp is touched, because nothing was read.
     */
    private fun applyCurrentData(pid: Int, data: List<Int>, nowMs: Long) {
        when (pid) {
            ObdPid.SPEED -> ObdPid.decodeSpeedKph(data)?.let { value ->
                states.update { it.copy(speedKph = Signal.Value(value, nowMs), lastUpdatedMs = nowMs) }
            }

            ObdPid.RPM -> ObdPid.decodeRpm(data)?.let { value ->
                states.update { it.copy(engineRpm = Signal.Value(value, nowMs), lastUpdatedMs = nowMs) }
            }

            ObdPid.COOLANT -> ObdPid.decodeCoolantCelsius(data)?.let { value ->
                states.update {
                    it.copy(coolantTemperatureCelsius = Signal.Value(value, nowMs), lastUpdatedMs = nowMs)
                }
            }

            ObdPid.FUEL_LEVEL -> ObdPid.decodeFuelPercent(data)?.let { value ->
                states.update {
                    it.copy(fuelLevelPercent = Signal.Value(value, nowMs), lastUpdatedMs = nowMs)
                }
            }

            ObdPid.ODOMETER -> ObdPid.decodeOdometerKm(data)?.let { value ->
                states.update { it.copy(odometerKm = Signal.Value(value, nowMs), lastUpdatedMs = nowMs) }
            }
        }
    }

    // -- diagnostics ---------------------------------------------------------------------------

    private suspend fun diagnosticsLoop(context: PollSession) {
        while (currentCoroutineContext().isActive) {
            scan(context)
            delay(diagnosticsIntervalMs)
        }
    }

    /**
     * One diagnostics pass: monitor status, then the three DTC services.
     *
     * A scan **completes** only when every request was answered: the monitor status request
     * returned data or a clean NO DATA, and every DTC service returned [DtcReadResult.Codes]. Any
     * [DtcReadResult.Failed] — including NO DATA — means that service did not complete, so the
     * existing issue list stands. Only a completed scan may retire a fault.
     */
    private suspend fun scan(context: PollSession) {
        var complete = true
        var milOn: Boolean? = null

        if (context.capability.canSupply(ObdPid.MONITOR_STATUS)) {
            when (val result = context.session.request(ObdCommand.CurrentData(ObdPid.MONITOR_STATUS))) {
                is ObdResult.Data -> {
                    record(context, result, "monitor status")
                    val nowMs = clock.nowMs()
                    val status = ObdPid.decodeMonitorStatus(result.bytes)
                    if (status == null) {
                        complete = false
                    } else {
                        milOn = status.milOn
                        states.update {
                            it.copy(
                                malfunctionIndicatorLampOn = Signal.Value(status.milOn, nowMs),
                                lastUpdatedMs = nowMs,
                            )
                        }
                        diagnosticsStates.update {
                            it.copy(
                                malfunctionIndicatorLampOn = Signal.Value(status.milOn, nowMs),
                                storedDtcCount = Signal.Value(status.dtcCount, nowMs),
                            )
                        }
                    }
                }

                ObdResult.NoData -> record(context, result, "monitor status")

                else -> {
                    record(context, result, "monitor status")
                    complete = false
                }
            }
        }

        if (context.lost.isCompleted || !currentCoroutineContext().isActive) return

        val found = mutableMapOf<String, DiagnosticStatus>()
        for ((command, status) in DTC_SERVICES) {
            if (context.lost.isCompleted || !currentCoroutineContext().isActive) return

            // readDtcResult, never the deprecated readDtcs: that one collapses every failure to an
            // empty list, which is indistinguishable from a clean scan and would retire live faults.
            when (val result = context.session.readDtcResult(command)) {
                is DtcReadResult.Codes -> {
                    context.unanswered.set(0)
                    result.codes.forEach { code -> found.merge(code, status, ::mostSevere) }
                }

                is DtcReadResult.Failed -> {
                    record(context, result.cause, "service ${command.request}")
                    complete = false
                }
            }
        }

        if (!complete) {
            vehicleLog.diagnosticsFailed("the scan did not complete; the existing issue list stands")
            return
        }
        reconcile(found, milOn)
    }

    /**
     * Replaces the issue list with what this completed scan found, preserving `firstSeenMs` for
     * faults that persist. A code absent from a completed scan has been retired by the vehicle.
     */
    private fun reconcile(found: Map<String, DiagnosticStatus>, milOn: Boolean?) {
        val nowMs = clock.nowMs()
        diagnosticsStates.update { previous ->
            val previousById = previous.issues.associateBy { it.id }
            val lampOn = milOn ?: previous.malfunctionIndicatorLampOn.valueOrNull() ?: false
            val issues = found.entries.sortedBy { it.key }.map { (code, status) ->
                val issueId = "dtc:$code"
                DiagnosticIssue(
                    id = issueId,
                    code = DiagnosticCode.Dtc(code),
                    // No trusted code-to-text mapping ships in this version, and inventing one
                    // would be naming a failed component we have not diagnosed.
                    title = null,
                    description = null,
                    // Structural SAE J2012 decoding only: what the characters state, nothing more.
                    classification = DtcDecoder.classify(code),
                    severity = DtcDecoder.severityFor(status, lampOn),
                    status = status,
                    source = DiagnosticSource.Obd2,
                    firstSeenMs = previousById[issueId]?.firstSeenMs ?: nowMs,
                    lastSeenMs = nowMs,
                )
            }
            previous.copy(issues = issues, lastScanMs = nowMs)
        }
        vehicleLog.diagnosticsScan(found.keys.toList())
    }

    private fun mostSevere(left: DiagnosticStatus, right: DiagnosticStatus): DiagnosticStatus =
        if (STATUS_RANK.indexOf(left) >= STATUS_RANK.indexOf(right)) left else right

    // -- liveness, bookkeeping and logging -------------------------------------------------------

    /**
     * One beat per response actually received, stamped from the shared [clock].
     *
     * Never called on send, on a timeout, on a retry or from a timer: [Elm327Session] invokes the
     * single callback only after a capability bank decoded, a current-data request produced
     * [ObdResult.Data], or a DTC service produced [DtcReadResult.Codes]. Centralising that handoff
     * avoids both missed capability responses and duplicate poll/diagnostic beats.
     */
    private suspend fun beat(atMs: Long) {
        beats.emit(atMs)
    }

    /**
     * Feeds one exchange outcome into the lost-connection detector.
     *
     * The four non-data outcomes are four different facts and are treated as such:
     *
     *  - Every result except [ObdResult.Timeout] completes the exchange and breaks the timeout run.
     *    It does not necessarily prove that the vehicle answered, so only successful vehicle data
     *    produces a liveness beat.
     *  - [ObdResult.Timeout] is counted. It is precisely the outcome that leaves the session owed a
     *    reply that will never come, and a run of them is what makes the session unrecoverable.
     *  - A received parser [ObdResult.AdapterError] is deliberately not a reconnect trigger: an
     *    ISO-TP multi-frame DTC response or disagreeing ECUs do not poison the byte stream. An
     *    AdapterError marked as a transport failure is different: the incoming stream has proved
     *    it ended, so this session is abandoned even if no [TransportEvent.Failed] was emitted.
     */
    private fun record(context: PollSession, result: ObdResult, what: String) {
        when (result) {
            is ObdResult.Data, ObdResult.NoData, is ObdResult.BusError -> context.unanswered.set(0)

            is ObdResult.AdapterError -> {
                if (result.transportFailure) {
                    context.lost.complete("the transport stopped delivering replies: ${result.reason}")
                } else {
                    context.unanswered.set(0)
                    vehicleLog.protocolError("unusable reply to $what: ${result.reason}")
                }
            }

            ObdResult.Timeout -> {
                val run = context.unanswered.incrementAndGet()
                vehicleLog.protocolError("no reply to $what; $run in a row")
                if (run >= maxUnansweredExchanges) {
                    context.lost.complete("the adapter did not answer $run consecutive requests")
                }
            }
        }
    }

    private fun setConnection(next: VehicleConnectionState) {
        val previous = connections.value
        connections.value = next
        if (previous == next) return
        when (next) {
            is VehicleConnectionState.Connected -> vehicleLog.connectionEstablished()
            is VehicleConnectionState.ConnectionLost -> vehicleLog.connectionLost(next.reason)
            is VehicleConnectionState.UnsupportedDevice -> vehicleLog.unsupportedDevice(next.reason)
            is VehicleConnectionState.VehicleCommunicationUnavailable -> vehicleLog.protocolError(next.reason)
            is VehicleConnectionState.Error -> vehicleLog.protocolError(next.reason)
            VehicleConnectionState.Disconnected,
            is VehicleConnectionState.DeviceDetected,
            is VehicleConnectionState.PermissionRequired,
            is VehicleConnectionState.PermissionDenied,
            VehicleConnectionState.Connecting,
            VehicleConnectionState.Reading,
            -> Unit
        }
    }

    private fun clearTelemetry() {
        states.value = VehicleState.unavailable(id)
        diagnosticsStates.value = VehicleDiagnosticsState.empty()
    }

    /**
     * This layer speaks to a [VehicleTransport] and has no USB visibility, but
     * [VehicleConnectionState.UnsupportedDevice] carries a descriptor. When the USB layer did not
     * supply one, the identity the device reported is used as its name and the identifiers are
     * left at [UNKNOWN_USB_ID] — that is an admission of ignorance, not a claim about the hardware.
     */
    private fun descriptorFor(identity: String): UsbDeviceDescriptor = device ?: UsbDeviceDescriptor(
        deviceName = identity.ifBlank { "unidentified adapter" },
        vendorId = UNKNOWN_USB_ID,
        productId = UNKNOWN_USB_ID,
        chipset = null,
    )

    private companion object {
        /** Read every fast poll. Both are bank 0x00 PIDs that any OBD-II vehicle advertises. */
        val FAST_PIDS = listOf(ObdPid.SPEED, ObdPid.RPM)

        /**
         * Read on the slow poll. Deliberately excludes PID 0x31 (distance since codes cleared):
         * no field may hold it, so nothing may request it.
         */
        val SLOW_PIDS = listOf(ObdPid.COOLANT, ObdPid.FUEL_LEVEL, ObdPid.ODOMETER)

        val DTC_SERVICES: List<Pair<ObdCommand, DiagnosticStatus>> = listOf(
            ObdCommand.StoredDtcs to DiagnosticStatus.Stored,
            ObdCommand.PendingDtcs to DiagnosticStatus.Pending,
            ObdCommand.PermanentDtcs to DiagnosticStatus.Permanent,
        )

        /** Least to most severe, for a code reported by more than one service. */
        val STATUS_RANK = listOf(
            DiagnosticStatus.LiveSignal,
            DiagnosticStatus.Pending,
            DiagnosticStatus.Stored,
            DiagnosticStatus.Permanent,
        )

        const val UNKNOWN_USB_ID = 0
    }
}
