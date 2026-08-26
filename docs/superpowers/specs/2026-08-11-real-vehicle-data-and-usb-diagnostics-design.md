# Real Vehicle Data & USB Diagnostics — Design

Date: 2026-08-11
Status: Approved (design), pending implementation plan
Scope: replace the mock dashboard data with a layered vehicle-data architecture, add read-only
OBD-II diagnostics over USB, and keep the dashboard honest when data is unavailable.

---

## 1. Investigation findings

### 1.1 Existing repository

Single Gradle module (`:app`), Jetpack Compose, `minSdk 24`, `compileSdk`/`targetSdk 34`,
Kotlin 1.9.24, AGP 8.4.2. 940 lines of Kotlin across 9 files.

There is **no** ViewModel, repository, domain model, dependency-injection container, or test of any
kind. `app/build.gradle.kts` declares `testInstrumentationRunner` but no test dependencies, and
neither `src/test` nor `src/androidTest` exists.

Every displayed value originates inside `ui/dashboard/DashboardScreen.kt`:

| Value | Origin | Line |
| --- | --- | --- |
| Speed | `mockSpeedSequence` driven by a `while(true) { delay() }` loop **inside the `SpeedPanel` composable** | 110, 272–281 |
| RPM, Fuel, Gear, Temp | `mockMetrics(mode)` — pre-formatted strings, including `"Range 420 km"` | 82–87 |
| Warnings | `mockWarningScenarios`, rotated by a `LaunchedEffect` in `DashboardScreen` every 3.2 s | 89–127 |
| Trip, Range | inline string literals in `SpeedPanel` | 312–313 |
| Odometer, Trip A | inline string literals in `OdometerPanel` | 500–501 |

`DashboardMetric`, `DashboardWarningState` and `DriveMode` are `private` to that file and already
hold display-formatted strings, so they are presentation types rather than a domain model.

Layout behaviour that must not regress (each is a deliberate, commented fix):

- portrait `SpeedPanel` uses a fixed `height(360.dp)`, because a `weight(1f)` child of an unbounded
  `Column` inside `verticalScroll` measures to zero;
- `SpeedGauge` is sized `minOf(maxWidth, maxHeight, 260.dp)` to stay circular;
- `windowInsetsPadding(WindowInsets.systemBars)` on the root;
- landscape chooses `compact` below `maxHeight < 480.dp`, and the wide layout requires
  `maxWidth >= 720.dp && maxHeight >= 300.dp`;
- `MetricGrid` only takes `weight(1f)` when `fillHeight` is set;
- `MetricTile` drops its `heightIn(min = 128.dp)` floor when compact;
- `WarningPanel` uses `SpaceEvenly` in a weighted column when compact, so four rows fit;
- `DriveMode` selection is held in `rememberSaveable` so rotation preserves it;
- the Gear helper reads off the selected `DriveMode`, so it must be rebuilt per mode.

### 1.2 Android Automotive OS (AAOS)

AAOS exposes `android.car.CarPropertyManager`. Access divides sharply by permission protection
level. Permissions at `dangerous` are runtime-grantable to ordinary third-party apps; everything
else is `signature|privileged` and reachable only by apps signed with the platform key or shipped
in the system image by the OEM.

Obtainable by a third-party app:

| Property | Permission |
| --- | --- |
| `PERF_VEHICLE_SPEED`, `PERF_VEHICLE_SPEED_DISPLAY` | `android.car.permission.CAR_SPEED` |
| `FUEL_LEVEL`, `FUEL_LEVEL_LOW`, `EV_BATTERY_LEVEL`, `RANGE_REMAINING` | `android.car.permission.CAR_ENERGY` |
| `GEAR_SELECTION`, `CURRENT_GEAR`, `PARKING_BRAKE_ON` | `android.car.permission.CAR_POWERTRAIN` |
| `ENV_OUTSIDE_TEMPERATURE` | `android.car.permission.CAR_EXTERIOR_ENVIRONMENT` |
| `INFO_MAKE`, `INFO_MODEL`, `INFO_MODEL_YEAR` | `android.car.permission.CAR_INFO` |
| fuel-port / charge-port info | `android.car.permission.CAR_ENERGY_PORTS` |
| display units | `android.car.permission.READ_CAR_DISPLAY_UNITS` |

**Not** obtainable by a third-party app:

