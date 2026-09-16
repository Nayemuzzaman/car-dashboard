# In-App Navigation / Map — Design

Date: 2026-09-13
Status: Implemented; superseded in part by `2026-09-16-navigation-map-completion-design.md` (tiles, routing provider, geocoder, screen modes)
Scope: an open-source, in-app turn-by-turn navigation screen behind isolated location / map /
routing interfaces, with heading-up camera, maneuver guidance, rerouting, day/night, and a seam for
future Android Auto / AAOS navigation.

---

## 1. Stack evaluation

| Concern | Decision | Why |
| --- | --- | --- |
| Map renderer | **MapLibre Native Android** (`org.maplibre.gl:android-sdk:11.13.5`) | BSD-2-Clause, GPU vector tiles, offline regions, minSdk 21. Maintained fork of Mapbox GL Native after Mapbox closed its license. |
| Tile data | **OpenStreetMap** via a configurable style URL | Open data; `MapStyleProvider` keeps the URL injectable and the offline-region seam ready. |
| Routing engine | **GraphHopper** (Apache-2.0, Java/JVM) behind `RoutingEngine` | The only one of OSRM/Valhalla/GraphHopper that can later run on-device/offline (JVM), and it runs as a self-hosted HTTP server today. |
| Routing deployment (v1) | **Self-hosted HTTP** | Open, no paid API; `RoutingEngine` + `RoutingResponseParser` keep OSRM/Valhalla swappable and on-device GraphHopper a later drop-in. |
| Turn-by-turn / reroute logic | **Own pure-Kotlin state machine** (`nav.engine`) | Testable on JVM for heading, maneuver progression, rerouting, lifecycle. The old MapLibre Navigation SDK (Mapbox Navigation v0.19 fork) is avoided. |
| Location / heading | **Platform `LocationManager` + `SensorManager`** (no Google Play services) | No proprietary dependency; GPS course while moving, compass azimuth while stationary. AAOS `CarSensors` can later replace these behind the same interfaces. |
| Tiles / routing / geocoder (v1.1) | OpenFreeMap / OSRM public + optional GraphHopper / Photon | See the v1.1 spec. |

---

## 2. Architecture

```
Compose UI            NavigationScreen (map + maneuver panel + ETA + destination picker)
   ▲                  stateless; renders NavigationUiState
ViewModel             NavigationViewModel — StateFlow
   ▲
Repository            NavigationRepository — route + session, staleness, reroute orchestration
   ▲
Engine (pure)         NavigationEngine / HeadingSmoother / ManeuverProgressTracker /
   │                  OffRouteDetector / RouteProgressCalculator   ← no Android imports
   ├─ LocationProvider ── GpsLocationProvider (LocationManager)
   ├─ HeadingProvider  ── CompassHeadingProvider (SensorManager)
   ├─ RoutingEngine    ── HttpRoutingEngine (self-hosted GraphHopper) / FakeRoutingEngine
   └─ NavigationMap    ── MapLibreNavigationMap (org.maplibre.gl:android-sdk)  ← replaceable seam
```

`nav.domain` and `nav.engine` import only `vehicle.domain.Clock` and `Signal` (shared primitives)
and contain no Android imports; a structural test enforces this, and JVM tests run against the
stubbed `android.jar` so an accidental Android call fails loudly.

---

## 3. Data-integrity rules

- **No placeholder position.** A missing GPS fix is `Signal.Unknown`, rendered as "No GPS fix",
  never `(0,0)` and never the last known position presented as current.
- **No fabricated route.** Before a route resolves the screen shows "No route" / "Finding route…".
- **Heading is sourced, never silently inferred.** Moving → GPS course-over-ground; stationary →
  compass azimuth; the choice is a tested policy, then smoothed.
- **Rerouting failure is a state.** Off-route triggers a reroute; failure shows "Unable to reroute".
- **ETA/remaining derive only from a resolved route plus a live fix.**

---

## 4. Key interfaces

- `RoutingEngine.route(RouteRequest): RouteResult`
- `LocationProvider.readings: Flow<LocationReading>` / `HeadingProvider.readings: Flow<Float>`
- `NavigationMap` (setStyle, showRoute, clearRoute, showPosition, followHeading,
  setInteractionEnabled, setMapTapListener)
- `ManeuverType` is a sealed hierarchy with a test-pinned vocabulary (turn left/right, roundabout,
  U-turn, exit, keep left/right, merge, depart, arrive, …).

Heading-up: `HeadingSmoother` turns the shortest arc (350°→10° is +20°, never -340°) and clamps
turn rate; the camera eases to the smoothed bearing. Maneuver phrasing uses "Turn left in 300 m",
"Turn right in 1.2 km", "Turn left now" (< 20 m), "At the roundabout, take the 2nd exit",
"Make a U-turn in …", "Arrive at destination". Off-route detection requires N consecutive fixes
beyond a lateral threshold; a single in-route fix resets the streak.

---

## 5. Android Auto / AAOS readiness

`NavigationState`/`NavigationEngine` are presentation-agnostic. Future Android Auto (Car App
Library projected) and AAOS `androidx.car.app` `NavigationTemplate` surfaces consume the same
`NavigationState`; `LocationProvider`/`HeadingProvider` are the seam for AAOS `CarSensors`. Car App
Library templates are **out of scope for v1**. No screen mirroring hacks.

---

## 6. Out of scope (v1)

Android Auto / AAOS navigation templates; on-device offline routing (embedded GraphHopper);
background navigation / foreground service; offline tile-region downloader UI. All are designed-for
but not shipped. (v1.1 added Photon search, OSRM routing, OpenFreeMap tiles, screen modes, persistent
recents.)

## 7. Verification status policy

Every claim is labelled **verified on device** or **implemented, device verification pending**. No
claim of real-vehicle navigation is made: no GPS-enabled device or routing server is available in
this environment.
