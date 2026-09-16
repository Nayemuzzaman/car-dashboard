# In-App Navigation / Map — Completion (v1.1) — Design

Date: 2026-09-16
Status: Implemented; emulator-verified 2026-09-16 (see README "Navigation")
Amends: `2026-09-13-in-app-navigation-map-design.md` (v1). Everything not mentioned here is unchanged.
Scope: make the navigation screen usable end-to-end on a real phone with no infrastructure —
street-level OpenStreetMap tiles, road routing from a public open server, search-as-you-type with
proximity bias, and a full-screen map with planning / overview / guidance modes.

---

## 1. What v1 left incomplete

| Gap | v1 behaviour | v1.1 |
| --- | --- | --- |
| Tiles | `demotiles.maplibre.org` — country outlines, no streets | OpenFreeMap OSM vector tiles, day + night |
| Routing | self-hosted GraphHopper only; otherwise straight line | GraphHopper (if configured) → OSRM public → straight line |
| Search | Nominatim on every keystroke (violates its usage policy); raw coordinates in results | Photon typeahead, biased to current fix, structured results with distance |
| Map-tap destination | shown as "23.810, 90.412" | reverse-geocoded to a place name |
| Camera | snaps to GPS fix on every update, even while the user pans | follow mode broken by gestures, Recenter restores |
| Route start | guidance begins the instant a route resolves | overview (fit whole route) → **Start** → guidance |
| Recents | in-memory, coordinates only | persisted, name + point |

---

## 2. Stack additions

| Concern | Decision | Why |
| --- | --- | --- |
| Tile style | **OpenFreeMap** `https://tiles.openfreemap.org/styles/liberty` (day) and `/styles/dark` (night) | Free, OSM-derived vector tiles, no API key, no usage cap for apps. Both URLs stay Gradle-overridable (`mapDayStyleUrl`, `mapNightStyleUrl`) so a self-hosted style is a config change. |
| Public routing | **OSRM** demo server `https://router.project-osrm.org` behind a new `OsrmRoutingEngine` | Open, no key, GeoJSON geometry and per-step maneuvers (`type`/`modifier`/`exit`) that map cleanly onto `ManeuverType`. Base URL is `BuildConfig.OSRM_BASE_URL`, overridable. |
| Geocoder | **Photon** `https://photon.komoot.io` behind a new `PhotonGeocodingEngine` | Open-source OSM geocoder built for search-as-you-type; supports `lat`/`lon` bias, `lang=en`, and `/reverse`. Nominatim forbids client-side autocomplete; its engine is kept as an alternative implementation. |

Attribution: the screen shows MapLibre's built-in attribution control (© OpenStreetMap contributors,
OpenFreeMap). README credits OSRM and Photon.

---

## 3. Routing chain

```
FallbackRoutingEngine(
    [HttpRoutingEngine(GraphHopper)]   ← only when BuildConfig.ROUTING_BASE_URL is non-empty
    OsrmRoutingEngine(OSRM_BASE_URL)
    StraightLineRoutingEngine()        ← Route.isPreview = true, UI shows "Straight-line preview"
)
```

- `FallbackRoutingEngine` takes an ordered list and returns the first `Success`; it is unchanged in
  spirit, generalised from two engines to N.
- `ROUTING_BASE_URL` default changes from `http://10.0.2.2:8989` to **empty**. A configured-but-dead
  GraphHopper URL would otherwise add a 10 s connect timeout to every route on a phone.
- `OsrmResponseParser` is a pure object (JVM-tested) mapping:
  - `depart` → `Depart`, `arrive` → `Arrive`
  - `turn`/`end of road`/`continue`/`new name`/`notification` + modifier → `TurnLeft/Right`,
    `SlightLeft/Right`, `SharpLeft/Right`, `UTurn`, `Continue`
  - `roundabout`/`rotary`/`roundabout turn` + `exit` → `Roundabout(exitNumber)`
  - `exit roundabout`/`exit rotary` → `Continue`
  - `fork` + modifier → `KeepLeft/Right`; `merge` → `Merge`; `on ramp` → `KeepLeft/Right`;
    `off ramp` → `Exit(number = null)`
  - anything else → `Unknown`
  - Geometry: `routes[0].geometry.coordinates` (`[lon, lat]`), per-step geometry from
    `legs[].steps[].geometry`. Non-`Ok` `code`, empty routes, or < 2 points → `RouteResult.Failure`.