| Property | Permission | Level |
| --- | --- | --- |
| `ENGINE_RPM`, `ENGINE_COOLANT_TEMP`, `ENGINE_OIL_TEMP` | `CAR_ENGINE_DETAILED` | signature\|privileged |
| `PERF_ODOMETER` | `CAR_MILEAGE` | signature\|privileged |
| `TIRE_PRESSURE` | `CAR_TIRES` | signature\|privileged |
| `DOOR_POS` / door open state | `READ_CAR_DOOR_POS` / `CONTROL_CAR_DOORS` | signature\|privileged |
| `SEAT_BELT_BUCKLED` | `CONTROL_CAR_SEATS` | signature\|privileged |

There is **no DTC / diagnostic-trouble-code property in the AAOS third-party API at all**. AAOS
diagnostics (`CarDiagnosticManager`) is a system-only surface.

Conclusion: AAOS could supply speed, fuel, range, gear and outside temperature to this app. RPM,
coolant temperature, odometer, tyre pressure, doors, seatbelts and DTCs would each require OEM
signing or a privileged system placement.

### 1.3 Android Auto — a limited subset, not a primary source

Android Auto is **screen projection**. The app runs on the phone; the head unit is a display and
input surface. `MainActivity` never executes on the vehicle, and `CarPropertyManager` does not exist
on Android Auto.

Android Auto *can* expose a limited subset of vehicle data, through the Car Hardware APIs in the Car
App Library (`androidx.car.app:app-projected`). The `CarInfo` surface is usable from a
`CarAppService` template app in a Google-approved app category, gated behind
`com.google.android.gms.permission.CAR_*` permissions, and provides:

| Available over Android Auto | Permission |
| --- | --- |
| make, model, year | `CAR_INFO` |
| energy profile (fuel types, EV connector types) | `CAR_FUEL` |
| energy level — fuel %, range remaining, fuel-low | `CAR_FUEL` |
| speed (raw and cluster-display) | `CAR_SPEED` |
| mileage / odometer | `CAR_MILEAGE` |
| toll-card state | (as energy level) |

`CarSensors` additionally provides accelerometer, gyroscope, compass and location.

What it does not provide: Google's Car Hardware API documentation explicitly lists **RPM, coolant
temperature, gear selection, tyre pressure, door status and seatbelt status as not exposed**, and
there is no DTC or diagnostics surface at all.

Conclusion: Android Auto is a real but partial source — roughly speed, fuel, range and odometer. It
cannot satisfy the complete dashboard and diagnostics requirement, and adopting it would also mean
rebuilding the UI as Car App Library templates rather than the existing Compose dashboard. It is
therefore **not suitable as the primary data source** for this feature. It remains a plausible
future `VehicleDataSource` implementation for the subset above, which is one reason the source
abstraction is kept transport-agnostic.

### 1.4 USB — what the hardware actually has to be

A USB cable by itself carries no vehicle data. A car's USB sockets are media/charging ports on the
infotainment bus; they do not bridge to the powertrain CAN bus.

Vehicle data is reachable at the **OBD-II / SAE J1962 connector**, mandatory on petrol cars sold in
the US since 1996 and the EU since 2001 (diesel: EU 2004). Reaching it requires a diagnostic adapter
that speaks the vehicle's transport (almost always ISO 15765-4 CAN on modern cars; older cars use
ISO 9141-2, ISO 14230 KWP2000 or SAE J1850) and re-exposes it as a serial stream. The de-facto
standard is the **ELM327 command set**, and USB variants of these adapters present as USB-serial
devices using FTDI, CH340, CP210x, Prolific or USB CDC-ACM chipsets.

Required topology:

```
CAR  ──(OBD-II / SAE J1962 port)──  ELM327-class USB diagnostic adapter
                                              │  USB-serial (FTDI / CH340 / CP210x / CDC-ACM)
                                              ▼
                            Android head unit or phone in USB host (OTG) mode
                                              ▼
                                      Car Dashboard app
```

Android reads this through USB host mode (`android.hardware.usb.host`, available since API 12).
Android ships no kernel-side USB-serial driver exposed to apps, so a userspace driver is required;
`usb-serial-for-android` (MIT, mik3y) covers all of the chipsets above.

What standard SAE J1979 / ISO 15031-5 actually provides:

