# Vehicle data architecture

How the Car Dashboard app gets vehicle data, what it is allowed to say about it, and what it
deliberately refuses to say.

Application package: **`com.csjotlab.cardashboard`** (instrumentation package
`com.csjotlab.cardashboard.test`).

This document describes the code as it exists. Where a layer is not yet verified against real
hardware, it says so rather than implying coverage that does not exist.

---

## 1. The layers, and why the split exists

```
                    ┌─────────────────────────────────────────┐
   ui/dashboard/    │  DashboardScreen · VehicleHealthPanel   │  Compose. No data loops,
   ui/diagnostics/  │  DiagnosticDetailScreen                 │  no timers, no delay().
                    └───────────────────▲─────────────────────┘
                                        │ DashboardUiState
                    ┌───────────────────┴─────────────────────┐
   ui/dashboard/    │  DashboardViewModel                     │  Holds UI state, owns
                    │  VehicleStateFormatter                  │  presentation decisions.
                    └───────────────────▲─────────────────────┘
                                        │ VehicleSnapshot
                    ┌───────────────────┴─────────────────────┐
   vehicle/data/    │  VehicleRepository                      │  Staleness watchdog,
                    │  VehicleLog                             │  single source of truth.
                    └───────────────────▲─────────────────────┘
                                        │ VehicleDataSource
                    ┌───────────────────┴─────────────────────┐
   vehicle/source/  │  VehicleSourceSelector                  │  Chooses mock or real.
                    │  MockVehicleDataSource                  │  Never both, never a
                    │  ObdVehicleDataSource                   │  silent substitution.
                    └───────────────────▲─────────────────────┘
                                        │ ObdCommand / ObdResult
                    ┌───────────────────┴─────────────────────┐
   vehicle/protocol/│  Elm327Session · ObdCommand · ObdPid     │  Knows OBD-II. Knows
                    │  SupportedPidSet · DtcDecoder           │  nothing about USB.
                    └───────────────────▲─────────────────────┘
                                        │ ByteArray
                    ┌───────────────────┴─────────────────────┐
   vehicle/transport│  VehicleTransport (interface)           │  Knows USB. Knows
                    │  UsbSerialTransport  ← Task 18, GATED   │  nothing about OBD-II.
                    └─────────────────────────────────────────┘
```

`vehicle/domain/` sits beside all of it and contains the value types (`Signal`, `VehicleState`,
`Diagnostics`, `VehicleConnectionState`). **`domain` and `protocol` contain zero Android imports.**
Their unit tests run on the JVM against a stubbed `android.jar` where `android.util.Log` throws "not
mocked", so an accidental Android import fails the test instead of passing silently.

**Why transport and protocol are separate.** The protocol layer speaks in `ByteArray` over an
interface with four methods (`open`, `write`, `incoming`, `close`) plus an event flow. That is the
entire contract. Because the protocol never touches a `UsbDevice`, the whole OBD-II stack — framing,
handshake, capability discovery, DTC decoding, timeouts, cancellation — is testable on the JVM with
a fake transport, with no adapter and no car. That is why the protocol, source and repository layers
carry roughly 170 unit tests for a feature whose hardware has never been connected. (The suite total
is larger; the rest covers UI, formatter, navigation and DI.)

---

## 2. Signal semantics — the heart of the design

```kotlin
sealed interface Signal<out T> {
    data class Value<T>(val value: T, val timestampMs: Long) : Signal<T>
    data object Unknown : Signal<Nothing>
    data object Unsupported : Signal<Nothing>
}
```

There is deliberately **no numeric default anywhere in the vehicle domain**. A field can hold a
number only because a source actually reported one.

| State | Means | Renders as |
|---|---|---|
| `Value(v, t)` | A reading that genuinely came from the active source, with the timestamp it was taken | the value |
| `Unknown` | The source may be able to provide this, but no reading has arrived yet | `—` + "Not reported by vehicle" |
| `Unsupported` | The **currently selected source** has determined it cannot provide this field | `—` + "Not available from this source" |

`Unsupported` is scoped to the active source, **not** a claim about the vehicle. A future OEM or
enhanced-PID source may return `Value` for the same field with no change to the UI contract. The
distinction is preserved all the way to the tile helper text: `MetricAvailability.Unsupported` and
`MetricAvailability.Unknown` render different strings, so the two statements never collapse into one.

If capability discovery itself *fails*, affected fields are `Unknown` — not `Unsupported`. A failure
to ask is not evidence of an answer.

