# In-App Navigation — Drive Experience (v2) — Design

Date: 2026-09-27
Amends: `2026-09-16-navigation-map-completion-design.md` (v1.1). Everything not mentioned here is unchanged.
Trigger: field report — "once navigation starts I cannot end the trip", "text blends into the
background", "the map should rotate with the car". v1.1 was a working map viewer; v2 makes it
behave like a vehicle navigation system.

---

## 1. Root causes of the reported problems

| Report | Root cause | Fix |
| --- | --- | --- |
| Cannot end the trip | The **End** control is a `PillButton` with a dark-gray fill and `MaterialTheme.colorScheme.onSurface` text. In the light (day) theme `onSurface` is `#090E17`, so the label is near-black on `#1F2937`, 12 sp — effectively invisible. | Explicit navigation palette (§6) with checked contrast; End is a large red button in a fixed bottom bar; system Back asks "End navigation?". |
| Text blends into background | `DashboardPanel` hard-codes a **dark** surface while the overlays colour text from the **theme**; in day mode that is dark-on-dark everywhere. | Every nav overlay reads `NavColors` (day/night pairs), never the app theme or the dashboard panel. |
| Map does not follow the car's direction | Heading-up existed but (a) tilt 0 and the car centred, (b) one 300 ms ease per 1 Hz fix, so the camera stutters, (c) stationary heading came from the phone compass, which in a car mount is unrelated to the car and spins the map, (d) emulators and some chips omit GPS bearing, leaving no heading at all. | Frame-interpolated vehicle + camera (§4), last-reliable-course hold, course derived from successive fixes when the chip reports none. |

## 2. Engine changes (pure Kotlin, `nav/engine`)

- **`RouteIndex`** — built once per route: cumulative geometric distances, step start offsets from the
  steps' own geometry (so maneuver positions and the progress projection use the same metric), a
  windowed `project(point, fromMeters, toMeters)` returning distance-along, lateral offset, snapped
  point and segment bearing, and `split(alongMeters)` for traveled/remaining geometry.
- **`RouteProgressTracker`** — stateful, monotonic: searches a window around the last progress
  (−40 m … +max(300 m, 4 s × speed)) so a route that doubles back on itself cannot jump; falls back
  to a global search when the window's lateral error exceeds the off-route threshold.
- **Maneuvers** — `ManeuverProgress` now carries the step index, the road after the maneuver and the
  following ("then") maneuver when it is within 250 m of the next one. `ManeuverPhrasing.instruction`
  gives the banner text without distance ("Turn right onto Route 3"); the distance is a separate
  field. New types `RampLeft` / `RampRight` for highway entrances (OSRM `on ramp`, Valhalla 18/19).
- **`LocationFilter`** — rejects fixes with accuracy > 100 m (GPS quality `Poor`), rejects jumps
  implying > 70 m/s, holds position while stationary (speed < 0.5 m/s and within accuracy of the
  held point), and derives a course from successive fixes ≥ 8 m apart when the chip reports none.
  Never invents a position.
- **Heading** — `HeadingSourcePolicy`: GPS course while moving (≥ 2 m/s, bearing accuracy ≤ 35° when
  reported); when stationary or unreliable, **hold the last reliable course**; the compass is used
  only when no course has ever been reliable (parked at start). Smoother turn rate raised to 120°/s.
- **GPS loss** — a stale fix sets `gpsQuality = Lost`, keeps the last progress/maneuver/ETA instead
  of re-measuring from the route origin, and the phase stays `Navigating`. The marker is dimmed, not
  moved.
- **Off-route** — threshold `max(30 m, 1.5 × accuracy)` capped at 80 m; fixes with accuracy > 50 m
  neither extend nor reset the streak; 3 consecutive fixes **and** ≥ 4 s.
- **Camera policy** (`NavigationCameraPolicy`) — follow zoom from speed (17.5 at walking pace → 15
  at 110 km/h) and closer (≥ 17) within 200 m of a maneuver; tilt 45° heading-up, 0° north-up; the
  vehicle sits at 70 % of the map height.
- **Day/night** (`SolarDayNight`) — NOAA solar elevation; `Auto` picks night below −0.833°.

## 3. Repository / routing

- `RouteResult.Success(route, alternatives)`; Valhalla requests `alternates: 2`, OSRM
  `alternatives=true`. Overview lists them; choosing one makes it the active route. Reroutes use the
  primary only.
- A failed **re**route keeps the current route (state `Failed`, "Unable to reroute") and retries after
  15 s once the car has moved 25 m. Only a failed **initial** route clears the route; the Overview
  offers **Retry**.