| Data | Access | Availability |
| --- | --- | --- |
| Vehicle speed | Mode 01, PID `0D` | universal |
| Engine RPM | Mode 01, PID `0C` | universal |
| Engine coolant temperature | Mode 01, PID `05` | universal |
| Fuel tank level (%) | Mode 01, PID `2F` | common, not guaranteed |
| MIL state + stored-DTC count | Mode 01, PID `01` | universal |
| Stored / pending / permanent DTCs | Modes `03` / `07` / `0A` | universal (`0A` on newer ECUs) |
| Run time since start | Mode 01, PID `1F` | common |
| Distance travelled with MIL on | Mode 01, PID `21` | common |
| Distance since codes cleared | Mode 01, PID `31` | common — **not a resettable trip meter** |
| Ambient air temperature | Mode 01, PID `46` | common |
| Total odometer | Mode 01, PID `A6` | standardised late; rarely supported |
| **Gear selection** | no generic PID | **unavailable from generic J1979** — carried on manufacturer-specific UDS / enhanced PIDs |
| **Tyre pressure** | no generic PID | **unavailable from generic J1979** — TPMS module, manufacturer-specific |
| **Door / seatbelt state** | no generic PID | **unavailable from generic J1979** — body CAN, manufacturer-specific |
| **Estimated range** | no generic PID | **unavailable from generic J1979** — cluster-computed, manufacturer-specific |

The distinction matters and the wording is deliberate. These properties are *unavailable from the
generic J1979 source*, not universally impossible to obtain. Many vehicles do expose them over
manufacturer-specific enhanced PIDs or UDS (ISO 14229) services, and the same ELM327-class adapter
can generally carry those requests. What is missing is a **documented, per-make specification**,
which this design does not assume access to.

The architecture must therefore leave room for that: an enhanced/OEM protocol implementation is a
future addition *behind the same `VehicleDataSource` interface*, and the domain model must express
"this source cannot provide it" as a property of the **active source**, not as a permanent fact
about the vehicle. Concretely, `Signal.Unsupported` means "the currently selected source cannot
supply this", and a future OEM source may return `Signal.Value` for the very same field.

This is the central constraint of the whole design: **no single source supplies the full metric
list**, so the domain model must distinguish "not yet known" from "this source cannot provide it".

---

## 2. Safety boundary

The application is **read-only**. It issues only:

- ELM327 `AT` configuration commands to the adapter (not to the vehicle);
- OBD-II service `01` (current data), `03`, `07`, `0A` (read DTCs), `09` PID `00`/`06` capability
  probes.

It must never implement, and the code must contain no path toward: mode `04` (clear DTCs), any UDS
write/routine/session-control service, arbitrary CAN transmission, ECU coding or flashing, or any
actuation of throttle, brakes, steering, gear, doors, locks or safety systems.

The command layer enforces this structurally: `ObdCommand` is a **sealed** hierarchy whose only
subtypes are the read services above, so an unlisted service cannot be constructed. A unit test
asserts the sealed set, so widening it requires deliberately editing that test.

Additionally, the app does **not** read the VIN (mode `09` PID `02`). It has no product need for a
unique vehicle identifier, and not reading it removes the question of storing or logging one.

### 2.1 Data-integrity boundary — capability discovery is authoritative

A second boundary sits alongside the safety one, and is equally binding.

**Capability discovery is the sole authority on what a source can provide.** For
`ObdVehicleDataSource` that is the mode-01 support bitmasks (PIDs `00`, `20`, `40`, `60`, `80`,
`A0`) plus the set of services the adapter answered during handshake. A field is populated **only**
when its own discovered capability says it can be, and the value came from the response to that
field's own request.

From this the following are prohibited, without exception:

1. **No inference across signals.** Gear must never be derived from speed and RPM ratios. Range must
   never be computed from fuel level and an assumed consumption figure. Odometer must never be
   accumulated from integrated speed. Trip distance must never be substituted from PID `31`
   (distance since codes cleared), which is not a resettable trip meter. Tyre pressure, door state
   and seatbelt state have no generic signal to infer from and must never be synthesised.
2. **No inferred diagnosis.** A DTC's identity is the code. The app must not conclude which
   component has failed, and must not promote an unrelated signal (a temperature reading, a fuel
   trim, a misfire counter) into a named fault. Only §4.3 structural decoding is permitted.
3. **No placeholder standing in for a reading.** A field whose capability is unknown or absent
   renders as unavailable (`—`), never as `0`, `N/A`-styled-as-data, a last-known value, or a
   neighbouring source's value.
