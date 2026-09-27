# Car Dashboard

An Android instrument cluster built with Kotlin and Jetpack Compose. It renders speed, revs, coolant
temperature, fuel level, distance, drive mode and vehicle warnings on a dark, adaptive screen, and it
is built around a single rule: **the dashboard never shows a number that a data source did not
actually report.**

> **This build cannot read a real vehicle yet.** The USB transport that would talk to an OBD-II
> adapter is not implemented — the interface and the whole protocol stack above it are, but no bytes
> flow. With no real source present the app shows an honest empty state, and a debug-only mock source
> can be switched on for development. See [Current status](#current-status).

## Screenshots

| Disconnected — the honest empty state | Mock mode — clearly labelled | Warnings and health |
| --- | --- | --- |
| ![Disconnected dashboard](docs/screenshots/disconnected.png) | ![Mock mode dashboard](docs/screenshots/mock-mode.png) | ![Warnings and vehicle health](docs/screenshots/warnings-and-health.png) |

Note the difference between the two states. Disconnected shows an em dash and
*"Not reported by vehicle"* for every field — never a zero. Mock mode carries a permanent
`SIMULATED DATA — NOT A REAL VEHICLE` banner and a `MOCK ON` chip, so simulated values can never be
mistaken for real ones.

## The data-integrity rules

These are enforced by the type system and by tests, not by convention:

- **No placeholder values.** Every measurement is a `Signal<T>`, which is either a real reading, or a
  stated reason there is no reading. There is no numeric default anywhere in the vehicle domain — a
  field can only hold a number because a source reported one.
- **No cross-signal inference.** The app will not derive one reading from another (for example,
  estimating range from fuel level), because the result would be presented with the same confidence
  as a measured value.
- **No inferred diagnosis.** A fault is shown only when the vehicle reports a diagnostic trouble
  code. A missing reading is never rendered as "OK".
- **Discovery failure is not a capability.** If the app cannot determine whether a vehicle supports a
  reading, that is reported as unknown — not as unsupported, and not as zero.
- **Simulated data is always labelled**, and mock mode can never be reached in a release build.

### Signal semantics

| State | Meaning | Shown as |
| --- | --- | --- |
| `Signal.Value(v, t)` | A reading that genuinely came from the active source, with its timestamp | The value |
| `Signal.Unknown` | The source may be able to provide this, but no reading has arrived yet | `—` + *"Not reported by vehicle"* |
| `Signal.Unsupported` | The **currently selected source** has determined it cannot provide this field | `—` + *"Not available from this source"* |

`Unsupported` is deliberately scoped to the source, not to the vehicle. A generic OBD-II source
cannot read seatbelt status; a future manufacturer-specific source might. Nothing in the UI contract
would have to change.

## Architecture

Unidirectional data flow with manual dependency injection — no DI framework.

```
Compose UI          DashboardScreen / DiagnosticDetailScreen
   ▲ state          stateless, render a DashboardUiState
   │
ViewModel           DashboardViewModel — StateFlow, viewModelScope
   ▲
Repository          VehicleRepository — source selection, staleness, logging
   ▲
Data source         VehicleDataSource
                      ├─ MockVehicleDataSource   debug builds only, always labelled
                      └─ ObdVehicleDataSource    capability discovery + polling loops
   ▲
Protocol            Elm327Session — framing, handshake, error replies, timeouts
   ▲
Transport           VehicleTransport (interface)   ← not implemented yet
```

Packages under `com.csjotlab.cardashboard`:

| Package | Contents |
| --- | --- |
| `vehicle.domain` | `Signal`, `VehicleState`, `Diagnostics`, connection states, `Clock` |
| `vehicle.protocol` | `Elm327Session`, `ObdCommand`, `ObdPid`, `SupportedPidSet`, `DtcDecoder` |
| `vehicle.source` | `VehicleDataSource` and its mock / OBD implementations, `VehicleSourceSelector` |
| `vehicle.transport` | `VehicleTransport` — the byte-level seam an adapter driver plugs into |
| `vehicle.data` | `VehicleRepository`, staleness handling, `VehicleLog` |
| `di` | `VehicleContainer` — plain manual DI |
| `ui.dashboard` | Screen, UI state, ViewModel, formatter, connection banner, health panel |
| `ui.diagnostics` | Diagnostic trouble code detail screen and its UI state |
| `ui.theme` | Colours, typography, spacing, shapes |
| `navigation` | Routes and the app-level navigation host |

### Staleness

Readings expire. `VehicleRepository` stamps every value against a `Clock` and drops it back to
`Unknown` **3 seconds** after it arrived. The timeout is measured from the reading's own timestamp
rather than from a blind timer, so a value that arrives late is not granted a fresh lease. A frozen
adapter therefore produces em dashes rather than a plausible-looking speed that stopped updating.

### The protocol layer

`Elm327Session` speaks to an ELM327-compatible adapter and implements SAE J1979 (OBD-II) on top of
it. Capability discovery walks the supported-PID bitmask banks, so the app asks only for PIDs the
vehicle has declared.

Three polling loops run at different rates:

| Loop | Interval | PIDs |
| --- | --- | --- |
| Fast | 500 ms | `SPEED`, `RPM` |
| Slow | 5000 ms | `COOLANT`, `FUEL_LEVEL`, `ODOMETER` |
| Diagnostics | 15000 ms | Stored, pending and permanent trouble codes |

**The protocol layer is read-only by construction.** `ObdCommand` is a sealed type that can only
produce services `01` (current data), `03` (stored DTCs), `07` (pending DTCs) and `0A` (permanent
DTCs). Service `04` — clear diagnostic information — is not representable, so the app cannot erase a
vehicle's emissions readiness data even by mistake. There is no UDS write path and the VIN is never
requested.

### Diagnostics

Trouble codes are decoded per SAE J2012 into their standard form: a system letter (`P` powertrain,
`C` chassis, `B` body, `U` network), then four hex digits. Each code gets a detail screen. The app
reports codes; it does not interpret them into a diagnosis.

### What generic OBD-II cannot provide

Six fields have no generic J1979 PID at all and are reported `Unsupported` unconditionally by the OBD
source, by design and by test:

| Field | Why |
| --- | --- |
| `gear` | No generic PID |
| `tripDistanceKm` | No generic PID for a user trip meter |
| `estimatedRangeKm` | No generic PID; deriving it would be cross-signal inference |
| `seatbelts` | Body-domain data, not emissions-related |
| `doors` | Body-domain data, not emissions-related |
| `tirePressuresKpa` | Manufacturer-specific |

Odometer uses PID `01A6`, a J1979-2 (WWH-OBD) extension rather than classic mode 01, so most vehicles
will not answer it and it will resolve to `Unsupported` on them.

## Mock mode

A mock source exists for development. It is gated twice over:

- The affordance that enables it lives in the **`debug` source set** and is replaced by a
  same-signature no-op in `release`, so it cannot be compiled into a release build.
- The selector requires **both** `BuildConfig.DEBUG` and an explicitly enabled toggle.

A real source always wins over the mock, and a **failing real source never falls back to the mock** —
that fallback is exactly what would let simulated data be presented as real. Mock mode is never
inferred from "no real source is present"; it has to be turned on deliberately.

To use it, run a debug build and tap the `MOCK OFF` chip in the top-right corner of the dashboard.
It becomes `MOCK ON` and the simulated-data banner appears.

A structural test enforces that nothing in the `main` source set can call the toggle, and a
companion assertion checks that the guard is really reading the source tree — so it cannot quietly
become a no-op if the package ever moves again.

## Navigation

The **Navigate** chip opens a full-screen, open-source map with search and turn-by-turn guidance.
No account, key, or server is needed:

| Concern | Service | Override |
| --- | --- | --- |
| Map tiles | [OpenFreeMap](https://openfreemap.org) (OpenStreetMap vector tiles) | `-PmapDayStyleUrl=… -PmapNightStyleUrl=…` |
| Road routing | [Valhalla](https://valhalla.github.io/valhalla/) public server run by FOSSGIS — reports and avoids toll roads | `-PvalhallaBaseUrl=…` |
| Fallback routing | [OSRM](https://project-osrm.org) public demo server (no toll data) | `-PosrmBaseUrl=…` |
| Optional routing | Self-hosted [GraphHopper](https://www.graphhopper.com/open-source/) — tried first when set | `-ProutingBaseUrl=http://<host>:8989` |
| Search / reverse | [Photon](https://photon.komoot.io) (komoot's open OSM geocoder) | `-PgeocodingBaseUrl=…` |
| Nearby places (Fuel, Parking, …) | [Overpass API](https://overpass-api.de) — OSM tags within 5–25 km of the car; three public servers queried in parallel, first answer wins | `-PoverpassBaseUrls=a,b,…` |

Map data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright) (ODbL). The
public Valhalla, OSRM and Photon endpoints are community services with no SLA; point the overrides
at self-hosted instances for production use.

**Planning.** A **Where to?** field (with a **Your location** start row and ⇅ swap) searches as you
type — places, addresses, stations, POIs, and typed coordinates (`23.8103, 90.4125`, `33.59°N
130.4°E`, answered locally without a network call). One-tap chips find **Fuel, Convenience, Parking,
Food, Hospital, Charging, Hotel** near you — every OSM place of that kind within 5 km (25 km if fewer than
three), nearest first — and drop them on the map. Chain stores show their English brand
(FamilyMart, Lawson, 7-Eleven) with the branch name underneath. Tapping the map
picks a destination and names it by reverse geocoding. Recent places are kept.

**Overview.** The camera moves to the destination, drops a pin, and fits the route once it arrives.
When the router offers alternatives (Valhalla `alternates`, OSRM `alternatives`), each appears as a
card with time, distance, main road and toll status; the others are drawn in grey. Toll badge and
**Avoid tolls** work as before. If no route can be found, **Retry** replaces **Start**.

**Guidance** is laid out for driving: the **maneuver banner** at the top (vector turn arrow, distance
such as **300 m**, and the instruction with the road name — *Turn right onto Route 3* — plus a
*Then ↰* preview when the next turn follows closely); the **map** in the middle; and a **trip bar** at
the bottom with the arrival time, time and distance left, route overview, and a large red **End**
button. Speed and the current road are shown above the trip bar. System **Back** asks *End
navigation?* instead of silently leaving. The screen stays on while guiding.

- **The map follows the car.** A direction arrow shows which way the vehicle is travelling. The
  camera is heading-up and tilted, with the car low on the screen so the road ahead is visible;
  the compass button switches between heading-up and north-up. The marker glides between GPS fixes
  and the camera moves with it on the same animation frame, so nothing steps once a second.
  Zoom widens with speed and closes in before a turn.
- **Stable heading.** Direction comes from the GPS course while moving. A stopped car keeps its last
  reliable direction; the phone compass (which in a car mount says where the *phone* points) is used
  only before any course is known. When the chip reports no course, it is derived from successive
  fixes once the car has moved 8 m.
- **Your gestures win.** Pan, pinch, rotate, tilt or double-tap stops following immediately;
  **◎ Recenter** returns to the car at the automatic zoom and orientation.
- **Progress.** The driven part of the route turns grey; instructions advance as each maneuver is
  passed. Progress is tracked in a window ahead of the last position, so a road that doubles back
  on itself cannot make the instructions jump.
- **Rerouting.** Leaving the route needs 3 consecutive fixes **and** 4 seconds beyond
  `max(30 m, 1.5 × GPS accuracy)` (fixes worse than 50 m are ignored), so one bad fix never reroutes.
  The banner shows *Rerouting…*, the new route starts at the car and replaces the old one. If the
  router is unreachable the current route is kept (*Unable to reroute*) and retried after 15 s once
  the car has moved on.
- **GPS problems are shown, not hidden.** Fixes worse than 100 m and physically impossible jumps are
  rejected; a parked car does not drift. *Weak GPS signal* or *GPS signal lost* appears under the
  banner and the arrow turns grey. In a tunnel the last progress, instruction and ETA are held —
  the marker is never moved by guesswork.
- **Day / night.** The map, route colours and every panel switch together. **Auto** follows the sun at
  the car's position; the ☀/☾/A button forces day or night. All panel text meets WCAG AA contrast
  in both themes (primary text ≥ 7:1).
- **Screen off / other apps.** While guiding, a foreground service of type `location` keeps GPS
  running and shows the next instruction in a notification with an **End** action. It is started
  only from the foreground when you press Start, so background-location permission is not requested.
  GPS and compass are otherwise on only while the navigation screen is visible.

**Vehicle data.** `nav/vehicle/VehicleDataProvider` is the seam for vehicle signals (speed, heading,
gear, ignition). The one implementation forwards **speed and gear** from the dashboard's OBD-II source
(used to decide "stationary" when the GPS reports no speed). OBD-II has no heading or ignition PID,
so those stay unknown rather than being inferred, and data from the simulated MOCK source is ignored.
Navigation works fully on Android location alone; AAOS `CarPropertyManager`, CAN or OEM sources can be
added behind the same interface.

Screenshots: [nav-planning](docs/screenshots/nav-planning.png), [nav-search](docs/screenshots/nav-search.png),
[nav-overview](docs/screenshots/nav-overview.png), [nav-guidance](docs/screenshots/nav-guidance.png)
(v1.1; not yet retaken for the v2 layout).

**Verified on emulator (2026-09-16, v1.1):** OSM tiles render; Photon search returns nearby named places
with distances; Valhalla road routes draw and fit; Kitakyushu → Hakata shows **Toll road · via E3**
(77 km, 1 h 10 min) and **Avoid tolls** re-routes to **Toll-free · via 199** (71 km, 2 h 1 min).
**v2 (2026-09-27)** is verified by 568 JVM tests — including a simulated trip through several turns,
a missed turn, one reroute from the car, and arrival — and 59 instrumented UI tests (End visible and
working in day and night, Back asks before ending, banner content). On an arm64 emulator (API 37)
a Fukuoka → Kitakyushu route from the live Valhalla server was driven with `adb emu geo fix`: the
map turned heading-up with the road ahead at the top, the route split into driven/ahead, instructions
advanced (*Turn left onto 昭和通り* → *Turn right onto 渡辺通り* → *Take the ramp on the right*),
*Toll ahead* appeared, and **End** returned to planning; the notification's **End** stops the
service and removes the notification.

**Verified on a Pixel 7a (Android 16, 2026-09-27, indoors in Kitakyushu):** installed as an in-place
update (recents kept); live GPS fix (8–15 m) centres the map with the direction arrow, greyed when
the signal weakens; Auto picked night after sunset; **Fuel** lists real stations nearest first
(751 m, ENEOS 3.1 km, Showa Shell…) with pins; overview offers route alternatives; guidance shows the
banner, ETA and **End**; dragging the map stops following and **Recenter** restores it; with the
screen off the guidance service keeps high-accuracy GPS and the notification shows *50 m · Turn
right / Arrive 20:25 · 814 m* with **End**. **Not yet verified:** an actual drive (heading-up
rotation from live GPS course, rerouting on real roads, arrival).

## Platform support

- Android phones and tablets, **minSdk 24** (Android 7.0), targetSdk 34. Verified on a Pixel 7a.
- Two layouts, chosen by available space rather than by orientation: a wide side-by-side layout at
  720dp x 300dp or larger, and a single scrolling column below that. Both are covered by instrumented
  tests that pin the dimensions the cluster must not lose.
- **Not** an Android Automotive OS (AAOS) build. AAOS exposes vehicle data through
  `CarPropertyManager` behind signature/privileged permissions, and its third-party API has no
  diagnostic-trouble-code property at all — a different data story that would need its own source
  implementation behind the same `VehicleDataSource` interface.
- **Not** Android Auto. Android Auto is screen projection: the app runs on the phone and the head
  unit is only a display, so the relevant USB port would be the phone's, not the car's.
- A car's own USB sockets sit on the infotainment bus and do not bridge to the powertrain CAN bus.
  Reading live vehicle data needs an adapter in the OBD-II port.

## Building and running

Requires JDK 17 and an Android SDK with platform 34. Gradle 8.10, AGP 8.4.2, Kotlin 1.9.24,
Compose BOM 2024.06.00.

```bash
./gradlew assembleDebug           # build the debug APK
./gradlew installDebug            # install on a connected device or emulator
./gradlew assembleRelease         # unsigned release build
```

Then launch **Car Dashboard** from the launcher, or:

```bash
adb shell am start -n com.csjotlab.cardashboard/.MainActivity
```

Create `local.properties` with `sdk.dir=/path/to/Android/sdk` if the SDK is not already
discoverable.

Vehicle-layer logging is tagged `CarDash/Vehicle`:

```bash
adb logcat -s CarDash/Vehicle
```

## Testing

```bash
./gradlew testDebugUnitTest          # 568 JVM unit tests
./gradlew connectedDebugAndroidTest  # 59 instrumented tests (needs a device or emulator)
```

Unit tests cover the `Signal` contract, state validation, unit formatting, the ELM327 session
(framing, handshake, error replies, timeouts), OBD command encoding, PID decoding, the supported-PID
bitmask, DTC decoding, capability discovery, the polling loops and liveness, repository source
selection and staleness, the mock source, the ViewModel, and structural guards over the mock gate and
UI purity. The whole protocol stack is exercised through a fake transport, so it is verified without
an adapter.

Instrumented tests cover rendering, the disconnected and populated states, warning rows, the
diagnostic detail route, protected layout dimensions in both orientations, on-device staleness
behaviour, and package identity.

## Current status

Everything above the byte path is built and tested. The one remaining gap is the USB transport:

```kotlin
// VehicleContainer.kt
private val realSourceAvailability = MutableStateFlow<VehicleDataSource?>(null)
```

Implementing it is **blocked on hardware facts that must not be guessed**, because a wrong guess
produces code that compiles, passes nothing meaningful, and misleads the next reader into believing
USB support works. Four of them actually block design decisions:

1. **Adapter USB chipset** — FTDI / CH340 / CP210x / Prolific / CDC-ACM. A serial driver is selected
   by chipset; the wrong one moves no bytes. Also useful: make and model, whether it is a genuine
   ELM327 or a clone, the string it returns to `ATI`, and its baud rate.
2. **Vehicle OBD-II protocol** — ISO 15765-4 CAN, ISO 9141-2, ISO 14230 KWP2000 or SAE J1850, which
   follows from make, model, year and fuel type.
3. **Android platform flavour** — standard Android, AAOS, or Android Auto projection, plus the head
   unit or navigation model if it is not a phone.
4. **USB host (OTG) capability** of the port, and whether it supplies enough bus power for the
   adapter or needs a powered hub.

Until those are known, no USB library, VID/PID mapping, transport implementation or manifest USB
entry is added to this project.

## Documentation

[`docs/vehicle-data-architecture.md`](docs/vehicle-data-architecture.md) is the developer reference:
the full data flow, protocol details, telemetry semantics, diagnostics behaviour, staleness handling,
mock safety, platform distinctions and testing strategy. Its facts are pinned by
`DocumentationAccuracyTest`, which derives every quoted constant from the production source at test
time, so the document fails the build if it drifts out of line with the code.

The design and plan documents live under [`docs/superpowers/`](docs/superpowers/).