- `VehicleDataProvider` (`nav/vehicle`): speed / gear / heading / ignition as nullable values with a
  timestamp. The only implementation adapts `VehicleRepository` and forwards **speed and gear**
  (OBD-II has no heading or ignition PID; those stay null — documented, not inferred). Snapshots
  from the `MOCK` source are ignored so simulated data can never steer navigation. Vehicle speed
  (≤ 2 s old) is used only for the stationary decision when the GPS reports none.

## 4. Map layer (`nav/map`, Android)

- `VehicleMarkerController` — a `ValueAnimator` interpolates position (linear) and bearing
  (shortest arc) from the displayed state to each new fix over the measured fix interval
  (300–1 500 ms). Each frame updates the puck source and, while following, moves the camera with
  `moveCamera` — the puck and the camera can never drift apart.
- `MapCameraController` — modes `Follow(HeadingUp|NorthUp)`, `Free`, `Overview`. Zoom and tilt ease
  toward the policy target (time constant 1.2 s). A gesture drops to `Free` synchronously inside the
  map callback so the next frame never fights the finger.
- Layers: alternatives (gray, under), route casing + remaining (blue), traveled (gray), destination
  pin, search-result dots, vehicle arrow (`icon-rotation-alignment=map`, `icon-pitch-alignment=map`),
  accuracy halo. The puck is gray when GPS is `Poor`/`Lost`.
- The route split (traveled/remaining) is updated at fix rate at the fix the puck is animating
  **from**, so the boundary stays under or behind the car.
- The map reports its bearing through a listener for the Compose compass; the compass rotates in a
  `graphicsLayer`, so the reading does not recompose the screen.

## 5. UI modes

| Mode | Top | Bottom | Right edge |
| --- | --- | --- | --- |
| Planning | "Where to?" planner (start/destination rows), category chips (Fuel, Parking, Food, Hospital, Charging, Hotel), results / recents | — | compass, my-location, zoom, theme |
| Overview | — | destination + address, route option cards, toll badge / via / avoid tolls, **Start** (56 dp), **Retry** on failure, ✕ | compass, zoom |
| Guidance | maneuver banner: glyph, distance (40 sp), instruction with road name; "Then ↰" strip; status strip (Rerouting…, GPS signal lost, Unable to reroute) | ETA (large), time · distance, **End** (red), route overview | compass (heading-up ⇄ north-up), Recenter when free, zoom; speed bubble bottom-left |
| Arrived | — | Arrived at <name>, **Done** | — |

- System Back: Guidance → "End navigation?" dialog; Overview → cancel route; Planning → dashboard.
- Keep-screen-on while in Guidance.
- Selecting a search result or category hit moves the camera to it (before the route resolves),
  places the destination pin and shows the Overview card.
- Search accepts coordinates ("23.8103, 90.4125", "23.8103 N 90.4125 E") via
  `CoordinateGeocodingEngine` wrapping Photon — no network round trip for coordinates.

## 6. Colours

`NavColors` day/night pairs; all body text ≥ 7:1 on its panel, secondary text ≥ 4.5:1, accent
buttons with dark-on-accent labels. Night route line `#60A5FA`, day `#1D6FE8`; traveled `#9CA3AF`.
The map style follows the chosen theme (Auto / Day / Night), not the system theme.

## 7. Background & lifecycle

- `NavigationService` (foreground, `foregroundServiceType="location"`) runs only while guidance is
  active, so a screen-off or app switch does not throttle GPS to a few fixes an hour. It posts an
  ongoing notification with the current instruction and an **End** action. Started from the
  foreground with fine location granted, so `ACCESS_BACKGROUND_LOCATION` is **not** requested.
- `NavigationContainer` reference-counts sensor use (visible navigation screen, service). At zero
  the GPS and compass stop — the dashboard alone no longer keeps GPS on.
- `MapView` follows the host lifecycle (start/resume/pause/stop), not just composition.

## 8. Testing

JVM: `RouteIndexTest`, `RouteProgressTrackerTest` (overlapping route), `LocationFilterTest`,
heading hold/derive, off-route with accuracy and time, GPS-loss hold, reroute-failure keeps route,
`NavigationCameraPolicyTest`, `SolarDayNightTest`, `CoordinateQueryParserTest`, alternatives in both
parsers, phrasing with road names, `VehicleDataProvider` mock exclusion, a **simulated drive**
through several turns → deviation → reroute → arrival → cancel, and ViewModel tests for End from
guidance, orientation toggle, route overview, alternative selection and retry.

## 9. Limitations (documented, not faked)

- No vehicle heading, ignition or gear-from-CAN source exists in this build; `VehicleDataProvider`
  only forwards what the OBD source reports (speed, gear when present).
- Android Auto / AAOS templates, offline tiles/routing, voice, lanes and traffic remain out of scope.
- Public Valhalla/OSRM/Photon have no SLA; alternatives depend on the server returning them.
