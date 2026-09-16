# In-App Navigation / Map — Implementation Plan

**Goal:** Add an open-source, in-app turn-by-turn navigation screen behind isolated
location / map / routing interfaces, with heading-up camera, maneuver guidance, rerouting, day/night,
and a seam for future Android Auto / AAOS navigation.

**Spec:** `docs/superpowers/specs/2026-09-13-in-app-navigation-map-design.md`

## Global constraints

- Do not commit unless asked.
- `nav.domain` and `nav.engine` contain no Android imports (structurally enforced + JVM tests).
- No proprietary map/routing dependency; no paid API. Self-hosted GraphHopper for routing.
- MapLibre stays behind `NavigationMap`; platform location stays behind `LocationProvider` /
  `HeadingProvider`; routing stays behind `RoutingEngine`.
- `ui/navigation` owns no data loops or timers.

## Task order (TDD, domain-first, network/map-last)

- [x] 0 Build infra (MapLibre + kotlinx-serialization deps, location/internet permissions)
- [x] 1 `nav.domain` (GeoPoint, ManeuverType sealed set, Route, NavigationState) + tests
- [x] 2 HeadingSmoother + heading source policy + tests
- [x] 3 ManeuverProgressTracker + ManeuverPhrasing + tests
- [x] 4 OffRouteDetector + reroute policy + GeoMath + tests
- [x] 5 RouteProgressCalculator (remaining + ETA) + tests
- [x] 6 NavigationEngine + tests
- [x] 7 RoutingEngine + FakeRoutingEngine + RoutingResponseParser + HttpRoutingEngine + tests
- [x] 8 LocationProvider + HeadingProvider + platform impls
- [x] 9 NavigationMap + MapStyleProvider + MapLibreNavigationMap
- [x] 10 NavigationRepository (route + session + staleness + reroute) + tests
- [x] 11 NavigationContainer + CarDashboardApplication wiring + shutdown
- [x] 12 NavigationUiState/Formatter/ViewModel + NavigationScreen + destination picker + recents
- [x] 13 dashboard NavigationChip + nav route + Compose UI smoke test
- [x] 14 structural guards + docs

## Verification

- `./gradlew testDebugUnitTest` — JVM unit tests (heading, maneuver progression, rerouting,
  lifecycle, routing parsing, repository orchestration, formatter, structural guards).
- `./gradlew connectedDebugAndroidTest` — instrumented tests on a device/emulator.
- Device checklist: GPS fix + heading-up rotation; tap and recent destination; route draw; maneuver
  phrasing including roundabout/U-turn; off-route reroute; day/night style; ETA only once navigating.

## Follow-up

Completed to a usable end-to-end state by `2026-09-16-navigation-map-completion.md`.