4. **Discovery failure is not capability.** If capability discovery itself fails, every affected
   field is `Signal.Unknown` — not `Unsupported`, and certainly not defaulted.

Rule 1 is enforced structurally rather than by convention: mapping from protocol responses to
`VehicleState` is one-to-one per field, so a `VehicleState` field can only be written by the decoder
for its own PID. Unit tests assert that a source with an empty `SupportedPidSet` emits a
`VehicleState` containing no `Signal.Value` at all, and that gear, tyre, door, seatbelt, odometer
and range remain non-`Value` when only speed, RPM, coolant and fuel are supported.

---

## 3. Architecture

```
CAR
 │  OBD-II / SAE J1962
 ▼
ELM327-class USB adapter          (read-only diagnostic interface)
 │  USB-serial
 ▼
UsbSerialTransport  ──────────────  FakeVehicleTransport      : VehicleTransport
 │  bytes
 ▼
Elm327Session → ObdResponseParser → DtcDecoder                : pure Kotlin, no Android imports
 │  domain objects
 ▼
ObdVehicleDataSource  ────────────  MockVehicleDataSource     : VehicleDataSource
 │  Flow<VehicleState>, Flow<VehicleDiagnosticsState>
 ▼
VehicleRepository                 (source selection, dedup, staleness, disconnect clearing)
 │
 ▼
DashboardViewModel                (StateFlow<DashboardUiState>)
 │
 ▼
DashboardScreen · VehicleHealthPanel · DiagnosticDetailScreen  (Compose, no side effects)
```

Package layout under `com.csjotlab.cardashboard.vehicle`:

| Package | Contents | Android deps |
| --- | --- | --- |
| `domain` | `Signal`, `VehicleState`, `VehicleDiagnosticsState`, `DiagnosticIssue`, `VehicleConnectionState`, position enums | none |
| `protocol` | `ObdCommand`, `ObdPid`, `Elm327Session`, `ObdResponseParser`, `DtcDecoder`, `SupportedPidSet` | none |
| `transport` | `VehicleTransport`, `TransportEvent`, `UsbSerialTransport`, `UsbDeviceDescriptor` | yes (USB only) |
| `source` | `VehicleDataSource`, `ObdVehicleDataSource`, `MockVehicleDataSource`, `VehicleSourceSelector` | minimal |
| `data` | `VehicleRepository`, `VehicleLog` | minimal |

`domain` and `protocol` contain no Android imports and are covered by JVM unit tests. The rule is
self-enforcing in practice: their tests run on the JVM against the stubbed `android.jar`, where any
call into e.g. `android.util.Log` throws `Method not mocked`, so an accidental Android dependency
fails the test rather than passing silently. Test fakes (`FakeVehicleTransport`, scripted mock
timelines) live in `src/test` and `src/androidTest`.

### 3.1 Dependency injection

A hand-rolled `VehicleContainer` held by a custom `CarDashboardApplication`, plus a
`ViewModelProvider.Factory`. No Hilt: the app is small, and adding kapt/ksp for one graph is not
justified. The container is constructor-injectable in tests.

### 3.2 Staleness

The repository treats staleness at the connection level, not per field, to avoid values flickering
in and out. While `Reading`, if no successful PID response arrives for 3 s the connection moves to
`VehicleCommunicationUnavailable` and telemetry is cleared to `Unknown` in one step. Individual
`Signal.Value` timestamps are retained for display (`lastUpdatedMs`) but are not independently aged.

---

## 4. Domain model

### 4.1 Signal — the mechanism that keeps unknown values unknown

```kotlin
sealed interface Signal<out T> {
    data class Value<T>(val value: T, val timestampMs: Long) : Signal<T>
    /** Source may provide this, but no reading has arrived yet. */
    data object Unknown : Signal<Nothing>
    /** Source has determined it cannot provide this at all. */
    data object Unsupported : Signal<Nothing>
}
```

Every `VehicleState` field is a `Signal`. No field has a numeric default, so a value can only exist
because a source reported it.