---

## 3. Capability discovery is authoritative

A field may hold a value **only** when its own discovered capability permits it **and** the value
came from the response to that field's own request.

Prohibited without exception, and enforced by tests:

1. **No cross-signal inference.** Gear is never derived from speed or RPM. Range is never computed
   from fuel level × assumed consumption. Odometer is never accumulated from integrated speed. Trip
   distance is never substituted from PID `0x31` (distance since codes cleared). Tyre pressure, door
   state and seatbelt state are never synthesised.
2. **No inferred diagnosis.** A DTC's identity is its code. The app never concludes which component
   failed and never promotes an unrelated signal into a named fault. Only structural SAE J2012
   decoding of the code format is permitted.
3. **No placeholder standing in for a reading.** A field with no reading renders as `—`, never `0`,
   never a last-known value, never a neighbouring source's value. Decoders return `null` on short or
   malformed frames — never a zero.
4. **Discovery failure is not capability.** See §2.

The `SLOW_PIDS` list in `ObdVehicleDataSource` carries this comment, which is the rule made concrete:
*"Deliberately excludes PID 0x31 (distance since codes cleared): no field may hold it, so nothing may
request it."* The app does not merely decline to display trip distance — it declines to ask for it.

---

## 4. Mock → Repository → ViewModel → UI

`MockVehicleDataSource` lives in `src/main`, not in a test source set, so tests, instrumented tests
and Compose previews can all use it. Only *selection* is gated.

The flow, end to end:

1. **`MockVehicleDataSource`** runs a scripted timeline on the application scope: disconnected →
   `Connecting` → `Connected` → `Reading`, then telemetry, a door-open event, a low-tyre event, a
   stored DTC, then a **partial-coverage** step and a **coverage-restored** step (these are what make
   the "n of m not reported" path reachable on a device), and finally a disconnect. Internally it
   holds a private `MockFrame` and projects it into the same three flows every source exposes —
   `vehicleState`, `diagnostics`, `connectionState`. `ObdVehicleDataSource` has no frame type at all;
   it holds three independent `MutableStateFlow`s. The *interface* is what the two share, not the
   internals.
2. **`VehicleRepository`** collects whichever source is active, applies the staleness watchdog (§6),
   and exposes a single `VehicleSnapshot` — `state` + `diagnostics` + `connection` — as a
   `StateFlow` shared with `SharingStarted.Eagerly`.
3. **`DashboardViewModel`** maps that snapshot through `VehicleStateFormatter` into
   `DashboardUiState`, and owns the drive-mode label.
4. **Compose** renders `DashboardUiState`. Composables own no data loops, no timers, no `while(true)`
   and no `delay()`. Everything they show arrived as state.

Because selection happens at the source layer, the mock exercises the *entire* real architecture —
repository, staleness, view model, formatter, navigation — rather than hand-feeding Compose state.
The Task 13 instrumented test `debugMockWalksTheCompleteArchitectureTimelineAndClearsOnLoss` drives
exactly this path on a physical device through the real debug toggle.

---

## 5. Source selection and mock-mode safety

```kotlin
when {
    real != null              -> real
    debugBuild && mockEnabled -> cachedMock ?: mockSourceFactory().also { cachedMock = it }
    else                      -> null
}
```

Three properties, each load-bearing:

- **A real source always wins.** If one is present it is used, regardless of the toggle.
- **The mock requires both halves of the gate**: `BuildConfig.DEBUG` *and* an explicit user toggle.
  The debug half is a build-type fact, not a runtime flag someone can flip.
- **There is never a fallback from a failing real source to the mock.** If the real source goes
  away the answer is "no source", and the dashboard says so. A plausible-looking simulation
  silently replacing a dead sensor feed is precisely the failure this design exists to prevent.

The affordance that sets the toggle lives in the **`debug` source set**
(`src/debug/.../DebugMockModeToggle.kt`). The `release` source set carries a same-signature no-op in
its place, so the control is not merely hidden in a release build — it is not compiled into one, and
neither are its strings. `DebugMockModeGateTest` enforces all of this structurally, including that
nothing in `main` calls `setMockModeEnabled`.

### How to actually turn it on

In a **debug** build the dashboard renders a small chip reading `MOCK OFF`, supplied by
`DebugMockModeToggle` and placed by `CarDashboardApp` into an explicit measured dashboard slot
(column one in landscape, a full-width row in portrait — it is deliberately not floated over the
layout, which would cover the warnings summary or the USB action). Tap it: it flips to `MOCK ON`,
`VehicleContainer.setMockModeEnabled(true)` runs, the selector hands the repository a
`MockVehicleDataSource`, and the scripted timeline begins.

