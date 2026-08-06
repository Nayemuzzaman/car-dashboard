# Car Dashboard

An Android instrument cluster built with Kotlin and Jetpack Compose. It shows speed, revs, gear,
energy, trip data, temperatures and the usual telltales on a dark, landscape-first screen.

**The app has no connection to a real vehicle.** Everything on screen comes from a built-in
simulation, and the dashboard says so with a permanent "DEMO DATA" badge whenever simulated values
are being displayed. See [Connecting real vehicle data](#connecting-real-vehicle-data).

## What it does

- **Driving information** — large speedometer with a tappable km/h ⇄ mph toggle, rev counter with a
  red zone, P / R / N / D / S gear display, odometer, trip distance, estimated range, clock and date.
- **Energy** — one panel that covers both powertrains. Fuel vehicles get level, range and a low-fuel
  warning; electric vehicles get state of charge, range, charging status and a regeneration
  indicator.
- **Telltales** — turn signals, hazards, high and low beam, parking brake, seat belt, door open,
  engine, low fuel / low battery, tyre pressure, temperature, ABS and traction control. Nothing is
  lit unless a data source reports it.
- **Temperatures** — outside and engine (or battery pack). A sensor that reports nothing shows `--`
  rather than a fabricated zero.
- **Trip computer** — distance, average speed, driving time, consumption, and a reset that asks for
  confirmation and never touches the odometer.
- **Drive modes** — Eco, Normal and Sport. They change the cluster's accent colour and how the
  simulation drives. They do not command a vehicle.
- **Settings** — units, demo mode, demo powertrain, theme, gauge animations, reset demo data, and an
  about section. Preferences are stored with DataStore and restored on launch.

## Supported platform

- Android phones and tablets, **minSdk 24** (Android 7.0), targetSdk 34.
- Not an Android Automotive OS build — see [Connecting real vehicle data](#connecting-real-vehicle-data).
- Designed for landscape; portrait and small screens get a single scrolling column.

## Building and running

Requires JDK 17 and an Android SDK with platform 34.

```bash
./gradlew assembleDebug          # build the debug APK
./gradlew installDebug           # install on a connected device or emulator
```

Then launch **Car Dashboard** from the launcher, or:

```bash
adb shell am start -n com.example.cardashboard/.MainActivity
```

Create `local.properties` with `sdk.dir=/path/to/Android/sdk` if the SDK is not already discoverable.

## Using demo mode

Demo mode is **on by default**, so the cluster is fully populated the first time the app is opened.

It runs a fixed two-minute drive cycle: park, a short reverse manoeuvre, neutral, a full drive up to
about 126 km/h with accelerations and braking, then back to park. Along the way it lights every
telltale in turn, drops the outside-temperature sensor for a few seconds so the "sensor unavailable"
rendering can be seen, and — on the electric profile — recuperates while slowing and charges while
parked. The energy level drains faster than real life so the low-energy telltale can be reached in a
few minutes rather than a few hours.

In **Settings → Data source** you can:

- turn demo mode off, which switches to the (unavailable) vehicle source,
- switch the demo between the **Fuel** and **Electric** profiles,
- **Reset demo data**, which returns the simulation to its starting point including the odometer.

The simulation is deterministic: the same number of steps from the same starting point always
produces the same values, which is what the tests rely on.

## Connecting real vehicle data

This build targets ordinary phones and tablets, which have no supported way to read a vehicle's
speed, revs or fuel level. Rather than inventing numbers, `NoVehicleInterfaceDataSource` reports that
no interface exists and the dashboard shows an honest empty state.

To connect real data, replace that one class with an implementation of `VehicleDataSource` backed by
an interface you are actually authorised to use — Android Automotive OS `CarPropertyManager`, or a
manufacturer SDK — and wire it into `DefaultAppContainer.liveVehicleDataSource`. Nothing else has to
change: the repository, ViewModel and UI already handle `VehicleDataState.Available` from any source,
and snapshots are clamped centrally before they are drawn.

The app deliberately does not include CAN bus, OBD, hidden-API or privileged vehicle access.

## Architecture

MVVM with a small domain layer, unidirectional data flow and manual dependency injection.

```
UI (Compose)            DashboardScreen / SettingsScreen — stateless, render a UI state
   ▲ state   │ events
ViewModel               DashboardViewModel / SettingsViewModel — StateFlow, viewModelScope
   ▲         │
Repository              VehicleRepository, SettingsRepository (interfaces)
   ▲         │          DefaultVehicleRepository picks the source and clamps every snapshot
Data sources            DemoVehicleDataSource  ── simulated
                        NoVehicleInterfaceDataSource ── real-vehicle slot
                        DataStoreSettingsRepository ── persisted preferences
Domain                  VehicleState, EnergyState, Indicators, TripData, Temperatures, units
```

Package layout under `com.example.cardashboard`:

| Package | Contents |
| --- | --- |
| `domain.model` | `VehicleState` and friends, unit conversion, `sanitized()` validation |
| `domain.repository` | `VehicleRepository`, `SettingsRepository` interfaces |
| `data.demo` | `DemoVehicleSimulator` (pure, deterministic) and its flow wrapper |
| `data.vehicle` | `VehicleDataSource`, the real-vehicle slot, `DefaultVehicleRepository` |
| `data.settings` | DataStore keys, mapping and repository |
| `di` | `AppContainer` — plain manual DI, no framework |
| `ui.dashboard` / `ui.settings` | Screens, UI state and ViewModels |
| `ui.components` | Gauges, telltales, panels — small and reusable |
| `ui.theme` | Colours, typography, spacing, semantic cluster colours |

Notable decisions:

- **One validation point.** `VehicleState.sanitized()` clamps speed, revs, percentages, distances and
  temperatures, discards impossible readings, and derives the low-energy and overheating telltales.
  `DefaultVehicleRepository` applies it to everything it emits, so no screen has to defend itself.
- **Immutable UI state.** Screens take a `DashboardUiState` and emit events; they can be previewed
  and tested without a ViewModel or a running simulation.
- **Lifecycle-safe simulation.** The demo flow is cold and driven by `delay`; `stateIn` with
  `WhileSubscribed` stops it shortly after the screen goes away. No `GlobalScope`, no leaked threads.
- **Honest provenance.** Snapshots carry the source that produced them. Simulated values always show
  the DEMO badge, and last-known readings are dropped when the data source changes so demo values can
  never be presented as real.

## Accessibility

- Every telltale exposes a content description and an explicit on/off state description.
- Active and inactive telltales differ in fill, border and text weight as well as colour, so colour
  is never the only signal.
- All text is sized in `sp` and follows the system font scale; gauges resize their numerals to the
  space available.
- Interactive targets are at least 48dp; the drive-mode and settings choices use selectable-group
  semantics with a radio-button role.
- Content is inset from system bars, notches and the gesture area.

## Limitations

- No real vehicle data on this platform, by design (see above).
- Demo energy drain is faster than real consumption so the low-energy state is reachable during a
  review; the consumption figure shown is the realistic one.
- Drive mode is recorded and reflected in the cluster and the simulation only.
- Strings are English only; there are no translated resource folders yet.
- `targetSdk` is 34 to match the project's `compileSdk`; lint reports this as not-latest.

## Testing

```bash
./gradlew testDebugUnitTest        # 100 JVM unit tests
./gradlew connectedDebugAndroidTest # 29 Compose UI tests (needs a device or emulator)
./gradlew lint                     # Android Lint
./gradlew assembleDebug assembleRelease
```

Unit tests cover value clamping and validation, unit conversion, trip maths, energy states, telltale
derivation, formatting, the demo simulator (determinism, ranges, trip reset preserving the odometer),
the demo flow's lifecycle, repository source switching and error handling, settings persistence
through a real DataStore, and both ViewModels. Instrumented tests cover rendering, unit switching,
trip reset with confirmation, fuel and battery display, telltale states, the demo badge, loading and
unavailable states, and the settings controls.

Instrumented tests need Espresso 3.7 or newer; earlier releases crash on recent Android versions.