`Unsupported` is populated from discovered capability, per the authoritative rule in §2.1: at
connect time `ObdVehicleDataSource` reads the mode-01 support bitmasks into a `SupportedPidSet`; a
PID absent from that set maps to `Unsupported`, and fields with no generic J1979 PID (gear, tyres,
doors, belts, range) are `Unsupported` for *this source*. As §1.4 notes, `Unsupported` is scoped to
the active source, not a claim about the vehicle — a future OEM/enhanced source may return
`Signal.Value` for the same field. If discovery itself fails, affected fields are `Unknown`.

### 4.2 VehicleState

```kotlin
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
    val tirePressures: Signal<Map<TirePosition, Float>>,   // kPa
    val malfunctionIndicatorLampOn: Signal<Boolean>,
    val source: VehicleSourceId,
    val lastUpdatedMs: Long?,
) {
    companion object {
        fun unavailable(source: VehicleSourceId): VehicleState  // every field Unknown
    }
}
```

`Gear` is `sealed`: `Park`, `Reverse`, `Neutral`, `Drive`, `Low`, `Manual(n: Int)`, `Unknown`.
`VehicleSourceId` is an enum: `NONE`, `MOCK`, `OBD_USB`.

### 4.3 Diagnostics

```kotlin
data class DiagnosticIssue(
    val id: String,                     // stable key for list diffing and nav
    val code: DiagnosticCode,           // Dtc("P0301") | SignalDerived(DoorOpen(RearLeft))
    val title: String?,                 // null unless a trusted mapping supplied one
    val description: String?,           // null unless a trusted mapping supplied one
    val classification: DtcClassification?,
    val severity: Severity,             // Info | Warning | Critical
    val status: DiagnosticStatus,       // Stored | Pending | Permanent | LiveSignal
    val source: DiagnosticSource,       // Obd2 | VehicleSignal | Mock
    val firstSeenMs: Long,
    val lastSeenMs: Long,
)

data class VehicleDiagnosticsState(
    val issues: List<DiagnosticIssue>,
    val malfunctionIndicatorLampOn: Signal<Boolean>,
    val storedDtcCount: Signal<Int>,
    val lastScanMs: Long?,
)
```

**Never overstate diagnosis.** `title` and `description` stay `null` unless a trusted mapping
supplies them; no such mapping ships in this version, so the UI renders
`Engine diagnostic code detected` / `Code: P0301` and nothing more.

`DtcDecoder` decodes only what the SAE J2012 code *format* itself encodes — this is structural
decoding, not a fault claim:

- first two bits of byte 1 → `P` powertrain, `C` chassis, `B` body, `U` network;
- next two bits → second character `0`–`3`, where `0` and `2` are SAE-generic and `1` and `3` are
  manufacturer-specific;
- for `P` codes the third character indicates the subsystem group (`0`–`2` fuel & air metering,
  `3` ignition system or misfire, `4` auxiliary emission controls, `5` speed/idle control,
  `6` computer output circuit, `7`–`8` transmission);
- remaining nibbles are the fault number.

So `P0301` yields `DtcClassification(system = Powertrain, manufacturerSpecific = false,
subsystem = "Ignition system or misfire")`. The decoder never asserts a failed component. An
unrecognised or malformed code yields `classification = null` and the raw code is still displayed.

Severity is derived only from OBD semantics, never inferred from the code number. Rules are
evaluated in order and the **first match wins**:

| # | Condition | Severity |
| --- | --- | --- |
| 1 | permanent DTC (mode `0A`) **or** MIL illuminated | `Critical` |
| 2 | stored DTC (mode `03`) with MIL off | `Warning` |
| 3 | pending DTC (mode `07`) | `Info` |

An issue is removed from `issues` when a **completed** scan no longer reports it. A scan that failed
or timed out is not a completed scan and must not retire issues. No "resolved" tombstone is kept in
this version.

### 4.4 Connection state

```kotlin
sealed interface VehicleConnectionState {
    data object Disconnected
    data class  DeviceDetected(val device: UsbDeviceDescriptor)
    data class  PermissionRequired(val device: UsbDeviceDescriptor)
    data class  PermissionDenied(val device: UsbDeviceDescriptor)
    data object Connecting
    data class  Connected(val adapter: AdapterIdentity, val protocol: String?)
    data object Reading
    data class  UnsupportedDevice(val device: UsbDeviceDescriptor, val reason: String)
    data class  VehicleCommunicationUnavailable(val reason: String)
    data class  ConnectionLost(val reason: String)
    data class  Error(val reason: String)
}
```