In a **release** build the chip does not exist — the composable is a no-op compiled from
`src/release`, and the strings `MOCK ON` / `MOCK OFF` are not in the binary at all.

Whenever the mock is the active source the dashboard shows a persistent banner reading exactly:

```
SIMULATED DATA — NOT A REAL VEHICLE
```

(The dash is a genuine em dash, U+2014. The string is asserted byte-exact in tests.)

---

## 6. Staleness — the 3-second watchdog

`VehicleRepository` has `staleTimeoutMs: Long = 3_000L`. That is **3000 ms / 3 seconds**.

The rule: a reading is only current if the vehicle actually answered recently. If no response
arrives within the window, the repository emits
`VehicleConnectionState.VehicleCommunicationUnavailable("No response from vehicle for 3000ms")` and
**clears telemetry and diagnostics**. The UI shows "Vehicle communication unavailable" and every
reading reverts to `—`.

Two details that matter:

- The deadline is measured from the last real response on the injected `Clock`, not by a blind
  timer, so data that arrives late is judged on its own timestamp.
- Liveness is stamped **only** on a genuine vehicle response — never on a send, a timeout, a retry or
  a keep-alive. An adapter that is happily echoing while the car is silent does not count as alive.

Recovery is automatic: the next real response restores telemetry and the connection state returns to
`Reading`. `DashboardStalenessArchitectureTest` drives this whole loop — real mock source, real
repository, real view model, real Compose — on a device, though it injects a shorter timeout
(1200 ms) to keep the test quick. **The 3000 ms production default is exercised by JVM tests, not
on-device.**

---

## 7. The real source: ELM327 and SAE J1979

### Read-only service list — enforced by the type system

`ObdCommand` is a sealed hierarchy containing **only read services**:

| Service | Meaning |
|---|---|
| `01` | Current data for one PID, and the capability bitmask banks |
| `03` | Stored DTCs |
| `07` | Pending DTCs |
| `0A` | Permanent DTCs |

There is deliberately **no way to express service `04` (clear DTCs)**, any UDS write/routine/session
service, or a raw CAN frame. The type system prevents it, not a code review. **The VIN is never
read** (mode `09` PID `02`). `Elm327Session` likewise exposes no method taking a caller-supplied
string, so arbitrary commands are unreachable by construction.

### Handshake

The session sends a fixed `AtCommand` enum, in order: `ATZ` (reset), `ATE0` (echo off), `ATL0`
(linefeeds off), `ATS0` (spaces off), `ATH0` (headers off), `ATSP0` (auto protocol), `ATI`
(identify). The `ATI` banner must contain the ELM signature or the result is `NotElmCompatible`.
Then capability bitmasks are read from banks `00`, `20`, `40`, `60`, `80`, `A0`.

Outcomes: `Ready(supportedPids, undiscoveredBanks, …)` · `NotElmCompatible(identity)` ·
`BusUnavailable(reason)` · `CapabilityDiscoveryFailed(identity, reason)`. `undiscoveredBanks` is the
mechanism behind §2's "discovery failure is not capability": a PID whose bank was never decoded
starts as `Unknown`, while a PID absent from a bank that *was* decoded starts as `Unsupported`. The last is distinct from `Ready` with an empty PID
set — "we could not ask" is not "the vehicle answered nothing".

### Polling cadence

| Loop | Interval | PIDs |
|---|---|---|
| Fast | **500** ms | `SPEED` (0x0D), `RPM` (0x0C) |
| Slow | **5000** ms | `COOLANT` (0x05), `FUEL_LEVEL` (0x2F), `ODOMETER` (0xA6) |
| Diagnostics | **15000** ms | monitor status (0x01), then services `03`, `07`, `0A` |

Each loop skips any PID absent from the discovered capability set. A response updates **only** the
field it was requested for; `NoData`, `Timeout` and error replies leave the previous value untouched
and never write a zero.

### Diagnostics and DTC behaviour

- A DTC is decoded structurally per SAE J2012: the first character gives the system
  (`P` powertrain, `C` chassis, `B` body, **`U` network**), and for `P` codes the third character
  names a subsystem group. Note the letter for network codes is `U`, not `N` — `U0100` is a network
  code. `DtcDecoder` **never names a failed component** — "P0301" yields a system and a
  subsystem, not "cylinder 1 misfire diagnosis".
