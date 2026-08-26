package com.csjotlab.cardashboard.ui.dashboard

import com.csjotlab.cardashboard.vehicle.data.VehicleSnapshot
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticCode
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticIssue
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticStatus
import com.csjotlab.cardashboard.vehicle.domain.DoorPosition
import com.csjotlab.cardashboard.vehicle.domain.DoorState
import com.csjotlab.cardashboard.vehicle.domain.Gear
import com.csjotlab.cardashboard.vehicle.domain.SeatPosition
import com.csjotlab.cardashboard.vehicle.domain.SeatbeltState
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.SignalIssueKind
import com.csjotlab.cardashboard.vehicle.domain.TirePosition
import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull
import java.util.Locale

private const val MAX_SPEED_KPH = 220
private const val LOW_TIRE_KPA = 180f

/** Display formatting is pinned so output does not drift with the device locale (or the CI agent's). */
private val DISPLAY_LOCALE: Locale = Locale.US

/**
 * Turns a domain snapshot into display strings. This is the only place a missing reading becomes a
 * character on screen, which keeps "we do not know this" testable in one spot.
 */
object VehicleStateFormatter {

    fun toUiState(snapshot: VehicleSnapshot, driveModeLabel: String): DashboardUiState {
        val state = snapshot.state
        val speed = state.speedKph.valueOrNull()?.toInt()

        return DashboardUiState(
            speedText = speed?.toString() ?: UNAVAILABLE,
            speedForGauge = speed,
            maxSpeedKph = MAX_SPEED_KPH,
            tripText = state.tripDistanceKm.valueOrNull()
                ?.let { String.format(DISPLAY_LOCALE, "%.1f km", it) } ?: UNAVAILABLE,
            rangeText = state.estimatedRangeKm.valueOrNull()?.let { "${it.toInt()} km" } ?: UNAVAILABLE,
            odometerText = state.odometerKm.valueOrNull()
                ?.let { String.format(DISPLAY_LOCALE, "%,d km", it.toLong()) } ?: UNAVAILABLE,
            metrics = metrics(state, driveModeLabel),
            warnings = warnings(state, snapshot.diagnostics.issues),
            diagnostics = snapshot.diagnostics.issues.map(::toDiagnosticUi),
            connectionLabel = connectionLabel(snapshot.connection),
            connectionActionLabel = if (snapshot.connection is VehicleConnectionState.PermissionRequired) {
                "Grant USB access"
            } else {
                null
            },
            isSimulated = state.source == VehicleSourceId.MOCK,
            isConnected = snapshot.connection is VehicleConnectionState.Reading,
        )
    }

    /** Always four tiles, in a fixed order, so the grid never reflows between states. */
    private fun metrics(state: VehicleState, driveModeLabel: String): List<MetricUi> = listOf(
        metric("RPM", state.engineRpm, "x1000") { String.format(DISPLAY_LOCALE, "%,d", it) },
        metric("Fuel", state.fuelLevelPercent, "Tank level") { "${it.toInt()}%" },
        gearMetric(state.gear, driveModeLabel),
        metric("Temp", state.coolantTemperatureCelsius, "Coolant") { "$it C" },
    )

    /**
     * The signal is passed in whole rather than pre-flattened to a nullable, so the tile can say
     * *why* it is blank. Both blank cases still render [UNAVAILABLE] — never a stand-in number.
     */
    private fun <T> metric(
        label: String,
        signal: Signal<T>,
        helper: String,
        render: (T) -> String,
    ): MetricUi = when (signal) {
        is Signal.Value -> MetricUi(label, render(signal.value), helper, true, MetricAvailability.Available)
        Signal.Unsupported ->
            MetricUi(label, UNAVAILABLE, NOT_SUPPORTED_BY_SOURCE, false, MetricAvailability.Unsupported)
        Signal.Unknown ->
            MetricUi(label, UNAVAILABLE, NOT_REPORTED, false, MetricAvailability.Unknown)
    }