---

## 4. Geocoding

```kotlin
data class Place(
    val name: String,
    val point: GeoPoint,
    val address: String? = null,   // "Street 12, City" — display-only
    val category: String? = null,  // osm_value, e.g. "aerodrome", "station"
)

interface GeocodingEngine {
    suspend fun search(query: String, near: GeoPoint?): GeocodeResult
    suspend fun reverse(point: GeoPoint): Place?
}
```

- `PhotonGeocodingEngine.search` calls `/api/?q=&limit=8&lang=en[&lat=&lon=]`;
  `reverse` calls `/reverse?lat=&lon=&lang=en` and returns null on failure (the UI then falls back
  to the coordinate string).
- `PhotonResponseParser` is pure: name from `properties.name` (fallback `street` + `housenumber`,
  then `city`, then coordinates), address from the non-null subset of `street`, `housenumber`,
  `city`, `country`, coordinates from `geometry.coordinates[1], [0]`.
- `NominatimGeocodingEngine` is updated to the new interface (`near` → `viewbox` bias,
  `reverse` → `/reverse`) but is no longer the default.
- ViewModel search policy: query must be ≥ 2 characters after trim; 400 ms debounce; `near` is the
  latest GPS fix if any; results are paired with `distanceMeters` from the fix via
  `GeoMath.distanceMeters` and formatted by `NavigationFormatter.formatDistance`.
- `GeocodeResult.Failure` becomes a visible `searchError: String?` ("Search unavailable") instead
  of an empty list. A blank query clears both results and error.

---

## 5. Screen modes

One full-screen `MapView`; the overlays depend on `ScreenMode`, derived in `NavigationViewModel`:

| Mode | Condition | Overlays | Camera |
| --- | --- | --- | --- |
| **Planning** | no destination | floating search bar (top), results list (LazyColumn, max 40% height) or recents; Back | last fix at zoom 15, or world if no fix |
| **Overview** | route resolved, `guidanceStarted == false` | bottom panel: destination name, distance, time, ETA, preview warning, **Start** / **Cancel** | `fitRoute` with padding, north-up |
| **Guidance** | route resolved, `guidanceStarted == true`, not arrived | maneuver panel with turn arrow + phrase, distance/time/ETA, speed, **End**; **Recenter** when `followMode == false` | heading-up follow at zoom 17 while `followMode` |
| **Arrived** | `NavigationPhase.Arrived` | "Arrived" panel with **Done** | hold |
| *(any)* | `rerouteState == Failed` / `awaitingRoute` | status text in the panel: "Unable to find route" / "Finding route…" | — |

- Zoom **+ / −** buttons sit on the right edge in every mode.
- A map tap in Planning mode sets the destination as `Place(coords, point)` immediately, then
  replaces the label with the reverse-geocoded name when it arrives.
- `fitRoute` runs once per resolved route (keyed on route identity), not on every GPS fix, so the
  Overview camera holds still while the user reads it.
- **Start** sets `guidanceStarted = true` and `followMode = true`. Any user pan / pinch / rotate
  sets `followMode = false`; **Recenter** sets it back to true. `guidanceStarted` resets to false
  whenever the destination changes or navigation ends.
- Turn arrows are drawn as text glyphs per `ManeuverType` (`↰ ↱ ↖ ↗ ↺ ⟳ ⬆ ⇲ …`) — no icon assets.

`NavigationMap` seam additions (implemented only in `MapLibreNavigationMap`):

