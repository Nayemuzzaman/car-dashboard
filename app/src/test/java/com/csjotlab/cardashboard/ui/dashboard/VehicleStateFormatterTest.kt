package com.csjotlab.cardashboard.ui.dashboard

import com.csjotlab.cardashboard.vehicle.data.VehicleSnapshot
import com.csjotlab.cardashboard.vehicle.domain.AdapterIdentity
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticCode
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticIssue
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticSource
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticStatus
import com.csjotlab.cardashboard.vehicle.domain.DoorPosition
import com.csjotlab.cardashboard.vehicle.domain.DoorState
import com.csjotlab.cardashboard.vehicle.domain.Gear
import com.csjotlab.cardashboard.vehicle.domain.SeatPosition
import com.csjotlab.cardashboard.vehicle.domain.SeatbeltState
import com.csjotlab.cardashboard.vehicle.domain.Severity
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.SignalIssueKind
import com.csjotlab.cardashboard.vehicle.domain.TirePosition
import com.csjotlab.cardashboard.vehicle.domain.UsbDeviceDescriptor
import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleDiagnosticsState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VehicleStateFormatterTest {

    private fun reading(
        state: VehicleState,
        diagnostics: VehicleDiagnosticsState = VehicleDiagnosticsState.empty(),
    ) = VehicleSnapshot(
        state = state,
        diagnostics = diagnostics,
        connection = VehicleConnectionState.Reading,
    )

    private fun issue(
        id: String,
        code: DiagnosticCode,
        title: String? = null,
        status: DiagnosticStatus = DiagnosticStatus.Stored,
        severity: Severity = Severity.Warning,
        source: DiagnosticSource = DiagnosticSource.Obd2,
    ) = DiagnosticIssue(
        id = id,
        code = code,
        title = title,
        description = null,
        classification = null,
        severity = severity,
        status = status,
        source = source,
        firstSeenMs = 1L,
        lastSeenMs = 2L,
    )

    private fun diagnostics(vararg issues: DiagnosticIssue) = VehicleDiagnosticsState.empty()
        .copy(issues = issues.toList())

    private val device = UsbDeviceDescriptor("usb", 1, 2, null)

    @Test
    fun `disconnected shows no numbers anywhere`() {
        val ui = VehicleStateFormatter.toUiState(VehicleSnapshot.Disconnected, driveModeLabel = "Comfort")

        assertEquals(UNAVAILABLE, ui.speedText)
        assertEquals(UNAVAILABLE, ui.tripText)
        assertEquals(UNAVAILABLE, ui.rangeText)
        assertEquals(UNAVAILABLE, ui.odometerText)
        assertEquals("Vehicle not connected", ui.connectionLabel)
        assertFalse(ui.isConnected)
        assertTrue(ui.metrics.none { it.available })
        assertEquals(4, ui.metrics.size)
        assertEquals(4, ui.warnings.size)
    }

    @Test
    fun `an unavailable field never renders as zero`() {
        val ui = VehicleStateFormatter.toUiState(VehicleSnapshot.Disconnected, "Comfort")

        assertTrue(ui.metrics.none { it.value == "0" || it.value == "0%" || it.value == "0 C" })
        // A missing speed must leave the gauge with no position rather than pinning it to zero.
        assertEquals(null, ui.speedForGauge)
    }

    @Test
    fun `unreported safety signals are neutral not OK`() {
        val ui = VehicleStateFormatter.toUiState(VehicleSnapshot.Disconnected, "Comfort")

        val safetyRows = ui.warnings.filter { it.label in setOf("Seatbelt", "Door", "Tire Pressure") }
        assertEquals(3, safetyRows.size)
        assertTrue(
            "green OK without data would be a fabricated safety claim",
            safetyRows.all { it.level == WarningLevel.NotReported }
        )
        assertTrue(safetyRows.all { it.helper == "Not reported" })
    }

    // --- Partial safety maps (regression: a partial map once rendered as a whole-vehicle all-clear)

    @Test
    fun `a partially reported seatbelt map never reads as secured`() {
        val ui = VehicleStateFormatter.toUiState(
            reading(
                VehicleState.unavailable(VehicleSourceId.MOCK).copy(
                    // Only the driver's belt is reported; four other seats are unknown.
                    seatbelts = Signal.Value(mapOf(SeatPosition.Driver to SeatbeltState.Buckled), 1L),
                )
            ),
            "Comfort",
        )

        val row = ui.warnings.first { it.label == "Seatbelt" }
        assertEquals(
            "claiming all belts fastened from one reported seat is a fabricated safety claim",
            WarningLevel.NotReported,
            row.level,
        )
        assertEquals("4 of 5 not reported", row.helper)
    }

    @Test
    fun `a partially reported door map never reads as all closed`() {
        val ui = VehicleStateFormatter.toUiState(
            reading(
                VehicleState.unavailable(VehicleSourceId.OBD_USB).copy(
                    doors = Signal.Value(
                        mapOf(
                            DoorPosition.FrontLeft to DoorState.Closed,
                            DoorPosition.FrontRight to DoorState.Closed,
                        ),
                        1L,
                    ),
                )
            ),
            "Comfort",
        )

        val row = ui.warnings.first { it.label == "Door" }
        assertEquals(WarningLevel.NotReported, row.level)
        assertEquals("4 of 6 not reported", row.helper)
    }

    @Test
    fun `a partially reported tyre map never reads as nominal`() {
        val ui = VehicleStateFormatter.toUiState(
            reading(
                VehicleState.unavailable(VehicleSourceId.OBD_USB).copy(
                    tirePressuresKpa = Signal.Value(
                        mapOf(TirePosition.FrontLeft to 230f, TirePosition.FrontRight to 232f),
                        1L,
                    ),
                )
            ),
            "Comfort",
        )

        val row = ui.warnings.first { it.label == "Tire Pressure" }
        assertEquals(WarningLevel.NotReported, row.level)
        assertEquals("2 of 4 not reported", row.helper)
    }

    @Test
    fun `an unsafe reported seat stays active even when the rest are unreported`() {
        val ui = VehicleStateFormatter.toUiState(
            reading(
                VehicleState.unavailable(VehicleSourceId.MOCK).copy(
                    seatbelts = Signal.Value(mapOf(SeatPosition.Driver to SeatbeltState.Unbuckled), 1L),
                )
            ),
            "Comfort",
        )

        val row = ui.warnings.first { it.label == "Seatbelt" }
        assertEquals("a known-unsafe belt must win over incomplete coverage", WarningLevel.Active, row.level)
        assertEquals("1 unbuckled", row.helper)
    }

    @Test
    fun `a fully reported and safe safety map reads as ok`() {
        val ui = VehicleStateFormatter.toUiState(
            reading(
                VehicleState.unavailable(VehicleSourceId.MOCK).copy(
                    seatbelts = Signal.Value(
                        SeatPosition.entries.associateWith { SeatbeltState.Buckled },
                        1L,
                    ),
                    doors = Signal.Value(DoorPosition.entries.associateWith { DoorState.Closed }, 1L),
                    tirePressuresKpa = Signal.Value(TirePosition.entries.associateWith { 230f }, 1L),
                    malfunctionIndicatorLampOn = Signal.Value(false, 1L),
                )
            ),
            "Comfort",
        )

        assertEquals(WarningLevel.Ok, ui.warnings.first { it.label == "Seatbelt" }.level)
        assertEquals("Secured", ui.warnings.first { it.label == "Seatbelt" }.helper)
        assertEquals(WarningLevel.Ok, ui.warnings.first { it.label == "Door" }.level)
        assertEquals("All doors closed", ui.warnings.first { it.label == "Door" }.helper)
        assertEquals(WarningLevel.Ok, ui.warnings.first { it.label == "Tire Pressure" }.level)
        assertEquals("Nominal", ui.warnings.first { it.label == "Tire Pressure" }.helper)
        assertEquals(WarningLevel.Ok, ui.warnings.first { it.label == "Check Engine" }.level)
        assertEquals("No fault", ui.warnings.first { it.label == "Check Engine" }.helper)
        assertEquals(4, ui.warnings.size)
    }

    @Test
    fun `open doors and low tyres are named in an active row`() {
        val ui = VehicleStateFormatter.toUiState(
            reading(
                VehicleState.unavailable(VehicleSourceId.MOCK).copy(
                    doors = Signal.Value(
                        DoorPosition.entries.associateWith { DoorState.Closed } +
                            (DoorPosition.RearLeft to DoorState.Open),
                        1L,
                    ),
                    tirePressuresKpa = Signal.Value(
                        TirePosition.entries.associateWith { 230f } + (TirePosition.FrontRight to 150f),
                        1L,
                    ),
                )
            ),
            "Comfort",
        )

        val door = ui.warnings.first { it.label == "Door" }
        assertEquals(WarningLevel.Active, door.level)
        assertEquals("Rear left open", door.helper)

        val tire = ui.warnings.first { it.label == "Tire Pressure" }
        assertEquals(WarningLevel.Active, tire.level)
        assertEquals("Front right low", tire.helper)
    }

    @Test
    fun `reported values are formatted with their units`() {
        val ui = VehicleStateFormatter.toUiState(
            reading(
                VehicleState.unavailable(VehicleSourceId.OBD_USB).copy(
                    speedKph = Signal.Value(60f, 1L),
                    engineRpm = Signal.Value(2100, 1L),
                    fuelLevelPercent = Signal.Value(62f, 1L),
                    coolantTemperatureCelsius = Signal.Value(91, 1L),
                )
            ),
            driveModeLabel = "Sport",
        )

        assertEquals("60", ui.speedText)
        assertEquals(60, ui.speedForGauge)
        assertEquals("2,100", ui.metrics.first { it.label == "RPM" }.value)
        assertEquals("62%", ui.metrics.first { it.label == "Fuel" }.value)
        assertEquals("91 C", ui.metrics.first { it.label == "Temp" }.value)
    }

    @Test
    fun `the gear helper stays mode aware when a gear is known`() {
        val ui = VehicleStateFormatter.toUiState(
            reading(VehicleState.unavailable(VehicleSourceId.MOCK).copy(gear = Signal.Value(Gear.Drive, 1L))),
            driveModeLabel = "Eco",
        )

        val gear = ui.metrics.first { it.label == "Gear" }
        assertEquals("D", gear.value)
        assertEquals("Eco shift", gear.helper)
        assertTrue(gear.available)
    }

    @Test
    fun `the gear tile is honest when no gear is reported`() {
        val ui = VehicleStateFormatter.toUiState(
            reading(VehicleState.unavailable(VehicleSourceId.OBD_USB).copy(gear = Signal.Unknown)),
            driveModeLabel = "Eco",
        )

        val gear = ui.metrics.first { it.label == "Gear" }
        assertEquals(UNAVAILABLE, gear.value)
        assertEquals(NOT_REPORTED, gear.helper)
        assertFalse(gear.available)
        assertEquals(MetricAvailability.Unknown, gear.availability)
    }

    // --- Unsupported vs Unknown (regression: both once claimed "Not reported by vehicle")

    @Test
    fun `an unsupported field is attributed to the source and not to the vehicle`() {
        val ui = VehicleStateFormatter.toUiState(
            reading(VehicleState.unavailable(VehicleSourceId.OBD_USB).copy(engineRpm = Signal.Unsupported)),
            "Comfort",
        )

        val rpm = ui.metrics.first { it.label == "RPM" }
        assertEquals(UNAVAILABLE, rpm.value)
        assertFalse(rpm.available)
        assertEquals(MetricAvailability.Unsupported, rpm.availability)
        assertEquals(
            "\"the vehicle did not report it\" is a claim the system cannot make about an unsupported field",
            NOT_SUPPORTED_BY_SOURCE,
            rpm.helper,
        )
    }

    @Test
    fun `an unsupported gear is attributed to the source`() {
        val ui = VehicleStateFormatter.toUiState(
            reading(VehicleState.unavailable(VehicleSourceId.OBD_USB).copy(gear = Signal.Unsupported)),
            driveModeLabel = "Eco",
        )

        val gear = ui.metrics.first { it.label == "Gear" }
        assertEquals(UNAVAILABLE, gear.value)
        assertFalse(gear.available)
        assertEquals(MetricAvailability.Unsupported, gear.availability)
        assertEquals(NOT_SUPPORTED_BY_SOURCE, gear.helper)
    }

    @Test
    fun `unsupported and unknown do not collapse into the same tile`() {
        val ui = VehicleStateFormatter.toUiState(
            reading(
                VehicleState.unavailable(VehicleSourceId.OBD_USB).copy(
                    engineRpm = Signal.Unsupported,
                    fuelLevelPercent = Signal.Unknown,
                )
            ),
            "Comfort",
        )

        val rpm = ui.metrics.first { it.label == "RPM" }
        val fuel = ui.metrics.first { it.label == "Fuel" }

        // Both are honestly blank...
        assertEquals(UNAVAILABLE, rpm.value)
        assertEquals(UNAVAILABLE, fuel.value)
        // ...but they are not the same statement.
        assertNotEquals(rpm.availability, fuel.availability)
        assertNotEquals(rpm.helper, fuel.helper)
        assertEquals(MetricAvailability.Unknown, fuel.availability)
        assertEquals(NOT_REPORTED, fuel.helper)
    }

    @Test
    fun `every door position gets a readable name`() {
        val ui = VehicleStateFormatter.toUiState(
            reading(
                VehicleState.unavailable(VehicleSourceId.MOCK).copy(
                    doors = Signal.Value(DoorPosition.entries.associateWith { DoorState.Open }, 1L),
                )
            ),
            "Comfort",
        )

        assertEquals(
            "Front left, Front right, Rear left, Rear right, Hood, Trunk open",
            ui.warnings.first { it.label == "Door" }.helper,
        )
    }

    @Test
    fun `every tyre position gets a readable name`() {
        val ui = VehicleStateFormatter.toUiState(
            reading(
                VehicleState.unavailable(VehicleSourceId.MOCK).copy(
                    tirePressuresKpa = Signal.Value(TirePosition.entries.associateWith { 120f }, 1L),
                )
            ),
            "Comfort",
        )

        assertEquals(
            "Front left, Front right, Rear left, Rear right low",
            ui.warnings.first { it.label == "Tire Pressure" }.helper,
        )
    }

    // --- Check Engine, DTC wiring and diagnostics mapping

    @Test
    fun `the check engine row names the reported code and links to its issue`() {
        val ui = VehicleStateFormatter.toUiState(
            reading(
                VehicleState.unavailable(VehicleSourceId.OBD_USB)
                    .copy(malfunctionIndicatorLampOn = Signal.Value(true, 1L)),
                diagnostics(issue("dtc-1", DiagnosticCode.Dtc("P0301"))),
            ),
            "Comfort",
        )

        val row = ui.warnings.first { it.label == "Check Engine" }
        assertEquals(WarningLevel.Active, row.level)
        assertEquals("Code P0301", row.helper)
        assertEquals("dtc-1", row.issueId)
    }

    @Test
    fun `an active lamp with no stored code claims no code`() {
        val ui = VehicleStateFormatter.toUiState(
            reading(
                VehicleState.unavailable(VehicleSourceId.OBD_USB)
                    .copy(malfunctionIndicatorLampOn = Signal.Value(true, 1L))
            ),
            "Comfort",
        )

        val row = ui.warnings.first { it.label == "Check Engine" }
        assertEquals(WarningLevel.Active, row.level)
        assertEquals("Engine diagnostic code detected", row.helper)
        assertEquals(null, row.issueId)
    }

    @Test
    fun `an active safety row links to its signal derived issue`() {
        val ui = VehicleStateFormatter.toUiState(
            reading(
                VehicleState.unavailable(VehicleSourceId.MOCK).copy(
                    doors = Signal.Value(
                        DoorPosition.entries.associateWith { DoorState.Closed } +
                            (DoorPosition.Trunk to DoorState.Open),
                        1L,
                    ),
                ),
                diagnostics(
                    issue(
                        "door-1",
                        DiagnosticCode.SignalDerived(SignalIssueKind.DoorOpen, "Trunk open"),
                        status = DiagnosticStatus.LiveSignal,
                        source = DiagnosticSource.VehicleSignal,
                    ),
                    issue(
                        "belt-1",
                        DiagnosticCode.SignalDerived(SignalIssueKind.SeatbeltUnbuckled, "Driver"),
                        status = DiagnosticStatus.LiveSignal,
                        source = DiagnosticSource.VehicleSignal,
                    ),
                ),
            ),
            "Comfort",
        )

        val door = ui.warnings.first { it.label == "Door" }
        assertEquals(WarningLevel.Active, door.level)
        assertEquals("Trunk open", door.helper)
        assertEquals("door-1", door.issueId)
        // A row that is not Active carries no issue link, even when diagnostics hold a matching kind.
        assertEquals(null, ui.warnings.first { it.label == "Seatbelt" }.issueId)
    }

    @Test
    fun `diagnostics map to display rows without inventing a diagnosis`() {
        val ui = VehicleStateFormatter.toUiState(
            reading(
                VehicleState.unavailable(VehicleSourceId.OBD_USB),
                diagnostics(
                    issue("a", DiagnosticCode.Dtc("P0301"), status = DiagnosticStatus.Stored),
                    issue("b", DiagnosticCode.Dtc("P0420"), status = DiagnosticStatus.Pending, severity = Severity.Info),
                    issue("c", DiagnosticCode.Dtc("C1234"), status = DiagnosticStatus.Permanent, severity = Severity.Critical),
                    issue(
                        "d",
                        DiagnosticCode.SignalDerived(SignalIssueKind.TirePressureLow, "Front left low"),
                        status = DiagnosticStatus.LiveSignal,
                    ),
                ),
            ),
            "Comfort",
        )

        assertEquals(listOf("a", "b", "c", "d"), ui.diagnostics.map { it.id })
        assertEquals(listOf("P0301", "P0420", "C1234", "Front left low"), ui.diagnostics.map { it.code })
        assertEquals(
            listOf("Stored", "Pending", "Permanent", "Live"),
            ui.diagnostics.map { it.statusLabel },
        )
        assertEquals(
            listOf(Severity.Warning, Severity.Info, Severity.Critical, Severity.Warning),
            ui.diagnostics.map { it.severity },
        )
        // No trusted code→component mapping ships, so the headline states the fact, not a cause.
        assertEquals("Engine diagnostic code detected", ui.diagnostics[0].headline)
        assertEquals("Vehicle signal reported", ui.diagnostics[3].headline)
    }

    @Test
    fun `a supplied issue title is used verbatim as the headline`() {
        val ui = VehicleStateFormatter.toUiState(
            reading(
                VehicleState.unavailable(VehicleSourceId.OBD_USB),
                diagnostics(issue("a", DiagnosticCode.Dtc("P0301"), title = "Cylinder 1 misfire")),
            ),
            "Comfort",
        )

        assertEquals("Cylinder 1 misfire", ui.diagnostics.single().headline)
    }

    // --- Connection labels and actions

    @Test
    fun `every connection state has its own label`() {
        fun labelFor(connection: VehicleConnectionState) = VehicleStateFormatter.toUiState(
            VehicleSnapshot.Disconnected.copy(connection = connection),
            "Comfort",
        ).connectionLabel

        assertEquals("Vehicle not connected", labelFor(VehicleConnectionState.Disconnected))
        assertEquals("USB device detected", labelFor(VehicleConnectionState.DeviceDetected(device)))
        assertEquals("USB permission required", labelFor(VehicleConnectionState.PermissionRequired(device)))
        assertEquals("USB permission denied", labelFor(VehicleConnectionState.PermissionDenied(device)))
        assertEquals("Connecting", labelFor(VehicleConnectionState.Connecting))
        assertEquals(
            "Connected",
            labelFor(VehicleConnectionState.Connected(AdapterIdentity("ELM327 v1.5", true), "ISO 15765-4")),
        )
        assertEquals("Reading data", labelFor(VehicleConnectionState.Reading))
        assertEquals("Unsupported device", labelFor(VehicleConnectionState.UnsupportedDevice(device, "no driver")))
        assertEquals(
            "Vehicle communication unavailable",
            labelFor(VehicleConnectionState.VehicleCommunicationUnavailable("no response")),
        )
        assertEquals("Connection lost", labelFor(VehicleConnectionState.ConnectionLost("unplugged")))
        assertEquals("Error", labelFor(VehicleConnectionState.Error("boom")))
    }

    @Test
    fun `only a permission prompt offers an action`() {
        fun uiFor(connection: VehicleConnectionState) = VehicleStateFormatter.toUiState(
            VehicleSnapshot.Disconnected.copy(connection = connection),
            "Comfort",
        )

        assertEquals("Grant USB access", uiFor(VehicleConnectionState.PermissionRequired(device)).connectionActionLabel)
        assertEquals(null, uiFor(VehicleConnectionState.PermissionDenied(device)).connectionActionLabel)
        assertEquals(null, uiFor(VehicleConnectionState.Reading).connectionActionLabel)
        assertTrue(uiFor(VehicleConnectionState.Reading).isConnected)
        assertFalse(uiFor(VehicleConnectionState.Connecting).isConnected)
    }

    @Test
    fun `the simulated flag follows the source and nothing else`() {
        assertTrue(
            VehicleStateFormatter.toUiState(
                reading(VehicleState.unavailable(VehicleSourceId.MOCK)),
                "Comfort",
            ).isSimulated
        )
        assertFalse(
            VehicleStateFormatter.toUiState(
                reading(VehicleState.unavailable(VehicleSourceId.OBD_USB)),
                "Comfort",
            ).isSimulated
        )
    }

    // --- Distances

    @Test
    fun `trip range and odometer carry their units when reported`() {
        val ui = VehicleStateFormatter.toUiState(
            reading(
                VehicleState.unavailable(VehicleSourceId.OBD_USB).copy(
                    tripDistanceKm = Signal.Value(12.34, 1L),
                    estimatedRangeKm = Signal.Value(140.7, 1L),
                    odometerKm = Signal.Value(12345.0, 1L),
                )
            ),
            "Comfort",
        )

        // Locale-pinned: these must read the same on a CI agent set to any locale.
        assertEquals("12.3 km", ui.tripText)
        assertEquals("140 km", ui.rangeText)
        assertEquals("12,345 km", ui.odometerText)
    }

    @Test
    fun `number formatting does not follow the ambient locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val ui = VehicleStateFormatter.toUiState(
                reading(
                    VehicleState.unavailable(VehicleSourceId.OBD_USB).copy(
                        tripDistanceKm = Signal.Value(12.34, 1L),
                        odometerKm = Signal.Value(12345.0, 1L),
                        engineRpm = Signal.Value(2100, 1L),
                    )
                ),
                "Comfort",
            )

            assertEquals("12.3 km", ui.tripText)
            assertEquals("12,345 km", ui.odometerText)
            assertEquals("2,100", ui.metrics.first { it.label == "RPM" }.value)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `a reported value is marked available`() {
        val ui = VehicleStateFormatter.toUiState(
            reading(
                VehicleState.unavailable(VehicleSourceId.OBD_USB)
                    .copy(coolantTemperatureCelsius = Signal.Value(91, 1L))
            ),
            "Comfort",
        )

        val temp = ui.metrics.first { it.label == "Temp" }
        assertEquals(MetricAvailability.Available, temp.availability)
        assertTrue(temp.available)
        assertEquals("Coolant", temp.helper)
    }

    // --- Warnings panel header -------------------------------------------------------------
    //
    // "no row is Active" and "every row reported and is safe" are different facts. Only the second
    // one may say "All clear"; the first, on its own, is a claim about four safety systems that
    // nothing has read.

    private fun warningRow(label: String, level: WarningLevel) =
        WarningUi(label = label, level = level, helper = "", issueId = null)

    @Test
    fun `warnings with nothing reported never claim all clear`() {
        val summary = VehicleStateFormatter.warningsSummary(
            listOf(
                warningRow("Seatbelt", WarningLevel.NotReported),
                warningRow("Door", WarningLevel.NotReported),
                warningRow("Tire Pressure", WarningLevel.NotReported),
                warningRow("Check Engine", WarningLevel.NotReported),
            )
        )

        assertNotEquals("no reading is not a safety claim", "All clear", summary)
        assertEquals(UNAVAILABLE, summary)
    }

    @Test
    fun `a single unreported row is enough to withhold all clear`() {
        val summary = VehicleStateFormatter.warningsSummary(
            listOf(
                warningRow("Seatbelt", WarningLevel.Ok),
                warningRow("Door", WarningLevel.Ok),
                warningRow("Tire Pressure", WarningLevel.Ok),
                warningRow("Check Engine", WarningLevel.NotReported),
            )
        )

        assertNotEquals("All clear", summary)
        assertEquals(UNAVAILABLE, summary)
    }

    @Test
    fun `all clear requires every row to have reported and be safe`() {
        val summary = VehicleStateFormatter.warningsSummary(
            listOf(
                warningRow("Seatbelt", WarningLevel.Ok),
                warningRow("Door", WarningLevel.Ok),
                warningRow("Tire Pressure", WarningLevel.Ok),
                warningRow("Check Engine", WarningLevel.Ok),
            )
        )

        assertEquals("All clear", summary)
    }

    @Test
    fun `an active row is counted even when other rows never reported`() {
        val summary = VehicleStateFormatter.warningsSummary(
            listOf(
                warningRow("Seatbelt", WarningLevel.Active),
                warningRow("Door", WarningLevel.NotReported),
                warningRow("Tire Pressure", WarningLevel.Active),
                warningRow("Check Engine", WarningLevel.NotReported),
            )
        )

        assertEquals("2 active", summary)
    }

    @Test
    fun `the disconnected dashboard does not announce all clear`() {
        val ui = VehicleStateFormatter.toUiState(VehicleSnapshot.Disconnected, "Comfort")

        assertTrue(ui.warnings.all { it.level == WarningLevel.NotReported })
        assertNotEquals(
            "the only reachable launch state must not assert four safety systems are fine",
            "All clear",
            VehicleStateFormatter.warningsSummary(ui.warnings),
        )
    }
}