- `title` and `description` on `DiagnosticIssue` are `null` unless a trusted mapping supplied them.
  **No such mapping ships in this version**, so the UI shows "No description available for this
  code" rather than inventing prose.
- A scan counts as *completed* only when the monitor-status request and every DTC service request
  returned `Data` or `NoData`. On a completed scan, issues absent from the result are removed and
  `firstSeenMs` is preserved for issues that persist. **On a failed scan the previous issue list is
  retained unchanged** — half a scan is not a scan, and a failure to read is not evidence that a
  fault cleared.
- Codes reported by more than one service take the most severe status, ranked
  `LiveSignal < Pending < Stored < Permanent`.

### A known, accepted protocol limitation

`decodeCodes` requires a mode-03 payload shaped as a count byte followed by that many code pairs,
and refuses a mismatch in either direction rather than guessing how many codes a frame holds
(tested). The trade is deliberate: permanently reporting a failure is safe, whereas serving a
miscounted frame would report a fault-bearing scan as clean.

**Inference, not a verified measurement:** legacy ISO 9141-2 / ISO 14230 KWP2000 mode-03 replies are
generally described as carrying no count byte, which would make them fail that check. No code path
in this project names those protocols, and no pre-CAN vehicle has ever been tested against it — so
treat "DTC reads will not work on a pre-CAN car" as a *prediction to verify*, not an established
fact. It is called out here because it would be an expensive surprise to discover during Task 18.

---

## 8. Logging

`VehicleLog`, tag **`CarDash/Vehicle`**. It logs lifecycle only: source selection, device
attach/detach, permission result, adapter identity, connection established/lost, unsupported device,
protocol error, transport close failure, and diagnostics scan results. Repeated events are
rate-limited to one per 10 seconds, and any telemetry summary to one per 10 seconds. **Per-sample
telemetry is never logged, and USB serial numbers are never logged** — `UsbDeviceDescriptor`
deliberately does not even carry one.

---

## 9. USB — what the hardware actually has to be

**A USB cable by itself carries no vehicle data.** A car's USB sockets are media/charging ports on
the infotainment bus; they do not bridge to the powertrain CAN bus. Plugging the head unit into the
car's own USB port achieves nothing.

Vehicle data is reachable at the **OBD-II / SAE J1962 connector**, mandatory on petrol cars sold in
the US since 1996 and the EU since 2001 (EU diesel: 2004). Required topology:

```
CAR ──(OBD-II / SAE J1962 port)── ELM327-class USB diagnostic adapter
                                            │  USB-serial
                                            ▼
                          Android head unit or phone in USB host (OTG) mode
                                            ▼
                                    Car Dashboard app
```

USB variants of these adapters present as USB-serial devices using one of several chipset families —
FTDI, CH340, CP210x, Prolific or USB CDC-ACM. **Which one is a property of the specific adapter and
must be established from the hardware, not assumed**; the driver is selected by chipset, so a wrong
guess yields no bytes at all.

### Permissions

USB host access needs **no `<uses-permission>`**. It needs a `<uses-feature
android:name="android.hardware.usb.host" android:required="false" />` entry, plus a runtime
`UsbManager.requestPermission` grant from the user. Neither is present in the manifest today,
because Task 18 is gated.

---

## 10. Android, AAOS and Android Auto are three different things

| Platform | Where the app runs | Vehicle data available to a third-party app |
|---|---|---|
| **Standard Android** (phone / aftermarket head unit) | on the device | Nothing from the OS. Requires an OBD-II adapter over USB host — this app's design |
| **Android Automotive OS (AAOS)** | on the vehicle's head unit | `CarPropertyManager`. Speed, fuel, range, gear, outside temperature are third-party grantable. RPM, coolant temp, odometer, tyre pressure, doors, seatbelts each need `signature\|privileged` — OEM signing or a system image placement. **There is no DTC property in the third-party API at all**; `CarDiagnosticManager` is system-only |
| **Android Auto** | on the **phone** — the head unit is only a display and input surface | `CarPropertyManager` does not exist. `MainActivity` never executes on the vehicle. Only a limited Car Hardware subset via the Car App Library |

This distinction is decisive for Task 18: **if the target is Android Auto, the relevant USB port is
the phone's, not the head unit's.** No AAOS code ships in this version — it could not compile against
this project's `compileSdk` without the automotive SDK — but the mapping is recorded here so a future
product flavour does not have to re-derive it.