```kotlin
fun fitRoute(route: Route, paddingPx: Int, bottomPaddingPx: Int)  // bottom clears the overview panel
fun showDestination(point: GeoPoint)
fun clearDestination()
fun zoomIn(); fun zoomOut()
fun setUserGestureListener(listener: () -> Unit)
```

---

## 6. State ownership

- `NavigationRepository`, `NavigationEngine`, providers, reroute logic: **unchanged**.
- `NavigationViewModel` adds `guidanceStarted`, `followMode`, `searchError`, `selectedPlace`
  (destination `Place` for the label), and exposes `screenMode: StateFlow<ScreenMode>` combined
  from repository snapshot + those flags.
- `RecentDestinationsStore` stores `Place` (name + point) and gains a `SharedPreferences`-backed
  implementation (JSON via kotlinx-serialization, max 8). `InMemoryRecentDestinationsStore` stays
  for tests.

---

## 7. Data-integrity rules (additions)

- A search result is never shown with a distance unless a live GPS fix exists.
- A reverse-geocode failure never blocks routing; the coordinate label stays.
- The straight-line preview warning is shown in Overview **and** Guidance whenever
  `Route.isPreview` — a straight line is never presented as a road route.

---

## 8. Testing

- JVM: `OsrmResponseParserTest` and `PhotonResponseParserTest` against fixtures captured from the
  live endpoints (OSRM fixture must include roundabout, U-turn, off-ramp, fork); `FallbackRoutingEngineTest`
  for N-engine ordering and empty-GraphHopper skipping; `NavigationViewModelTest` for mode
  transitions (Planning → Overview → Guidance → Arrived, Cancel/End resets, follow-mode toggling)
  and search policy (min length, debounce, error surfacing, distance pairing);
  `SharedPreferencesRecentDestinationsStoreTest` via a fake `SharedPreferences`.
- Compose (`androidTest`): `NavigationScreenTest` extended for the three panels with fake state.
- Device: emulator with `adb emu geo fix` stepping along an OSRM route — verify tiles render,
  search returns nearby places, Start fits route then follows heading-up, panning shows Recenter,
  End returns to Planning. Screenshots captured for the README.

## 9. Out of scope (still)

Android Auto / AAOS templates; offline routing and offline tile downloads; voice guidance; lane
guidance; traffic; multi-stop routes; alternative routes.

---

## 10. Addendum (2026-09-16, same day): planner and toll awareness

- **Routing chain** is now GraphHopper (if configured) → **Valhalla** (`valhalla1.openstreetmap.de`,
  `VALHALLA_BASE_URL`) → OSRM → straight line. Valhalla is the only open router that reports
  `has_toll` and per-maneuver `toll`, and accepts `costing_options.auto.use_tolls = 0`.
  `ValhallaResponseParser` decodes the precision-6 polyline (`Polyline`) and maps Valhalla type
  codes onto `ManeuverType`.
- **Domain:** `Route.hasToll: Boolean?` (null = unknown), `RouteStep.toll`, `RouteStep.roadName`;
  `RouteRequest.avoidTolls`. `NavigationRepository.setAvoidTolls` re-routes when the preference
  changes and every later route/reroute carries it.
- **UI:** `NavigationUiState.tollLabel` ("Toll road" / "Toll-free" / "Toll info unavailable" /
  "Tolls unavoidable"), `viaText` ("via <longest road>"), `nextStepToll`, `avoidTolls`. Overview
  shows the badge, via text and an **Avoid tolls** switch; Guidance shows a **Toll ahead** tag.
- **Planner:** two endpoint rows (Your location / Choose destination) with a ⇅ swap
  (`NavigationViewModel.swapEndpoints`; swapping "Your location" into the destination uses the
  current fix), category glyphs (`placeGlyph`) on suggestions, and a vertical Recent list.
- **Engine:** the off-route reroute trigger is a movement gate (`MIN_REROUTE_MOVE_METERS = 25`)
  instead of a rising edge, so a stationary off-road fix cannot poll the router after every new route.