    private fun gearMetric(gear: Signal<Gear>, driveModeLabel: String): MetricUi {
        val label = when (val value = gear.valueOrNull()) {
            Gear.Park -> "P"
            Gear.Reverse -> "R"
            Gear.Neutral -> "N"
            Gear.Drive -> "D"
            Gear.Low -> "L"
            is Gear.Manual -> "M${value.position}"
            // A reported Gear.Unknown is a reading that names no position: blank, but not "unsupported".
            Gear.Unknown, null -> null
        }
        val availability = when {
            label != null -> MetricAvailability.Available
            gear is Signal.Unsupported -> MetricAvailability.Unsupported
            else -> MetricAvailability.Unknown
        }
        return MetricUi(
            label = "Gear",
            value = label ?: UNAVAILABLE,
            // The mode-aware helper survives, but only where there is a gear to describe.
            helper = when (availability) {
                MetricAvailability.Available -> "$driveModeLabel shift"
                MetricAvailability.Unsupported -> NOT_SUPPORTED_BY_SOURCE
                MetricAvailability.Unknown -> NOT_REPORTED
            },
            available = label != null,
            availability = availability,
        )
    }

    /**
     * The Warnings panel header. Coverage matters as much as the count: "no row is Active" and
     * "every row reported and is safe" are different facts, and only the second one may be phrased
     * as "All clear".
     *
     * Zero active rows over a list that includes a [WarningLevel.NotReported] row means nothing has
     * been read yet — the state every launch starts in while no source is connected. Announcing
     * "All clear" there would assert that the seatbelts, doors, tyres and check-engine lamp are all
     * fine on no data at all: a placeholder standing in for four readings, which is exactly what
     * [row] refuses to do one level down. Unknown coverage renders as [UNAVAILABLE], the same
     * marker the rows themselves use.
     *
     * An empty list is "nothing known", not "nothing wrong".
     */
    fun warningsSummary(warnings: List<WarningUi>): String {
        val activeWarningCount = warnings.count { it.level == WarningLevel.Active }
        val allReported = warnings.isNotEmpty() && warnings.none { it.level == WarningLevel.NotReported }
        return when {
            activeWarningCount > 0 -> "$activeWarningCount active"
            allReported -> "All clear"
            else -> UNAVAILABLE
        }
    }

    /** Always four rows, in a fixed order, so the compact-landscape SpaceEvenly fix still holds. */
    private fun warnings(state: VehicleState, issues: List<DiagnosticIssue>): List<WarningUi> {
        val seatbelts = state.seatbelts.valueOrNull()
        val doors = state.doors.valueOrNull()
        val tires = state.tirePressuresKpa.valueOrNull()
        val mil = state.malfunctionIndicatorLampOn.valueOrNull()
        val dtcIssue = issues.firstOrNull { it.code is DiagnosticCode.Dtc }

        return listOf(
            row(
                label = "Seatbelt",
                reported = seatbelts,
                expected = SeatPosition.entries.toSet(),
                offending = seatbelts?.filterValues { it == SeatbeltState.Unbuckled }?.keys.orEmpty(),
                activeHelper = { "${it.size} unbuckled" },
                okHelper = "Secured",
                issueId = signalIssueId(issues, SignalIssueKind.SeatbeltUnbuckled),
            ),
            row(
                label = "Door",
                reported = doors,
                expected = DoorPosition.entries.toSet(),
                offending = doors?.filterValues { it == DoorState.Open }?.keys.orEmpty(),
                activeHelper = { positions -> positions.joinToString { readable(it) } + " open" },
                okHelper = "All doors closed",
                issueId = signalIssueId(issues, SignalIssueKind.DoorOpen),
            ),
            row(
                label = "Tire Pressure",
                reported = tires,
                expected = TirePosition.entries.toSet(),
                offending = tires?.filterValues { it < LOW_TIRE_KPA }?.keys.orEmpty(),
                activeHelper = { positions -> positions.joinToString { readable(it) } + " low" },
                okHelper = "Nominal",
                issueId = signalIssueId(issues, SignalIssueKind.TirePressureLow),
            ),
            when (mil) {
                null -> WarningUi("Check Engine", WarningLevel.NotReported, "Not reported", null)
                true -> WarningUi(
                    "Check Engine",
                    WarningLevel.Active,
                    dtcIssue?.let { "Code ${(it.code as DiagnosticCode.Dtc).code}" } ?: "Engine diagnostic code detected",
                    dtcIssue?.id,
                )
                false -> WarningUi("Check Engine", WarningLevel.Ok, "No fault", null)
            },
        )
    }