`UnsupportedDevice` covers both "no USB-serial driver for this chipset" and "device answered `ATI`
with something that is not an ELM327-compatible identity".
`VehicleCommunicationUnavailable` covers an adapter that responds but cannot reach the vehicle bus —
ELM327 `UNABLE TO CONNECT`, `BUS INIT: ERROR`, `CAN ERROR`, or `NO DATA` to the initial `0100`.

---

## 5. Transport and protocol

```kotlin
interface VehicleTransport {
    val events: Flow<TransportEvent>              // Opened, Detached, Error
    suspend fun open()
    suspend fun write(bytes: ByteArray)
    fun incoming(): Flow<ByteArray>
    suspend fun close()
}
```

`Elm327Session` owns framing and the command lifecycle: writes an ASCII command terminated by `\r`,
accumulates incoming bytes until the `>` prompt, strips `SEARCHING...`, and applies a per-command
timeout. Recognised non-data replies (`NO DATA`, `?`, `STOPPED`, `UNABLE TO CONNECT`, `BUS INIT`,
`CAN ERROR`, `BUFFER FULL`) map to typed results rather than exceptions at the parse boundary.

Handshake: `ATZ` → `ATE0` → `ATL0` → `ATS0` → `ATH0` → `ATSP0` → `ATI` (identity check) → `0100`
(bus reachability + first support bitmask).

Polling cadence, chosen because an ELM327 over CAN realistically sustains only ~10–20 queries/second:

| Group | Contents | Interval |
| --- | --- | --- |
| Fast | speed `0D`, RPM `0C` | 500 ms |
| Slow | coolant `05`, fuel `2F`, plus `A6`/`31` when supported | 5 s |
| Diagnostics | `01` (MIL + count), `03`, `07`, `0A` | 15 s |

Unsupported PIDs are never polled.

### 5.1 USB specifics

`UsbSerialTransport` wraps `usb-serial-for-android` behind our own interface, so the library stays
replaceable. Device discovery enumerates `UsbManager.deviceList` and matches against the library's
prober plus our own `SupportedAdapters` table; **no vendor/product ID is hardcoded** as the sole
match path, since the target adapter is not yet chosen.

`ACTION_USB_DEVICE_ATTACHED` is handled by a runtime-registered receiver (not a manifest
`intent-filter` launching the activity, which would fight the existing single-activity setup).
`ACTION_USB_DEVICE_DETACHED` closes the transport and drives `ConnectionLost`. USB permission uses
`UsbManager.requestPermission` with a mutable `PendingIntent`. Reconnect uses bounded exponential
backoff (1 s → 8 s, capped), only while a device is present.

Manifest additions: `<uses-feature android:name="android.hardware.usb.host" android:required="false" />`.
No `<uses-permission>` is required for USB host access.

---

## 6. Source selection

`VehicleSourceSelector` produces the active `VehicleDataSource`:

1. a supported USB adapter is attached and permitted → `ObdVehicleDataSource`;
2. otherwise, in a **debug build** with mock mode explicitly enabled → `MockVehicleDataSource`;
3. otherwise → no source; repository emits `VehicleState.unavailable(NONE)` with
   `VehicleConnectionState.Disconnected`.

There is **no fallback from real to mock**. If the real source fails, the repository emits the
failure state and clears telemetry; it never substitutes plausible values. A unit test asserts that
an `ObdVehicleDataSource` error never yields a `Signal.Value`.

`MockVehicleDataSource` itself lives in `src/main` so that unit tests, instrumented tests and
Compose previews can all use it. What is gated is **selection**, not compilation: the selector will
only ever return it when `BuildConfig.DEBUG` is true *and* mock mode has been explicitly switched
on. It is never auto-selected, and whenever it is active the dashboard shows a persistent
`SIMULATED DATA — NOT A REAL VEHICLE` banner. A unit test asserts the selector never resolves to
`MOCK` when the debug flag is false, regardless of the mock-mode toggle.

---

## 7. UI changes

The dashboard's structure, sizing rules and layout fixes are unchanged. Composables become pure
functions of `DashboardUiState`; both `LaunchedEffect` data loops are deleted, and `SpeedPanel` no
longer owns any state. `DashboardViewModel` exposes
`StateFlow<DashboardUiState>` via `stateIn(SharingStarted.WhileSubscribed(5_000))`; the screen
collects with `collectAsStateWithLifecycle()`. All string formatting happens in a
`VehicleStateFormatter` in the UI layer, so the domain never carries display strings.

