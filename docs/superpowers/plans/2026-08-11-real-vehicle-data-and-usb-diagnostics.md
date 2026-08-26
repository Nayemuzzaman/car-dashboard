# Real Vehicle Data & USB Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the mock dashboard data with a layered, honest vehicle-data architecture that reads real telemetry and read-only OBD-II diagnostics over USB, and renders unavailable data as unavailable rather than inventing it.

**Architecture:** A four-layer stack — `VehicleTransport` → `Elm327Session`/protocol → `VehicleDataSource` → `VehicleRepository` → `DashboardViewModel` → Compose. Domain and protocol packages are pure Kotlin with JVM unit tests. Two interchangeable sources (`MockVehicleDataSource`, `ObdVehicleDataSource`) sit behind one interface; the UI never touches hardware. Build order is domain-first and hardware-last, so a fully working, fully tested dashboard exists before any USB code is written.

**Tech Stack:** Kotlin 1.9.24, AGP 8.4.2, Jetpack Compose (BOM 2024.06.00), Coroutines/Flow, AndroidX Lifecycle ViewModel, JUnit4 + kotlinx-coroutines-test + Turbine, Compose UI test. `usb-serial-for-android` (JitPack) — **added only at Task 18, not before**.

**Spec:** `docs/superpowers/specs/2026-08-11-real-vehicle-data-and-usb-diagnostics-design.md`

## Global Constraints

- **Do not commit. Do not push.** Leave every change in the working tree. Each task ends with a verification checkpoint, not a commit. This overrides the usual TDD commit cadence.
- **Read-only vehicle access.** Only ELM327 `AT` configuration commands and OBD-II services `01`, `03`, `07`, `0A`, `09 PID 00`. Never service `04` (clear DTCs), never any UDS write/routine/session service, never arbitrary CAN transmission. `ObdCommand` is `sealed` and a test asserts its subtype set.
- **Never read the VIN** (mode `09` PID `02`).
- **Capability discovery is authoritative** (spec §2.1). A field is populated only when its own discovered capability permits and the value came from that field's own request. No cross-signal inference: gear never from speed/RPM, range never from fuel × assumed consumption, odometer never from integrated speed, trip never substituted from PID `31`. No inferred diagnosis. No placeholder standing in for a reading. Discovery failure yields `Signal.Unknown`, never `Signal.Unsupported`.
- **`Signal.Unsupported` is scoped to the active source**, not a claim about the vehicle.
- **No fallback from a real source to mock.** A failing real source emits a failure state and clears telemetry.
- **Mock mode:** class lives in `src/main`; *selection* requires `BuildConfig.DEBUG` **and** an explicit toggle. When active, a persistent `SIMULATED DATA — NOT A REAL VEHICLE` banner shows.
- **No regressions** to: portrait `SpeedPanel` fixed `height(360.dp)`; `SpeedGauge` sized `minOf(maxWidth, maxHeight, 260.dp)`; root `windowInsetsPadding(WindowInsets.systemBars)`; wide layout threshold `maxWidth >= 720.dp && maxHeight >= 300.dp`; compact threshold `maxHeight < 480.dp`; `MetricGrid` weight only when `fillHeight`; `MetricTile` `heightIn(min = 128.dp)` dropped when compact; `WarningPanel` `SpaceEvenly` in a weighted column when compact; exactly four metric tiles and four warning rows in every state; `DriveMode` in `rememberSaveable`.
- **Package root:** `com.csjotlab.cardashboard.vehicle` with sub-packages `domain`, `protocol`, `transport`, `source`, `data`.
- **`domain` and `protocol` contain zero Android imports.**
- Units are explicit in field names (`speedKph`, `coolantTemperatureCelsius`, `odometerKm`).
- Run unit tests with `./gradlew testDebugUnitTest`; instrumented tests with `./gradlew connectedDebugAndroidTest`.

## File Structure

**Domain (pure Kotlin, no Android):** `vehicle/domain/`
- `Signal.kt` — `Signal<T>` sealed interface + accessors
- `VehicleEnums.kt` — `Gear`, `DoorPosition`, `DoorState`, `SeatPosition`, `SeatbeltState`, `TirePosition`, `VehicleSourceId`
- `VehicleState.kt` — `VehicleState` + `unavailable()`
- `Diagnostics.kt` — `DiagnosticCode`, `DtcSystem`, `DtcClassification`, `DiagnosticStatus`, `Severity`, `DiagnosticSource`, `DiagnosticIssue`, `VehicleDiagnosticsState`
- `VehicleConnectionState.kt` — connection states, `UsbDeviceDescriptor`, `AdapterIdentity`

**Protocol (pure Kotlin, no Android):** `vehicle/protocol/`
- `DtcDecoder.kt` — J2012 structural decoding + byte-pair decoding + severity rules
- `ObdCommand.kt` — sealed read-only command set
- `ObdPid.kt` — PID constants and per-PID decoders
- `SupportedPidSet.kt` — capability bitmask decoding
- `Elm327Session.kt` — framing, handshake, error replies, timeouts

**Transport:** `vehicle/transport/`
- `VehicleTransport.kt` — interface + `TransportEvent`
- `UsbSerialTransport.kt` — **Task 18 only**
- `UsbDeviceScanner.kt` — **Task 18 only**

**Sources:** `vehicle/source/`
- `VehicleDataSource.kt` — interface
- `MockVehicleDataSource.kt` — scripted domain-level timeline
- `ObdVehicleDataSource.kt` — capability discovery, polling groups, diagnostics scans
- `VehicleSourceSelector.kt` — selection policy

**Data:** `vehicle/data/`
- `VehicleRepository.kt` — active source, dedup, staleness, disconnect clearing
- `VehicleLog.kt` — rate-limited lifecycle logging

**App wiring:** `CarDashboardApplication.kt`, `di/VehicleContainer.kt`

**UI:** `ui/dashboard/`
- `DashboardUiState.kt`, `VehicleStateFormatter.kt`, `DashboardViewModel.kt`
- `DashboardScreen.kt` *(modified — data removed, structure preserved)*
- `VehicleHealthPanel.kt`, `ConnectionBanner.kt`
- `ui/diagnostics/DiagnosticDetailScreen.kt`

**Tests:** `src/test/java/.../vehicle/**`, `src/androidTest/java/.../ui/**`, plus `src/test/.../fakes/FakeVehicleTransport.kt`

---

## Task Dependency Order

```
0  build & test infrastructure
1  Signal + enums          ─┐
2  VehicleState             ├─ domain
3  Diagnostics model        │
4  DtcDecoder + severity    │
5  Connection state        ─┘
6  VehicleDataSource + MockVehicleDataSource
7  VehicleRepository (dedup, disconnect clearing)
8  Staleness + VehicleSourceSelector
9  DashboardUiState + Formatter + ViewModel
10 Dashboard integration (three-state warnings, "Not reported")
11 Vehicle Health panel + banners
12 Diagnostic detail screen + nav route
13 Compose UI tests (8 states, rotation, landscape)
── working, tested dashboard on mock data ──
14 VehicleTransport abstraction + FakeVehicleTransport
15 ObdCommand + ObdPid decoders + SupportedPidSet
16 Elm327Session
17 ObdVehicleDataSource (capability discovery authoritative)
── protocol fully tested without hardware ──
18 GATED: UsbSerialTransport — blocked on adapter identification
19 Developer documentation
```

---

### Task 0: Build and test infrastructure

The project currently has **zero test dependencies** — `testInstrumentationRunner` is declared but nothing can run. This task makes TDD possible. No `usb-serial-for-android` and no JitPack repository yet; those arrive at Task 18 so the hardware dependency stays explicit and late.

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/test/java/com/csjotlab/cardashboard/InfrastructureSmokeTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: a runnable `testDebugUnitTest` task; `runTest` from `kotlinx-coroutines-test`; `turbine`'s `Flow<T>.test {}`; Compose test rules for Task 13.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.csjotlab.cardashboard