### AAOS property/permission mapping, for a future flavour

Obtainable by an ordinary third-party app (permissions at `dangerous`, runtime-grantable):

| `CarPropertyManager` property | Permission |
|---|---|
| `PERF_VEHICLE_SPEED`, `PERF_VEHICLE_SPEED_DISPLAY` | `android.car.permission.CAR_SPEED` |
| `FUEL_LEVEL`, `FUEL_LEVEL_LOW`, `EV_BATTERY_LEVEL`, `RANGE_REMAINING` | `android.car.permission.CAR_ENERGY` |
| `GEAR_SELECTION`, `CURRENT_GEAR`, `PARKING_BRAKE_ON` | `android.car.permission.CAR_POWERTRAIN` |
| `ENV_OUTSIDE_TEMPERATURE` | `android.car.permission.CAR_EXTERIOR_ENVIRONMENT` |
| `INFO_MAKE`, `INFO_MODEL`, `INFO_MODEL_YEAR` | `android.car.permission.CAR_INFO` |

**Not** obtainable — `signature|privileged`, needing OEM signing or a system-image placement:

| Property | Permission |
|---|---|
| `ENGINE_RPM`, `ENGINE_COOLANT_TEMP`, `ENGINE_OIL_TEMP` | `CAR_ENGINE_DETAILED` |
| `PERF_ODOMETER` | `CAR_MILEAGE` |
| `TIRE_PRESSURE` | `CAR_TIRES` |
| `DOOR_POS` / door open state | `READ_CAR_DOOR_POS` / `CONTROL_CAR_DOORS` |
| `SEAT_BELT_BUCKLED` | `CONTROL_CAR_SEATS` |

Note the shape of that split: AAOS would supply speed, fuel, range and gear — several fields the OBD
source marks `Unsupported` — while *losing* RPM, coolant temperature and DTCs, which the OBD source
provides. The two sources are complementary, not ranked. And there is **no DTC property in the
third-party AAOS API at all**; `CarDiagnosticManager` is system-only.

---

## 11. Known limitations — what generic J1979 cannot give you

`ObdVehicleDataSource` marks these `Signal.Unsupported` **unconditionally**, because no generic SAE
J1979 PID exists for them:

| Field | Why |
|---|---|
| `gear` | No generic PID. Never inferred from speed or RPM |
| `tripDistanceKm` | No generic PID. PID 0x31 exists but measures distance since codes cleared — a different quantity, and substituting it is forbidden |
| `estimatedRangeKm` | No generic PID. Never computed from fuel level × assumed consumption |
| `seatbelts` | No generic PID. Never synthesised |
| `doors` | No generic PID. Never synthesised |
| `tirePressuresKpa` | No generic PID. Never synthesised |

`odometerKm` is polled via PID `0xA6` — requested as ordinary mode 01 (`01A6`) — but it was
standardised late and is rarely supported. On a vehicle that does not advertise it, the field ends up
**`Unsupported`**, not `Unknown`.

That is worth stating precisely, because it is §2's distinction in action. `Capability.initialSignal`
returns `Unknown` only when the PID is supported, or when the bank that would have advertised it was
never decoded. A vehicle whose capability chain simply stops before bank `0xA0` leaves that bank
neither decoded nor recorded as undiscovered, so `0xA6` resolves to `Unsupported` — "this source
cannot supply it" — and the tile reads "Not available from this source". `Unknown` is reached only
when discovery itself failed for the relevant bank.

Filling any of these properly would need documented per-make enhanced-PID or UDS specifications —
a separate project. Because `Unsupported` is scoped to the source, such a source could be added
later without touching the UI contract.

---

## 12. Testing strategy

| What you want to test | Use | Where |
|---|---|---|
| Domain rules, `Signal` semantics, DTC decoding | plain JVM unit tests | `src/test` |
| The OBD-II protocol: framing, handshake, timeouts, cancellation, error replies | `FakeVehicleTransport` | `src/test` |
| Source behaviour, capability discovery, liveness, staleness | `ControllableVehicleDataSource`, virtual time | `src/test` |
| Dashboard state mapping across the eight-state Task 13 matrix | `VehicleStateFormatter` + Compose | `src/androidTest` |
| Layout contracts in both branches, rotation, drive modes | Compose UI tests with pinned density | `src/androidTest` |
| The whole architecture end to end on a device | `DashboardArchitectureTest` via the real debug toggle | `src/androidTest` |
| The USB byte path | **nothing — requires hardware** | Task 18 |