`DriveMode` remains UI-owned and stays in `rememberSaveable` — it is a display preference, not
vehicle data.

### 7.1 Honest rendering of unavailable data

Every panel keeps its place in every state; only its content changes. Unavailable values render as
`—` with a neutral helper.

| Panel | Real OBD-II | Rendering when unavailable |
| --- | --- | --- |
| Speed gauge | live | gauge at 0, value `—` |
| RPM, Fuel, Temp tiles | live | `—` + `Not reported by vehicle` |
| Gear tile | unavailable | `—` + `Not reported by vehicle` |
| Trip, Range, Odometer | unavailable | `—` |
| Check Engine row | MIL + DTC count | real |
| Seatbelt, Door, Tire rows | unavailable | **neutral grey `Not reported`** |

The seatbelt/door/tyre rows are the important case. Today they render green `OK` with helpers such
as `All doors closed`. With no door data, green `OK` is a fabricated safety assurance, so
`WarningRow` gains a third visual state — neutral grey, label `Not reported` — distinct from both
green `OK` and orange `ON`. The panel still renders exactly four rows, so the compact-landscape
`SpaceEvenly` fix is untouched.

The Gear helper stays mode-aware: `"${mode.label} shift"` when a gear value exists, and
`Not reported by vehicle` when it does not.

### 7.2 Vehicle Health panel

A new panel in the existing visual system (same `DashboardPanel`, spacing and typography), titled
`Vehicle Health`, listing active `DiagnosticIssue`s with code, status chip and severity colour. It
renders `No issues reported` when the list is empty and `Vehicle not connected` when disconnected —
those are different states and must not look alike.

Placement differs by layout, deliberately. In **portrait** it is a full panel below `WarningPanel`,
which is safe because that column scrolls. In **landscape it is not a new panel**: the compact
landscape column already holds `WarningPanel` + `DriveModePanel` within a bounded ~320 dp height,
and that bound is exactly what caused the clipping fixed in commit `ecf85cd`. Adding a third panel
there would reintroduce it. Instead, landscape shows a diagnostics count chip in the existing
`WarningPanel` header, and the full list is reached through the detail route. A Compose test asserts
no vertical overflow in the compact landscape configuration.

### 7.3 Diagnostic detail screen

A second nav route, `diagnostics/{issueId}`, in the existing `NavHost`. It shows problem, diagnostic
code, status, severity, detected time, affected system (from `DtcClassification`), available
description (or an explicit `No description available for this code`), and a clear
`Live vehicle signal` vs `Stored diagnostic code` distinction. Warning rows and health entries are
tappable only when a corresponding issue exists.

### 7.4 Connection banner

A compact status strip reflecting `VehicleConnectionState`, including a `Grant USB access` action in
`PermissionRequired`. `Disconnected` reads `Vehicle not connected`.

---

## 8. Logging

A `VehicleLog` wrapper around `android.util.Log`, tag `CarDash/Vehicle`. It logs lifecycle facts
only: source selected, USB attached/detached, permission result, connection established/lost,
unsupported device, protocol error, adapter identity, diagnostics scan result (count and codes).

Telemetry samples are never logged per-sample; a debug-only summary is rate-limited to once per
10 s. USB serial numbers are not logged; devices are identified by vendor/product ID and chipset
name. The VIN is never read, so it can never be logged.

---

## 9. Testing strategy

TDD: domain, protocol, repository and source-selection tests are written before their
implementations. Hardware-touching code (`UsbSerialTransport`) is kept deliberately thin because it
is the one layer that cannot be verified without hardware.

Two distinct fakes, and the distinction is deliberate:

- **`FakeVehicleTransport`** — a scriptable `VehicleTransport` that answers written ELM327 commands
  with canned byte responses (`41 0C 1A F8` → 1726 rpm). It exercises the real protocol code path,
  so the entire ELM327/J1979 layer is verified without a car.
- **`MockVehicleDataSource`** — a scripted timeline at the domain level, used for signals no OBD
  adapter provides (doors, tyres, belts) and for UI development.

Both feed the same `VehicleRepository`; neither touches Compose state directly.

### 9.1 Unit tests (`src/test`, JVM)

Vehicle state: speed / RPM / fuel / temperature / gear updates propagate to the repository;
unsupported PIDs remain `Signal.Unsupported`; unread supported PIDs remain `Signal.Unknown`; no
field ever acquires a numeric default.