import app.cash.turbine.test
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class InfrastructureSmokeTest {
    @Test
    fun `coroutines test and turbine are available`() = runTest {
        flowOf(1, 2).test {
            assertEquals(1, awaitItem())
            assertEquals(2, awaitItem())
            awaitComplete()
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*InfrastructureSmokeTest*'`
Expected: FAIL — compilation error, `Unresolved reference: turbine` / `runTest`.

- [ ] **Step 3: Add the dependencies**

In `app/build.gradle.kts`, add inside `android { }`:

```kotlin
    testOptions {
        unitTests {
            isReturnDefaultValues = false
        }
    }
```

Leave `isReturnDefaultValues = false` (the default) deliberately: it is what makes an accidental Android import in `domain`/`protocol` fail loudly with `Method ... not mocked`, per spec §3.

Add to `dependencies { }`:

```kotlin
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*InfrastructureSmokeTest*'`
Expected: PASS.

- [ ] **Step 5: Verify the app still builds**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. **Do not commit.**

---

### Task 1: `Signal` and vehicle enums

The type that makes "unknown stays unknown" structural rather than a convention.

**Files:**
- Create: `app/src/main/java/com/csjotlab/cardashboard/vehicle/domain/Signal.kt`
- Create: `app/src/main/java/com/csjotlab/cardashboard/vehicle/domain/VehicleEnums.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/vehicle/domain/SignalTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `sealed interface Signal<out T>` with `Signal.Value<T>(value: T, timestampMs: Long)`, `Signal.Unknown`, `Signal.Unsupported`
  - `fun <T> Signal<T>.valueOrNull(): T?`
  - `val Signal<*>.isValue: Boolean`
  - `enum class VehicleSourceId { NONE, MOCK, OBD_USB }`
  - `sealed interface Gear` with `Park`, `Reverse`, `Neutral`, `Drive`, `Low`, `Manual(position: Int)`, `Unknown`
  - `enum class DoorPosition { FrontLeft, FrontRight, RearLeft, RearRight, Hood, Trunk }`
  - `enum class DoorState { Open, Closed }`
  - `enum class SeatPosition { Driver, FrontPassenger, RearLeft, RearCenter, RearRight }`
  - `enum class SeatbeltState { Buckled, Unbuckled }`
  - `enum class TirePosition { FrontLeft, FrontRight, RearLeft, RearRight }`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.csjotlab.cardashboard.vehicle.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalTest {

    @Test
    fun `value exposes its payload and timestamp`() {
        val signal: Signal<Int> = Signal.Value(42, timestampMs = 1_000L)
        assertEquals(42, signal.valueOrNull())
        assertTrue(signal.isValue)
        assertEquals(1_000L, (signal as Signal.Value).timestampMs)
    }

    @Test
    fun `unknown has no value`() {
        val signal: Signal<Int> = Signal.Unknown
        assertNull(signal.valueOrNull())
        assertFalse(signal.isValue)
    }

    @Test
    fun `unsupported has no value`() {
        val signal: Signal<Int> = Signal.Unsupported
        assertNull(signal.valueOrNull())
        assertFalse(signal.isValue)
    }

    @Test
    fun `unknown and unsupported are distinct states`() {
        assertFalse(Signal.Unknown == Signal.Unsupported)
    }

    @Test
    fun `manual gear carries its position`() {
        assertEquals(3, (Gear.Manual(3) as Gear.Manual).position)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*SignalTest*'`
Expected: FAIL — `Unresolved reference: Signal`.

- [ ] **Step 3: Write the implementation**

`Signal.kt`:

```kotlin
package com.csjotlab.cardashboard.vehicle.domain

/**
 * A single vehicle measurement, or the reason there isn't one.
 *
 * There is deliberately no numeric default anywhere in the vehicle domain: a field can only hold a
 * number because a source actually reported one. See spec section 2.1.
 */
sealed interface Signal<out T> {

    /** A reading that genuinely came from the active source. */
    data class Value<T>(val value: T, val timestampMs: Long) : Signal<T>

    /** The source may be able to provide this, but no reading has arrived yet. */
    data object Unknown : Signal<Nothing>

    /**
     * The *currently selected source* has determined it cannot provide this field.
     * This is not a claim about the vehicle: a future OEM/enhanced source may supply it.
     */
    data object Unsupported : Signal<Nothing>
}

fun <T> Signal<T>.valueOrNull(): T? = when (this) {
    is Signal.Value -> value
    Signal.Unknown, Signal.Unsupported -> null
}

val Signal<*>.isValue: Boolean get() = this is Signal.Value
```

`VehicleEnums.kt`:

```kotlin
package com.csjotlab.cardashboard.vehicle.domain

enum class VehicleSourceId { NONE, MOCK, OBD_USB }

sealed interface Gear {
    data object Park : Gear
    data object Reverse : Gear
    data object Neutral : Gear
    data object Drive : Gear
    data object Low : Gear
    data class Manual(val position: Int) : Gear
    data object Unknown : Gear
}

enum class DoorPosition { FrontLeft, FrontRight, RearLeft, RearRight, Hood, Trunk }

enum class DoorState { Open, Closed }

enum class SeatPosition { Driver, FrontPassenger, RearLeft, RearCenter, RearRight }

enum class SeatbeltState { Buckled, Unbuckled }

enum class TirePosition { FrontLeft, FrontRight, RearLeft, RearRight }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*SignalTest*'`
Expected: PASS (5 tests). **Do not commit.**

---

### Task 2: `VehicleState`

**Files:**
- Create: `app/src/main/java/com/csjotlab/cardashboard/vehicle/domain/VehicleState.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/vehicle/domain/VehicleStateTest.kt`

**Interfaces:**
- Consumes: `Signal`, `Gear`, `DoorPosition`, `DoorState`, `SeatPosition`, `SeatbeltState`, `TirePosition`, `VehicleSourceId` (Task 1)
- Produces: `data class VehicleState(...)` with fields `speedKph: Signal<Float>`, `engineRpm: Signal<Int>`, `fuelLevelPercent: Signal<Float>`, `coolantTemperatureCelsius: Signal<Int>`, `gear: Signal<Gear>`, `odometerKm: Signal<Double>`, `tripDistanceKm: Signal<Double>`, `estimatedRangeKm: Signal<Double>`, `seatbelts: Signal<Map<SeatPosition, SeatbeltState>>`, `doors: Signal<Map<DoorPosition, DoorState>>`, `tirePressuresKpa: Signal<Map<TirePosition, Float>>`, `malfunctionIndicatorLampOn: Signal<Boolean>`, `source: VehicleSourceId`, `lastUpdatedMs: Long?`; plus `VehicleState.unavailable(source: VehicleSourceId): VehicleState` and `val VehicleState.allSignals: List<Signal<*>>`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.csjotlab.cardashboard.vehicle.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleStateTest {

    @Test
    fun `unavailable state holds no values at all`() {
        val state = VehicleState.unavailable(VehicleSourceId.NONE)

        assertTrue(
            "unavailable() must not fabricate any reading",
            state.allSignals.none { it.isValue }
        )
        assertNull(state.lastUpdatedMs)
        assertEquals(VehicleSourceId.NONE, state.source)
    }

    @Test
    fun `unavailable state uses Unknown rather than Unsupported`() {
        val state = VehicleState.unavailable(VehicleSourceId.NONE)

        // Nothing has been discovered yet, so nothing may claim to be unsupported.
        assertTrue(state.allSignals.all { it == Signal.Unknown })
    }

    @Test
    fun `allSignals covers every telemetry field`() {
        assertEquals(12, VehicleState.unavailable(VehicleSourceId.NONE).allSignals.size)
    }

    @Test
    fun `a populated field keeps its value and timestamp`() {
        val state = VehicleState.unavailable(VehicleSourceId.OBD_USB)
            .copy(speedKph = Signal.Value(60f, 5_000L), lastUpdatedMs = 5_000L)

        assertEquals(60f, state.speedKph.valueOrNull())
        assertEquals(5_000L, state.lastUpdatedMs)
        assertEquals(11, state.allSignals.count { it == Signal.Unknown })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*VehicleStateTest*'`
Expected: FAIL — `Unresolved reference: VehicleState`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.csjotlab.cardashboard.vehicle.domain

/**
 * A complete snapshot of what the active source currently knows about the vehicle.
 *
 * Every field is a [Signal]; none has a numeric default. Construct via [unavailable] and `copy`
 * the fields a source genuinely reported.
 */
data class VehicleState(
    val speedKph: Signal<Float>,
    val engineRpm: Signal<Int>,
    val fuelLevelPercent: Signal<Float>,
    val coolantTemperatureCelsius: Signal<Int>,
    val gear: Signal<Gear>,
    val odometerKm: Signal<Double>,
    val tripDistanceKm: Signal<Double>,
    val estimatedRangeKm: Signal<Double>,
    val seatbelts: Signal<Map<SeatPosition, SeatbeltState>>,
    val doors: Signal<Map<DoorPosition, DoorState>>,
    val tirePressuresKpa: Signal<Map<TirePosition, Float>>,
    val malfunctionIndicatorLampOn: Signal<Boolean>,
    val source: VehicleSourceId,
    val lastUpdatedMs: Long?,
) {
    companion object {
        /** Nothing is known yet. Used on startup, on disconnect, and on discovery failure. */
        fun unavailable(source: VehicleSourceId): VehicleState = VehicleState(
            speedKph = Signal.Unknown,
            engineRpm = Signal.Unknown,
            fuelLevelPercent = Signal.Unknown,
            coolantTemperatureCelsius = Signal.Unknown,
            gear = Signal.Unknown,
            odometerKm = Signal.Unknown,
            tripDistanceKm = Signal.Unknown,
            estimatedRangeKm = Signal.Unknown,
            seatbelts = Signal.Unknown,
            doors = Signal.Unknown,
            tirePressuresKpa = Signal.Unknown,
            malfunctionIndicatorLampOn = Signal.Unknown,
            source = source,
            lastUpdatedMs = null,
        )
    }
}

/** Every telemetry signal, for invariant checks such as "no value survived a disconnect". */
val VehicleState.allSignals: List<Signal<*>>
    get() = listOf(
        speedKph, engineRpm, fuelLevelPercent, coolantTemperatureCelsius,
        gear, odometerKm, tripDistanceKm, estimatedRangeKm,
        seatbelts, doors, tirePressuresKpa, malfunctionIndicatorLampOn,
    )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*VehicleStateTest*'`
Expected: PASS (4 tests). **Do not commit.**

---

### Task 3: Diagnostics domain model

`title` and `description` are **nullable and stay null** in this version — no trusted DTC text mapping ships. The UI renders the code itself.

**Files:**
- Create: `app/src/main/java/com/csjotlab/cardashboard/vehicle/domain/Diagnostics.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/vehicle/domain/DiagnosticsTest.kt`

**Interfaces:**
- Consumes: `Signal` (Task 1)
- Produces:
  - `enum class DtcSystem { Powertrain, Chassis, Body, Network }`
  - `data class DtcClassification(val system: DtcSystem, val manufacturerSpecific: Boolean, val subsystem: String?)`
  - `sealed interface DiagnosticCode` with `Dtc(val code: String)` and `SignalDerived(val kind: SignalIssueKind, val detail: String)`
  - `enum class SignalIssueKind { DoorOpen, SeatbeltUnbuckled, TirePressureLow, MalfunctionIndicatorLamp }`
  - `enum class DiagnosticStatus { Stored, Pending, Permanent, LiveSignal }`
  - `enum class Severity { Info, Warning, Critical }`
  - `enum class DiagnosticSource { Obd2, VehicleSignal, Mock }`
  - `data class DiagnosticIssue(id, code, title, description, classification, severity, status, source, firstSeenMs, lastSeenMs)`
  - `data class VehicleDiagnosticsState(issues, malfunctionIndicatorLampOn, storedDtcCount, lastScanMs)` + `VehicleDiagnosticsState.empty()`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.csjotlab.cardashboard.vehicle.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {

    @Test
    fun `a dtc issue carries no invented description`() {
        val issue = DiagnosticIssue(
            id = "dtc:P0301",
            code = DiagnosticCode.Dtc("P0301"),
            title = null,
            description = null,
            classification = DtcClassification(DtcSystem.Powertrain, false, "Ignition system or misfire"),
            severity = Severity.Warning,
            status = DiagnosticStatus.Stored,
            source = DiagnosticSource.Obd2,
            firstSeenMs = 1_000L,
            lastSeenMs = 1_000L,
        )

        assertNull("no trusted mapping ships, so title must stay null", issue.title)
        assertNull("no trusted mapping ships, so description must stay null", issue.description)
        assertEquals("P0301", (issue.code as DiagnosticCode.Dtc).code)
    }

    @Test
    fun `empty diagnostics state reports nothing and claims nothing`() {
        val state = VehicleDiagnosticsState.empty()

        assertTrue(state.issues.isEmpty())
        assertEquals(Signal.Unknown, state.malfunctionIndicatorLampOn)
        assertEquals(Signal.Unknown, state.storedDtcCount)
        assertNull(state.lastScanMs)
    }

    @Test
    fun `a signal derived issue records which signal produced it`() {
        val issue = DiagnosticIssue(
            id = "signal:DoorOpen:RearLeft",
            code = DiagnosticCode.SignalDerived(SignalIssueKind.DoorOpen, "RearLeft"),
            title = null,
            description = null,
            classification = null,
            severity = Severity.Warning,
            status = DiagnosticStatus.LiveSignal,
            source = DiagnosticSource.VehicleSignal,
            firstSeenMs = 2_000L,
            lastSeenMs = 2_500L,
        )

        assertEquals(DiagnosticStatus.LiveSignal, issue.status)
        assertEquals(SignalIssueKind.DoorOpen, (issue.code as DiagnosticCode.SignalDerived).kind)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*DiagnosticsTest*'`
Expected: FAIL — `Unresolved reference: DiagnosticIssue`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.csjotlab.cardashboard.vehicle.domain

enum class DtcSystem { Powertrain, Chassis, Body, Network }

/**
 * What the SAE J2012 *code format* itself states. This is structural decoding, never a diagnosis:
 * it says which system the code belongs to, not which component failed.
 */
data class DtcClassification(
    val system: DtcSystem,
    val manufacturerSpecific: Boolean,
    val subsystem: String?,
)

enum class SignalIssueKind { DoorOpen, SeatbeltUnbuckled, TirePressureLow, MalfunctionIndicatorLamp }

sealed interface DiagnosticCode {
    /** A diagnostic trouble code exactly as reported, e.g. "P0301". */
    data class Dtc(val code: String) : DiagnosticCode

    /** An issue observed from a live vehicle signal rather than a stored code. */
    data class SignalDerived(val kind: SignalIssueKind, val detail: String) : DiagnosticCode
}

enum class DiagnosticStatus { Stored, Pending, Permanent, LiveSignal }

enum class Severity { Info, Warning, Critical }

enum class DiagnosticSource { Obd2, VehicleSignal, Mock }

data class DiagnosticIssue(
    val id: String,
    val code: DiagnosticCode,
    /** Null unless a trusted mapping supplied it. No such mapping ships in this version. */
    val title: String?,
    /** Null unless a trusted mapping supplied it. No such mapping ships in this version. */
    val description: String?,
    val classification: DtcClassification?,
    val severity: Severity,
    val status: DiagnosticStatus,
    val source: DiagnosticSource,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
)

data class VehicleDiagnosticsState(
    val issues: List<DiagnosticIssue>,
    val malfunctionIndicatorLampOn: Signal<Boolean>,
    val storedDtcCount: Signal<Int>,
    val lastScanMs: Long?,
) {
    companion object {
        fun empty(): VehicleDiagnosticsState = VehicleDiagnosticsState(
            issues = emptyList(),
            malfunctionIndicatorLampOn = Signal.Unknown,
            storedDtcCount = Signal.Unknown,
            lastScanMs = null,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*DiagnosticsTest*'`
Expected: PASS (3 tests). **Do not commit.**

---

### Task 4: `DtcDecoder` — structural decoding and severity rules

Two responsibilities: turn a raw two-byte DTC pair into a code string, and classify a code string using only the J2012 format. Plus the ordered severity rules from spec §4.3.

**Files:**
- Create: `app/src/main/java/com/csjotlab/cardashboard/vehicle/protocol/DtcDecoder.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/vehicle/protocol/DtcDecoderTest.kt`

**Interfaces:**
- Consumes: `DtcClassification`, `DtcSystem`, `DiagnosticStatus`, `Severity` (Task 3)
- Produces:
  - `DtcDecoder.decodePair(byteA: Int, byteB: Int): String?` — null for the `0x0000` padding pair
  - `DtcDecoder.classify(code: String): DtcClassification?` — null for malformed codes
  - `DtcDecoder.severityFor(status: DiagnosticStatus, milOn: Boolean): Severity`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.csjotlab.cardashboard.vehicle.protocol

import com.csjotlab.cardashboard.vehicle.domain.DiagnosticStatus
import com.csjotlab.cardashboard.vehicle.domain.DtcSystem
import com.csjotlab.cardashboard.vehicle.domain.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcDecoderTest {

    // --- decodePair -------------------------------------------------------

    @Test
    fun `decodes a powertrain misfire code`() {
        // 0x03 0x01 -> P0301
        assertEquals("P0301", DtcDecoder.decodePair(0x03, 0x01))
    }

    @Test
    fun `decodes each system letter from the top two bits`() {
        assertEquals("P0100", DtcDecoder.decodePair(0x01, 0x00))
        assertEquals("C0100", DtcDecoder.decodePair(0x41, 0x00))
        assertEquals("B0100", DtcDecoder.decodePair(0x81, 0x00))
        assertEquals("U0100", DtcDecoder.decodePair(0xC1, 0x00))
    }

    @Test
    fun `decodes hex digits above nine`() {
        assertEquals("P1ABC", DtcDecoder.decodePair(0x1A, 0xBC))
    }

    @Test
    fun `the all zero pair is padding not a code`() {
        assertNull(DtcDecoder.decodePair(0x00, 0x00))
    }

    // --- classify ---------------------------------------------------------

    @Test
    fun `classifies a generic powertrain misfire code`() {
        val classification = DtcDecoder.classify("P0301")!!

        assertEquals(DtcSystem.Powertrain, classification.system)
        assertFalse(classification.manufacturerSpecific)
        assertEquals("Ignition system or misfire", classification.subsystem)
    }

    @Test
    fun `second character one marks a manufacturer specific code`() {
        assertTrue(DtcDecoder.classify("P1301")!!.manufacturerSpecific)
        assertTrue(DtcDecoder.classify("P3301")!!.manufacturerSpecific)
        assertFalse(DtcDecoder.classify("P2301")!!.manufacturerSpecific)
    }

    @Test
    fun `non powertrain codes get no subsystem`() {
        assertNull(DtcDecoder.classify("B0100")!!.subsystem)
    }

    @Test
    fun `malformed codes classify as null rather than guessing`() {
        assertNull(DtcDecoder.classify(""))
        assertNull(DtcDecoder.classify("P030"))
        assertNull(DtcDecoder.classify("X0301"))
        assertNull(DtcDecoder.classify("P9301"))   // second char must be 0..3
        assertNull(DtcDecoder.classify("P03G1"))   // not hex
    }

    // --- severityFor ------------------------------------------------------

    @Test
    fun `permanent codes are critical`() {
        assertEquals(Severity.Critical, DtcDecoder.severityFor(DiagnosticStatus.Permanent, milOn = false))
    }

    @Test
    fun `an illuminated MIL is critical whatever the status`() {
        assertEquals(Severity.Critical, DtcDecoder.severityFor(DiagnosticStatus.Stored, milOn = true))
        assertEquals(Severity.Critical, DtcDecoder.severityFor(DiagnosticStatus.Pending, milOn = true))
    }

    @Test
    fun `stored codes with the MIL off are warnings`() {
        assertEquals(Severity.Warning, DtcDecoder.severityFor(DiagnosticStatus.Stored, milOn = false))
    }

    @Test
    fun `pending codes with the MIL off are informational`() {
        assertEquals(Severity.Info, DtcDecoder.severityFor(DiagnosticStatus.Pending, milOn = false))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*DtcDecoderTest*'`
Expected: FAIL — `Unresolved reference: DtcDecoder`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.csjotlab.cardashboard.vehicle.protocol

import com.csjotlab.cardashboard.vehicle.domain.DiagnosticStatus
import com.csjotlab.cardashboard.vehicle.domain.DtcClassification
import com.csjotlab.cardashboard.vehicle.domain.DtcSystem
import com.csjotlab.cardashboard.vehicle.domain.Severity

/**
 * Decodes diagnostic trouble codes using only what SAE J2012 encodes in the code format itself.
 *
 * This deliberately never names a failed component. "P0301" yields "Powertrain / Ignition system or
 * misfire" because those facts are carried by the characters, not because we concluded anything
 * about the vehicle. See spec section 2.1 rule 2.
 */
object DtcDecoder {

    /**
     * Decodes one two-byte DTC pair from an OBD-II mode 03/07/0A response.
     * Returns null for the 0x0000 padding pair, which means "no code in this slot".
     */
    fun decodePair(byteA: Int, byteB: Int): String? {
        val a = byteA and 0xFF
        val b = byteB and 0xFF
        if (a == 0 && b == 0) return null

        val letter = when ((a shr 6) and 0x03) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            else -> 'U'
        }
        val second = (a shr 4) and 0x03
        val third = a and 0x0F
        val fourth = (b shr 4) and 0x0F
        val fifth = b and 0x0F

        return buildString {
            append(letter)
            append(second)
            append(third.toString(16).uppercase())
            append(fourth.toString(16).uppercase())
            append(fifth.toString(16).uppercase())
        }
    }

    /** Returns null for anything that is not a well-formed code, rather than guessing. */
    fun classify(code: String): DtcClassification? {
        if (code.length != 5) return null

        val system = when (code[0].uppercaseChar()) {
            'P' -> DtcSystem.Powertrain
            'C' -> DtcSystem.Chassis
            'B' -> DtcSystem.Body
            'U' -> DtcSystem.Network
            else -> return null
        }

        val second = code[1]
        if (second !in '0'..'3') return null
        if (code.drop(2).any { it.digitToIntOrNull(16) == null }) return null

        return DtcClassification(
            system = system,
            // 0 and 2 are SAE-generic; 1 and 3 are manufacturer-specific.
            manufacturerSpecific = second == '1' || second == '3',
            subsystem = if (system == DtcSystem.Powertrain) powertrainSubsystem(code[2]) else null,
        )
    }

    /** The third character of a P code names a subsystem group in J2012. */
    private fun powertrainSubsystem(third: Char): String? = when (third.uppercaseChar()) {
        '1', '2' -> "Fuel and air metering"
        '3' -> "Ignition system or misfire"
        '4' -> "Auxiliary emission controls"
        '5' -> "Vehicle speed control and idle control system"
        '6' -> "Computer output circuits"
        '7', '8' -> "Transmission"
        else -> null
    }

    /** Spec section 4.3. Rules are evaluated in order; the first match wins. */
    fun severityFor(status: DiagnosticStatus, milOn: Boolean): Severity = when {
        status == DiagnosticStatus.Permanent || milOn -> Severity.Critical
        status == DiagnosticStatus.Stored -> Severity.Warning
        else -> Severity.Info
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*DtcDecoderTest*'`
Expected: PASS (12 tests). **Do not commit.**

---

### Task 5: Connection state model

**Files:**
- Create: `app/src/main/java/com/csjotlab/cardashboard/vehicle/domain/VehicleConnectionState.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/vehicle/domain/VehicleConnectionStateTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `data class UsbDeviceDescriptor(val deviceName: String, val vendorId: Int, val productId: Int, val chipset: String?)`
  - `data class AdapterIdentity(val rawIdentity: String, val elmCompatible: Boolean)`
  - `sealed interface VehicleConnectionState` with `Disconnected`, `DeviceDetected(device)`, `PermissionRequired(device)`, `PermissionDenied(device)`, `Connecting`, `Connected(adapter, protocol: String?)`, `Reading`, `UnsupportedDevice(device, reason)`, `VehicleCommunicationUnavailable(reason)`, `ConnectionLost(reason)`, `Error(reason)`
  - `val VehicleConnectionState.isLiveData: Boolean` — true only for `Reading`
  - `val VehicleConnectionState.isTerminalFailure: Boolean` — true for `PermissionDenied`, `UnsupportedDevice`, `Error`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.csjotlab.cardashboard.vehicle.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleConnectionStateTest {

    private val device = UsbDeviceDescriptor("/dev/bus/usb/001/002", vendorId = 0x0403, productId = 0x6001, chipset = "FTDI")

    @Test
    fun `only Reading counts as live data`() {
        assertTrue(VehicleConnectionState.Reading.isLiveData)
        assertFalse(VehicleConnectionState.Connected(AdapterIdentity("ELM327 v1.5", true), "ISO 15765-4").isLiveData)
        assertFalse(VehicleConnectionState.Disconnected.isLiveData)
        assertFalse(VehicleConnectionState.Connecting.isLiveData)
    }

    @Test
    fun `terminal failures are distinguished from recoverable ones`() {
        assertTrue(VehicleConnectionState.PermissionDenied(device).isTerminalFailure)
        assertTrue(VehicleConnectionState.UnsupportedDevice(device, "no driver").isTerminalFailure)
        assertTrue(VehicleConnectionState.Error("boom").isTerminalFailure)

        assertFalse(VehicleConnectionState.ConnectionLost("cable removed").isTerminalFailure)
        assertFalse(VehicleConnectionState.VehicleCommunicationUnavailable("UNABLE TO CONNECT").isTerminalFailure)
        assertFalse(VehicleConnectionState.Disconnected.isTerminalFailure)
    }

    @Test
    fun `states carry the context needed to explain themselves`() {
        val unsupported = VehicleConnectionState.UnsupportedDevice(device, "no USB-serial driver for chipset")
        assertEquals(0x0403, unsupported.device.vendorId)
        assertEquals("no USB-serial driver for chipset", unsupported.reason)

        val connected = VehicleConnectionState.Connected(AdapterIdentity("ELM327 v1.5", true), "ISO 15765-4 CAN")
        assertTrue(connected.adapter.elmCompatible)
        assertEquals("ISO 15765-4 CAN", connected.protocol)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*VehicleConnectionStateTest*'`
Expected: FAIL — `Unresolved reference: VehicleConnectionState`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.csjotlab.cardashboard.vehicle.domain

/** Identifying details of an attached USB device. Serial numbers are deliberately not carried. */
data class UsbDeviceDescriptor(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val chipset: String?,
)

data class AdapterIdentity(
    val rawIdentity: String,
    val elmCompatible: Boolean,
)

sealed interface VehicleConnectionState {
    data object Disconnected : VehicleConnectionState
    data class DeviceDetected(val device: UsbDeviceDescriptor) : VehicleConnectionState
    data class PermissionRequired(val device: UsbDeviceDescriptor) : VehicleConnectionState
    data class PermissionDenied(val device: UsbDeviceDescriptor) : VehicleConnectionState
    data object Connecting : VehicleConnectionState
    data class Connected(val adapter: AdapterIdentity, val protocol: String?) : VehicleConnectionState
    data object Reading : VehicleConnectionState

    /** Attached, but we have no driver for it or it is not an ELM327-compatible adapter. */
    data class UnsupportedDevice(val device: UsbDeviceDescriptor, val reason: String) : VehicleConnectionState

    /** The adapter answers, but cannot reach the vehicle bus. */
    data class VehicleCommunicationUnavailable(val reason: String) : VehicleConnectionState

    data class ConnectionLost(val reason: String) : VehicleConnectionState
    data class Error(val reason: String) : VehicleConnectionState
}

/** True only while telemetry is genuinely flowing. */
val VehicleConnectionState.isLiveData: Boolean
    get() = this is VehicleConnectionState.Reading

/** Failures that retrying by itself will not fix. */
val VehicleConnectionState.isTerminalFailure: Boolean
    get() = this is VehicleConnectionState.PermissionDenied ||
        this is VehicleConnectionState.UnsupportedDevice ||
        this is VehicleConnectionState.Error
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*VehicleConnectionStateTest*'`
Expected: PASS (3 tests).

- [ ] **Step 5: Run the whole domain suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS — all tests from Tasks 0–5. **Do not commit.**

---

### Task 6: `VehicleDataSource` abstraction and `MockVehicleDataSource`

The mock source is a **domain-level** scripted timeline. It is the only way to exercise signals that no OBD-II adapter provides (doors, tyres, seatbelts), and it is what makes the dashboard developable without a car. It lives in `src/main` so tests, instrumented tests and previews can all use it; only its *selection* is debug-gated (Task 8).

`Clock` is introduced here so no test depends on wall time.

**Files:**
- Create: `app/src/main/java/com/csjotlab/cardashboard/vehicle/source/VehicleDataSource.kt`
- Create: `app/src/main/java/com/csjotlab/cardashboard/vehicle/source/MockVehicleDataSource.kt`
- Create: `app/src/main/java/com/csjotlab/cardashboard/vehicle/domain/Clock.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/vehicle/source/MockVehicleDataSourceTest.kt`

**Interfaces:**
- Consumes: `VehicleState`, `VehicleDiagnosticsState`, `VehicleConnectionState`, `Signal`, `Gear`, position enums, `VehicleSourceId` (Tasks 1–5)
- Produces:
  - `fun interface Clock { fun nowMs(): Long }` plus `object SystemClock : Clock`
  - ```kotlin
    interface VehicleDataSource {
        val id: VehicleSourceId
        val vehicleState: Flow<VehicleState>
        val diagnostics: Flow<VehicleDiagnosticsState>
        val connectionState: Flow<VehicleConnectionState>
        suspend fun start()
        suspend fun stop()
    }
    ```
  - `class MockVehicleDataSource(clock: Clock, script: List<MockStep> = MockVehicleDataSource.DEMO_SCRIPT)`
  - `data class MockStep(val delayMs: Long, val apply: (MockFrame) -> MockFrame)` and `data class MockFrame(val state: VehicleState, val diagnostics: VehicleDiagnosticsState, val connection: VehicleConnectionState)`
  - `MockVehicleDataSource.DEMO_SCRIPT` — the spec's scenario: connect → 60 km/h / 2100 rpm / 62 % / 91 °C / gear D → rear-left door open → front-right tyre low → engine DTC → cable removed

- [ ] **Step 1: Write the failing test**

```kotlin
package com.csjotlab.cardashboard.vehicle.source

import app.cash.turbine.test
import com.csjotlab.cardashboard.vehicle.domain.Clock
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticCode
import com.csjotlab.cardashboard.vehicle.domain.DoorPosition
import com.csjotlab.cardashboard.vehicle.domain.DoorState
import com.csjotlab.cardashboard.vehicle.domain.Gear
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.TirePosition
import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.allSignals
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockVehicleDataSourceTest {

    private val clock = Clock { 10_000L }

    @Test
    fun `identifies itself as the mock source`() {
        assertEquals(VehicleSourceId.MOCK, MockVehicleDataSource(clock).id)
    }

    @Test
    fun `demo script reaches the specified telemetry values`() = runTest {
        val source = MockVehicleDataSource(clock)
        source.vehicleState.test {
            // Initial frame: nothing known yet.
            assertTrue(awaitItem().allSignals.none { it.isValue })

            var latest = awaitItem()
            while (latest.speedKph.valueOrNull() != 60f) latest = awaitItem()

            assertEquals(60f, latest.speedKph.valueOrNull())
            assertEquals(2100, latest.engineRpm.valueOrNull())
            assertEquals(62f, latest.fuelLevelPercent.valueOrNull())
            assertEquals(91, latest.coolantTemperatureCelsius.valueOrNull())
            assertEquals(Gear.Drive, latest.gear.valueOrNull())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `demo script opens the rear left door then lowers the front right tyre`() = runTest {
        val source = MockVehicleDataSource(clock)
        source.vehicleState.test {
            var sawOpenDoor = false
            var sawLowTyre = false
            repeat(40) {
                val state = awaitItem()
                if (state.doors.valueOrNull()?.get(DoorPosition.RearLeft) == DoorState.Open) sawOpenDoor = true
                val frontRight = state.tirePressuresKpa.valueOrNull()?.get(TirePosition.FrontRight)
                if (frontRight != null && frontRight < 180f) sawLowTyre = true
                if (sawOpenDoor && sawLowTyre) return@repeat
            }
            assertTrue("rear-left door should open in the demo script", sawOpenDoor)
            assertTrue("front-right tyre should go low in the demo script", sawLowTyre)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `demo script raises an engine diagnostic code`() = runTest {
        val source = MockVehicleDataSource(clock)
        source.diagnostics.test {
            var codes = emptyList<String>()
            repeat(40) {
                val issues = awaitItem().issues
                codes = issues.mapNotNull { (it.code as? DiagnosticCode.Dtc)?.code }
                if (codes.isNotEmpty()) return@repeat
            }
            assertTrue("demo script should report a DTC, got $codes", codes.isNotEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `demo script ends disconnected with every value cleared`() = runTest {
        val source = MockVehicleDataSource(clock)
        var lastState = com.csjotlab.cardashboard.vehicle.domain.VehicleState.unavailable(VehicleSourceId.MOCK)
        var lastConnection: VehicleConnectionState = VehicleConnectionState.Disconnected

        source.connectionState.test {
            repeat(60) {
                lastConnection = awaitItem()
                if (lastConnection is VehicleConnectionState.ConnectionLost) return@repeat
            }
            cancelAndIgnoreRemainingEvents()
        }
        source.vehicleState.test {
            repeat(60) { lastState = awaitItem() }
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(lastConnection is VehicleConnectionState.ConnectionLost)
        assertTrue(
            "no telemetry may survive the simulated cable removal",
            lastState.allSignals.none { it.isValue }
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*MockVehicleDataSourceTest*'`
Expected: FAIL — `Unresolved reference: MockVehicleDataSource`.

- [ ] **Step 3: Write `Clock.kt` and `VehicleDataSource.kt`**

```kotlin
package com.csjotlab.cardashboard.vehicle.domain

/** Injected so no test depends on wall-clock time. */
fun interface Clock {
    fun nowMs(): Long
}

object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
```

```kotlin
package com.csjotlab.cardashboard.vehicle.source

import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleDiagnosticsState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import kotlinx.coroutines.flow.Flow

/**
 * One vehicle-data provider. The dashboard never knows which implementation is active.
 *
 * Implementations must honour spec section 2.1: a field may only carry a value that the source
 * genuinely obtained for that field.
 */
interface VehicleDataSource {
    val id: VehicleSourceId
    val vehicleState: Flow<VehicleState>
    val diagnostics: Flow<VehicleDiagnosticsState>
    val connectionState: Flow<VehicleConnectionState>

    suspend fun start()
    suspend fun stop()
}
```

- [ ] **Step 4: Write `MockVehicleDataSource.kt`**

```kotlin
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
import com.csjotlab.cardashboard.vehicle.domain.Severity
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.TirePosition
import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleDiagnosticsState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import com.csjotlab.cardashboard.vehicle.protocol.DtcDecoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

data class MockFrame(
    val state: VehicleState,
    val diagnostics: VehicleDiagnosticsState,
    val connection: VehicleConnectionState,
)

data class MockStep(val delayMs: Long, val apply: (MockFrame) -> MockFrame)

/**
 * A scripted, domain-level source. It is the only way to exercise signals no OBD-II adapter
 * provides (doors, tyres, seatbelts), and it drives UI development without a vehicle.
 *
 * It is never selected automatically — see VehicleSourceSelector.
 */
class MockVehicleDataSource(
    private val clock: Clock,
    private val script: List<MockStep> = DEMO_SCRIPT,
) : VehicleDataSource {

    override val id: VehicleSourceId = VehicleSourceId.MOCK

    private val frames = MutableStateFlow(initialFrame())

    override val vehicleState: Flow<VehicleState> = timeline().map { it.state }
    override val diagnostics: Flow<VehicleDiagnosticsState> = timeline().map { it.diagnostics }
    override val connectionState: Flow<VehicleConnectionState> = timeline().map { it.connection }

    override suspend fun start() = Unit
    override suspend fun stop() {
        frames.value = initialFrame()
    }

    private fun timeline(): Flow<MockFrame> = flow {
        var frame = initialFrame()
        emit(frame)
        for (step in script) {
            delay(step.delayMs)
            frame = step.apply(frame)
            emit(frame)
        }
    }

    private fun initialFrame() = MockFrame(
        state = VehicleState.unavailable(VehicleSourceId.MOCK),
        diagnostics = VehicleDiagnosticsState.empty(),
        connection = VehicleConnectionState.Disconnected,
    )

    private fun now() = clock.nowMs()

    companion object {
        /** The scenario named in the spec, in order. */
        val DEMO_SCRIPT: List<MockStep> = listOf(
            MockStep(400) { it.copy(connection = VehicleConnectionState.Connecting) },
            MockStep(600) {
                it.copy(
                    connection = VehicleConnectionState.Connected(
                        AdapterIdentity("MOCK SOURCE", elmCompatible = false),
                        protocol = "Simulated",
                    ),
                )
            },
            MockStep(400) { frame -> frame.copy(connection = VehicleConnectionState.Reading) },
            MockStep(600) { frame -> frame.copy(state = drivingState(frame.state)) },
            MockStep(1_500) { frame -> frame.copy(state = withRearLeftDoorOpen(frame.state)) },
            MockStep(1_500) { frame -> frame.copy(state = withFrontRightTyreLow(frame.state)) },
            MockStep(1_500) { frame ->
                frame.copy(
                    state = frame.state.copy(malfunctionIndicatorLampOn = Signal.Value(true, frame.state.lastUpdatedMs ?: 0L)),
                    diagnostics = withEngineDtc(frame.state.lastUpdatedMs ?: 0L),
                )
            },
            MockStep(2_000) { frame ->
                // Simulated cable removal: every value is cleared in one step.
                frame.copy(
                    state = VehicleState.unavailable(VehicleSourceId.MOCK),
                    diagnostics = VehicleDiagnosticsState.empty(),
                    connection = VehicleConnectionState.ConnectionLost("Simulated cable removal"),
                )
            },
        )

        private const val T = 1_000L

        private fun drivingState(previous: VehicleState) = previous.copy(
            speedKph = Signal.Value(60f, T),
            engineRpm = Signal.Value(2100, T),
            fuelLevelPercent = Signal.Value(62f, T),
            coolantTemperatureCelsius = Signal.Value(91, T),
            gear = Signal.Value(Gear.Drive, T),
            doors = Signal.Value(DoorPosition.entries.associateWith { DoorState.Closed }, T),
            seatbelts = Signal.Value(mapOf(SeatPosition.Driver to SeatbeltState.Buckled), T),
            tirePressuresKpa = Signal.Value(TirePosition.entries.associateWith { 230f }, T),
            malfunctionIndicatorLampOn = Signal.Value(false, T),
            lastUpdatedMs = T,
        )

        private fun withRearLeftDoorOpen(previous: VehicleState): VehicleState {
            val doors = previous.doors.let { signal ->
                when (signal) {
                    is Signal.Value -> signal.value + (DoorPosition.RearLeft to DoorState.Open)
                    else -> mapOf(DoorPosition.RearLeft to DoorState.Open)
                }
            }
            return previous.copy(doors = Signal.Value(doors, T))
        }

        private fun withFrontRightTyreLow(previous: VehicleState): VehicleState {
            val tyres = previous.tirePressuresKpa.let { signal ->
                when (signal) {
                    is Signal.Value -> signal.value + (TirePosition.FrontRight to 150f)
                    else -> mapOf(TirePosition.FrontRight to 150f)
                }
            }
            return previous.copy(tirePressuresKpa = Signal.Value(tyres, T))
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
```

Note the unused-import cleanup: remove `asStateFlow` and the `frames` field if the implementer keeps the pure-`flow` timeline shown above. `Severity` is imported for readability of the DTC construction.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*MockVehicleDataSourceTest*'`
Expected: PASS (5 tests). **Do not commit.**

---

### Task 7: `VehicleRepository` — active source, dedup, disconnect clearing

**Files:**
- Create: `app/src/main/java/com/csjotlab/cardashboard/vehicle/data/VehicleRepository.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/vehicle/data/VehicleRepositoryTest.kt`
- Test helper: `app/src/test/java/com/csjotlab/cardashboard/vehicle/fakes/ControllableVehicleDataSource.kt`

**Interfaces:**
- Consumes: `VehicleDataSource` (Task 6), all domain types
- Produces:
  - `data class VehicleSnapshot(val state: VehicleState, val diagnostics: VehicleDiagnosticsState, val connection: VehicleConnectionState)`
  - `class VehicleRepository(sources: Flow<VehicleDataSource?>, clock: Clock, scope: CoroutineScope, staleTimeoutMs: Long = 3_000L)`
  - `val VehicleRepository.snapshot: StateFlow<VehicleSnapshot>`
  - `class ControllableVehicleDataSource(override val id: VehicleSourceId)` with `emitState(VehicleState)`, `emitDiagnostics(...)`, `emitConnection(...)`

- [ ] **Step 1: Write the test fake**

```kotlin
package com.csjotlab.cardashboard.vehicle.fakes

import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleDiagnosticsState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import com.csjotlab.cardashboard.vehicle.source.VehicleDataSource
import kotlinx.coroutines.flow.MutableStateFlow

/** A source whose emissions the test drives directly. */
class ControllableVehicleDataSource(
    override val id: VehicleSourceId,
) : VehicleDataSource {

    private val states = MutableStateFlow(VehicleState.unavailable(id))
    private val diags = MutableStateFlow(VehicleDiagnosticsState.empty())
    private val connections = MutableStateFlow<VehicleConnectionState>(VehicleConnectionState.Disconnected)

    override val vehicleState = states
    override val diagnostics = diags
    override val connectionState = connections

    var started = false
        private set

    override suspend fun start() { started = true }
    override suspend fun stop() { started = false }

    fun emitState(state: VehicleState) { states.value = state }
    fun emitDiagnostics(state: VehicleDiagnosticsState) { diags.value = state }
    fun emitConnection(state: VehicleConnectionState) { connections.value = state }
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.csjotlab.cardashboard.vehicle.data

import app.cash.turbine.test
import com.csjotlab.cardashboard.vehicle.domain.Clock
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import com.csjotlab.cardashboard.vehicle.domain.allSignals
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull
import com.csjotlab.cardashboard.vehicle.fakes.ControllableVehicleDataSource
import com.csjotlab.cardashboard.vehicle.source.VehicleDataSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleRepositoryTest {

    private val clock = Clock { 1_000L }

    @Test
    fun `telemetry from the active source reaches the snapshot`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val sources = MutableStateFlow<VehicleDataSource?>(source)
        val repository = VehicleRepository(sources, clock, TestScope(this.coroutineContext))

        repository.snapshot.test {
            awaitItem()   // initial
            source.emitConnection(VehicleConnectionState.Reading)
            source.emitState(
                VehicleState.unavailable(VehicleSourceId.OBD_USB)
                    .copy(speedKph = Signal.Value(60f, 1_000L), engineRpm = Signal.Value(2100, 1_000L))
            )
            advanceUntilIdle()

            val snapshot = expectMostRecentItem()
            assertEquals(60f, snapshot.state.speedKph.valueOrNull())
            assertEquals(2100, snapshot.state.engineRpm.valueOrNull())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `identical consecutive states are not re-emitted`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val repository = VehicleRepository(MutableStateFlow(source), clock, TestScope(this.coroutineContext))
        val moving = VehicleState.unavailable(VehicleSourceId.OBD_USB).copy(speedKph = Signal.Value(60f, 1_000L))

        repository.snapshot.test {
            awaitItem()
            source.emitState(moving)
            advanceUntilIdle()
            awaitItem()

            source.emitState(moving)   // byte-identical
            advanceUntilIdle()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no real value survives a disconnect`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val repository = VehicleRepository(MutableStateFlow(source), clock, TestScope(this.coroutineContext))

        repository.snapshot.test {
            awaitItem()
            source.emitConnection(VehicleConnectionState.Reading)
            source.emitState(VehicleState.unavailable(VehicleSourceId.OBD_USB).copy(speedKph = Signal.Value(60f, 1_000L)))
            advanceUntilIdle()
            assertEquals(60f, expectMostRecentItem().state.speedKph.valueOrNull())

            source.emitConnection(VehicleConnectionState.ConnectionLost("cable removed"))
            advanceUntilIdle()

            val cleared = expectMostRecentItem()
            assertTrue(
                "stale real values must not outlive the connection",
                cleared.state.allSignals.none { it.isValue }
            )
            assertTrue(cleared.diagnostics.issues.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `removing the source clears everything and reports disconnected`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val sources = MutableStateFlow<VehicleDataSource?>(source)
        val repository = VehicleRepository(sources, clock, TestScope(this.coroutineContext))

        repository.snapshot.test {
            awaitItem()
            source.emitConnection(VehicleConnectionState.Reading)
            source.emitState(VehicleState.unavailable(VehicleSourceId.OBD_USB).copy(speedKph = Signal.Value(60f, 1_000L)))
            advanceUntilIdle()
            expectMostRecentItem()

            sources.value = null
            advanceUntilIdle()

            val snapshot = expectMostRecentItem()
            assertEquals(VehicleConnectionState.Disconnected, snapshot.connection)
            assertEquals(VehicleSourceId.NONE, snapshot.state.source)
            assertTrue(snapshot.state.allSignals.none { it.isValue })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switching source starts the new one and stops the old`() = runTest {
        val mock = ControllableVehicleDataSource(VehicleSourceId.MOCK)
        val real = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val sources = MutableStateFlow<VehicleDataSource?>(mock)
        val repository = VehicleRepository(sources, clock, TestScope(this.coroutineContext))

        repository.snapshot.test {
            awaitItem()
            advanceUntilIdle()
            assertTrue(mock.started)

            sources.value = real
            advanceUntilIdle()

            assertTrue("new source must be started", real.started)
            assertTrue("old source must be stopped", !mock.started)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*VehicleRepositoryTest*'`
Expected: FAIL — `Unresolved reference: VehicleRepository`.

- [ ] **Step 4: Write the implementation**

```kotlin
package com.csjotlab.cardashboard.vehicle.data

import com.csjotlab.cardashboard.vehicle.domain.Clock
import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleDiagnosticsState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import com.csjotlab.cardashboard.vehicle.domain.isLiveData
import com.csjotlab.cardashboard.vehicle.source.VehicleDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

data class VehicleSnapshot(
    val state: VehicleState,
    val diagnostics: VehicleDiagnosticsState,
    val connection: VehicleConnectionState,
) {
    companion object {
        val Disconnected = VehicleSnapshot(
            state = VehicleState.unavailable(VehicleSourceId.NONE),
            diagnostics = VehicleDiagnosticsState.empty(),
            connection = VehicleConnectionState.Disconnected,
        )
    }
}

/**
 * Owns the currently active source and publishes one coherent snapshot.
 *
 * Two invariants matter more than anything else here:
 *  - a real source that fails is never replaced by plausible values (there is no fallback path);
 *  - when the connection is not live, telemetry is cleared in the same emission.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VehicleRepository(
    sources: Flow<VehicleDataSource?>,
    private val clock: Clock,
    scope: CoroutineScope,
) {
    private var active: VehicleDataSource? = null

    val snapshot: StateFlow<VehicleSnapshot> = sources
        .onEach { next ->
            if (active !== next) {
                active?.stop()
                active = next
                next?.start()
            }
        }
        .flatMapLatest { source ->
            if (source == null) {
                flowOf(VehicleSnapshot.Disconnected)
            } else {
                combine(
                    source.vehicleState,
                    source.diagnostics,
                    source.connectionState,
                ) { state, diagnostics, connection ->
                    if (connection.isLiveData) {
                        VehicleSnapshot(state, diagnostics, connection)
                    } else {
                        // Not live: publish the connection state, but never stale readings.
                        VehicleSnapshot(
                            state = VehicleState.unavailable(source.id),
                            diagnostics = VehicleDiagnosticsState.empty(),
                            connection = connection,
                        )
                    }
                }
            }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, VehicleSnapshot.Disconnected)
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*VehicleRepositoryTest*'`
Expected: PASS (5 tests). **Do not commit.**

---

### Task 8: Staleness and `VehicleSourceSelector`

Staleness is handled at the **connection** level, not per field, so values never flicker in and out (spec §3.2). Selection policy enforces "no fallback to mock" and the debug gate.

**Files:**
- Modify: `app/src/main/java/com/csjotlab/cardashboard/vehicle/data/VehicleRepository.kt`
- Create: `app/src/main/java/com/csjotlab/cardashboard/vehicle/source/VehicleSourceSelector.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/vehicle/data/VehicleStalenessTest.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/vehicle/source/VehicleSourceSelectorTest.kt`

**Interfaces:**
- Consumes: `VehicleRepository` (Task 7), `VehicleDataSource` (Task 6)
- Produces:
  - `VehicleRepository(sources, clock, scope, staleTimeoutMs: Long = 3_000L)` — after `staleTimeoutMs` with no new `Reading` state, emits `VehicleCommunicationUnavailable("No response from vehicle for ${staleTimeoutMs}ms")` and clears telemetry
  - ```kotlin
    class VehicleSourceSelector(
        realSourceAvailability: Flow<VehicleDataSource?>,
        mockModeEnabled: Flow<Boolean>,
        debugBuild: Boolean,
        mockSourceFactory: () -> VehicleDataSource,
    ) { val activeSource: Flow<VehicleDataSource?> }
    ```

- [ ] **Step 1: Write the failing staleness test**

```kotlin
package com.csjotlab.cardashboard.vehicle.data

import app.cash.turbine.test
import com.csjotlab.cardashboard.vehicle.domain.Clock
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import com.csjotlab.cardashboard.vehicle.domain.allSignals
import com.csjotlab.cardashboard.vehicle.fakes.ControllableVehicleDataSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleStalenessTest {

    @Test
    fun `silence for the stale timeout reports communication unavailable and clears telemetry`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val repository = VehicleRepository(
            sources = MutableStateFlow(source),
            clock = Clock { currentTime },
            scope = TestScope(this.coroutineContext),
            staleTimeoutMs = 3_000L,
        )

        repository.snapshot.test {
            awaitItem()
            source.emitConnection(VehicleConnectionState.Reading)
            source.emitState(VehicleState.unavailable(VehicleSourceId.OBD_USB).copy(speedKph = Signal.Value(60f, 0L)))
            advanceUntilIdle()
            expectMostRecentItem()

            advanceTimeBy(3_500L)
            advanceUntilIdle()

            val stale = expectMostRecentItem()
            assertTrue(stale.connection is VehicleConnectionState.VehicleCommunicationUnavailable)
            assertTrue(stale.state.allSignals.none { it.isValue })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `continued updates keep the connection live`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val repository = VehicleRepository(
            sources = MutableStateFlow(source),
            clock = Clock { currentTime },
            scope = TestScope(this.coroutineContext),
            staleTimeoutMs = 3_000L,
        )

        repository.snapshot.test {
            awaitItem()
            source.emitConnection(VehicleConnectionState.Reading)
            repeat(5) { tick ->
                source.emitState(
                    VehicleState.unavailable(VehicleSourceId.OBD_USB)
                        .copy(speedKph = Signal.Value(60f + tick, currentTime), lastUpdatedMs = currentTime)
                )
                advanceTimeBy(1_000L)
                advanceUntilIdle()
            }

            assertTrue(expectMostRecentItem().connection is VehicleConnectionState.Reading)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*VehicleStalenessTest*'`
Expected: FAIL — `staleTimeoutMs` is not a parameter of `VehicleRepository`.

- [ ] **Step 3: Add staleness to `VehicleRepository`**

Add the constructor parameter and wrap the live branch. Replace the `flatMapLatest` body's non-null branch with:

```kotlin
                combine(
                    source.vehicleState,
                    source.diagnostics,
                    source.connectionState,
                ) { state, diagnostics, connection ->
                    if (connection.isLiveData) {
                        VehicleSnapshot(state, diagnostics, connection)
                    } else {
                        VehicleSnapshot(
                            state = VehicleState.unavailable(source.id),
                            diagnostics = VehicleDiagnosticsState.empty(),
                            connection = connection,
                        )
                    }
                }.withStaleTimeout(source.id, staleTimeoutMs)
```

and add, in the same file:

```kotlin
/**
 * Staleness is a connection-level fact, not a per-field one. Ageing fields individually would make
 * values flicker in and out; instead the whole connection degrades in one step. Spec section 3.2.
 */
private fun Flow<VehicleSnapshot>.withStaleTimeout(
    sourceId: VehicleSourceId,
    staleTimeoutMs: Long,
): Flow<VehicleSnapshot> = channelFlow {
    var watchdog: Job? = null
    collect { snapshot ->
        watchdog?.cancel()
        send(snapshot)
        if (snapshot.connection.isLiveData) {
            watchdog = launch {
                delay(staleTimeoutMs)
                send(
                    VehicleSnapshot(
                        state = VehicleState.unavailable(sourceId),
                        diagnostics = VehicleDiagnosticsState.empty(),
                        connection = VehicleConnectionState.VehicleCommunicationUnavailable(
                            "No response from vehicle for ${staleTimeoutMs}ms",
                        ),
                    ),
                )
            }
        }
    }
}
```

Add imports: `kotlinx.coroutines.Job`, `kotlinx.coroutines.channels.channelFlow` (actually `kotlinx.coroutines.flow.channelFlow`), `kotlinx.coroutines.delay`, `kotlinx.coroutines.launch`.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*VehicleStalenessTest*'`
Expected: PASS (2 tests).

- [ ] **Step 5: Write the failing selector test**

```kotlin
package com.csjotlab.cardashboard.vehicle.source

import app.cash.turbine.test
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.fakes.ControllableVehicleDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleSourceSelectorTest {

    private fun mockFactory(): VehicleDataSource = ControllableVehicleDataSource(VehicleSourceId.MOCK)

    @Test
    fun `a real source always wins`() = runTest {
        val real = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val selector = VehicleSourceSelector(
            realSourceAvailability = MutableStateFlow(real),
            mockModeEnabled = MutableStateFlow(true),
            debugBuild = true,
            mockSourceFactory = ::mockFactory,
        )

        selector.activeSource.test {
            assertEquals(VehicleSourceId.OBD_USB, awaitItem()?.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no real source and mock off means no source at all`() = runTest {
        val selector = VehicleSourceSelector(
            realSourceAvailability = MutableStateFlow(null),
            mockModeEnabled = MutableStateFlow(false),
            debugBuild = true,
            mockSourceFactory = ::mockFactory,
        )

        selector.activeSource.test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `mock requires both the debug build and the explicit toggle`() = runTest {
        val selector = VehicleSourceSelector(
            realSourceAvailability = MutableStateFlow(null),
            mockModeEnabled = MutableStateFlow(true),
            debugBuild = true,
            mockSourceFactory = ::mockFactory,
        )

        selector.activeSource.test {
            assertEquals(VehicleSourceId.MOCK, awaitItem()?.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `release builds never resolve to mock however the toggle is set`() = runTest {
        val selector = VehicleSourceSelector(
            realSourceAvailability = MutableStateFlow(null),
            mockModeEnabled = MutableStateFlow(true),
            debugBuild = false,
            mockSourceFactory = ::mockFactory,
        )

        selector.activeSource.test {
            assertNull("mock must be unreachable outside debug builds", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `losing the real source falls back to nothing not to mock`() = runTest {
        val real = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val availability = MutableStateFlow<VehicleDataSource?>(real)
        val selector = VehicleSourceSelector(
            realSourceAvailability = availability,
            mockModeEnabled = MutableStateFlow(false),
            debugBuild = true,
            mockSourceFactory = ::mockFactory,
        )

        selector.activeSource.test {
            assertEquals(VehicleSourceId.OBD_USB, awaitItem()?.id)
            availability.value = null
            assertNull("a failing real source must never be replaced by fake values", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*VehicleSourceSelectorTest*'`
Expected: FAIL — `Unresolved reference: VehicleSourceSelector`.

- [ ] **Step 7: Write the selector**

```kotlin
package com.csjotlab.cardashboard.vehicle.source

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Decides which source is active.
 *
 * There is deliberately no path from a failing real source to the mock: if the real source goes
 * away, the answer is "no source", and the dashboard says so. Spec section 6.
 */
class VehicleSourceSelector(
    realSourceAvailability: Flow<VehicleDataSource?>,
    mockModeEnabled: Flow<Boolean>,
    private val debugBuild: Boolean,
    private val mockSourceFactory: () -> VehicleDataSource,
) {
    private var cachedMock: VehicleDataSource? = null

    val activeSource: Flow<VehicleDataSource?> =
        combine(realSourceAvailability, mockModeEnabled) { real, mockEnabled ->
            when {
                real != null -> real
                debugBuild && mockEnabled -> cachedMock ?: mockSourceFactory().also { cachedMock = it }
                else -> null
            }
        }.distinctUntilChanged()
}
```

- [ ] **Step 8: Run it to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*VehicleSourceSelectorTest*'`
Expected: PASS (5 tests).

- [ ] **Step 9: Run the full suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS — Tasks 0–8. **Do not commit.**

---

### Task 9: `DashboardUiState`, `VehicleStateFormatter`, `DashboardViewModel`

All string formatting happens here, never in the domain. The formatter is the single place that turns "no value" into `—`, so honesty is testable in isolation.

**Files:**
- Create: `app/src/main/java/com/csjotlab/cardashboard/ui/dashboard/DashboardUiState.kt`
- Create: `app/src/main/java/com/csjotlab/cardashboard/ui/dashboard/VehicleStateFormatter.kt`
- Create: `app/src/main/java/com/csjotlab/cardashboard/ui/dashboard/DashboardViewModel.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/ui/dashboard/VehicleStateFormatterTest.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/ui/dashboard/DashboardViewModelTest.kt`

**Interfaces:**
- Consumes: `VehicleRepository`, `VehicleSnapshot` (Tasks 7–8), all domain types
- Produces:
  - `enum class WarningLevel { Ok, Active, NotReported }` — the third state that stops "no data" reading as "all clear"
  - `data class MetricUi(val label: String, val value: String, val helper: String, val available: Boolean)`
  - `data class WarningUi(val label: String, val level: WarningLevel, val helper: String, val issueId: String?)`
  - `data class DiagnosticUi(val id: String, val code: String, val headline: String, val statusLabel: String, val severity: Severity)`
  - `data class DashboardUiState(speedText, speedForGauge: Int?, maxSpeedKph: Int, tripText, rangeText, odometerText, metrics: List<MetricUi>, warnings: List<WarningUi>, diagnostics: List<DiagnosticUi>, connectionLabel: String, connectionActionLabel: String?, isSimulated: Boolean, isConnected: Boolean)` + `DashboardUiState.disconnected(driveModeLabel: String)`
  - `object VehicleStateFormatter` with `fun toUiState(snapshot: VehicleSnapshot, driveModeLabel: String): DashboardUiState`
  - `const val UNAVAILABLE = "—"` and `const val NOT_REPORTED = "Not reported by vehicle"`
  - `class DashboardViewModel(repository: VehicleRepository)` with `val uiState: StateFlow<DashboardUiState>`, `fun setDriveModeLabel(label: String)`

- [ ] **Step 1: Write the failing formatter test**

```kotlin
package com.csjotlab.cardashboard.ui.dashboard

import com.csjotlab.cardashboard.vehicle.data.VehicleSnapshot
import com.csjotlab.cardashboard.vehicle.domain.Gear
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleDiagnosticsState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleStateFormatterTest {

    private fun reading(state: VehicleState) = VehicleSnapshot(
        state = state,
        diagnostics = VehicleDiagnosticsState.empty(),
        connection = VehicleConnectionState.Reading,
    )

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
            reading(VehicleState.unavailable(VehicleSourceId.OBD_USB).copy(gear = Signal.Unsupported)),
            driveModeLabel = "Eco",
        )

        val gear = ui.metrics.first { it.label == "Gear" }
        assertEquals(UNAVAILABLE, gear.value)
        assertEquals(NOT_REPORTED, gear.helper)
        assertFalse(gear.available)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*VehicleStateFormatterTest*'`
Expected: FAIL — `Unresolved reference: VehicleStateFormatter`.

- [ ] **Step 3: Write `DashboardUiState.kt`**

```kotlin
package com.csjotlab.cardashboard.ui.dashboard

import com.csjotlab.cardashboard.vehicle.domain.Severity

const val UNAVAILABLE = "—"
const val NOT_REPORTED = "Not reported by vehicle"

/**
 * Three states, not two. "No data" must never render the same as "checked and fine" — that would be
 * a fabricated safety claim. Spec section 7.1.
 */
enum class WarningLevel { Ok, Active, NotReported }

data class MetricUi(
    val label: String,
    val value: String,
    val helper: String,
    val available: Boolean,
)

data class WarningUi(
    val label: String,
    val level: WarningLevel,
    val helper: String,
    /** Non-null when this row has a diagnostic issue behind it and can be opened. */
    val issueId: String?,
)

data class DiagnosticUi(
    val id: String,
    val code: String,
    val headline: String,
    val statusLabel: String,
    val severity: Severity,
)

data class DashboardUiState(
    val speedText: String,
    /** Null means "no reading", which the gauge must render as no position — not as zero. */
    val speedForGauge: Int?,
    val maxSpeedKph: Int,
    val tripText: String,
    val rangeText: String,
    val odometerText: String,
    val metrics: List<MetricUi>,
    val warnings: List<WarningUi>,
    val diagnostics: List<DiagnosticUi>,
    val connectionLabel: String,
    val connectionActionLabel: String?,
    val isSimulated: Boolean,
    val isConnected: Boolean,
) {
    companion object {
        fun disconnected(driveModeLabel: String): DashboardUiState =
            VehicleStateFormatter.toUiState(
                com.csjotlab.cardashboard.vehicle.data.VehicleSnapshot.Disconnected,
                driveModeLabel,
            )
    }
}
```

- [ ] **Step 4: Write `VehicleStateFormatter.kt`**

```kotlin
package com.csjotlab.cardashboard.ui.dashboard

import com.csjotlab.cardashboard.vehicle.data.VehicleSnapshot
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticCode
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticIssue
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticStatus
import com.csjotlab.cardashboard.vehicle.domain.DoorPosition
import com.csjotlab.cardashboard.vehicle.domain.DoorState
import com.csjotlab.cardashboard.vehicle.domain.Gear
import com.csjotlab.cardashboard.vehicle.domain.SeatbeltState
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.TirePosition
import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull

private const val MAX_SPEED_KPH = 220
private const val LOW_TIRE_KPA = 180f

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
            tripText = state.tripDistanceKm.valueOrNull()?.let { "%.1f km".format(it) } ?: UNAVAILABLE,
            rangeText = state.estimatedRangeKm.valueOrNull()?.let { "${it.toInt()} km" } ?: UNAVAILABLE,
            odometerText = state.odometerKm.valueOrNull()?.let { "%,d km".format(it.toLong()) } ?: UNAVAILABLE,
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
        metric("RPM", state.engineRpm.valueOrNull()?.let { "%,d".format(it) }, "x1000"),
        metric("Fuel", state.fuelLevelPercent.valueOrNull()?.let { "${it.toInt()}%" }, "Tank level"),
        gearMetric(state.gear, driveModeLabel),
        metric("Temp", state.coolantTemperatureCelsius.valueOrNull()?.let { "$it C" }, "Coolant"),
    )

    private fun metric(label: String, value: String?, helper: String) = MetricUi(
        label = label,
        value = value ?: UNAVAILABLE,
        helper = if (value == null) NOT_REPORTED else helper,
        available = value != null,
    )

    private fun gearMetric(gear: Signal<Gear>, driveModeLabel: String): MetricUi {
        val label = when (val value = gear.valueOrNull()) {
            Gear.Park -> "P"
            Gear.Reverse -> "R"
            Gear.Neutral -> "N"
            Gear.Drive -> "D"
            Gear.Low -> "L"
            is Gear.Manual -> "M${value.position}"
            Gear.Unknown, null -> null
        }
        return MetricUi(
            label = "Gear",
            value = label ?: UNAVAILABLE,
            // The mode-aware helper survives, but only where there is a gear to describe.
            helper = if (label == null) NOT_REPORTED else "$driveModeLabel shift",
            available = label != null,
        )
    }

    /** Always four rows, in a fixed order, so the compact-landscape SpaceEvenly fix still holds. */
    private fun warnings(state: VehicleState, issues: List<DiagnosticIssue>): List<WarningUi> {
        val unbuckled = state.seatbelts.valueOrNull()?.filterValues { it == SeatbeltState.Unbuckled }?.keys
        val openDoors = state.doors.valueOrNull()?.filterValues { it == DoorState.Open }?.keys
        val lowTires = state.tirePressuresKpa.valueOrNull()?.filterValues { it < LOW_TIRE_KPA }?.keys
        val mil = state.malfunctionIndicatorLampOn.valueOrNull()
        val dtcIssue = issues.firstOrNull { it.code is DiagnosticCode.Dtc }

        return listOf(
            row("Seatbelt", unbuckled, { "${it.size} unbuckled" }, "Secured", null),
            row("Door", openDoors, { positions -> positions.joinToString { readable(it) } + " open" }, "All doors closed", null),
            row("Tire Pressure", lowTires, { positions -> positions.joinToString { readable(it) } + " low" }, "Nominal", null),
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

    private fun <T> row(
        label: String,
        offending: Set<T>?,
        activeHelper: (Set<T>) -> String,
        okHelper: String,
        issueId: String?,
    ): WarningUi = when {
        offending == null -> WarningUi(label, WarningLevel.NotReported, "Not reported", null)
        offending.isEmpty() -> WarningUi(label, WarningLevel.Ok, okHelper, null)
        else -> WarningUi(label, WarningLevel.Active, activeHelper(offending), issueId)
    }

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
```

- [ ] **Step 5: Run the formatter test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*VehicleStateFormatterTest*'`
Expected: PASS (6 tests).

- [ ] **Step 6: Write the failing ViewModel test**

```kotlin
package com.csjotlab.cardashboard.ui.dashboard

import app.cash.turbine.test
import com.csjotlab.cardashboard.vehicle.data.VehicleRepository
import com.csjotlab.cardashboard.vehicle.domain.Clock
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import com.csjotlab.cardashboard.vehicle.fakes.ControllableVehicleDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `speed updates propagate to the ui state`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.OBD_USB)
        val repository = VehicleRepository(MutableStateFlow(source), Clock { 0L }, TestScope(this.coroutineContext))
        val viewModel = DashboardViewModel(repository)

        viewModel.uiState.test {
            awaitItem()
            source.emitConnection(VehicleConnectionState.Reading)
            source.emitState(
                VehicleState.unavailable(VehicleSourceId.OBD_USB).copy(speedKph = Signal.Value(88f, 0L))
            )
            advanceUntilIdle()

            assertEquals("88", expectMostRecentItem().speedText)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `changing the drive mode label refreshes the gear helper`() = runTest {
        val source = ControllableVehicleDataSource(VehicleSourceId.MOCK)
        val repository = VehicleRepository(MutableStateFlow(source), Clock { 0L }, TestScope(this.coroutineContext))
        val viewModel = DashboardViewModel(repository)

        source.emitConnection(VehicleConnectionState.Reading)
        source.emitState(
            VehicleState.unavailable(VehicleSourceId.MOCK)
                .copy(gear = Signal.Value(com.csjotlab.cardashboard.vehicle.domain.Gear.Drive, 0L))
        )
        viewModel.setDriveModeLabel("Sport")
        advanceUntilIdle()

        assertEquals("Sport shift", viewModel.uiState.value.metrics.first { it.label == "Gear" }.helper)
    }
}
```

- [ ] **Step 7: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*DashboardViewModelTest*'`
Expected: FAIL — `Unresolved reference: DashboardViewModel`.

- [ ] **Step 8: Write the ViewModel**

```kotlin
package com.csjotlab.cardashboard.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.csjotlab.cardashboard.vehicle.data.VehicleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/**
 * Maps repository snapshots to display state. The dashboard owns no timers and no data loops.
 */
class DashboardViewModel(
    repository: VehicleRepository,
) : ViewModel() {

    private val driveModeLabel = MutableStateFlow("Comfort")

    val uiState: StateFlow<DashboardUiState> =
        combine(repository.snapshot, driveModeLabel) { snapshot, mode ->
            VehicleStateFormatter.toUiState(snapshot, mode)
        }
            .distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = DashboardUiState.disconnected(driveModeLabel.value),
            )

    /** The drive mode is a display preference owned by the UI, not vehicle data. */
    fun setDriveModeLabel(label: String) {
        driveModeLabel.value = label
    }

    class Factory(private val repository: VehicleRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DashboardViewModel(repository) as T
    }
}
```

- [ ] **Step 9: Run it to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*DashboardViewModelTest*'`
Expected: PASS (2 tests). **Do not commit.**

---

### Task 10: Dashboard integration — delete the mock loops, keep every layout fix

This task is a **surgical replacement of data inputs**, not a redesign. Do not touch any sizing rule, threshold, weight, padding or `rememberSaveable` usage listed in Global Constraints.

**Files:**
- Modify: `app/src/main/java/com/csjotlab/cardashboard/ui/dashboard/DashboardScreen.kt` — delete lines 58–110 (`DashboardMetric`, `DashboardWarningState`, `mockMetrics`, `mockWarningScenarios`, `mockSpeedSequence`), delete the `LaunchedEffect` at 120–127 and the one at 274–281
- Create: `app/src/main/java/com/csjotlab/cardashboard/CarDashboardApplication.kt`
- Create: `app/src/main/java/com/csjotlab/cardashboard/di/VehicleContainer.kt`
- Modify: `app/src/main/AndroidManifest.xml` — add `android:name=".CarDashboardApplication"`
- Modify: `app/src/main/java/com/csjotlab/cardashboard/navigation/CarDashboardApp.kt`

**Interfaces:**
- Consumes: `DashboardViewModel`, `DashboardUiState`, `MetricUi`, `WarningUi`, `WarningLevel` (Task 9); `VehicleSourceSelector`, `MockVehicleDataSource` (Tasks 6, 8)
- Produces:
  - `class VehicleContainer(applicationScope: CoroutineScope, debugBuild: Boolean)` exposing `val repository: VehicleRepository` and `fun setMockModeEnabled(enabled: Boolean)`
  - `class CarDashboardApplication : Application()` exposing `val container: VehicleContainer`
  - `DashboardScreen(uiState: DashboardUiState, onModeSelected: (DriveMode) -> Unit, onIssueClick: (String) -> Unit, modifier: Modifier)`
  - `SpeedPanel(uiState:, accent:, modifier:, compact:)` — **stateless**

- [ ] **Step 1: Write the failing test**

```kotlin
package com.csjotlab.cardashboard.ui.dashboard

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * A structural guard, not a style check: the composable must not own a data loop again, and the
 * hardcoded mock literals must be gone rather than merely unused.
 */
class DashboardScreenPurityTest {

    private val source = File("src/main/java/com/csjotlab/cardashboard/ui/dashboard/DashboardScreen.kt").readText()

    @Test
    fun `no mock data literals remain in the composable file`() {
        listOf("mockSpeedSequence", "mockWarningScenarios", "mockMetrics", "142.8 km", "38,421 km", "Range 420 km")
            .forEach { assertFalse("DashboardScreen must not contain '$it'", source.contains(it)) }
    }

    @Test
    fun `no polling loop remains in the composable file`() {
        assertFalse("data loops belong in the repository, not a composable", source.contains("while (true)"))
        assertFalse(source.contains("kotlinx.coroutines.delay"))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*DashboardScreenPurityTest*'`
Expected: FAIL — the file still contains `mockSpeedSequence` and `while (true)`.

- [ ] **Step 3: Write the DI container and Application**

```kotlin
package com.csjotlab.cardashboard.di

import com.csjotlab.cardashboard.vehicle.data.VehicleRepository
import com.csjotlab.cardashboard.vehicle.domain.SystemClock
import com.csjotlab.cardashboard.vehicle.source.MockVehicleDataSource
import com.csjotlab.cardashboard.vehicle.source.VehicleDataSource
import com.csjotlab.cardashboard.vehicle.source.VehicleSourceSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Hand-rolled graph. The app is small enough that a DI framework would cost more than it saves.
 */
class VehicleContainer(
    applicationScope: CoroutineScope,
    debugBuild: Boolean,
) {
    /** Task 18 replaces this with real USB device availability. Until then, no real source exists. */
    private val realSourceAvailability = MutableStateFlow<VehicleDataSource?>(null)

    private val mockModeEnabled = MutableStateFlow(false)

    private val selector = VehicleSourceSelector(
        realSourceAvailability = realSourceAvailability,
        mockModeEnabled = mockModeEnabled,
        debugBuild = debugBuild,
        mockSourceFactory = { MockVehicleDataSource(SystemClock) },
    )

    val repository = VehicleRepository(
        sources = selector.activeSource,
        clock = SystemClock,
        scope = applicationScope,
    )

    fun setMockModeEnabled(enabled: Boolean) {
        mockModeEnabled.value = enabled
    }
}
```

```kotlin
package com.csjotlab.cardashboard

import android.app.Application
import com.csjotlab.cardashboard.di.VehicleContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

class CarDashboardApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val container: VehicleContainer by lazy {
        VehicleContainer(applicationScope, debugBuild = BuildConfig.DEBUG)
    }
}
```

In `AndroidManifest.xml`, add to `<application>`: `android:name=".CarDashboardApplication"`.

- [ ] **Step 4: Rewrite the data-owning parts of `DashboardScreen.kt`**

Delete lines 58–110 entirely. Replace the `DashboardScreen` composable (currently lines 112–168) with:

```kotlin
private enum class DriveMode(
    val label: String,
    val accent: Color
) {
    Eco("Eco", Color(0xFF22C55E)),
    Comfort("Comfort", DashboardAccent),
    Sport("Sport", Color(0xFFF97316))
}

@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    onDriveModeLabelChanged: (String) -> Unit,
    onIssueClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Saveable, not remember: rotation recreates MainActivity, and a plain remember drops
    // the selection back to Comfort. DriveMode is an enum and so java.io.Serializable,
    // which the default saver can put in the bundle without a custom Saver.
    var selectedMode by rememberSaveable { mutableStateOf(DriveMode.Comfort) }

    LaunchedEffect(selectedMode) { onDriveModeLabelChanged(selectedMode.label) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            selectedMode.accent.copy(alpha = 0.16f),
                            MaterialTheme.colorScheme.background,
                            Color(0xFF0B1220)
                        )
                    )
                )
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(DashboardSpacing.screenPadding)
        ) {
            // The landscape layout fills a bounded height rather than scrolling, so it is
            // only safe above a floor. Anything shorter falls back to the scrolling
            // portrait layout instead of clipping its columns.
            val wideLayout = maxWidth >= 720.dp && maxHeight >= 300.dp

            if (wideLayout) {
                LandscapeDashboardLayout(
                    uiState = uiState,
                    selectedMode = selectedMode,
                    onModeSelected = { selectedMode = it },
                    onIssueClick = onIssueClick
                )
            } else {
                PortraitDashboardLayout(
                    uiState = uiState,
                    selectedMode = selectedMode,
                    onModeSelected = { selectedMode = it },
                    onIssueClick = onIssueClick
                )
            }
        }
    }
}
```

`LaunchedEffect(selectedMode)` is a UI-preference notification, not a data loop — it fires once per selection change and the purity test allows it because it contains no `while` or `delay`.

Thread `uiState` through `LandscapeDashboardLayout` and `PortraitDashboardLayout` unchanged in structure — **every `Modifier`, weight, threshold and `Arrangement` stays exactly as it is today**. Replace only the data arguments:
- `SpeedPanel(accent = …, modifier = …, compact = …)` gains `uiState = uiState` and loses its `var speed by remember` and `LaunchedEffect`;
- `MetricGrid(mode = selectedMode, …)` becomes `MetricGrid(metrics = uiState.metrics, accent = selectedMode.accent, …)`;
- `OdometerPanel(…)` gains `totalText = uiState.odometerText, tripText = uiState.tripText`;
- `WarningPanel(warnings = warnings, …)` becomes `WarningPanel(warnings = uiState.warnings, onIssueClick = onIssueClick, …)`.

- [ ] **Step 5: Make `SpeedPanel` stateless**

Replace the body of `SpeedPanel` (currently lines 266–317) with:

```kotlin
@Composable
private fun SpeedPanel(
    uiState: DashboardUiState,
    accent: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    DashboardPanel(
        modifier = modifier,
        contentPadding = if (compact) DashboardSpacing.compact else DashboardSpacing.medium
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PanelHeader(title = "Speed", value = uiState.connectionLabel)
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                // Square, and never larger than the space it was given, so the arc stays
                // circular in both the portrait and landscape layouts.
                SpeedGauge(
                    speed = uiState.speedForGauge,
                    speedText = uiState.speedText,
                    maxSpeed = uiState.maxSpeedKph,
                    accent = accent,
                    modifier = Modifier.size(minOf(maxWidth, maxHeight, 260.dp))
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SmallReadout(label = "Trip", value = uiState.tripText)
                SmallReadout(label = "Range", value = uiState.rangeText)
            }
        }
    }
}
```

In `SpeedGauge`, change the signature to `speed: Int?, speedText: String, maxSpeed: Int, …` and compute:

```kotlin
    // A missing reading leaves the arc empty rather than pinning it to zero, which would look
    // exactly like a stationary car.
    val targetProgress = speed?.let { it.coerceIn(0, maxSpeed).toFloat() / maxSpeed.toFloat() } ?: 0f
```

and render `Text(text = speedText, …)` instead of `animatedSpeed.toInt().toString()`. Keep both `animateFloatAsState` calls and all drawing code unchanged; drop the now-unused `animatedSpeed` if `speed` is null-safe, or keep it driven by `speed ?: 0`.

- [ ] **Step 6: Give `WarningRow` its third state**

Replace the dot colour and trailing label logic in `WarningRow` (currently lines 548–584):

```kotlin
@Composable
private fun WarningRow(warning: WarningUi, onIssueClick: (String) -> Unit) {
    val (dotColor, statusLabel, statusColor) = when (warning.level) {
        WarningLevel.Active -> Triple(DashboardWarning, "ON", DashboardWarning)
        WarningLevel.Ok -> Triple(Color(0xFF22C55E), "OK", Color(0xFF22C55E))
        // Neutral, never green: with no data, "OK" would be a safety claim we cannot make.
        WarningLevel.NotReported -> Triple(DashboardTextMuted, "—", DashboardTextMuted)
    }

    val rowModifier = if (warning.issueId != null) {
        Modifier
            .fillMaxWidth()
            .clickable { onIssueClick(warning.issueId) }
    } else {
        Modifier.fillMaxWidth()
    }

    Row(
        modifier = rowModifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(dotColor)
            )
            Column {
                Text(
                    text = warning.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = warning.helper,
                    style = MaterialTheme.typography.labelMedium,
                    color = DashboardTextMuted
                )
            }
        }
        Text(
            text = statusLabel,
            style = MaterialTheme.typography.labelMedium,
            color = statusColor
        )
    }
}
```

Add `import androidx.compose.foundation.clickable`. In `WarningPanel`, change the header count to `warnings.count { it.level == WarningLevel.Active }` and keep the `SpaceEvenly`/weighted-column compact branch exactly as it is.

- [ ] **Step 7: Wire the ViewModel in `CarDashboardApp`**

```kotlin
@Composable
fun CarDashboardApp() {
    val navController = rememberNavController()
    val application = LocalContext.current.applicationContext as CarDashboardApplication
    val viewModel: DashboardViewModel = viewModel(
        factory = DashboardViewModel.Factory(application.container.repository)
    )

    NavHost(
        navController = navController,
        startDestination = DashboardRoute.route
    ) {
        composable(DashboardRoute.route) {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            DashboardScreen(
                uiState = uiState,
                onDriveModeLabelChanged = viewModel::setDriveModeLabel,
                onIssueClick = { navController.navigate(DiagnosticDetailRoute.of(it)) }
            )
        }
    }
}
```

`DiagnosticDetailRoute` arrives in Task 12; until then, make `onIssueClick` a no-op lambda so this task compiles on its own.

- [ ] **Step 8: Update the previews**

Replace both `@Preview` composables to pass explicit state, so previews never depend on a running source:

```kotlin
@Preview(showBackground = true, widthDp = 900, heightDp = 480)
@Composable
private fun DashboardScreenLandscapePreview() {
    CarDashboardTheme {
        DashboardScreen(
            uiState = DashboardUiState.disconnected("Comfort"),
            onDriveModeLabelChanged = {},
            onIssueClick = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun DashboardScreenPortraitPreview() {
    CarDashboardTheme {
        DashboardScreen(
            uiState = DashboardUiState.disconnected("Comfort"),
            onDriveModeLabelChanged = {},
            onIssueClick = {}
        )
    }
}
```

- [ ] **Step 9: Run the purity test and build**

Run: `./gradlew testDebugUnitTest --tests '*DashboardScreenPurityTest*' && ./gradlew assembleDebug`
Expected: PASS, then BUILD SUCCESSFUL. **Do not commit.**

---

### Task 11: Vehicle Health panel, connection banner, simulation banner

Placement differs by orientation **on purpose**. Portrait scrolls, so a full panel is safe. Landscape's third column is height-bounded at roughly 320 dp and already holds `WarningPanel` + `DriveModePanel` — that bound is exactly what clipped before commit `ecf85cd`. Landscape therefore gets a count chip in the existing `WarningPanel` header, not a third panel.

**Files:**
- Create: `app/src/main/java/com/csjotlab/cardashboard/ui/dashboard/VehicleHealthPanel.kt`
- Create: `app/src/main/java/com/csjotlab/cardashboard/ui/dashboard/ConnectionBanner.kt`
- Modify: `app/src/main/java/com/csjotlab/cardashboard/ui/dashboard/DashboardScreen.kt`
- Test: `app/src/androidTest/java/com/csjotlab/cardashboard/ui/dashboard/VehicleHealthPanelTest.kt`

**Interfaces:**
- Consumes: `DashboardUiState`, `DiagnosticUi`, `Severity` (Task 9)
- Produces:
  - `VehicleHealthPanel(diagnostics: List<DiagnosticUi>, isConnected: Boolean, onIssueClick: (String) -> Unit, modifier: Modifier, compact: Boolean)`
  - `ConnectionBanner(label: String, actionLabel: String?, onAction: () -> Unit, modifier: Modifier)`
  - `SimulationBanner(modifier: Modifier)` — renders the literal text `SIMULATED DATA — NOT A REAL VEHICLE`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.csjotlab.cardashboard.ui.dashboard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.csjotlab.cardashboard.vehicle.domain.Severity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VehicleHealthPanelTest {

    @get:Rule val compose = createComposeRule()

    private val issue = DiagnosticUi(
        id = "dtc:P0301",
        code = "P0301",
        headline = "Engine diagnostic code detected",
        statusLabel = "Stored",
        severity = Severity.Warning,
    )

    @Test
    fun `disconnected and healthy must not look the same`() {
        compose.setContent { VehicleHealthPanel(emptyList(), isConnected = false, onIssueClick = {}) }
        compose.onNodeWithText("Vehicle not connected").assertIsDisplayed()

        compose.setContent { VehicleHealthPanel(emptyList(), isConnected = true, onIssueClick = {}) }
        compose.onNodeWithText("No issues reported").assertIsDisplayed()
    }

    @Test
    fun `an issue shows its code and never invents a description`() {
        compose.setContent { VehicleHealthPanel(listOf(issue), isConnected = true, onIssueClick = {}) }

        compose.onNodeWithText("P0301").assertIsDisplayed()
        compose.onNodeWithText("Engine diagnostic code detected").assertIsDisplayed()
        compose.onNodeWithText("Stored").assertIsDisplayed()
    }

    @Test
    fun `tapping an issue reports its id`() {
        var clicked: String? = null
        compose.setContent { VehicleHealthPanel(listOf(issue), isConnected = true, onIssueClick = { clicked = it }) }

        compose.onNodeWithText("P0301").performClick()
        assertEquals("dtc:P0301", clicked)
    }

    @Test
    fun `the simulation banner states plainly that the data is not real`() {
        compose.setContent { SimulationBanner() }
        compose.onNodeWithText("SIMULATED DATA — NOT A REAL VEHICLE").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew connectedDebugAndroidTest --tests '*VehicleHealthPanelTest*'`
Expected: FAIL — `Unresolved reference: VehicleHealthPanel`.

- [ ] **Step 3: Write the composables**

Follow the existing visual system exactly: `DashboardPanel`, `PanelHeader`, `DashboardSpacing`, `MaterialTheme.typography`. Severity colours: `Critical` → `DashboardWarning`, `Warning` → `DashboardWarning`, `Info` → `DashboardTextMuted`.

```kotlin
@Composable
fun VehicleHealthPanel(
    diagnostics: List<DiagnosticUi>,
    isConnected: Boolean,
    onIssueClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    DashboardPanel(
        modifier = modifier,
        contentPadding = if (compact) DashboardSpacing.compact else DashboardSpacing.medium
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(
                if (compact) DashboardSpacing.small else DashboardSpacing.medium
            )
        ) {
            PanelHeader(
                title = "Vehicle Health",
                value = if (diagnostics.isEmpty()) "" else "${diagnostics.size} reported"
            )
            when {
                // Two different facts, two different messages. "No issues" would be a claim we
                // cannot make while disconnected.
                !isConnected -> Text(
                    text = "Vehicle not connected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DashboardTextMuted
                )
                diagnostics.isEmpty() -> Text(
                    text = "No issues reported",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DashboardTextMuted
                )
                else -> diagnostics.forEach { issue ->
                    DiagnosticRow(issue = issue, onClick = { onIssueClick(issue.id) })
                }
            }
        }
    }
}
```

`DiagnosticRow` shows `issue.code` in `headlineMedium`-weight text with the severity colour, `issue.headline` in `bodyMedium`, and `issue.statusLabel` as a trailing muted label; the whole row is `clickable`.

`SimulationBanner` is a full-width `Surface` with `DashboardWarning` background and the exact text `SIMULATED DATA — NOT A REAL VEHICLE` in `labelMedium`.

`ConnectionBanner` shows `label` on the left; when `actionLabel != null` it shows a `ModeChip`-styled button on the right calling `onAction`.

- [ ] **Step 4: Place them in the dashboard**

In `PortraitDashboardLayout`, insert between `WarningPanel` and `DriveModePanel`:

```kotlin
        VehicleHealthPanel(
            diagnostics = uiState.diagnostics,
            isConnected = uiState.isConnected,
            onIssueClick = onIssueClick,
            modifier = Modifier.fillMaxWidth()
        )
```

In `LandscapeDashboardLayout`, **add no panel**. Instead pass the count into the existing header by changing `WarningPanel`'s `PanelHeader` value to include `uiState.diagnostics.size` when non-zero.

In both layouts, put `ConnectionBanner` above the first panel, and `SimulationBanner` above that when `uiState.isSimulated`. In landscape both banners are single-line and sit inside the existing `Column`, so the height budget shifts by one text line; verify against the compact test in Task 13.

- [ ] **Step 5: Run the test**

Run: `./gradlew connectedDebugAndroidTest --tests '*VehicleHealthPanelTest*'`
Expected: PASS (4 tests). **Do not commit.**

---

### Task 12: Diagnostic detail screen and nav route

**Files:**
- Create: `app/src/main/java/com/csjotlab/cardashboard/navigation/DiagnosticDetailRoute.kt`
- Create: `app/src/main/java/com/csjotlab/cardashboard/ui/diagnostics/DiagnosticDetailScreen.kt`
- Create: `app/src/main/java/com/csjotlab/cardashboard/ui/diagnostics/DiagnosticDetailUiState.kt`
- Modify: `app/src/main/java/com/csjotlab/cardashboard/navigation/CarDashboardApp.kt`
- Modify: `app/src/main/java/com/csjotlab/cardashboard/ui/dashboard/DashboardViewModel.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/ui/diagnostics/DiagnosticDetailUiStateTest.kt`

**Interfaces:**
- Consumes: `DiagnosticIssue`, `DtcClassification`, `DiagnosticStatus`, `Severity` (Tasks 3–4)
- Produces:
  - `object DiagnosticDetailRoute { const val route = "diagnostics/{issueId}"; const val ARG = "issueId"; fun of(issueId: String): String }`
  - `data class DiagnosticDetailUiState(problem, code, statusLabel, severityLabel, detectedTimeLabel, affectedSystem, description, originLabel)` + `fun from(issue: DiagnosticIssue, formatTime: (Long) -> String): DiagnosticDetailUiState`
  - `DashboardViewModel.issueById(id: String): DiagnosticIssue?`
  - `DiagnosticDetailScreen(state: DiagnosticDetailUiState?, onBack: () -> Unit)`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.csjotlab.cardashboard.ui.diagnostics

import com.csjotlab.cardashboard.vehicle.domain.DiagnosticCode
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticIssue
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticSource
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticStatus
import com.csjotlab.cardashboard.vehicle.domain.DtcClassification
import com.csjotlab.cardashboard.vehicle.domain.DtcSystem
import com.csjotlab.cardashboard.vehicle.domain.Severity
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticDetailUiStateTest {

    private fun issue(
        status: DiagnosticStatus = DiagnosticStatus.Stored,
        source: DiagnosticSource = DiagnosticSource.Obd2,
        title: String? = null,
    ) = DiagnosticIssue(
        id = "dtc:P0301",
        code = DiagnosticCode.Dtc("P0301"),
        title = title,
        description = null,
        classification = DtcClassification(DtcSystem.Powertrain, false, "Ignition system or misfire"),
        severity = Severity.Warning,
        status = status,
        source = source,
        firstSeenMs = 1_000L,
        lastSeenMs = 2_000L,
    )

    @Test
    fun `a code with no trusted mapping says so instead of inventing text`() {
        val state = DiagnosticDetailUiState.from(issue()) { "10:00" }

        assertEquals("Engine diagnostic code detected", state.problem)
        assertEquals("P0301", state.code)
        assertEquals("No description available for this code", state.description)
        assertEquals("Powertrain — Ignition system or misfire", state.affectedSystem)
    }

    @Test
    fun `stored codes are labelled as stored not live`() {
        assertEquals("Stored diagnostic code", DiagnosticDetailUiState.from(issue()) { "10:00" }.originLabel)
    }

    @Test
    fun `live signals are labelled as live`() {
        val live = issue(status = DiagnosticStatus.LiveSignal, source = DiagnosticSource.VehicleSignal)
        assertEquals("Live vehicle signal", DiagnosticDetailUiState.from(live) { "10:00" }.originLabel)
    }

    @Test
    fun `a trusted title is used when one exists`() {
        val state = DiagnosticDetailUiState.from(issue(title = "Cylinder 1 misfire detected")) { "10:00" }
        assertEquals("Cylinder 1 misfire detected", state.problem)
    }

    @Test
    fun `detected time comes from the formatter not from wall clock`() {
        assertEquals("10:00", DiagnosticDetailUiState.from(issue()) { "10:00" }.detectedTimeLabel)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*DiagnosticDetailUiStateTest*'`
Expected: FAIL — `Unresolved reference: DiagnosticDetailUiState`.

- [ ] **Step 3: Write `DiagnosticDetailUiState.kt`**

```kotlin
package com.csjotlab.cardashboard.ui.diagnostics

import com.csjotlab.cardashboard.vehicle.domain.DiagnosticCode
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticIssue
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticStatus
import com.csjotlab.cardashboard.vehicle.domain.Severity

data class DiagnosticDetailUiState(
    val problem: String,
    val code: String,
    val statusLabel: String,
    val severityLabel: String,
    val detectedTimeLabel: String,
    val affectedSystem: String,
    val description: String,
    val originLabel: String,
) {
    companion object {
        fun from(issue: DiagnosticIssue, formatTime: (Long) -> String) = DiagnosticDetailUiState(
            // Only a supplied title may name a fault. Otherwise we state the fact and stop.
            problem = issue.title ?: when (issue.code) {
                is DiagnosticCode.Dtc -> "Engine diagnostic code detected"
                is DiagnosticCode.SignalDerived -> "Vehicle signal reported"
            },
            code = when (val code = issue.code) {
                is DiagnosticCode.Dtc -> code.code
                is DiagnosticCode.SignalDerived -> "${code.kind} — ${code.detail}"
            },
            statusLabel = when (issue.status) {
                DiagnosticStatus.Stored -> "Stored"
                DiagnosticStatus.Pending -> "Pending"
                DiagnosticStatus.Permanent -> "Permanent"
                DiagnosticStatus.LiveSignal -> "Live"
            },
            severityLabel = when (issue.severity) {
                Severity.Info -> "Info"
                Severity.Warning -> "Warning"
                Severity.Critical -> "Critical"
            },
            detectedTimeLabel = formatTime(issue.firstSeenMs),
            affectedSystem = issue.classification?.let { classification ->
                listOfNotNull(classification.system.name, classification.subsystem).joinToString(" — ")
            } ?: "Unknown",
            description = issue.description ?: "No description available for this code",
            // The spec requires live state and stored history to be visibly different.
            originLabel = if (issue.status == DiagnosticStatus.LiveSignal) {
                "Live vehicle signal"
            } else {
                "Stored diagnostic code"
            },
        )
    }
}
```

- [ ] **Step 4: Add the route and screen**

```kotlin
package com.csjotlab.cardashboard.navigation

object DiagnosticDetailRoute {
    const val ARG = "issueId"
    const val route = "diagnostics/{$ARG}"
    fun of(issueId: String): String = "diagnostics/${java.net.URLEncoder.encode(issueId, "UTF-8")}"
}
```

`DiagnosticDetailScreen` renders one `DashboardPanel` per labelled field in the order the spec lists — Problem, Diagnostic code, Status, Severity, Detected time, Affected system, Available description — with `originLabel` shown prominently at the top. When `state == null` it renders `Issue no longer reported` and a back action.

Add to `CarDashboardApp`'s `NavHost`:

```kotlin
        composable(DiagnosticDetailRoute.route) { entry ->
            val issueId = entry.arguments?.getString(DiagnosticDetailRoute.ARG).orEmpty()
            val issue = viewModel.issueById(java.net.URLDecoder.decode(issueId, "UTF-8"))
            DiagnosticDetailScreen(
                state = issue?.let { DiagnosticDetailUiState.from(it) { ms -> formatClockTime(ms) } },
                onBack = { navController.popBackStack() }
            )
        }
```

and to `DashboardViewModel`, backed by the repository snapshot:

```kotlin
    fun issueById(id: String) = repository.snapshot.value.diagnostics.issues.firstOrNull { it.id == id }
```

This requires keeping `repository` as a constructor `private val`.

- [ ] **Step 5: Run the test and build**

Run: `./gradlew testDebugUnitTest --tests '*DiagnosticDetailUiStateTest*' && ./gradlew assembleDebug`
Expected: PASS (5 tests), then BUILD SUCCESSFUL. **Do not commit.**

---

### Task 13: Compose UI tests — eight dashboard states, rotation, both layouts

**Files:**
- Test: `app/src/androidTest/java/com/csjotlab/cardashboard/ui/dashboard/DashboardScreenStateTest.kt`
- Test: `app/src/androidTest/java/com/csjotlab/cardashboard/ui/dashboard/DashboardLayoutTest.kt`

**Interfaces:**
- Consumes: `DashboardScreen`, `DashboardUiState`, `VehicleStateFormatter` (Tasks 9–11)
- Produces: no production code — this task is verification only

- [ ] **Step 1: Write the state tests**

Build each `DashboardUiState` by running a real `VehicleSnapshot` through `VehicleStateFormatter`, so the tests exercise the production mapping rather than hand-built UI state.

```kotlin
package com.csjotlab.cardashboard.ui.dashboard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.csjotlab.cardashboard.ui.theme.CarDashboardTheme
import com.csjotlab.cardashboard.vehicle.data.VehicleSnapshot
import com.csjotlab.cardashboard.vehicle.domain.DoorPosition
import com.csjotlab.cardashboard.vehicle.domain.DoorState
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.TirePosition
import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleDiagnosticsState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.VehicleState
import org.junit.Rule
import org.junit.Test

class DashboardScreenStateTest {

    @get:Rule val compose = createComposeRule()

    private fun show(snapshot: VehicleSnapshot) {
        compose.setContent {
            CarDashboardTheme {
                DashboardScreen(
                    uiState = VehicleStateFormatter.toUiState(snapshot, "Comfort"),
                    onDriveModeLabelChanged = {},
                    onIssueClick = {}
                )
            }
        }
    }

    private fun reading(state: VehicleState, diagnostics: VehicleDiagnosticsState = VehicleDiagnosticsState.empty()) =
        VehicleSnapshot(state, diagnostics, VehicleConnectionState.Reading)

    private val healthy = VehicleState.unavailable(VehicleSourceId.MOCK).copy(
        speedKph = Signal.Value(60f, 1L),
        engineRpm = Signal.Value(2100, 1L),
        fuelLevelPercent = Signal.Value(62f, 1L),
        coolantTemperatureCelsius = Signal.Value(91, 1L),
        doors = Signal.Value(DoorPosition.entries.associateWith { DoorState.Closed }, 1L),
        tirePressuresKpa = Signal.Value(TirePosition.entries.associateWith { 230f }, 1L),
        malfunctionIndicatorLampOn = Signal.Value(false, 1L),
    )

    @Test fun `no vehicle connected`() {
        show(VehicleSnapshot.Disconnected)
        compose.onNodeWithText("Vehicle not connected").assertIsDisplayed()
        compose.onNodeWithText("—").assertExists()
    }

    @Test fun `connected and healthy`() {
        show(reading(healthy))
        compose.onNodeWithText("60").assertIsDisplayed()
        compose.onNodeWithText("No fault").assertIsDisplayed()
    }

    @Test fun `connected with one warning`() {
        show(reading(healthy.copy(doors = Signal.Value(mapOf(DoorPosition.RearLeft to DoorState.Open), 1L))))
        compose.onNodeWithText("Rear left open").assertIsDisplayed()
    }

    @Test fun `connected with multiple warnings`() {
        show(
            reading(
                healthy.copy(
                    doors = Signal.Value(mapOf(DoorPosition.RearLeft to DoorState.Open), 1L),
                    tirePressuresKpa = Signal.Value(mapOf(TirePosition.FrontRight to 150f), 1L),
                    malfunctionIndicatorLampOn = Signal.Value(true, 1L),
                )
            )
        )
        compose.onNodeWithText("Rear left open").assertIsDisplayed()
        compose.onNodeWithText("Front right low").assertIsDisplayed()
    }

    @Test fun `disconnected while open clears every value`() {
        show(reading(healthy))
        compose.onNodeWithText("60").assertIsDisplayed()

        show(VehicleSnapshot(healthy, VehicleDiagnosticsState.empty(), VehicleConnectionState.ConnectionLost("removed")))
        compose.onNodeWithText("Connection lost").assertIsDisplayed()
        compose.onAllNodesWithText("60").assertCountEquals(0)
    }

    @Test fun `unknown property renders as not reported never as OK`() {
        show(reading(healthy.copy(doors = Signal.Unsupported, seatbelts = Signal.Unsupported)))
        compose.onAllNodesWithText("Not reported").assertCountEquals(2)
        compose.onAllNodesWithText("All doors closed").assertCountEquals(0)
    }

    @Test fun `diagnostic error surfaces the connection state`() {
        show(VehicleSnapshot(VehicleState.unavailable(VehicleSourceId.OBD_USB), VehicleDiagnosticsState.empty(), VehicleConnectionState.Error("protocol failure")))
        compose.onNodeWithText("Error").assertIsDisplayed()
    }
}
```

Add `import androidx.compose.ui.test.assertCountEquals`.

- [ ] **Step 2: Write the layout tests**

```kotlin
package com.csjotlab.cardashboard.ui.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.csjotlab.cardashboard.ui.theme.CarDashboardTheme
import com.csjotlab.cardashboard.vehicle.data.VehicleSnapshot
import org.junit.Rule
import org.junit.Test

class DashboardLayoutTest {

    @get:Rule val compose = createComposeRule()

    private fun showAt(width: Int, height: Int) {
        compose.setContent {
            CarDashboardTheme {
                Box(Modifier.size(width.dp, height.dp)) {
                    DashboardScreen(
                        uiState = VehicleStateFormatter.toUiState(VehicleSnapshot.Disconnected, "Comfort"),
                        onDriveModeLabelChanged = {},
                        onIssueClick = {}
                    )
                }
            }
        }
    }

    @Test fun `compact landscape shows every warning row and both mode controls`() {
        showAt(width = 900, height = 400)
        listOf("Seatbelt", "Door", "Tire Pressure", "Check Engine").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("Eco").assertIsDisplayed()
        compose.onNodeWithText("Sport").assertIsDisplayed()
    }

    @Test fun `portrait shows the four metric tiles`() {
        showAt(width = 390, height = 844)
        listOf("RPM", "Fuel", "Gear", "Temp").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test fun `all three drive modes are selectable`() {
        showAt(width = 390, height = 844)
        listOf("Eco", "Comfort", "Sport").forEach { mode ->
            compose.onNodeWithText(mode).performClick()
            compose.onNodeWithText(mode).assertIsDisplayed()
        }
    }
}
```

- [ ] **Step 3: Run all instrumented tests on the Pixel 7a**

Run: `./gradlew connectedDebugAndroidTest`
Expected: PASS. `assertIsDisplayed` on a clipped node fails, so the compact-landscape test is the guard against reintroducing the `ecf85cd` overflow.

- [ ] **Step 4: Manually verify rotation on the device**

Install and rotate: `./gradlew installDebug`, then rotate the device through portrait → landscape → portrait, selecting Sport before the first rotation. Confirm Sport survives (the `rememberSaveable` fix) and no panel clips. **Do not commit.**

---

> **Milestone.** At the end of Task 13 the app is a complete, tested, honest dashboard running on mock data, with no USB code and no third-party hardware dependency. Everything below adds the real source.

---

### Task 14: `VehicleTransport` abstraction and `FakeVehicleTransport`

A byte pipe, nothing more. It knows nothing about OBD-II, and the protocol layer knows nothing about USB. `FakeVehicleTransport` answers written commands with canned ELM327 bytes, so the entire protocol layer is verifiable without hardware.

**Files:**
- Create: `app/src/main/java/com/csjotlab/cardashboard/vehicle/transport/VehicleTransport.kt`
- Create: `app/src/test/java/com/csjotlab/cardashboard/vehicle/fakes/FakeVehicleTransport.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/vehicle/fakes/FakeVehicleTransportTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - ```kotlin
    sealed interface TransportEvent {
        data object Opened : TransportEvent
        data object Detached : TransportEvent
        data class Failed(val reason: String) : TransportEvent
    }
    interface VehicleTransport {
        val events: Flow<TransportEvent>
        suspend fun open()
        suspend fun write(bytes: ByteArray)
        fun incoming(): Flow<ByteArray>
        suspend fun close()
    }
    ```
  - `class FakeVehicleTransport(responses: Map<String, String>, defaultResponse: String = "NO DATA")` with `fun detach()`, `fun fail(reason: String)`, `val written: List<String>`, and `fun respondTo(command: String, reply: String)`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.csjotlab.cardashboard.vehicle.fakes

import app.cash.turbine.test
import com.csjotlab.cardashboard.vehicle.transport.TransportEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeVehicleTransportTest {

    @Test
    fun `answers a known command with its canned reply and a prompt`() = runTest {
        val transport = FakeVehicleTransport(mapOf("010C" to "41 0C 1A F8"))
        transport.open()

        transport.incoming().test {
            transport.write("010C\r".toByteArray())
            val reply = String(awaitItem())
            assertEquals("41 0C 1A F8\r\r>", reply)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("010C"), transport.written)
    }

    @Test
    fun `answers an unknown command with NO DATA`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.open()

        transport.incoming().test {
            transport.write("01FF\r".toByteArray())
            assertEquals("NO DATA\r\r>", String(awaitItem()))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `detaching emits a Detached event`() = runTest {
        val transport = FakeVehicleTransport(emptyMap())
        transport.events.test {
            transport.open()
            assertEquals(TransportEvent.Opened, awaitItem())
            transport.detach()
            assertEquals(TransportEvent.Detached, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*FakeVehicleTransportTest*'`
Expected: FAIL — `Unresolved reference: FakeVehicleTransport`.

- [ ] **Step 3: Write `VehicleTransport.kt`**

```kotlin
package com.csjotlab.cardashboard.vehicle.transport

import kotlinx.coroutines.flow.Flow

sealed interface TransportEvent {
    data object Opened : TransportEvent
    data object Detached : TransportEvent
    data class Failed(val reason: String) : TransportEvent
}

/**
 * A bidirectional byte pipe to a diagnostic adapter. It knows nothing about OBD-II; the protocol
 * layer knows nothing about USB. That split is what lets the whole protocol be tested without
 * hardware. Spec section 5.
 */
interface VehicleTransport {
    val events: Flow<TransportEvent>
    suspend fun open()
    suspend fun write(bytes: ByteArray)
    fun incoming(): Flow<ByteArray>
    suspend fun close()
}
```

- [ ] **Step 4: Write `FakeVehicleTransport.kt`**

Implement with a `MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)` for `incoming()` and a `MutableSharedFlow<TransportEvent>(extraBufferCapacity = 16)` for `events`. `write` strips `\r`, records the command in `written`, looks up `responses`, and emits `"$reply\r\r>"` — the trailing `>` is the ELM327 prompt the session waits for. `detach()` emits `TransportEvent.Detached`; `fail(reason)` emits `TransportEvent.Failed(reason)`.

- [ ] **Step 5: Run it to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*FakeVehicleTransportTest*'`
Expected: PASS (3 tests). **Do not commit.**

---

### Task 15: `ObdCommand`, PID decoders, `SupportedPidSet`

Pure functions over bytes. This is also where the read-only safety boundary is made structural.

**Files:**
- Create: `app/src/main/java/com/csjotlab/cardashboard/vehicle/protocol/ObdCommand.kt`
- Create: `app/src/main/java/com/csjotlab/cardashboard/vehicle/protocol/ObdPid.kt`
- Create: `app/src/main/java/com/csjotlab/cardashboard/vehicle/protocol/SupportedPidSet.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/vehicle/protocol/ObdCommandTest.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/vehicle/protocol/ObdPidTest.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/vehicle/protocol/SupportedPidSetTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `sealed interface ObdCommand { val request: String }` with **only** `CurrentData(pid: Int)`, `StoredDtcs`, `PendingDtcs`, `PermanentDtcs`, `SupportedPids(basePid: Int)`
  - `object ObdPid` with constants `SPEED = 0x0D`, `RPM = 0x0C`, `COOLANT = 0x05`, `FUEL_LEVEL = 0x2F`, `MONITOR_STATUS = 0x01`, `RUN_TIME = 0x1F`, `DISTANCE_WITH_MIL = 0x21`, `DISTANCE_SINCE_CLEAR = 0x31`, `AMBIENT_TEMP = 0x46`, `ODOMETER = 0xA6`
  - decoders: `decodeSpeedKph(List<Int>): Float?`, `decodeRpm`, `decodeCoolantCelsius`, `decodeFuelPercent`, `decodeMonitorStatus(List<Int>): MonitorStatus?`, `decodeOdometerKm`, `decodeDistanceKm`
  - `data class MonitorStatus(val milOn: Boolean, val dtcCount: Int)`
  - `object SupportedPidSet { fun decode(basePid: Int, data: List<Int>): Set<Int> }`

- [ ] **Step 1: Write the failing safety test**

```kotlin
package com.csjotlab.cardashboard.vehicle.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdCommandTest {

    /**
     * The read-only boundary is structural, not a convention. Widening this set requires
     * deliberately editing this test, which is the point.
     */
    @Test
    fun `the command set contains only read services`() {
        val subclasses = ObdCommand::class.sealedSubclasses.map { it.simpleName }.toSet()

        assertEquals(
            setOf("CurrentData", "StoredDtcs", "PendingDtcs", "PermanentDtcs", "SupportedPids"),
            subclasses
        )
    }

    @Test
    fun `no command can request a write service`() {
        val requests = listOf(
            ObdCommand.CurrentData(0x0D),
            ObdCommand.StoredDtcs,
            ObdCommand.PendingDtcs,
            ObdCommand.PermanentDtcs,
            ObdCommand.SupportedPids(0x00),
        ).map { it.request }

        // Mode 04 clears DTCs; mode 2F is an actuator control service. Neither may be constructible.
        assertTrue(requests.none { it.startsWith("04") })
        assertTrue(requests.none { it.startsWith("2F") })
        assertTrue(requests.none { it.startsWith("2E") })
        assertTrue(requests.none { it.startsWith("31") && it.length == 2 })
    }

    @Test
    fun `requests are formatted as two hex digit pairs`() {
        assertEquals("010D", ObdCommand.CurrentData(0x0D).request)
        assertEquals("01A6", ObdCommand.CurrentData(0xA6).request)
        assertEquals("03", ObdCommand.StoredDtcs.request)
        assertEquals("07", ObdCommand.PendingDtcs.request)
        assertEquals("0A", ObdCommand.PermanentDtcs.request)
        assertEquals("0100", ObdCommand.SupportedPids(0x00).request)
        assertEquals("0120", ObdCommand.SupportedPids(0x20).request)
    }
}
```

- [ ] **Step 2: Write the failing PID decoder test**

```kotlin
package com.csjotlab.cardashboard.vehicle.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdPidTest {

    @Test fun `speed is the raw byte in km per hour`() {
        assertEquals(60f, ObdPid.decodeSpeedKph(listOf(0x3C)))
        assertEquals(0f, ObdPid.decodeSpeedKph(listOf(0x00)))
    }

    @Test fun `rpm is a quarter of the sixteen bit value`() {
        // 0x1AF8 = 6904; 6904 / 4 = 1726
        assertEquals(1726, ObdPid.decodeRpm(listOf(0x1A, 0xF8)))
    }

    @Test fun `coolant temperature is offset by forty`() {
        assertEquals(91, ObdPid.decodeCoolantCelsius(listOf(0x83)))   // 131 - 40
        assertEquals(-40, ObdPid.decodeCoolantCelsius(listOf(0x00)))
    }

    @Test fun `fuel level is a percentage of two five five`() {
        assertEquals(100f, ObdPid.decodeFuelPercent(listOf(0xFF)))
        assertEquals(50f, ObdPid.decodeFuelPercent(listOf(0x80))!!, 0.5f)
    }

    @Test fun `monitor status splits the MIL bit from the code count`() {
        val on = ObdPid.decodeMonitorStatus(listOf(0x83, 0x00, 0x00, 0x00))!!
        assertTrue(on.milOn)
        assertEquals(3, on.dtcCount)

        val off = ObdPid.decodeMonitorStatus(listOf(0x03, 0x00, 0x00, 0x00))!!
        assertTrue(!off.milOn)
        assertEquals(3, off.dtcCount)
    }

    @Test fun `odometer is a thirty two bit value in tenths of a kilometre`() {
        // 0x0005DC10 = 384016 -> 38401.6 km
        assertEquals(38401.6, ObdPid.decodeOdometerKm(listOf(0x00, 0x05, 0xDC, 0x10))!!, 0.05)
    }

    @Test fun `short or empty frames decode to null rather than zero`() {
        assertNull(ObdPid.decodeRpm(listOf(0x1A)))
        assertNull(ObdPid.decodeSpeedKph(emptyList()))
        assertNull(ObdPid.decodeMonitorStatus(listOf(0x83)))
        assertNull(ObdPid.decodeOdometerKm(listOf(0x00, 0x05)))
    }
}
```

- [ ] **Step 3: Write the failing capability test**

```kotlin
package com.csjotlab.cardashboard.vehicle.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedPidSetTest {

    @Test
    fun `the top bit of the first byte means PID one is supported`() {
        val supported = SupportedPidSet.decode(0x00, listOf(0x80, 0x00, 0x00, 0x00))
        assertEquals(setOf(0x01), supported)
    }

    @Test
    fun `the bottom bit of the last byte means the next base PID is supported`() {
        val supported = SupportedPidSet.decode(0x00, listOf(0x00, 0x00, 0x00, 0x01))
        assertEquals(setOf(0x20), supported)
    }

    @Test
    fun `a realistic bitmask yields the expected PIDs`() {
        // BE1FA813: bit pattern chosen so that speed (0x0D) and RPM (0x0C) are both present.
        val supported = SupportedPidSet.decode(0x00, listOf(0xBE, 0x1F, 0xA8, 0x13))
        assertTrue(supported.contains(ObdPid.RPM))
        assertTrue(supported.contains(ObdPid.SPEED))
        assertTrue(supported.contains(ObdPid.COOLANT))
    }

    @Test
    fun `a malformed frame yields no capability at all`() {
        assertTrue(SupportedPidSet.decode(0x00, listOf(0xBE, 0x1F)).isEmpty())
        assertTrue(SupportedPidSet.decode(0x00, emptyList()).isEmpty())
    }

    @Test
    fun `the second bank offsets by thirty two`() {
        assertEquals(setOf(0x21), SupportedPidSet.decode(0x20, listOf(0x80, 0x00, 0x00, 0x00)))
    }
}
```

- [ ] **Step 4: Run all three to verify they fail**

Run: `./gradlew testDebugUnitTest --tests '*Obd*Test' --tests '*SupportedPidSetTest*'`
Expected: FAIL — unresolved references.

- [ ] **Step 5: Write `ObdCommand.kt`**

```kotlin
package com.csjotlab.cardashboard.vehicle.protocol

/**
 * Every OBD-II request this application is capable of making.
 *
 * The hierarchy is sealed and contains only read services. There is deliberately no way to express
 * mode 04 (clear DTCs), any UDS write or routine service, or a raw CAN frame — the type system, not
 * a code review, is what prevents it. See spec section 2 and ObdCommandTest.
 */
sealed interface ObdCommand {
    val request: String

    /** Service 01 — current data for one PID. */
    data class CurrentData(val pid: Int) : ObdCommand {
        override val request: String get() = "01%02X".format(pid)
    }

    /** Service 03 — stored DTCs. */
    data object StoredDtcs : ObdCommand {
        override val request: String get() = "03"
    }

    /** Service 07 — pending DTCs. */
    data object PendingDtcs : ObdCommand {
        override val request: String get() = "07"
    }

    /** Service 0A — permanent DTCs. */
    data object PermanentDtcs : ObdCommand {
        override val request: String get() = "0A"
    }

    /** Service 01, capability bitmask banks: 00, 20, 40, 60, 80, A0. */
    data class SupportedPids(val basePid: Int) : ObdCommand {
        override val request: String get() = "01%02X".format(basePid)
    }
}
```

- [ ] **Step 6: Write `ObdPid.kt`**

Every decoder returns null on a short frame — never a zero. That is what keeps a truncated response from becoming a plausible-looking reading.

```kotlin
package com.csjotlab.cardashboard.vehicle.protocol

data class MonitorStatus(val milOn: Boolean, val dtcCount: Int)

object ObdPid {
    const val MONITOR_STATUS = 0x01
    const val COOLANT = 0x05
    const val RPM = 0x0C
    const val SPEED = 0x0D
    const val RUN_TIME = 0x1F
    const val DISTANCE_WITH_MIL = 0x21
    const val FUEL_LEVEL = 0x2F
    const val DISTANCE_SINCE_CLEAR = 0x31
    const val AMBIENT_TEMP = 0x46
    const val ODOMETER = 0xA6

    fun decodeSpeedKph(data: List<Int>): Float? =
        data.getOrNull(0)?.let { (it and 0xFF).toFloat() }

    fun decodeRpm(data: List<Int>): Int? {
        if (data.size < 2) return null
        return (((data[0] and 0xFF) * 256) + (data[1] and 0xFF)) / 4
    }

    fun decodeCoolantCelsius(data: List<Int>): Int? =
        data.getOrNull(0)?.let { (it and 0xFF) - 40 }

    fun decodeFuelPercent(data: List<Int>): Float? =
        data.getOrNull(0)?.let { (100f / 255f) * (it and 0xFF) }

    fun decodeMonitorStatus(data: List<Int>): MonitorStatus? {
        if (data.size < 4) return null
        val a = data[0] and 0xFF
        return MonitorStatus(milOn = (a and 0x80) != 0, dtcCount = a and 0x7F)
    }

    fun decodeDistanceKm(data: List<Int>): Int? {
        if (data.size < 2) return null
        return ((data[0] and 0xFF) * 256) + (data[1] and 0xFF)
    }

    fun decodeOdometerKm(data: List<Int>): Double? {
        if (data.size < 4) return null
        val raw = ((data[0] and 0xFF).toLong() shl 24) or
            ((data[1] and 0xFF).toLong() shl 16) or
            ((data[2] and 0xFF).toLong() shl 8) or
            (data[3] and 0xFF).toLong()
        return raw / 10.0
    }
}
```

- [ ] **Step 7: Write `SupportedPidSet.kt`**

```kotlin
package com.csjotlab.cardashboard.vehicle.protocol

/**
 * Decodes a mode-01 capability bitmask. This is the authority on what a vehicle can report — see
 * spec section 2.1. An unreadable frame yields the empty set, never an assumed capability.
 */
object SupportedPidSet {

    fun decode(basePid: Int, data: List<Int>): Set<Int> {
        if (data.size < 4) return emptySet()

        val mask = ((data[0] and 0xFF).toLong() shl 24) or
            ((data[1] and 0xFF).toLong() shl 16) or
            ((data[2] and 0xFF).toLong() shl 8) or
            (data[3] and 0xFF).toLong()

        // Bit 31 is the first PID after the base; bit 0 is the 32nd.
        return (0 until 32)
            .filter { index -> ((mask shr (31 - index)) and 1L) == 1L }
            .map { index -> basePid + 1 + index }
            .toSet()
    }
}
```

- [ ] **Step 8: Run all three to verify they pass**

Run: `./gradlew testDebugUnitTest --tests '*Obd*Test' --tests '*SupportedPidSetTest*'`
Expected: PASS (16 tests). **Do not commit.**

---

### Task 16: `Elm327Session` — framing, handshake, error replies, timeouts

**Files:**
- Create: `app/src/main/java/com/csjotlab/cardashboard/vehicle/protocol/Elm327Session.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/vehicle/protocol/Elm327SessionTest.kt`

**Interfaces:**
- Consumes: `VehicleTransport` (Task 14), `ObdCommand`, `SupportedPidSet` (Task 15), `DtcDecoder` (Task 4)
- Produces:
  - `sealed interface ObdResult { data class Data(val bytes: List<Int>) ; data object NoData ; data class BusError(val reason: String) ; data class AdapterError(val reason: String) ; data object Timeout }`
  - `class Elm327Session(transport: VehicleTransport, commandTimeoutMs: Long = 1_000L)`
  - `suspend fun Elm327Session.handshake(): HandshakeResult` where `sealed interface HandshakeResult { data class Ready(val identity: AdapterIdentity, val protocol: String?, val supportedPids: Set<Int>) ; data class NotElmCompatible(val identity: String) ; data class BusUnavailable(val reason: String) }`
  - `suspend fun Elm327Session.request(command: ObdCommand): ObdResult`
  - `suspend fun Elm327Session.readDtcs(command: ObdCommand): List<String>`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.csjotlab.cardashboard.vehicle.protocol

import com.csjotlab.cardashboard.vehicle.fakes.FakeVehicleTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327SessionTest {

    private fun session(responses: Map<String, String>) =
        Elm327Session(FakeVehicleTransport(responses).also { }, commandTimeoutMs = 1_000L)

    private val handshakeResponses = mapOf(
        "ATZ" to "ELM327 v1.5",
        "ATE0" to "OK",
        "ATL0" to "OK",
        "ATS0" to "OK",
        "ATH0" to "OK",
        "ATSP0" to "OK",
        "ATI" to "ELM327 v1.5",
        "0100" to "41 00 BE 1F A8 13",
    )

    @Test
    fun `a successful handshake reports the adapter and its capabilities`() = runTest {
        val result = session(handshakeResponses).handshake()

        assertTrue(result is HandshakeResult.Ready)
        val ready = result as HandshakeResult.Ready
        assertTrue(ready.identity.elmCompatible)
        assertTrue(ready.supportedPids.contains(ObdPid.SPEED))
        assertTrue(ready.supportedPids.contains(ObdPid.RPM))
    }

    @Test
    fun `a device that is not an ELM327 is rejected rather than guessed at`() = runTest {
        val result = session(handshakeResponses + ("ATI" to "SOME USB WIDGET")).handshake()
        assertTrue(result is HandshakeResult.NotElmCompatible)
    }

    @Test
    fun `an adapter that cannot reach the bus reports bus unavailable`() = runTest {
        val result = session(handshakeResponses + ("0100" to "UNABLE TO CONNECT")).handshake()
        assertTrue(result is HandshakeResult.BusUnavailable)
    }

    @Test
    fun `a data reply is parsed into its payload bytes`() = runTest {
        val result = session(mapOf("010C" to "41 0C 1A F8")).request(ObdCommand.CurrentData(ObdPid.RPM))
        assertEquals(ObdResult.Data(listOf(0x1A, 0xF8)), result)
    }

    @Test
    fun `responses without spaces parse identically`() = runTest {
        val result = session(mapOf("010C" to "410C1AF8")).request(ObdCommand.CurrentData(ObdPid.RPM))
        assertEquals(ObdResult.Data(listOf(0x1A, 0xF8)), result)
    }

    @Test
    fun `the SEARCHING preamble is stripped`() = runTest {
        val result = session(mapOf("010D" to "SEARCHING...\r41 0D 3C")).request(ObdCommand.CurrentData(ObdPid.SPEED))
        assertEquals(ObdResult.Data(listOf(0x3C)), result)
    }

    @Test
    fun `every documented non data reply maps to a typed result`() = runTest {
        assertEquals(ObdResult.NoData, session(mapOf("010D" to "NO DATA")).request(ObdCommand.CurrentData(ObdPid.SPEED)))
        assertTrue(session(mapOf("010D" to "UNABLE TO CONNECT")).request(ObdCommand.CurrentData(ObdPid.SPEED)) is ObdResult.BusError)
        assertTrue(session(mapOf("010D" to "CAN ERROR")).request(ObdCommand.CurrentData(ObdPid.SPEED)) is ObdResult.BusError)
        assertTrue(session(mapOf("010D" to "BUS INIT: ERROR")).request(ObdCommand.CurrentData(ObdPid.SPEED)) is ObdResult.BusError)
        assertTrue(session(mapOf("010D" to "STOPPED")).request(ObdCommand.CurrentData(ObdPid.SPEED)) is ObdResult.AdapterError)
        assertTrue(session(mapOf("010D" to "?")).request(ObdCommand.CurrentData(ObdPid.SPEED)) is ObdResult.AdapterError)
        assertTrue(session(mapOf("010D" to "BUFFER FULL")).request(ObdCommand.CurrentData(ObdPid.SPEED)) is ObdResult.AdapterError)
    }

    @Test
    fun `a mismatched response header is not accepted as data`() = runTest {
        // Reply to a different PID than the one requested.
        val result = session(mapOf("010D" to "41 0C 1A F8")).request(ObdCommand.CurrentData(ObdPid.SPEED))
        assertTrue(result is ObdResult.AdapterError)
    }

    @Test
    fun `garbage is rejected rather than partially decoded`() = runTest {
        assertTrue(session(mapOf("010D" to "ZZZZ")).request(ObdCommand.CurrentData(ObdPid.SPEED)) is ObdResult.AdapterError)
    }

    @Test
    fun `mode 03 decodes two stored codes and drops the padding pair`() = runTest {
        // 43 02 = two codes; 0301 = P0301; 0420 = P0420; trailing 0000 is padding.
        val codes = session(mapOf("03" to "43 02 03 01 04 20 00 00")).readDtcs(ObdCommand.StoredDtcs)
        assertEquals(listOf("P0301", "P0420"), codes)
    }

    @Test
    fun `mode 03 with no codes returns an empty list`() = runTest {
        assertEquals(emptyList<String>(), session(mapOf("03" to "43 00")).readDtcs(ObdCommand.StoredDtcs))
        assertEquals(emptyList<String>(), session(mapOf("03" to "NO DATA")).readDtcs(ObdCommand.StoredDtcs))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*Elm327SessionTest*'`
Expected: FAIL — `Unresolved reference: Elm327Session`.

- [ ] **Step 3: Write the implementation**

Key behaviours the implementer must get right:

1. `write(command.request + "\r")`, then accumulate `incoming()` chunks into a `StringBuilder` until it contains `'>'`, bounded by `withTimeoutOrNull(commandTimeoutMs)`; a timeout yields `ObdResult.Timeout`.
2. Strip the prompt, `\r`, `\n`, `SEARCHING...`, and all spaces before parsing.
3. Match the non-data replies **before** attempting hex parsing, case-insensitively: `NO DATA` → `NoData`; `UNABLE TO CONNECT`, `CAN ERROR`, `BUS INIT` → `BusError`; `STOPPED`, `?`, `BUFFER FULL` → `AdapterError`.
4. For `CurrentData(pid)`, require the reply to start with `"41" + "%02X".format(pid)`; anything else is `AdapterError`. This is what makes rule 1 of spec §2.1 structural — a response can only populate the field it was requested for.
5. Any non-hex character in the remainder → `AdapterError`.
6. `handshake()` issues `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH0`, `ATSP0`, `ATI`, then `SupportedPids(0x00)`. `ATI` not containing `ELM327` → `NotElmCompatible`. A `BusError` or `NoData` on `0100` → `BusUnavailable`. Otherwise walk the banks `0x00, 0x20, 0x40, 0x60, 0x80, 0xA0`, querying the next bank only when the previous bank's set contains it, and union the results.
7. `readDtcs` sends the command, expects the mode-plus-0x40 header (`43`, `47`, `4A`), reads the count byte where present, then decodes successive byte pairs via `DtcDecoder.decodePair`, dropping nulls.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*Elm327SessionTest*'`
Expected: PASS (11 tests). **Do not commit.**

---

### Task 17: `ObdVehicleDataSource` — capability discovery is authoritative

**Files:**
- Create: `app/src/main/java/com/csjotlab/cardashboard/vehicle/source/ObdVehicleDataSource.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/vehicle/source/ObdVehicleDataSourceTest.kt`

**Interfaces:**
- Consumes: `Elm327Session`, `ObdCommand`, `ObdPid`, `DtcDecoder` (Tasks 4, 15, 16), `VehicleTransport` (Task 14), `VehicleDataSource` (Task 6)
- Produces: `class ObdVehicleDataSource(transport: VehicleTransport, clock: Clock, fastIntervalMs: Long = 500L, slowIntervalMs: Long = 5_000L, diagnosticsIntervalMs: Long = 15_000L) : VehicleDataSource`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.csjotlab.cardashboard.vehicle.source

import app.cash.turbine.test
import com.csjotlab.cardashboard.vehicle.domain.Clock
import com.csjotlab.cardashboard.vehicle.domain.Signal
import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull
import com.csjotlab.cardashboard.vehicle.fakes.FakeVehicleTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObdVehicleDataSourceTest {

    private val handshake = mapOf(
        "ATZ" to "ELM327 v1.5", "ATE0" to "OK", "ATL0" to "OK", "ATS0" to "OK",
        "ATH0" to "OK", "ATSP0" to "OK", "ATI" to "ELM327 v1.5",
        // Speed, RPM, coolant and fuel supported; nothing in later banks.
        "0100" to "41 00 BE 1F A8 10",
        "0120" to "41 20 00 00 00 00",
    )

    private fun source(extra: Map<String, String>) =
        ObdVehicleDataSource(FakeVehicleTransport(handshake + extra), Clock { 1_000L })

    @Test
    fun `supported PIDs become values`() = runTest {
        val source = source(mapOf("010D" to "41 0D 3C", "010C" to "41 0C 1A F8", "0105" to "41 05 83", "012F" to "41 2F 9E"))
        source.start()
        source.vehicleState.test {
            var state = awaitItem()
            while (state.speedKph.valueOrNull() == null) state = awaitItem()

            assertEquals(60f, state.speedKph.valueOrNull())
            assertEquals(1726, state.engineRpm.valueOrNull())
            assertEquals(91, state.coolantTemperatureCelsius.valueOrNull())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `PIDs absent from the capability bitmask are Unsupported not Unknown`() = runTest {
        val source = source(mapOf("010D" to "41 0D 3C"))
        source.start()
        source.vehicleState.test {
            var state = awaitItem()
            while (state.speedKph.valueOrNull() == null) state = awaitItem()

            // A6 is not in the bitmask, so the odometer is a capability fact, not a pending read.
            assertEquals(Signal.Unsupported, state.odometerKm)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `fields with no generic PID at all are always Unsupported`() = runTest {
        val source = source(mapOf("010D" to "41 0D 3C"))
        source.start()
        source.vehicleState.test {
            var state = awaitItem()
            while (state.speedKph.valueOrNull() == null) state = awaitItem()

            assertEquals(Signal.Unsupported, state.gear)
            assertEquals(Signal.Unsupported, state.tirePressuresKpa)
            assertEquals(Signal.Unsupported, state.doors)
            assertEquals(Signal.Unsupported, state.seatbelts)
            assertEquals(Signal.Unsupported, state.estimatedRangeKm)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a source with no capability at all reports no values whatsoever`() = runTest {
        val source = ObdVehicleDataSource(
            FakeVehicleTransport(handshake + ("0100" to "41 00 00 00 00 00")),
            Clock { 1_000L },
        )
        source.start()
        source.vehicleState.test {
            advanceTimeBy(2_000L)
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertTrue(
                "capability discovery is authoritative — nothing may be inferred",
                com.csjotlab.cardashboard.vehicle.domain.allSignals(state).none { it.isValue }
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `handshake failure reports communication unavailable and no values`() = runTest {
        val source = source(mapOf("0100" to "UNABLE TO CONNECT"))
        source.start()
        source.connectionState.test {
            var state = awaitItem()
            while (state !is VehicleConnectionState.VehicleCommunicationUnavailable) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a non ELM device reports unsupported device`() = runTest {
        val source = source(mapOf("ATI" to "SOME USB WIDGET"))
        source.start()
        source.connectionState.test {
            var state = awaitItem()
            while (state !is VehicleConnectionState.UnsupportedDevice) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stored DTCs become diagnostic issues with structural classification only`() = runTest {
        val source = source(mapOf("010D" to "41 0D 3C", "0101" to "41 01 83 00 00 00", "03" to "43 01 03 01"))
        source.start()
        source.diagnostics.test {
            var diagnostics = awaitItem()
            while (diagnostics.issues.isEmpty()) diagnostics = awaitItem()

            val issue = diagnostics.issues.first()
            assertEquals("P0301", (issue.code as com.csjotlab.cardashboard.vehicle.domain.DiagnosticCode.Dtc).code)
            assertEquals(null, issue.title)
            assertEquals(null, issue.description)
            assertEquals("Ignition system or misfire", issue.classification?.subsystem)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a code missing from a completed rescan is retired`() = runTest {
        val transport = FakeVehicleTransport(handshake + mapOf("010D" to "41 0D 3C", "0101" to "41 01 83 00 00 00", "03" to "43 01 03 01"))
        val source = ObdVehicleDataSource(transport, Clock { 1_000L }, diagnosticsIntervalMs = 1_000L)
        source.start()
        source.diagnostics.test {
            var diagnostics = awaitItem()
            while (diagnostics.issues.isEmpty()) diagnostics = awaitItem()

            transport.respondTo("03", "43 00")
            advanceTimeBy(2_000L)
            advanceUntilIdle()

            assertTrue(expectMostRecentItem().issues.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a failed rescan does not retire existing issues`() = runTest {
        val transport = FakeVehicleTransport(handshake + mapOf("010D" to "41 0D 3C", "0101" to "41 01 83 00 00 00", "03" to "43 01 03 01"))
        val source = ObdVehicleDataSource(transport, Clock { 1_000L }, diagnosticsIntervalMs = 1_000L)
        source.start()
        source.diagnostics.test {
            var diagnostics = awaitItem()
            while (diagnostics.issues.isEmpty()) diagnostics = awaitItem()

            transport.respondTo("03", "CAN ERROR")
            advanceTimeBy(2_000L)
            advanceUntilIdle()

            assertTrue(
                "a failed scan is not evidence that a fault cleared",
                expectMostRecentItem().issues.isNotEmpty()
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

Add `fun allSignals(state: VehicleState)` as a plain function alias in the domain, or use the existing `state.allSignals` extension — prefer the extension and adjust the test import accordingly.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*ObdVehicleDataSourceTest*'`
Expected: FAIL — `Unresolved reference: ObdVehicleDataSource`.

- [ ] **Step 3: Write the implementation**

Structure:

1. `start()` opens the transport, runs `handshake()`, and maps the result to `connectionState`: `Ready` → `Connected` then `Reading`; `NotElmCompatible` → `UnsupportedDevice`; `BusUnavailable` → `VehicleCommunicationUnavailable`.
2. Hold `supportedPids: Set<Int>` from the handshake. Build the initial `VehicleState` by mapping **each field independently**:
   - `speedKph` = `if (SPEED in supportedPids) Unknown else Unsupported`, likewise `engineRpm`, `coolantTemperatureCelsius`, `fuelLevelPercent`, `odometerKm`, `tripDistanceKm`, `malfunctionIndicatorLampOn`;
   - `gear`, `seatbelts`, `doors`, `tirePressuresKpa`, `estimatedRangeKm` = `Unsupported` unconditionally — no generic J1979 PID exists, so this source cannot supply them.
3. Three independent polling loops in the source's scope, each skipping PIDs absent from `supportedPids`: fast (`SPEED`, `RPM`) at `fastIntervalMs`; slow (`COOLANT`, `FUEL_LEVEL`, `ODOMETER`, `DISTANCE_SINCE_CLEAR`) at `slowIntervalMs`; diagnostics (`MONITOR_STATUS`, then `StoredDtcs`, `PendingDtcs`, `PermanentDtcs`) at `diagnosticsIntervalMs`.
4. A response updates **only** the field it was requested for. `ObdResult.NoData`, `Timeout` and errors leave the previous value untouched — they never write a zero.
5. Diagnostics reconciliation: a scan is *completed* only when the `MONITOR_STATUS` request and every DTC service request returned `Data` or `NoData`. On a completed scan, issues absent from the result are removed and `firstSeenMs` is preserved for issues that persist. On a failed scan, the previous issue list is retained unchanged.
6. `stop()` cancels the loops and closes the transport. A `TransportEvent.Detached` emits `ConnectionLost("USB device detached")`; `TransportEvent.Failed` emits `Error`.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*ObdVehicleDataSourceTest*'`
Expected: PASS (9 tests).

- [ ] **Step 5: Run the full unit suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS — Tasks 0–17. The entire protocol stack is now verified without any hardware. **Do not commit.**

---

### Task 18: 🚫 GATED — `UsbSerialTransport`

> **Do not start this task.** It is blocked on an explicit hardware/protocol dependency, per the standing instruction not to begin large USB-specific implementation until that dependency is resolved. Everything above ships and is fully tested without it.

**Preconditions — every one must be answered before a single line is written:**

- [ ] Exact adapter make and model
- [ ] Its USB chipset: FTDI / CH340 / CP210x / Prolific / CDC-ACM
- [ ] Whether it is a genuine ELM327 or a clone, and which ELM327 version it reports to `ATI`
- [ ] Target vehicle make, model and year
- [ ] That vehicle's OBD-II transport: ISO 15765-4 CAN / ISO 9141-2 / ISO 14230 KWP2000 / SAE J1850
- [ ] Whether the head unit's USB port supports host mode and supplies adequate bus power

**Why the gate is real, not ceremony:** `usb-serial-for-android` selects a driver by chipset, and baud rate, flow control and latency tuning all differ per adapter. Writing that layer against a guess produces code that compiles, passes nothing meaningful, and misleads the next reader into thinking USB support exists.

**When unblocked, the work is:**

**Files:**
- Modify: `settings.gradle.kts` — add `maven { url = uri("https://jitpack.io") }` to `dependencyResolutionManagement.repositories` (the library is not on Maven Central)
- Modify: `app/build.gradle.kts` — add `implementation("com.github.mik3y:usb-serial-for-android:3.8.1")`
- Modify: `app/src/main/AndroidManifest.xml` — add `<uses-feature android:name="android.hardware.usb.host" android:required="false" />`. No `<uses-permission>` is needed for USB host access.
- Create: `app/src/main/java/com/csjotlab/cardashboard/vehicle/transport/UsbSerialTransport.kt`
- Create: `app/src/main/java/com/csjotlab/cardashboard/vehicle/transport/UsbDeviceScanner.kt`
- Create: `app/src/main/java/com/csjotlab/cardashboard/vehicle/transport/SupportedAdapters.kt`
- Modify: `app/src/main/java/com/csjotlab/cardashboard/di/VehicleContainer.kt` — feed `realSourceAvailability` from `UsbDeviceScanner`

**Interfaces:**
- Consumes: `VehicleTransport`, `TransportEvent` (Task 14); `UsbDeviceDescriptor` (Task 5)
- Produces:
  - `class UsbSerialTransport(context: Context, device: UsbDevice, baudRate: Int) : VehicleTransport`
  - `class UsbDeviceScanner(context: Context)` with `val attachedAdapters: Flow<UsbDeviceDescriptor?>`, `suspend fun requestPermission(device): Boolean`
  - `object SupportedAdapters { fun describe(device: UsbDevice): UsbDeviceDescriptor }` — chipset resolved via the library's prober, **never a hardcoded vendor/product ID as the sole match path**

**Requirements when implemented:**
- `ACTION_USB_DEVICE_ATTACHED`/`DETACHED` via a **runtime-registered** receiver, not a manifest `intent-filter` — a manifest filter would launch a second activity instance and fight the existing single-activity setup.
- Permission via `UsbManager.requestPermission` with a mutable `PendingIntent`; denial maps to `PermissionDenied`, which `isTerminalFailure` already treats as non-retryable.
- Reconnect with bounded exponential backoff, 1 s → 8 s capped, attempted only while a device is present.
- A device with no matching driver maps to `UnsupportedDevice`, never to a silent failure.
- Instrumented tests can cover the scanner's state machine with a fake `UsbManager`; **the byte path itself cannot be verified without the adapter** and must be reported as such.

---

### Task 19: Developer documentation

**Files:**
- Create: `docs/vehicle-data-architecture.md`

**Interfaces:**
- Consumes: everything above
- Produces: no code

- [ ] **Step 1: Write the document**

It must cover, with no placeholders:

1. The layer diagram from spec §3, and why the transport/protocol split exists.
2. `Signal` semantics: `Value` vs `Unknown` vs `Unsupported`, and that `Unsupported` is scoped to the active source rather than to the vehicle.
3. Capability discovery as the authority (spec §2.1), with the list of prohibited inferences.
4. Mock mode: what it is, that it is debug-only and never auto-selected, how to enable it, and the banner.
5. The real source: ELM327 command set, the polling cadence table, and the read-only service list.
6. USB hardware requirement: the OBD-II topology diagram, why a bare USB cable provides nothing, supported chipsets.
7. Permissions: none for USB host; `<uses-feature>` only. AAOS property/permission mapping from spec §1.2, for a future flavour.
8. How to test without a car: `FakeVehicleTransport` for the protocol, `MockVehicleDataSource` for domain signals, and which belongs where.
9. How to test with an adapter, once Task 18's preconditions are answered.
10. Known limitations: the availability matrix from spec §1.4; gear, tyre pressure, doors, seatbelts, range and usually odometer are unavailable from generic J1979 and would need documented per-make enhanced-PID/UDS specifications.
11. Verification status: which layers are device-verified and which are hardware-pending.

- [ ] **Step 2: Cross-check against the spec**

Confirm every claim matches the spec sections cited. Contradictions between the spec and this document are bugs.

- [ ] **Step 3: Final verification sweep**

Run, in order:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew installDebug
./gradlew connectedDebugAndroidTest
```

Then on the Pixel 7a, with mock mode enabled, confirm: portrait, landscape, rotation preserving drive mode, all three drive modes, the simulated connect → telemetry → door → tyre → DTC → disconnect sequence, diagnostic appearance and retirement, and multiple simultaneous warnings.

Capture logcat during the run:

```bash
adb logcat -c && adb logcat -v time | grep -Ei "CarDash|AndroidRuntime|Compose|coroutine"
```

Check for crashes, USB exceptions, coroutine errors, Compose errors and state errors. **Do not commit. Do not push.**

---

## Verification Status Policy

Every claim in the final report must be labelled:

- **Verified on device** — Pixel 7a, Android 16, actually executed
- **Implemented, hardware verification pending** — compiles and passes tests, but no adapter or vehicle was present

No claim of real-vehicle verification may be made. No vehicle or OBD-II adapter is available in this environment, so the byte path in Task 18 is unverifiable here by definition.

## Plan Self-Review

**Spec coverage.** Each spec section maps to a task: §1 investigation → Task 19 docs; §2 safety boundary → Task 15 (sealed `ObdCommand` + test); §2.1 data integrity → Tasks 9, 17; §3 architecture → Tasks 6–9; §3.1 DI → Task 10; §3.2 staleness → Task 8; §4.1 `Signal` → Task 1; §4.2 `VehicleState` → Task 2; §4.3 diagnostics + severity → Tasks 3–4; §4.4 connection state → Task 5; §5 transport/protocol → Tasks 14–16; §5.1 USB → Task 18 (gated); §6 source selection → Task 8; §7 UI → Tasks 9–12; §7.1 honest rendering → Task 9 formatter + Task 13 UI tests; §7.2 health panel → Task 11; §7.3 detail screen → Task 12; §7.4 banner → Task 11; §8 logging → **gap, see below**; §9 testing → Tasks 13, 17; §10 docs → Task 19.

**Gap found and closed:** spec §8 (`VehicleLog`, rate-limited lifecycle logging, no per-sample logs, no serial numbers) had no task. Fold it into Task 17 Step 3 as an additional requirement: create `vehicle/data/VehicleLog.kt` with tag `CarDash/Vehicle`, log only source selection, attach/detach, permission result, connection established/lost, unsupported device, protocol error and diagnostics scan results; rate-limit any telemetry summary to once per 10 s; never log USB serial numbers. Add a unit test asserting `VehicleLog` emits nothing for repeated telemetry within the window.

**Type consistency check.** `VehicleState.tirePressuresKpa` (not `tirePressures`) is used consistently from Task 2 onward, including the spec's §4.2 sketch which named it `tirePressures` — the plan's name wins, and Task 19 should note it. `DashboardScreen` takes `onDriveModeLabelChanged` in Tasks 10–13 consistently. `VehicleRepository`'s `staleTimeoutMs` parameter is introduced in Task 8 and used in Task 8's tests only; Task 7's tests use the three-argument form, which remains valid because the parameter has a default.

**Known rough edge for the implementer:** Task 6's `MockVehicleDataSource` sketch carries a vestigial `frames` `MutableStateFlow` and two unused imports from an earlier shape; Step 4's note says to delete them. Do that rather than leaving dead state.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-11-real-vehicle-data-and-usb-diagnostics.md`.