Commands:

```bash
./gradlew testDebugUnitTest          # 468 unit tests
./gradlew connectedDebugAndroidTest  # 55 instrumented tests, needs a device
./gradlew assembleDebug assembleRelease
```

Two structural guards are worth knowing about, because they protect against tests that pass while
enforcing nothing: `DashboardScreenPurityTest` (no data loops or mock literals in the UI layer) and
`DebugMockModeGateTest` (the mock gate's structure). Both locate sources by filesystem path, and
both open with an assertion that they are actually reading files — a walk over a moved directory
yields nothing, and "nothing" would otherwise read as "no violations".

### Once an adapter exists (Task 18)

None of this can be run today; it is the procedure to follow when the hardware facts in §13 are
answered and `UsbSerialTransport` exists.

1. **Identify the adapter before writing code.** On a laptop, `lsusb` (Linux) or
   `system_profiler SPUSBDataType` (macOS) gives vendor/product ID and usually the chipset family.
2. **Confirm host mode** on the target Android device — a charge-only port cannot work no matter what
   the code does.
3. **Plug in with the app running.** Expect: attach event → permission dialog → `DeviceDetected` →
   handshake → `Connected` → `Reading`. A device with no matching driver must surface
   `UnsupportedDevice`, never a silent failure.
4. **Watch the log**, filtered to the tag: `adb logcat -s CarDash/Vehicle`. Lifecycle only — source
   selection, attach/detach, permission result, adapter identity, connection, protocol errors, scan
   results. If you see per-sample telemetry there, something has regressed.
5. **Check the honest-rendering contract holds against a real car.** Fields the vehicle does not
   report must read `—`, never `0`. This is the single most important thing to verify with real
   hardware, because it is the one property a fake transport cannot fully exercise.
6. **Unplug mid-session.** Expect `ConnectionLost`, telemetry cleared, no stale values left on screen.

**Testing with a real car must be done parked.** The safety boundary is enforced in code, but the
physical testing context is a human decision.

---

## 13. Task 18 — gated, and what unblocks it

`UsbSerialTransport` is the only unimplemented layer. Everything above it is complete and tested.
The single seam it fills is `VehicleContainer`'s `realSourceAvailability`, currently a
`MutableStateFlow<VehicleDataSource?>(null)`.

**Task 18 must not begin until these are known.** A guessed chipset produces code that compiles,
passes nothing meaningful, and misleads the next reader into believing USB support exists.

| Area | Required facts |
|---|---|
| Adapter | Make and model; **USB chipset** (FTDI / CH340 / CP210x / Prolific / CDC-ACM); genuine ELM327 or clone, and the exact `ATI` string it returns; baud rate |
| Vehicle | Make, model, year; fuel type; **OBD-II transport** (ISO 15765-4 CAN / ISO 9141-2 / ISO 14230 KWP2000 / SAE J1850) |
| Android host | Head-unit or navigation make and model; **which platform** (standard Android / AAOS / Android Auto); Android version; whether it can show a USB permission dialog |
| USB port | **Host (OTG) capable?** Adequate bus power, or is a powered hub needed? |

The four in bold are the ones that actually gate design decisions.

When unblocked, the work adds a JitPack repository entry, a USB-serial dependency, the
`<uses-feature>` manifest line, `UsbSerialTransport`, `UsbDeviceScanner` and `SupportedAdapters`,
and wires the scanner into `realSourceAvailability`. Chipset resolution must go through the
library's prober — never a hardcoded vendor/product ID as the sole match path.

---

## 14. Verification status

Every claim below is labelled by how it was established.

| Layer | Status |
|---|---|
| Domain types, `Signal`, DTC decoding | **Verified** — JVM unit tests |
| OBD-II protocol, handshake, framing, timeouts | **Verified without hardware** — JVM tests against `FakeVehicleTransport`. The logic is proven; the wire has never been touched |
| Repository, staleness, source selection | **Verified** — JVM tests plus on-device architecture tests |
| Dashboard UI, the eight-state Task 13 matrix, both layouts, rotation | **Verified on device** — Pixel 7a, Android 16, instrumented suite |
| Mock end-to-end through the real architecture | **Verified on device** |
| USB transport / byte path | **Not implemented. Task 18, gated on hardware facts** |
| Real-vehicle behaviour | **Never verified.** No vehicle or OBD-II adapter has been connected to this project |

No claim of real-vehicle verification is made anywhere in this project, and none should be until an
adapter and a car have actually been used.