Protocol: PID decode for `0D`, `0C`, `05`, `2F`, `01`, `1F`, `21`, `31`, `46`, `A6`; support-bitmask
parsing across `00`/`20`/`40`; DTC pair decoding for `P`, `C`, `B`, `U` and manufacturer-specific
codes; `NO DATA` / `?` / `STOPPED` / `UNABLE TO CONNECT` / `CAN ERROR` / `BUFFER FULL` handling;
malformed and truncated frames; command timeout; the sealed-`ObdCommand` safety assertion.

Diagnostics: issue appears; issue updates (`lastSeenMs` advances, no duplicate); resolved issue is
removed on the next scan that omits it; multiple simultaneous issues; unknown/malformed code yields
`classification = null` without crashing; severity mapping across stored/pending/permanent/MIL.

Connection: each state is reachable and observable — disconnected, detected, permission required,
permission denied, connecting, connected, reading, unsupported device, communication unavailable,
error, detach mid-read, reconnect.

Source switching: mock → real; real → disconnected; reconnect real; **no stale real values survive a
disconnect**; real-source failure never falls back to mock.

### 9.2 Instrumented / Compose tests (`src/androidTest`)

Dashboard rendering for: no vehicle connected; connected and healthy; connected with one warning;
connected with multiple warnings; disconnect while open; reconnect; unknown property; diagnostic
error. Plus portrait and landscape layouts, rotation preserving `DriveMode`, and all three drive
modes.

### 9.3 Build additions

`settings.gradle.kts` gains the JitPack repository (`usb-serial-for-android` is not on Maven
Central). `app/build.gradle.kts` gains `lifecycle-viewmodel-compose`,
`lifecycle-runtime-compose`, `usb-serial-for-android`, and the currently absent test stack: `junit`,
`kotlinx-coroutines-test`, `turbine`, `androidx.compose.ui:ui-test-junit4`, and
`debugImplementation` of `ui-test-manifest`.

---

## 10. Documentation deliverable

`docs/vehicle-data-architecture.md`, covering: the layer diagram; the mock-vs-real source split and
how to enable mock mode; the USB/OBD-II hardware requirement; supported adapter chipsets; required
permissions; how to test without a car (fake transport and mock timeline); how to test with an
adapter once one is chosen; and known limitations — the per-source availability matrix from §1, and
the fact that gear, tyre pressure, doors, seatbelts and range are unavailable over standard OBD-II.

---

## 11. Out of scope

Android Auto integration — it can serve only a subset (§1.3) and would require rebuilding the UI as
Car App Library templates. An AAOS product flavour or `android.car` code — the property/permission
mapping is documented here for a future flavour, but no AAOS code ships now, since it cannot compile
into or run from a phone APK. Manufacturer-specific enhanced PIDs and UDS (ISO 14229) reads — a
future `VehicleDataSource` behind the same interface, blocked on documented per-make specifications
(§13). Bluetooth and Wi-Fi transports. Any write, actuation or DTC-clearing capability.

---

## 12. Verification status policy

Every claim in the final report is labelled either **verified on device** (Pixel 7a, Android 16) or
**implemented, hardware verification pending**. No claim of real-vehicle verification will be made,
because no vehicle or adapter is available in this environment.

## 13. Remaining unknowns

Before a real car can be connected, the following must be supplied:

1. the exact adapter make/model and its USB chipset (FTDI / CH340 / CP210x / CDC-ACM), and whether
   it is genuinely ELM327-compatible or a clone with a reduced command set;
2. the target vehicle's make, model and year, and therefore its OBD-II transport protocol
   (ISO 15765-4 CAN, ISO 9141-2, ISO 14230 KWP2000 or SAE J1850);
3. whether the head unit's USB port supports host mode and supplies adequate bus power;
4. whether manufacturer-specific data (gear, TPMS, doors, belts, range, odometer) is required — and
   if so, the documented enhanced-PID or UDS specification for that make, which this design does not
   assume access to. This is the explicit dependency gating any OEM source: **no enhanced-protocol
   implementation begins until that specification is in hand.**

Item 4 is a data-availability dependency, not a blocker for the rest of the work — the generic J1979
source ships without it, and those fields render as unavailable per §2.1 until a source exists that
can genuinely supply them.