    /**
     * A safety row may only claim "all clear" when the source reported *every* position.
     *
     * An empty offending set means "none of the positions that were reported is unsafe" — which is
     * not the same statement as "the vehicle is fine". Claiming the latter from the former would
     * synthesise seatbelt/door/tyre state, so partial coverage stays [WarningLevel.NotReported] and
     * says how much is missing. A known-unsafe position still wins: it is true regardless of what
     * else went unreported.
     */
    private fun <T> row(
        label: String,
        reported: Map<T, *>?,
        expected: Set<T>,
        offending: Set<T>,
        activeHelper: (Set<T>) -> String,
        okHelper: String,
        issueId: String?,
    ): WarningUi = when {
        reported == null -> WarningUi(label, WarningLevel.NotReported, "Not reported", null)
        offending.isNotEmpty() -> WarningUi(label, WarningLevel.Active, activeHelper(offending), issueId)
        !reported.keys.containsAll(expected) -> {
            val missing = expected.count { it !in reported.keys }
            WarningUi(label, WarningLevel.NotReported, "$missing of ${expected.size} not reported", null)
        }
        else -> WarningUi(label, WarningLevel.Ok, okHelper, null)
    }

    /** Links a safety row to the live signal-derived issue behind it, when diagnostics carry one. */
    private fun signalIssueId(issues: List<DiagnosticIssue>, kind: SignalIssueKind): String? =
        issues.firstOrNull { (it.code as? DiagnosticCode.SignalDerived)?.kind == kind }?.id

    private fun readable(position: Any): String = when (position) {
        DoorPosition.FrontLeft, TirePosition.FrontLeft -> "Front left"
        DoorPosition.FrontRight, TirePosition.FrontRight -> "Front right"
        DoorPosition.RearLeft, TirePosition.RearLeft -> "Rear left"
        DoorPosition.RearRight, TirePosition.RearRight -> "Rear right"
        DoorPosition.Hood -> "Hood"
        DoorPosition.Trunk -> "Trunk"
        else -> position.toString()
    }

    private fun toDiagnosticUi(issue: DiagnosticIssue) = DiagnosticUi(
        id = issue.id,
        code = when (val code = issue.code) {
            is DiagnosticCode.Dtc -> code.code
            is DiagnosticCode.SignalDerived -> code.detail
        },
        // Never a diagnosis: the code is the fact, and the title stays null until a trusted mapping exists.
        headline = issue.title ?: when (issue.code) {
            is DiagnosticCode.Dtc -> "Engine diagnostic code detected"
            is DiagnosticCode.SignalDerived -> "Vehicle signal reported"
        },
        statusLabel = when (issue.status) {
            DiagnosticStatus.Stored -> "Stored"
            DiagnosticStatus.Pending -> "Pending"
            DiagnosticStatus.Permanent -> "Permanent"
            DiagnosticStatus.LiveSignal -> "Live"
        },
        severity = issue.severity,
    )

    private fun connectionLabel(connection: VehicleConnectionState): String = when (connection) {
        VehicleConnectionState.Disconnected -> "Vehicle not connected"
        is VehicleConnectionState.DeviceDetected -> "USB device detected"
        is VehicleConnectionState.PermissionRequired -> "USB permission required"
        is VehicleConnectionState.PermissionDenied -> "USB permission denied"
        VehicleConnectionState.Connecting -> "Connecting"
        is VehicleConnectionState.Connected -> "Connected"
        VehicleConnectionState.Reading -> "Reading data"
        is VehicleConnectionState.UnsupportedDevice -> "Unsupported device"
        is VehicleConnectionState.VehicleCommunicationUnavailable -> "Vehicle communication unavailable"
        is VehicleConnectionState.ConnectionLost -> "Connection lost"
        is VehicleConnectionState.Error -> "Error"
    }
}
