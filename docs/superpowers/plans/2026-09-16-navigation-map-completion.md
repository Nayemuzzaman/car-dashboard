# Navigation Map Completion (v1.1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the navigation screen work end-to-end on a phone with no infrastructure: real OSM street tiles, road routing from a public open server, search-as-you-type biased to the current fix, and a full-screen map with Planning → Overview → Guidance modes.

**Architecture:** The engine, repository, providers and reroute logic from v1 stay untouched. New providers plug into existing seams (`RoutingEngine`, `GeocodingEngine`, `MapStyleProvider`), the `NavigationMap` seam grows a few camera/marker methods, and `NavigationViewModel` gains the session flags (`guidanceStarted`, `followMode`) that drive a `ScreenMode`. The screen is split into a map host plus an overlays file.

**Tech Stack:** Kotlin 1.9.24, Compose BOM 2024.06.00, MapLibre Android 11.13.5, kotlinx-serialization-json 1.6.3 (no compiler plugin), kotlinx-coroutines-test 1.8.1, JUnit 4. Public services: OpenFreeMap tiles, OSRM demo router, Photon geocoder.

**Spec:** `docs/superpowers/specs/2026-09-16-navigation-map-completion-design.md` (amends `2026-09-13-in-app-navigation-map-design.md`)

## Global Constraints

- Do not commit unless the user asks. The "Commit" steps below are therefore **omitted**; each task ends with a passing test run instead.
- `nav/domain` and `nav/engine` contain no `import android` lines (`NavigationPurityTest`).
- `ui/navigation` contains no `while (` loops and no `delay(` calls (`NavigationPurityTest`).
- MapLibre types appear only in `nav/map/MapLibreNavigationMap.kt` and the `MapView` host in `ui/navigation/NavigationScreen.kt`.
- No proprietary map/routing/geocoding dependency, no API keys. Every base URL is a `BuildConfig` field overridable with `-P<name>=<url>`.
- `ROUTING_BASE_URL` (GraphHopper) default is the empty string; the GraphHopper engine is only constructed when it is non-blank.
- A straight-line route (`Route.isPreview == true`) is always labelled "Straight-line preview".
- Unit tests: `./gradlew testDebugUnitTest --offline -q`. Instrumented: `./gradlew connectedDebugAndroidTest` (emulator `emulator-5554` is running).
- Run a single test class with `./gradlew testDebugUnitTest --offline -q --tests "com.csjotlab.cardashboard.nav.routing.OsrmResponseParserTest"`.

---

## File structure

**Create**
- `app/src/main/java/com/csjotlab/cardashboard/nav/routing/OsrmResponseParser.kt` — pure OSRM JSON → `Route`
- `app/src/main/java/com/csjotlab/cardashboard/nav/routing/OsrmRoutingEngine.kt` — HTTP transport for OSRM
- `app/src/main/java/com/csjotlab/cardashboard/nav/geocoding/PhotonGeocodingEngine.kt` — Photon transport + `PhotonResponseParser`
- `app/src/main/java/com/csjotlab/cardashboard/nav/data/StringStorage.kt` — one-slot string persistence seam + SharedPreferences impl
- `app/src/main/java/com/csjotlab/cardashboard/nav/data/RecentDestinationsJson.kt` — pure codec for recents
- `app/src/main/java/com/csjotlab/cardashboard/ui/navigation/ScreenMode.kt` — `ScreenMode`, `SearchResultUi`, `SearchUiState`, `NavigationActions`
- `app/src/main/java/com/csjotlab/cardashboard/ui/navigation/ManeuverGlyph.kt` — `ManeuverType.glyph()`
- `app/src/main/java/com/csjotlab/cardashboard/ui/navigation/NavigationOverlays.kt` — search bar, results, recents, overview/guidance/arrived panels, map controls
- Tests: `nav/routing/OsrmResponseParserTest.kt`, `nav/routing/FallbackRoutingEngineTest.kt`, `nav/geocoding/PhotonResponseParserTest.kt`, `nav/fakes/FakeGeocodingEngine.kt`, `nav/fakes/InMemoryStringStorage.kt`, `nav/data/RecentDestinationsStoreTest.kt`, `nav/map/MapStyleConfigTest.kt`, `ui/navigation/ManeuverGlyphTest.kt`, `ui/navigation/NavigationViewModelTest.kt`

**Modify**
- `app/build.gradle.kts` — BuildConfig URLs
- `nav/map/MapStyleProvider.kt` — no demo default; URLs injected
- `nav/routing/FallbackRoutingEngine.kt` — ordered list of engines
- `nav/geocoding/GeocodingEngine.kt` — `Place` fields, `search(query, near)`, `reverse`
- `nav/geocoding/NominatimGeocodingEngine.kt` — new interface
- `nav/domain/GeoPoint.kt` — `toLabel()`
- `nav/data/RecentDestinationsStore.kt` — `Place`-based, persistent
- `nav/map/NavigationMap.kt`, `nav/map/MapLibreNavigationMap.kt` — seam additions
- `di/NavigationContainer.kt` — wiring
- `ui/navigation/NavigationUiState.kt` — `SEARCH_UNAVAILABLE`
- `ui/navigation/NavigationFormatter.kt` — `toSearchResult`
- `ui/navigation/NavigationViewModel.kt`, `ui/navigation/NavigationScreen.kt` — rewritten
- `navigation/CarDashboardApp.kt` — wiring
- `app/src/androidTest/.../ui/navigation/NavigationScreenTest.kt`
- `README.md`, both specs, v1 plan

---

### Task 1: Build config and OpenFreeMap style URLs

**Files:**
- Modify: `app/build.gradle.kts:6-13,26-27`
- Modify: `app/src/main/java/com/csjotlab/cardashboard/nav/map/MapStyleProvider.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/nav/map/MapStyleConfigTest.kt`

**Interfaces:**
- Produces: `BuildConfig.ROUTING_BASE_URL` (default `""`), `BuildConfig.OSRM_BASE_URL`, `BuildConfig.GEOCODING_BASE_URL` (now Photon), `BuildConfig.MAP_DAY_STYLE_URL`, `BuildConfig.MAP_NIGHT_STYLE_URL`; `ConfigurableMapStyleProvider(dayStyleUrl: String, nightStyleUrl: String)` with no defaults.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.csjotlab.cardashboard.nav.map

import com.csjotlab.cardashboard.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStyleConfigTest {

    @Test
    fun `day and night styles are distinct https urls and not the blank demo tiles`() {
        assertTrue(BuildConfig.MAP_DAY_STYLE_URL.startsWith("https://"))
        assertTrue(BuildConfig.MAP_NIGHT_STYLE_URL.startsWith("https://"))
        assertNotEquals(BuildConfig.MAP_DAY_STYLE_URL, BuildConfig.MAP_NIGHT_STYLE_URL)
        assertFalse(BuildConfig.MAP_DAY_STYLE_URL.contains("demotiles"))
        assertFalse(BuildConfig.MAP_NIGHT_STYLE_URL.contains("demotiles"))
    }

    @Test
    fun `graphhopper is opt-in and the public routers and geocoder are configured`() {
        assertEquals("", BuildConfig.ROUTING_BASE_URL)
        assertTrue(BuildConfig.OSRM_BASE_URL.startsWith("https://"))
        assertTrue(BuildConfig.GEOCODING_BASE_URL.startsWith("https://"))
    }

    @Test
    fun `provider returns the configured url per style`() {
        val provider = ConfigurableMapStyleProvider(dayStyleUrl = "https://day", nightStyleUrl = "https://night")

        assertEquals("https://day", provider.style(MapStyle.Day))
        assertEquals("https://night", provider.style(MapStyle.Night))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --offline -q --tests "com.csjotlab.cardashboard.nav.map.MapStyleConfigTest"`
Expected: compilation FAILS — `MAP_DAY_STYLE_URL`, `OSRM_BASE_URL` unresolved.

- [ ] **Step 3: Update `app/build.gradle.kts`**

Replace the two `val routingBaseUrl … val geocodingBaseUrl …` blocks (lines 6-13) with:

```kotlin
// Self-hosted GraphHopper Directions API. Empty (the default) means "not configured": the app then
// routes via the public OSRM server below. Set it for a device on the same LAN as a GraphHopper
// instance: `./gradlew assembleDebug -ProutingBaseUrl=http://<host-lan-ip>:8989`.
val routingBaseUrl = providers.gradleProperty("routingBaseUrl").getOrElse("")

// Public OSRM demo router (open, no key, no SLA). Override with a self-hosted OSRM for production.
val osrmBaseUrl = providers.gradleProperty("osrmBaseUrl").getOrElse("https://router.project-osrm.org")

// Photon (komoot) OSM geocoder: built for search-as-you-type, no key. Override with a self-hosted
// instance for heavier use.
val geocodingBaseUrl = providers.gradleProperty("geocodingBaseUrl").getOrElse("https://photon.komoot.io")

// OpenFreeMap OSM vector-tile styles (free, no key). Any MapLibre style URL works here.
val mapDayStyleUrl = providers.gradleProperty("mapDayStyleUrl").getOrElse("https://tiles.openfreemap.org/styles/liberty")
val mapNightStyleUrl = providers.gradleProperty("mapNightStyleUrl").getOrElse("https://tiles.openfreemap.org/styles/dark")
```

and replace the two `buildConfigField` lines inside `defaultConfig` with:

```kotlin
        buildConfigField("String", "ROUTING_BASE_URL", "\"$routingBaseUrl\"")
        buildConfigField("String", "OSRM_BASE_URL", "\"$osrmBaseUrl\"")
        buildConfigField("String", "GEOCODING_BASE_URL", "\"$geocodingBaseUrl\"")
        buildConfigField("String", "MAP_DAY_STYLE_URL", "\"$mapDayStyleUrl\"")
        buildConfigField("String", "MAP_NIGHT_STYLE_URL", "\"$mapNightStyleUrl\"")
```

- [ ] **Step 4: Rewrite `MapStyleProvider.kt`**

```kotlin
package com.csjotlab.cardashboard.nav.map

/**
 * Supplies a style URL per [MapStyle]. Kept behind an interface so day/night (and eventually
 * offline/self-hosted styles) are a configuration change, not a code change.
 */
interface MapStyleProvider {
    fun style(style: MapStyle): String
}

/**
 * Day/night style URLs injected from `BuildConfig` (OpenFreeMap by default). There is deliberately
 * no fallback URL here: a missing style is a build-configuration error, not something to paper over
 * with a blank demo map.
 */
class ConfigurableMapStyleProvider(
    private val dayStyleUrl: String,
    private val nightStyleUrl: String,
) : MapStyleProvider {
    override fun style(style: MapStyle): String = when (style) {
        MapStyle.Day -> dayStyleUrl
        MapStyle.Night -> nightStyleUrl
    }
}
```

`NavigationScreen.kt` currently calls `ConfigurableMapStyleProvider()` with no arguments; change that one call to
`ConfigurableMapStyleProvider(BuildConfig.MAP_DAY_STYLE_URL, BuildConfig.MAP_NIGHT_STYLE_URL)` and add
`import com.csjotlab.cardashboard.BuildConfig` so the module compiles (the screen is rewritten in Task 8).

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --offline -q --tests "com.csjotlab.cardashboard.nav.map.MapStyleConfigTest"`
Expected: PASS (3 tests).

---

### Task 2: OSRM response parser

**Files:**
- Create: `app/src/main/java/com/csjotlab/cardashboard/nav/routing/OsrmResponseParser.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/nav/routing/OsrmResponseParserTest.kt`

**Interfaces:**
- Consumes: `Route`, `RouteStep`, `Maneuver`, `ManeuverType` (`nav.domain`), `RouteResult` (`nav.routing`).
- Produces: `object OsrmResponseParser { fun parse(json: String): RouteResult; internal fun maneuverType(type: String?, modifier: String?, exit: Int?): ManeuverType }`.

OSRM step semantics match GraphHopper's: a step's `maneuver` happens at the step's start and its `distance`/`duration` are the travel until the next maneuver — exactly what `ManeuverProgressTracker` expects. Coordinates are `[lon, lat]`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.csjotlab.cardashboard.nav.routing

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OsrmResponseParserTest {

    // Shape captured from router.project-osrm.org (route/v1/driving, geometries=geojson, steps=true),
    // trimmed to the fields we read plus the ones we must ignore.
    private val osrmJson = """
        {
          "code": "Ok",
          "waypoints": [{"name": "Midsummer Boulevard", "location": [-0.759418, 52.040553]}],
          "routes": [
            {
              "distance": 1200.5,
              "duration": 95.4,
              "weight_name": "routability",
              "geometry": {
                "type": "LineString",
                "coordinates": [[-0.7594, 52.0405], [-0.7590, 52.0410], [-0.7580, 52.0420], [-0.7570, 52.0430], [-0.7560, 52.0440]]
              },
              "legs": [
                {
                  "steps": [
                    { "distance": 300.0, "duration": 20.0, "name": "Midsummer Boulevard", "mode": "driving",
                      "geometry": {"type": "LineString", "coordinates": [[-0.7594, 52.0405], [-0.7590, 52.0410]]},
                      "maneuver": {"type": "depart", "bearing_after": 326, "bearing_before": 0, "location": [-0.7594, 52.0405]} },
                    { "distance": 400.0, "duration": 30.0, "name": "V7 Saxon Gate", "mode": "driving",
                      "geometry": {"type": "LineString", "coordinates": [[-0.7590, 52.0410], [-0.7580, 52.0420]]},
                      "maneuver": {"type": "turn", "modifier": "right", "location": [-0.7590, 52.0410]} },
                    { "distance": 500.0, "duration": 45.0, "name": "H5 Portway", "mode": "driving",
                      "geometry": {"type": "LineString", "coordinates": [[-0.7580, 52.0420], [-0.7570, 52.0430]]},
                      "maneuver": {"type": "rotary", "modifier": "slight left", "exit": 3, "location": [-0.7580, 52.0420]} },
                    { "distance": 0.5, "duration": 0.4, "name": "H5 Portway", "mode": "driving",
                      "geometry": {"type": "LineString", "coordinates": [[-0.7570, 52.0430], [-0.7560, 52.0440]]},
                      "maneuver": {"type": "exit rotary", "modifier": "slight left", "exit": 3, "location": [-0.7570, 52.0430]} },
                    { "distance": 0.0, "duration": 0.0, "name": "Peterborough Gate", "mode": "driving",
                      "geometry": {"type": "LineString", "coordinates": [[-0.7560, 52.0440], [-0.7560, 52.0440]]},
                      "maneuver": {"type": "arrive", "location": [-0.7560, 52.0440]} }
                  ]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses an osrm route into domain steps with geometry in lat-lon order`() {
        val route = (OsrmResponseParser.parse(osrmJson) as RouteResult.Success).route

        assertEquals(GeoPoint(52.0405, -0.7594), route.origin)
        assertEquals(GeoPoint(52.0440, -0.7560), route.destination)
        assertEquals(5, route.geometry.size)
        assertEquals(1200.5f, route.totalDistanceMeters)
        assertEquals(95L, route.totalDurationSeconds)
        assertFalse(route.isPreview)

        assertEquals(5, route.steps.size)
        assertEquals(ManeuverType.Depart, route.steps[0].maneuver.type)
        assertEquals(300f, route.steps[0].distanceMeters)
        assertEquals(20L, route.steps[0].durationSeconds)
        assertEquals(listOf(GeoPoint(52.0405, -0.7594), GeoPoint(52.0410, -0.7590)), route.steps[0].geometry)

        assertEquals(ManeuverType.TurnRight, route.steps[1].maneuver.type)
        assertEquals("V7 Saxon Gate", route.steps[1].maneuver.instruction)
        assertEquals(ManeuverType.Roundabout(3), route.steps[2].maneuver.type)
        assertEquals(ManeuverType.Continue, route.steps[3].maneuver.type)
        assertEquals(ManeuverType.Arrive, route.steps[4].maneuver.type)
        assertEquals(0f, route.steps[4].distanceMeters)
    }

    @Test
    fun `maps every osrm maneuver type and modifier onto the domain vocabulary`() {
        val map = OsrmResponseParser::maneuverType

        assertEquals(ManeuverType.Depart, map("depart", null, null))
        assertEquals(ManeuverType.Arrive, map("arrive", null, null))
        assertEquals(ManeuverType.TurnLeft, map("turn", "left", null))
        assertEquals(ManeuverType.TurnRight, map("end of road", "right", null))
        assertEquals(ManeuverType.SlightLeft, map("turn", "slight left", null))
        assertEquals(ManeuverType.SlightRight, map("new name", "slight right", null))
        assertEquals(ManeuverType.SharpLeft, map("turn", "sharp left", null))
        assertEquals(ManeuverType.SharpRight, map("turn", "sharp right", null))
        assertEquals(ManeuverType.UTurn, map("turn", "uturn", null))
        assertEquals(ManeuverType.UTurn, map("continue", "uturn", null))
        assertEquals(ManeuverType.Continue, map("continue", "straight", null))
        assertEquals(ManeuverType.Continue, map("new name", null, null))
        assertEquals(ManeuverType.Continue, map("notification", "straight", null))
        assertEquals(ManeuverType.Roundabout(2), map("roundabout", "slight left", 2))
        assertEquals(ManeuverType.Roundabout(1), map("rotary", "straight", 1))
        assertEquals(ManeuverType.Roundabout(null), map("roundabout turn", "left", null))
        assertEquals(ManeuverType.Continue, map("exit roundabout", "slight left", 2))
        assertEquals(ManeuverType.Continue, map("exit rotary", "straight", 1))
        assertEquals(ManeuverType.KeepLeft, map("fork", "slight left", null))
        assertEquals(ManeuverType.KeepRight, map("fork", "right", null))
        assertEquals(ManeuverType.KeepRight, map("on ramp", "slight right", null))
        assertEquals(ManeuverType.KeepLeft, map("on ramp", "left", null))
        assertEquals(ManeuverType.Exit(null), map("off ramp", "slight right", null))
        assertEquals(ManeuverType.Merge, map("merge", "slight left", null))
        assertEquals(ManeuverType.Unknown, map("something new", "left", null))
        assertEquals(ManeuverType.Unknown, map(null, null, null))
    }

    @Test
    fun `a non-ok code is a failure carrying the server message`() {
        val json = """{"code":"NoRoute","message":"Impossible route between points"}"""

        val result = OsrmResponseParser.parse(json)

        assertTrue(result is RouteResult.Failure)
        assertTrue((result as RouteResult.Failure).reason.contains("NoRoute"))
        assertTrue(result.reason.contains("Impossible route"))
    }

    @Test
    fun `an empty routes array and malformed json are failures not throws`() {
        assertTrue(OsrmResponseParser.parse("""{"code":"Ok","routes":[]}""") is RouteResult.Failure)
        assertTrue(OsrmResponseParser.parse("not json") is RouteResult.Failure)
        assertTrue(OsrmResponseParser.parse("""{"code":"Ok","routes":[{"distance":1,"duration":1,"geometry":{"coordinates":[[0,0]]},"legs":[]}]}""") is RouteResult.Failure)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --offline -q --tests "com.csjotlab.cardashboard.nav.routing.OsrmResponseParserTest"`
Expected: compilation FAILS — `OsrmResponseParser` unresolved.

- [ ] **Step 3: Write `OsrmResponseParser.kt`**

```kotlin
package com.csjotlab.cardashboard.nav.routing

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.domain.RouteStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToLong

/**
 * Maps an OSRM `route/v1` response (`geometries=geojson&steps=true`) into our domain [Route].
 *
 * Pure and transport-independent, like [RoutingResponseParser] for GraphHopper. OSRM step semantics
 * are the ones [com.csjotlab.cardashboard.nav.engine.ManeuverProgressTracker] expects: the maneuver
 * happens at the start of the step and the step's distance is the travel until the next one. A
 * non-`Ok` code, an empty route list or malformed JSON is a [RouteResult.Failure], never a throw.
 */
object OsrmResponseParser {

    fun parse(json: String): RouteResult {
        return try {
            val root = Json.parseToJsonElement(json).jsonObject
            val code = root["code"]?.jsonPrimitive?.contentOrNull
            if (code != "Ok") {
                val message = root["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
                return RouteResult.Failure("OSRM returned ${code ?: "no code"}: $message".trimEnd(':', ' '))
            }
            val routes = root["routes"]?.jsonArray ?: return RouteResult.Failure("No routes in OSRM response")
            if (routes.isEmpty()) return RouteResult.Failure("No route in OSRM response")

            val route = routes[0].jsonObject
            val distanceMeters = route["distance"]?.jsonPrimitive?.doubleOrNull
                ?: return RouteResult.Failure("OSRM response is missing distance")
            val durationSeconds = route["duration"]?.jsonPrimitive?.doubleOrNull
                ?: return RouteResult.Failure("OSRM response is missing duration")

            val geometry = lineString(route["geometry"])
                ?: return RouteResult.Failure("OSRM response is missing geometry")
            if (geometry.size < 2) return RouteResult.Failure("OSRM response has too few points")

            val steps = route["legs"]?.jsonArray
                ?.flatMap { leg -> leg.jsonObject["steps"]?.jsonArray ?: JsonArray(emptyList()) }
                ?.map { parseStep(it.jsonObject) }
                ?: return RouteResult.Failure("OSRM response is missing steps")
            if (steps.size < 2) return RouteResult.Failure("OSRM response has too few steps")

            RouteResult.Success(
                Route(
                    origin = geometry.first(),
                    destination = geometry.last(),
                    steps = steps,
                    geometry = geometry,
                    totalDistanceMeters = distanceMeters.toFloat(),
                    totalDurationSeconds = durationSeconds.roundToLong(),
                ),
            )
        } catch (t: Throwable) {
            RouteResult.Failure(t.message ?: "Unable to parse OSRM response")
        }
    }

    private fun parseStep(json: JsonObject): RouteStep {
        val maneuver = json["maneuver"]?.jsonObject
        val type = maneuver?.get("type")?.jsonPrimitive?.contentOrNull
        val modifier = maneuver?.get("modifier")?.jsonPrimitive?.contentOrNull
        val exit = maneuver?.get("exit")?.jsonPrimitive?.intOrNull
        val roadName = json["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return RouteStep(
            maneuver = Maneuver(maneuverType(type, modifier, exit), roadName),
            distanceMeters = json["distance"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 0f,
            durationSeconds = json["duration"]?.jsonPrimitive?.doubleOrNull?.roundToLong() ?: 0L,
            geometry = lineString(json["geometry"]) ?: emptyList(),
        )
    }

    private fun lineString(element: JsonElement?): List<GeoPoint>? =
        element?.jsonObject?.get("coordinates")?.jsonArray?.map { toPoint(it.jsonArray) }

    private fun toPoint(coordinate: JsonArray): GeoPoint = GeoPoint(
        latitude = coordinate[1].jsonPrimitive.content.toDouble(),
        longitude = coordinate[0].jsonPrimitive.content.toDouble(),
    )

    internal fun maneuverType(type: String?, modifier: String?, exit: Int?): ManeuverType = when (type) {
        "depart" -> ManeuverType.Depart
        "arrive" -> ManeuverType.Arrive
        "roundabout", "rotary", "roundabout turn" -> ManeuverType.Roundabout(exit)
        "exit roundabout", "exit rotary" -> ManeuverType.Continue
        "fork", "on ramp" -> when (side(modifier)) {
            Side.Left -> ManeuverType.KeepLeft
            Side.Right -> ManeuverType.KeepRight
            Side.Straight -> ManeuverType.Continue
        }
        "off ramp" -> ManeuverType.Exit(null)
        "merge" -> ManeuverType.Merge
        "turn", "end of road", "continue", "new name", "notification", "use lane" -> fromModifier(modifier)
        else -> ManeuverType.Unknown
    }

    private enum class Side { Left, Right, Straight }

    private fun side(modifier: String?): Side = when {
        modifier == null -> Side.Straight
        modifier.endsWith("left") -> Side.Left
        modifier.endsWith("right") -> Side.Right
        else -> Side.Straight
    }

    private fun fromModifier(modifier: String?): ManeuverType = when (modifier) {
        null, "straight" -> ManeuverType.Continue
        "uturn" -> ManeuverType.UTurn
        "sharp right" -> ManeuverType.SharpRight
        "right" -> ManeuverType.TurnRight
        "slight right" -> ManeuverType.SlightRight
        "slight left" -> ManeuverType.SlightLeft
        "left" -> ManeuverType.TurnLeft
        "sharp left" -> ManeuverType.SharpLeft
        else -> ManeuverType.Unknown
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --offline -q --tests "com.csjotlab.cardashboard.nav.routing.OsrmResponseParserTest"`
Expected: PASS (4 tests).

---

### Task 3: OSRM transport, N-engine fallback chain, container wiring

**Files:**
- Create: `app/src/main/java/com/csjotlab/cardashboard/nav/routing/OsrmRoutingEngine.kt`
- Modify: `app/src/main/java/com/csjotlab/cardashboard/nav/routing/FallbackRoutingEngine.kt`
- Modify: `app/src/main/java/com/csjotlab/cardashboard/di/NavigationContainer.kt:34-37`
- Test: `app/src/test/java/com/csjotlab/cardashboard/nav/routing/FallbackRoutingEngineTest.kt`

**Interfaces:**
- Consumes: `OsrmResponseParser.parse`, `RoutingEngine`, `RouteRequest`, `RouteResult`, `FakeRoutingEngine` (test).
- Produces: `class FallbackRoutingEngine(engines: List<RoutingEngine>) : RoutingEngine`; `class OsrmRoutingEngine(baseUrl: String, profile: String = "driving", parse: (String) -> RouteResult = OsrmResponseParser::parse) : RoutingEngine`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.csjotlab.cardashboard.nav.routing

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.domain.RouteStep
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackRoutingEngineTest {

    private val request = RouteRequest(GeoPoint(0.0, 0.0), GeoPoint(0.01, 0.0))

    private fun route(tag: Float) = Route(
        origin = request.origin,
        destination = request.destination,
        steps = listOf(
            RouteStep(Maneuver(ManeuverType.Depart, null), tag, 1L, emptyList()),
            RouteStep(Maneuver(ManeuverType.Arrive, null), 0f, 0L, emptyList()),
        ),
        geometry = listOf(request.origin, request.destination),
        totalDistanceMeters = tag,
        totalDurationSeconds = 1L,
    )

    @Test
    fun `the first successful engine wins and later engines are not asked`() = runTest {
        val first = FakeRoutingEngine { RouteResult.Success(route(1f)) }
        val second = FakeRoutingEngine { RouteResult.Success(route(2f)) }

        val result = FallbackRoutingEngine(listOf(first, second)).route(request)

        assertEquals(1f, (result as RouteResult.Success).route.totalDistanceMeters)
        assertEquals(1, first.requests.size)
        assertEquals(0, second.requests.size)
    }

    @Test
    fun `a failing engine hands the same request to the next one`() = runTest {
        val first = FakeRoutingEngine { RouteResult.Failure("down") }
        val second = FakeRoutingEngine { RouteResult.Failure("also down") }
        val third = FakeRoutingEngine { RouteResult.Success(route(3f)) }

        val result = FallbackRoutingEngine(listOf(first, second, third)).route(request)

        assertEquals(3f, (result as RouteResult.Success).route.totalDistanceMeters)
        assertEquals(listOf(request), third.requests)
    }

    @Test
    fun `when every engine fails the last failure is returned`() = runTest {
        val engine = FallbackRoutingEngine(
            listOf(FakeRoutingEngine { RouteResult.Failure("first") }, FakeRoutingEngine { RouteResult.Failure("last") }),
        )

        val result = engine.route(request)

        assertTrue(result is RouteResult.Failure)
        assertEquals("last", (result as RouteResult.Failure).reason)
    }

    @Test
    fun `an empty chain is a configuration error`() {
        val thrown = runCatching { FallbackRoutingEngine(emptyList()) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --offline -q --tests "com.csjotlab.cardashboard.nav.routing.FallbackRoutingEngineTest"`
Expected: compilation FAILS — no `List` constructor on `FallbackRoutingEngine`.

- [ ] **Step 3: Rewrite `FallbackRoutingEngine.kt`**

```kotlin
package com.csjotlab.cardashboard.nav.routing

/**
 * Asks each engine in order and returns the first [RouteResult.Success]. The last engine in the
 * chain is expected to be [StraightLineRoutingEngine], which never fails and marks its result as a
 * preview, so the UI can always show *something* honest.
 */
class FallbackRoutingEngine(
    private val engines: List<RoutingEngine>,
) : RoutingEngine {

    init {
        require(engines.isNotEmpty()) { "FallbackRoutingEngine needs at least one engine" }
    }

    override suspend fun route(request: RouteRequest): RouteResult {
        var last: RouteResult = RouteResult.Failure("No routing engine configured")
        for (engine in engines) {
            last = engine.route(request)
            if (last is RouteResult.Success) return last
        }
        return last
    }
}
```

- [ ] **Step 4: Write `OsrmRoutingEngine.kt`**

```kotlin
package com.csjotlab.cardashboard.nav.routing

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin HTTP transport over an OSRM `route/v1` server. The public demo server is the default
 * (open, no key, no SLA); the base URL is injectable for a self-hosted instance. Parsing is
 * delegated to [OsrmResponseParser], so this class holds the only network code for OSRM.
 */
class OsrmRoutingEngine(
    private val baseUrl: String,
    private val profile: String = "driving",
    private val parse: (String) -> RouteResult = OsrmResponseParser::parse,
) : RoutingEngine {

    override suspend fun route(request: RouteRequest): RouteResult = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url(request)).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                // OSRM answers 4xx with a JSON body that carries the failure code; read whichever
                // stream has it so the reason reaches the UI instead of a bare exception.
                val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
                val body = stream?.bufferedReader()?.use { it.readText() }
                    ?: return@withContext RouteResult.Failure("OSRM returned HTTP ${connection.responseCode}")
                parse(body)
            } finally {
                connection.disconnect()
            }
        } catch (t: Throwable) {
            RouteResult.Failure(t.message ?: "Routing request failed")
        }
    }

    private fun url(request: RouteRequest): String {
        fun lonLat(p: GeoPoint) = "${p.longitude},${p.latitude}"
        return "$baseUrl/route/v1/$profile/${lonLat(request.origin)};${lonLat(request.destination)}" +
            "?overview=full&geometries=geojson&steps=true"
    }

    private companion object {
        const val USER_AGENT = "CarDashboard/1.0 (com.csjotlab.cardashboard)"
    }
}
```

- [ ] **Step 5: Wire the chain in `NavigationContainer.kt`**

Replace the `routingEngine` property (lines 34-37) with:

```kotlin
    // GraphHopper is opt-in (empty URL = skip it entirely, so a phone never waits on a connect
    // timeout to a server that isn't there); OSRM is the public default; straight-line is the
    // honest last resort.
    private val routingEngine = FallbackRoutingEngine(
        listOfNotNull(
            BuildConfig.ROUTING_BASE_URL.takeIf { it.isNotBlank() }?.let { HttpRoutingEngine(baseUrl = it) },
            OsrmRoutingEngine(baseUrl = BuildConfig.OSRM_BASE_URL),
            StraightLineRoutingEngine(),
        ),
    )
```

and add `import com.csjotlab.cardashboard.nav.routing.OsrmRoutingEngine`.

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --offline -q --tests "com.csjotlab.cardashboard.nav.routing.*"`
Expected: PASS — `FallbackRoutingEngineTest` (4), `OsrmResponseParserTest` (4), `RoutingResponseParserTest` unchanged.

---

### Task 4: Photon geocoder and the widened `GeocodingEngine` seam

**Files:**
- Modify: `app/src/main/java/com/csjotlab/cardashboard/nav/domain/GeoPoint.kt`
- Modify: `app/src/main/java/com/csjotlab/cardashboard/nav/geocoding/GeocodingEngine.kt`
- Create: `app/src/main/java/com/csjotlab/cardashboard/nav/geocoding/PhotonGeocodingEngine.kt`
- Modify: `app/src/main/java/com/csjotlab/cardashboard/nav/geocoding/NominatimGeocodingEngine.kt`
- Modify: `app/src/main/java/com/csjotlab/cardashboard/di/NavigationContainer.kt:39`
- Modify: `app/src/main/java/com/csjotlab/cardashboard/ui/navigation/NavigationViewModel.kt:67` (one-line compile fix; full rewrite in Task 6)
- Test: `app/src/test/java/com/csjotlab/cardashboard/nav/geocoding/PhotonResponseParserTest.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/nav/domain/GeoPointLabelTest.kt`
- Create (test fake): `app/src/test/java/com/csjotlab/cardashboard/nav/fakes/FakeGeocodingEngine.kt`

**Interfaces:**
- Produces:
  - `fun GeoPoint.toLabel(): String` — `"52.0405, -0.7594"` (4 decimals, Locale.US)
  - `data class Place(name: String, point: GeoPoint, address: String? = null, category: String? = null)`
  - `interface GeocodingEngine { suspend fun search(query: String, near: GeoPoint?): GeocodeResult; suspend fun reverse(point: GeoPoint): Place? }`
  - `object PhotonResponseParser { fun parse(json: String): GeocodeResult }`
  - `class PhotonGeocodingEngine(baseUrl: String, userAgent: String = …, language: String = "en", parse: (String) -> GeocodeResult = PhotonResponseParser::parse) : GeocodingEngine`
  - `class FakeGeocodingEngine(var searchResult: GeocodeResult, var reverseResult: Place?) : GeocodingEngine` with `searches: MutableList<Pair<String, GeoPoint?>>`, `reverses: MutableList<GeoPoint>`.

- [ ] **Step 1: Write the failing tests**

`GeoPointLabelTest.kt`:

```kotlin
package com.csjotlab.cardashboard.nav.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoPointLabelTest {
    @Test
    fun `label is lat comma lon with four decimals in us locale`() {
        assertEquals("23.8103, 90.4125", GeoPoint(23.81031, 90.41249).toLabel())
        assertEquals("-0.7594, 52.0000", GeoPoint(-0.7594, 52.0).toLabel())
    }
}
```

`PhotonResponseParserTest.kt`:

```kotlin
package com.csjotlab.cardashboard.nav.geocoding

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotonResponseParserTest {

    // Shape captured from photon.komoot.io/api/?q=dhaka%20airport&lang=en, trimmed.
    private val photonJson = """
        {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "geometry": {"type": "Point", "coordinates": [90.4053032, 23.8431441]},
              "properties": {
                "osm_type": "W", "osm_id": 1, "osm_key": "aeroway", "osm_value": "aerodrome",
                "name": "Hazrat Shahjalal International Airport",
                "city": "Dhaka", "country": "Bangladesh", "countrycode": "BD", "type": "house"
              }
            },
            {
              "type": "Feature",
              "geometry": {"type": "Point", "coordinates": [90.4081911, 23.8523202]},
              "properties": {
                "osm_key": "highway", "osm_value": "residential",
                "street": "Lane 11 East", "housenumber": "7", "city": "Dhaka", "country": "Bangladesh"
              }
            },
            {
              "type": "Feature",
              "geometry": {"type": "Point", "coordinates": [90.1, 23.9]},
              "properties": {"osm_key": "place", "osm_value": "locality", "country": "Bangladesh"}
            },
            {
              "type": "Feature",
              "geometry": {"type": "Point", "coordinates": ["bad"]},
              "properties": {"name": "Broken"}
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses named places with structured address and category`() {
        val places = (PhotonResponseParser.parse(photonJson) as GeocodeResult.Success).places

        assertEquals(3, places.size)
        assertEquals("Hazrat Shahjalal International Airport", places[0].name)
        assertEquals(GeoPoint(23.8431441, 90.4053032), places[0].point)
        assertEquals("Dhaka, Bangladesh", places[0].address)
        assertEquals("aerodrome", places[0].category)
    }

    @Test
    fun `an unnamed street result uses the street as its name and does not repeat it in the address`() {
        val places = (PhotonResponseParser.parse(photonJson) as GeocodeResult.Success).places

        assertEquals("Lane 11 East 7", places[1].name)
        assertEquals("Dhaka, Bangladesh", places[1].address)
        assertEquals("residential", places[1].category)
    }

    @Test
    fun `a result with nothing to name it falls back to its coordinates`() {
        val places = (PhotonResponseParser.parse(photonJson) as GeocodeResult.Success).places

        assertEquals("23.9000, 90.1000", places[2].name)
        assertEquals("Bangladesh", places[2].address)
    }

    @Test
    fun `a feature without usable coordinates is skipped not fatal`() {
        val places = (PhotonResponseParser.parse(photonJson) as GeocodeResult.Success).places
        assertTrue(places.none { it.name == "Broken" })
    }

    @Test
    fun `malformed json and a missing features array are failures`() {
        assertTrue(PhotonResponseParser.parse("nope") is GeocodeResult.Failure)
        assertTrue(PhotonResponseParser.parse("""{"type":"FeatureCollection"}""") is GeocodeResult.Failure)
        assertNull((PhotonResponseParser.parse("""{"features":[]}""") as GeocodeResult.Success).places.firstOrNull())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --offline -q --tests "com.csjotlab.cardashboard.nav.geocoding.*" --tests "com.csjotlab.cardashboard.nav.domain.GeoPointLabelTest"`
Expected: compilation FAILS — `toLabel`, `PhotonResponseParser` unresolved.

- [ ] **Step 3: Add `toLabel()` to `GeoPoint.kt`**

```kotlin
package com.csjotlab.cardashboard.nav.domain

import java.util.Locale

/** A WGS-84 coordinate. Degrees, not radians; latitude first. */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

/** The label of last resort when nothing named a place: "23.8103, 90.4125". */
fun GeoPoint.toLabel(): String = String.format(Locale.US, "%.4f, %.4f", latitude, longitude)
```

- [ ] **Step 4: Rewrite `GeocodingEngine.kt`**

```kotlin
package com.csjotlab.cardashboard.nav.geocoding

import com.csjotlab.cardashboard.nav.domain.GeoPoint

data class Place(
    val name: String,
    val point: GeoPoint,
    /** Display-only address line, e.g. "Lane 11 East, Dhaka, Bangladesh". */
    val address: String? = null,
    /** OSM feature value, e.g. "aerodrome", "station", "restaurant". */
    val category: String? = null,
)

sealed interface GeocodeResult {
    data class Success(val places: List<Place>) : GeocodeResult
    data class Failure(val reason: String) : GeocodeResult
}

/**
 * Turns free-text place queries into coordinates and coordinates back into names. Photon is the
 * open default; Nominatim is an alternative behind the same seam.
 */
interface GeocodingEngine {
    /** [near] biases ranking toward the driver's position; null when there is no fix. */
    suspend fun search(query: String, near: GeoPoint?): GeocodeResult

    /** Null when nothing is known about the point or the request failed — never a throw. */
    suspend fun reverse(point: GeoPoint): Place?
}
```

- [ ] **Step 5: Write `PhotonGeocodingEngine.kt`**

```kotlin
package com.csjotlab.cardashboard.nav.geocoding

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.toLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Thin HTTP transport over Photon (komoot's open OSM geocoder). Photon is built for
 * search-as-you-type and supports a lat/lon bias, which is why it replaced Nominatim as the
 * default: Nominatim's usage policy forbids client-side autocomplete.
 */
class PhotonGeocodingEngine(
    private val baseUrl: String,
    private val userAgent: String = "CarDashboard/1.0 (com.csjotlab.cardashboard)",
    private val language: String = "en",
    private val parse: (String) -> GeocodeResult = PhotonResponseParser::parse,
) : GeocodingEngine {

    override suspend fun search(query: String, near: GeoPoint?): GeocodeResult {
        if (query.isBlank()) return GeocodeResult.Success(emptyList())
        val encoded = URLEncoder.encode(query, "UTF-8")
        val bias = near?.let { "&lat=${it.latitude}&lon=${it.longitude}" }.orEmpty()
        return get("$baseUrl/api/?q=$encoded&limit=$LIMIT&lang=$language$bias")
    }

    override suspend fun reverse(point: GeoPoint): Place? =
        when (val result = get("$baseUrl/reverse?lat=${point.latitude}&lon=${point.longitude}&lang=$language")) {
            is GeocodeResult.Success -> result.places.firstOrNull()
            is GeocodeResult.Failure -> null
        }

    private suspend fun get(url: String): GeocodeResult = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", userAgent)
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parse(body)
            } finally {
                connection.disconnect()
            }
        } catch (t: Throwable) {
            GeocodeResult.Failure(t.message ?: "Search failed")
        }
    }

    private companion object {
        const val LIMIT = 8
    }
}

/** Pure Photon GeoJSON → [Place] mapping; JVM-tested, transport-independent. */
object PhotonResponseParser {

    fun parse(json: String): GeocodeResult = try {
        val features = Json.parseToJsonElement(json).jsonObject["features"]?.jsonArray
            ?: return GeocodeResult.Failure("No features in search response")
        GeocodeResult.Success(features.mapNotNull { runCatching { toPlace(it.jsonObject) }.getOrNull() })
    } catch (t: Throwable) {
        GeocodeResult.Failure(t.message ?: "Unable to parse search response")
    }

    private fun toPlace(feature: JsonObject): Place? {
        val coordinates = feature["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return null
        val longitude = coordinates.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return null
        val latitude = coordinates.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return null
        val point = GeoPoint(latitude, longitude)
        val properties = feature["properties"]?.jsonObject ?: JsonObject(emptyMap())

        fun property(key: String): String? =
            properties[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        val street = listOfNotNull(property("street"), property("housenumber"))
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
        val locality = property("city") ?: property("county") ?: property("state")
        val name = property("name") ?: street ?: locality ?: point.toLabel()
        val address = listOfNotNull(
            street?.takeIf { it != name },
            locality?.takeIf { it != name },
            property("country"),
        ).joinToString(", ").takeIf { it.isNotBlank() }

        return Place(name = name, point = point, address = address, category = property("osm_value"))
    }
}
```

- [ ] **Step 6: Update `NominatimGeocodingEngine.kt` to the new interface**

Replace the whole `search` function and add `reverse`:

```kotlin
    override suspend fun search(query: String, near: GeoPoint?): GeocodeResult {
        if (query.isBlank()) return GeocodeResult.Success(emptyList())
        val encoded = URLEncoder.encode(query, "UTF-8")
        // Nominatim has no proximity parameter; an unbounded viewbox around the fix ranks results
        // inside it first without excluding the rest of the world.
        val bias = near?.let {
            "&viewbox=${it.longitude - 0.5},${it.latitude + 0.5},${it.longitude + 0.5},${it.latitude - 0.5}&bounded=0"
        }.orEmpty()
        return get("$baseUrl/search?format=json&limit=5&q=$encoded$bias")
    }

    override suspend fun reverse(point: GeoPoint): Place? {
        val body = getBody("$baseUrl/reverse?format=json&lat=${point.latitude}&lon=${point.longitude}") ?: return null
        return NominatimResponseParser.parseReverse(body)
    }

    private suspend fun get(url: String): GeocodeResult {
        val body = getBody(url) ?: return GeocodeResult.Failure("Search failed")
        return parse(body)
    }

    private suspend fun getBody(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", userAgent)
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        } catch (t: Throwable) {
            null
        }
    }
```

Add to `NominatimResponseParser`:

```kotlin
    /** `/reverse?format=json` answers with one object, not an array. */
    fun parseReverse(json: String): Place? = try {
        val obj = Json.parseToJsonElement(json).jsonObject
        val lat = obj["lat"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        val lon = obj["lon"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        val name = obj["display_name"]?.jsonPrimitive?.contentOrNull
        if (lat == null || lon == null || name == null) null else Place(name, GeoPoint(lat, lon))
    } catch (t: Throwable) {
        null
    }
```

Add `import com.csjotlab.cardashboard.nav.domain.GeoPoint` at the top and replace the fully-qualified `com.csjotlab.cardashboard.nav.domain.GeoPoint(lat, lon)` in `parse` with `GeoPoint(lat, lon)`.

- [ ] **Step 7: Wire Photon in `NavigationContainer.kt` and keep the ViewModel compiling**

In `NavigationContainer.kt` replace line 39 with
`val geocodingEngine: GeocodingEngine = PhotonGeocodingEngine(baseUrl = BuildConfig.GEOCODING_BASE_URL)`
and swap the import `NominatimGeocodingEngine` → `PhotonGeocodingEngine`.

In `NavigationViewModel.kt` line 67 change `geocodingEngine.search(query)` to `geocodingEngine.search(query, near = null)` (the real search policy lands in Task 6).

- [ ] **Step 8: Write the test fake `FakeGeocodingEngine.kt`**

```kotlin
package com.csjotlab.cardashboard.nav.fakes

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.geocoding.GeocodeResult
import com.csjotlab.cardashboard.nav.geocoding.GeocodingEngine
import com.csjotlab.cardashboard.nav.geocoding.Place

class FakeGeocodingEngine(
    var searchResult: GeocodeResult = GeocodeResult.Success(emptyList()),
    var reverseResult: Place? = null,
) : GeocodingEngine {
    val searches = mutableListOf<Pair<String, GeoPoint?>>()
    val reverses = mutableListOf<GeoPoint>()

    override suspend fun search(query: String, near: GeoPoint?): GeocodeResult {
        searches += query to near
        return searchResult
    }

    override suspend fun reverse(point: GeoPoint): Place? {
        reverses += point
        return reverseResult
    }
}
```

- [ ] **Step 9: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --offline -q`
Expected: PASS — whole suite, including `PhotonResponseParserTest` (5), `GeoPointLabelTest` (1), and `NavigationPurityTest` (GeoPoint imports `java.util.Locale`, not Android).

---

### Task 5: Persistent, name-carrying recent destinations

**Files:**
- Create: `app/src/main/java/com/csjotlab/cardashboard/nav/data/StringStorage.kt`
- Create: `app/src/main/java/com/csjotlab/cardashboard/nav/data/RecentDestinationsJson.kt`
- Modify: `app/src/main/java/com/csjotlab/cardashboard/nav/data/RecentDestinationsStore.kt` (replace `InMemoryRecentDestinationsStore`)
- Modify: `app/src/main/java/com/csjotlab/cardashboard/di/NavigationContainer.kt:41`
- Create (test fake): `app/src/test/java/com/csjotlab/cardashboard/nav/fakes/InMemoryStringStorage.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/nav/data/RecentDestinationsStoreTest.kt`

**Interfaces:**
- Consumes: `Place`, `GeoPoint`.
- Produces:
  - `interface StringStorage { fun read(): String?; fun write(value: String) }`; `class SharedPreferencesStringStorage(prefs: SharedPreferences, key: String) : StringStorage`
  - `object RecentDestinationsJson { fun encode(places: List<Place>): String; fun decode(json: String?): List<Place> }`
  - `interface RecentDestinationsStore { fun recent(): List<Place>; fun record(place: Place) }`
  - `class PersistentRecentDestinationsStore(storage: StringStorage, maxItems: Int = 8) : RecentDestinationsStore`
  - `class InMemoryStringStorage(var value: String? = null) : StringStorage` (test)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.csjotlab.cardashboard.nav.data

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.fakes.InMemoryStringStorage
import com.csjotlab.cardashboard.nav.geocoding.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentDestinationsStoreTest {

    private val airport = Place("Airport", GeoPoint(23.8431, 90.4053), address = "Dhaka, Bangladesh", category = "aerodrome")
    private val station = Place("Station", GeoPoint(23.8523, 90.4082))

    @Test
    fun `json round-trips every place field and tolerates missing input`() {
        val encoded = RecentDestinationsJson.encode(listOf(airport, station))

        assertEquals(listOf(airport, station), RecentDestinationsJson.decode(encoded))
        assertTrue(RecentDestinationsJson.decode(null).isEmpty())
        assertTrue(RecentDestinationsJson.decode("garbage").isEmpty())
    }

    @Test
    fun `recording puts the newest first and dedups by point`() {
        val store = PersistentRecentDestinationsStore(InMemoryStringStorage())

        store.record(airport)
        store.record(station)
        store.record(airport.copy(name = "Airport (renamed)"))

        assertEquals(listOf("Airport (renamed)", "Station"), store.recent().map { it.name })
    }

    @Test
    fun `the list is capped at max items`() {
        val store = PersistentRecentDestinationsStore(InMemoryStringStorage(), maxItems = 2)

        store.record(airport)
        store.record(station)
        store.record(Place("Third", GeoPoint(1.0, 1.0)))

        assertEquals(listOf("Third", "Station"), store.recent().map { it.name })
    }

    @Test
    fun `recents survive a new store instance over the same storage`() {
        val storage = InMemoryStringStorage()
        PersistentRecentDestinationsStore(storage).record(airport)

        val reloaded = PersistentRecentDestinationsStore(storage)

        assertEquals(listOf(airport), reloaded.recent())
    }
}
```

`InMemoryStringStorage.kt` (test fake):

```kotlin
package com.csjotlab.cardashboard.nav.fakes

import com.csjotlab.cardashboard.nav.data.StringStorage

class InMemoryStringStorage(var value: String? = null) : StringStorage {
    override fun read(): String? = value
    override fun write(value: String) { this.value = value }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --offline -q --tests "com.csjotlab.cardashboard.nav.data.RecentDestinationsStoreTest"`
Expected: compilation FAILS — `StringStorage`, `RecentDestinationsJson`, `PersistentRecentDestinationsStore` unresolved.

- [ ] **Step 3: Write `StringStorage.kt`**

```kotlin
package com.csjotlab.cardashboard.nav.data

import android.content.SharedPreferences

/** One persisted string slot. SharedPreferences in the app; an in-memory value in tests. */
interface StringStorage {
    fun read(): String?
    fun write(value: String)
}

class SharedPreferencesStringStorage(
    private val prefs: SharedPreferences,
    private val key: String,
) : StringStorage {
    override fun read(): String? = prefs.getString(key, null)
    override fun write(value: String) = prefs.edit().putString(key, value).apply()
}
```

- [ ] **Step 4: Write `RecentDestinationsJson.kt`**

```kotlin
package com.csjotlab.cardashboard.nav.data

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.geocoding.Place
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Hand-built JSON for recents (no serialization compiler plugin in this project). */
object RecentDestinationsJson {

    fun encode(places: List<Place>): String = buildJsonArray {
        places.forEach { place ->
            add(
                buildJsonObject {
                    put("name", place.name)
                    put("lat", place.point.latitude)
                    put("lon", place.point.longitude)
                    place.address?.let { put("address", it) }
                    place.category?.let { put("category", it) }
                },
            )
        }
    }.toString()

    fun decode(json: String?): List<Place> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            Json.parseToJsonElement(json).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val lat = obj["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lon = obj["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                Place(
                    name = name,
                    point = GeoPoint(lat, lon),
                    address = obj["address"]?.jsonPrimitive?.contentOrNull,
                    category = obj["category"]?.jsonPrimitive?.contentOrNull,
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }
}
```

- [ ] **Step 5: Rewrite `RecentDestinationsStore.kt`**

```kotlin
package com.csjotlab.cardashboard.nav.data

import com.csjotlab.cardashboard.nav.geocoding.Place

/** Recently chosen destinations, newest first, with the name the user saw when choosing them. */
interface RecentDestinationsStore {
    fun recent(): List<Place>
    fun record(place: Place)
}

/**
 * Keeps recents in a [StringStorage] slot as JSON. Two records at the same point are one entry —
 * a map tap first lands as a coordinate label and is then renamed by reverse geocoding.
 */
class PersistentRecentDestinationsStore(
    private val storage: StringStorage,
    private val maxItems: Int = 8,
) : RecentDestinationsStore {

    private val items: MutableList<Place> = RecentDestinationsJson.decode(storage.read()).toMutableList()

    override fun recent(): List<Place> = items.toList()

    override fun record(place: Place) {
        items.removeAll { it.point == place.point }
        items.add(0, place)
        while (items.size > maxItems) items.removeAt(items.lastIndex)
        storage.write(RecentDestinationsJson.encode(items))
    }
}
```

- [ ] **Step 6: Wire it in `NavigationContainer.kt`**

Replace line 41 (`val recentDestinations: RecentDestinationsStore = InMemoryRecentDestinationsStore()`) with:

```kotlin
    val recentDestinations: RecentDestinationsStore = PersistentRecentDestinationsStore(
        SharedPreferencesStringStorage(
            context.getSharedPreferences("navigation", Context.MODE_PRIVATE),
            key = "recent_destinations",
        ),
    )
```

Swap the import `InMemoryRecentDestinationsStore` → `PersistentRecentDestinationsStore` and add `import com.csjotlab.cardashboard.nav.data.SharedPreferencesStringStorage`.

`NavigationViewModel.kt` still compiles against `recentStore.recent()`/`record(...)` only if its types line up; make the two minimal edits now (full rewrite in Task 6): `private val _recentDestinations = MutableStateFlow(recentStore.recent())` → type becomes `List<Place>`, so change `val recentDestinations: StateFlow<List<GeoPoint>>` to `StateFlow<List<Place>>` and in `selectDestination` change `recentStore.record(place.point)` to `recentStore.record(place)`. Delete `selectRecent` and its `GeoPoint` usage; in `NavigationScreen.kt` change `recentDestinations: List<GeoPoint>` to `List<Place>`, `onSelectRecent: (GeoPoint) -> Unit` to `(Place) -> Unit`, and in `RecentDestinations`/`DestinationChip` render `place.name` and call `onSelect(place)`. In `CarDashboardApp.kt` pass `onSelectRecent = navigationViewModel::selectDestination`.

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --offline -q`
Expected: PASS — whole suite including `RecentDestinationsStoreTest` (4).

---

### Task 6: ViewModel — screen modes, search policy, map-tap naming, follow mode

**Files:**
- Create: `app/src/main/java/com/csjotlab/cardashboard/ui/navigation/ScreenMode.kt`
- Modify: `app/src/main/java/com/csjotlab/cardashboard/ui/navigation/NavigationUiState.kt` (add one constant)
- Modify: `app/src/main/java/com/csjotlab/cardashboard/ui/navigation/NavigationFormatter.kt` (add `toSearchResult`)
- Rewrite: `app/src/main/java/com/csjotlab/cardashboard/ui/navigation/NavigationViewModel.kt`
- Test: `app/src/test/java/com/csjotlab/cardashboard/ui/navigation/NavigationViewModelTest.kt`
- Test: extend `app/src/test/java/com/csjotlab/cardashboard/ui/navigation/NavigationFormatterTest.kt`

**Interfaces:**
- Consumes: `NavigationRepository` (`snapshot`, `setDestination`, `setOrigin`), `RecentDestinationsStore`, `GeocodingEngine`, `GeoMath.distanceMeters`, `NavigationFormatter.formatRemainingDistance`, `GeoPoint.toLabel`.
- Produces (in `ScreenMode.kt`):
  - `enum class ScreenMode { Planning, Overview, Guidance, Arrived }`
  - `data class SearchResultUi(val place: Place, val distanceText: String?)`
  - `data class SearchUiState(val results: List<SearchResultUi>, val error: String?) { companion object { val Empty } }`
  - `data class NavigationActions(...)` — every screen callback with a no-op default (used by Task 8 and the Compose tests)
- Produces (ViewModel): `screenMode: StateFlow<ScreenMode>`, `followMode: StateFlow<Boolean>`, `destination: StateFlow<Place?>`, `originLabel: StateFlow<String>`, `searchQuery: StateFlow<String>`, `search: StateFlow<SearchUiState>`, `recentDestinations: StateFlow<List<Place>>`; functions `onSearchQueryChanged(String)`, `selectDestination(Place)`, `selectOrigin(Place)`, `useCurrentLocationAsOrigin()`, `selectMapPoint(GeoPoint)`, `startGuidance()`, `endNavigation()`, `onUserMovedMap()`, `recenter()`, `clearSearch()`.
- Constant: `SEARCH_UNAVAILABLE = "Search unavailable"`.

- [ ] **Step 1: Write `ScreenMode.kt`** (types the tests need)

```kotlin
package com.csjotlab.cardashboard.ui.navigation

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.geocoding.Place

/** Which overlay set the full-screen map shows. Derived in [NavigationViewModel], never in the screen. */
enum class ScreenMode { Planning, Overview, Guidance, Arrived }

/** A geocoder hit plus its distance from the current fix, already formatted; null when there is no fix. */
data class SearchResultUi(
    val place: Place,
    val distanceText: String?,
)

data class SearchUiState(
    val results: List<SearchResultUi>,
    val error: String?,
) {
    companion object {
        val Empty = SearchUiState(emptyList(), null)
    }
}

/** Every callback the navigation screen can raise, so the screen signature stays readable. */
data class NavigationActions(
    val onSearchQueryChanged: (String) -> Unit = {},
    val onSelectDestination: (Place) -> Unit = {},
    val onSelectOrigin: (Place) -> Unit = {},
    val onUseCurrentLocation: () -> Unit = {},
    val onMapTap: (GeoPoint) -> Unit = {},
    val onStart: () -> Unit = {},
    val onEnd: () -> Unit = {},
    val onRecenter: () -> Unit = {},
    val onUserMovedMap: () -> Unit = {},
    val onBack: () -> Unit = {},
) {
    companion object {
        val None = NavigationActions()
    }
}
```

- [ ] **Step 2: Write the failing formatter test** (append to `NavigationFormatterTest`)

```kotlin
    @Test
    fun `a search result carries a formatted distance only when a fix exists`() {
        val place = Place("Airport", GeoPoint(0.0, 0.0090))

        val withFix = NavigationFormatter.toSearchResult(place, near = GeoPoint(0.0, 0.0))
        val withoutFix = NavigationFormatter.toSearchResult(place, near = null)

        assertEquals("1.0 km", withFix.distanceText)
        assertEquals(place, withFix.place)
        assertNull(withoutFix.distanceText)
    }
```

(add `import com.csjotlab.cardashboard.nav.geocoding.Place`).

- [ ] **Step 3: Write the failing ViewModel test**

```kotlin
package com.csjotlab.cardashboard.ui.navigation

import com.csjotlab.cardashboard.nav.data.NavigationRepository
import com.csjotlab.cardashboard.nav.data.PersistentRecentDestinationsStore
import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Maneuver
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.domain.Route
import com.csjotlab.cardashboard.nav.domain.RouteStep
import com.csjotlab.cardashboard.nav.fakes.ControllableHeadingProvider
import com.csjotlab.cardashboard.nav.fakes.ControllableLocationProvider
import com.csjotlab.cardashboard.nav.fakes.FakeGeocodingEngine
import com.csjotlab.cardashboard.nav.fakes.InMemoryStringStorage
import com.csjotlab.cardashboard.nav.geocoding.GeocodeResult
import com.csjotlab.cardashboard.nav.geocoding.Place
import com.csjotlab.cardashboard.nav.location.LocationReading
import com.csjotlab.cardashboard.nav.routing.FakeRoutingEngine
import com.csjotlab.cardashboard.nav.routing.RouteResult
import com.csjotlab.cardashboard.vehicle.domain.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val here = GeoPoint(0.0, 0.0)
    private val airport = Place("Airport", GeoPoint(0.0, 0.0090), address = "Dhaka", category = "aerodrome")

    private val route = Route(
        origin = here,
        destination = airport.point,
        steps = listOf(
            RouteStep(Maneuver(ManeuverType.Depart, null), 1000f, 90L, emptyList()),
            RouteStep(Maneuver(ManeuverType.Arrive, null), 0f, 0L, emptyList()),
        ),
        geometry = listOf(here, airport.point),
        totalDistanceMeters = 1000f,
        totalDurationSeconds = 90L,
    )

    private fun fix(point: GeoPoint) = LocationReading(point, speedMps = 3f, courseDegrees = 90f, accuracyMeters = null, timestampMs = 0L)

    private class Harness(
        val viewModel: NavigationViewModel,
        val location: ControllableLocationProvider,
        val geocoder: FakeGeocodingEngine,
        val routing: FakeRoutingEngine,
    )

    private fun TestScope.harness(
        geocoder: FakeGeocodingEngine = FakeGeocodingEngine(),
        routing: FakeRoutingEngine = FakeRoutingEngine { RouteResult.Success(route) },
    ): Harness {
        val location = ControllableLocationProvider()
        val repository = NavigationRepository(
            routingEngine = routing,
            locationProvider = location,
            headingProvider = ControllableHeadingProvider(),
            clock = Clock { 0L },
            scope = backgroundScope,
        )
        val viewModel = NavigationViewModel(repository, PersistentRecentDestinationsStore(InMemoryStringStorage()), geocoder)
        // WhileSubscribed flows only run with a collector; keep the ones under test alive.
        backgroundScope.launch { viewModel.search.collect {} }
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return Harness(viewModel, location, geocoder, routing)
    }

    @Test
    fun `queries shorter than two characters never reach the geocoder`() = runTest(dispatcher.scheduler) {
        val h = harness()

        h.viewModel.onSearchQueryChanged("a")
        advanceTimeBy(1_000L); runCurrent()

        assertTrue(h.geocoder.searches.isEmpty())
        assertEquals(SearchUiState.Empty, h.viewModel.search.value)
    }

    @Test
    fun `search is debounced, biased to the current fix, and results carry a distance`() = runTest(dispatcher.scheduler) {
        val h = harness(FakeGeocodingEngine(searchResult = GeocodeResult.Success(listOf(airport))))
        h.location.emit(fix(here)); runCurrent()

        h.viewModel.onSearchQueryChanged("air")
        h.viewModel.onSearchQueryChanged("airp")
        advanceTimeBy(200L); runCurrent()
        assertTrue("still inside the debounce window", h.geocoder.searches.isEmpty())

        advanceTimeBy(300L); runCurrent()

        assertEquals(listOf("airp" to here), h.geocoder.searches)
        val result = h.viewModel.search.value.results.single()
        assertEquals(airport, result.place)
        assertEquals("1.0 km", result.distanceText)
        assertNull(h.viewModel.search.value.error)
    }

    @Test
    fun `without a fix the search is unbiased and results have no distance`() = runTest(dispatcher.scheduler) {
        val h = harness(FakeGeocodingEngine(searchResult = GeocodeResult.Success(listOf(airport))))

        h.viewModel.onSearchQueryChanged("airport")
        advanceTimeBy(500L); runCurrent()

        assertEquals(listOf("airport" to null), h.geocoder.searches)
        assertNull(h.viewModel.search.value.results.single().distanceText)
    }

    @Test
    fun `a geocoder failure is a visible error not an empty list`() = runTest(dispatcher.scheduler) {
        val h = harness(FakeGeocodingEngine(searchResult = GeocodeResult.Failure("boom")))

        h.viewModel.onSearchQueryChanged("airport")
        advanceTimeBy(500L); runCurrent()

        assertEquals(SEARCH_UNAVAILABLE, h.viewModel.search.value.error)
        assertTrue(h.viewModel.search.value.results.isEmpty())
    }

    @Test
    fun `clearing the query clears results immediately`() = runTest(dispatcher.scheduler) {
        val h = harness(FakeGeocodingEngine(searchResult = GeocodeResult.Success(listOf(airport))))
        h.viewModel.onSearchQueryChanged("airport")
        advanceTimeBy(500L); runCurrent()
        assertEquals(1, h.viewModel.search.value.results.size)

        h.viewModel.onSearchQueryChanged("")
        runCurrent()

        assertEquals(SearchUiState.Empty, h.viewModel.search.value)
    }

    @Test
    fun `choosing a destination enters overview, start enters guidance, end returns to planning`() = runTest(dispatcher.scheduler) {
        val h = harness()
        h.location.emit(fix(here)); runCurrent()
        assertEquals(ScreenMode.Planning, h.viewModel.screenMode.value)

        h.viewModel.selectDestination(airport)
        runCurrent()
        assertEquals(ScreenMode.Overview, h.viewModel.screenMode.value)
        assertEquals(airport, h.viewModel.destination.value)
        assertEquals(1, h.routing.requests.size)

        h.viewModel.startGuidance()
        runCurrent()
        assertEquals(ScreenMode.Guidance, h.viewModel.screenMode.value)
        assertTrue(h.viewModel.followMode.value)

        h.viewModel.endNavigation()
        runCurrent()
        assertEquals(ScreenMode.Planning, h.viewModel.screenMode.value)
        assertNull(h.viewModel.destination.value)
    }

    @Test
    fun `guidance ends in arrived when the fix reaches the destination`() = runTest(dispatcher.scheduler) {
        val h = harness()
        h.location.emit(fix(here)); runCurrent()
        h.viewModel.selectDestination(airport); runCurrent()
        h.viewModel.startGuidance(); runCurrent()

        h.location.emit(fix(airport.point)); runCurrent()

        assertEquals(ScreenMode.Arrived, h.viewModel.screenMode.value)
    }

    @Test
    fun `a new destination while guiding drops back to overview`() = runTest(dispatcher.scheduler) {
        val h = harness()
        h.location.emit(fix(here)); runCurrent()
        h.viewModel.selectDestination(airport); runCurrent()
        h.viewModel.startGuidance(); runCurrent()

        h.viewModel.selectDestination(Place("Station", GeoPoint(0.0, 0.02))); runCurrent()

        assertEquals(ScreenMode.Overview, h.viewModel.screenMode.value)
    }

    @Test
    fun `a user gesture breaks follow mode and recenter restores it`() = runTest(dispatcher.scheduler) {
        val h = harness()

        h.viewModel.onUserMovedMap()
        assertFalse(h.viewModel.followMode.value)

        h.viewModel.recenter()
        assertTrue(h.viewModel.followMode.value)
    }

    @Test
    fun `a map tap is labelled by its coordinates and then renamed by reverse geocoding`() = runTest(dispatcher.scheduler) {
        val tapped = GeoPoint(0.0, 0.0090)
        val h = harness(FakeGeocodingEngine(reverseResult = Place("Terminal 1", tapped, address = "Dhaka")))
        h.location.emit(fix(here)); runCurrent()

        h.viewModel.selectMapPoint(tapped)
        assertEquals("0.0000, 0.0090", h.viewModel.destination.value?.name)
        runCurrent()

        assertEquals(listOf(tapped), h.geocoder.reverses)
        assertEquals("Terminal 1", h.viewModel.destination.value?.name)
        assertEquals(ScreenMode.Overview, h.viewModel.screenMode.value)
        assertEquals(listOf("Terminal 1"), h.viewModel.recentDestinations.value.map { it.name })
    }

    @Test
    fun `a reverse geocode that fails leaves the coordinate label in place`() = runTest(dispatcher.scheduler) {
        val tapped = GeoPoint(0.0, 0.0090)
        val h = harness(FakeGeocodingEngine(reverseResult = null))

        h.viewModel.selectMapPoint(tapped); runCurrent()

        assertEquals("0.0000, 0.0090", h.viewModel.destination.value?.name)
    }

    @Test
    fun `selecting a destination records it in recents and clears the search`() = runTest(dispatcher.scheduler) {
        val h = harness()
        h.viewModel.onSearchQueryChanged("airport")

        h.viewModel.selectDestination(airport); runCurrent()

        assertEquals(listOf(airport), h.viewModel.recentDestinations.value)
        assertEquals("", h.viewModel.searchQuery.value)
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --offline -q --tests "com.csjotlab.cardashboard.ui.navigation.*"`
Expected: compilation FAILS — `toSearchResult`, `search`, `screenMode`, `selectMapPoint` … unresolved.

- [ ] **Step 5: Add the constant and formatter helper**

In `NavigationUiState.kt` add after `UNAVAILABLE`:

```kotlin
const val SEARCH_UNAVAILABLE = "Search unavailable"
```

In `NavigationFormatter.kt` add inside the object (imports: `com.csjotlab.cardashboard.nav.domain.GeoPoint`, `com.csjotlab.cardashboard.nav.engine.GeoMath`, `com.csjotlab.cardashboard.nav.geocoding.Place`):

```kotlin
    /** A distance is shown only when a live fix exists — never a distance from nowhere. */
    fun toSearchResult(place: Place, near: GeoPoint?): SearchResultUi = SearchResultUi(
        place = place,
        distanceText = near?.let { formatRemainingDistance(GeoMath.distanceMeters(it, place.point).toFloat()) },
    )
```

- [ ] **Step 6: Rewrite `NavigationViewModel.kt`**

```kotlin
package com.csjotlab.cardashboard.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.csjotlab.cardashboard.nav.data.NavigationRepository
import com.csjotlab.cardashboard.nav.data.RecentDestinationsStore
import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.NavigationPhase
import com.csjotlab.cardashboard.nav.domain.NavigationState
import com.csjotlab.cardashboard.nav.domain.toLabel
import com.csjotlab.cardashboard.nav.geocoding.GeocodeResult
import com.csjotlab.cardashboard.nav.geocoding.GeocodingEngine
import com.csjotlab.cardashboard.nav.geocoding.Place
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Session flags (`guidanceStarted`, `followMode`), the search policy, and the destination label
 * live here; route/position/reroute state stays in [NavigationRepository]. The screen only renders
 * what this class exposes.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class NavigationViewModel(
    private val repository: NavigationRepository,
    private val recentStore: RecentDestinationsStore,
    private val geocodingEngine: GeocodingEngine,
) : ViewModel() {

    val uiState: StateFlow<NavigationUiState> = repository.snapshot
        .map { snapshot -> NavigationFormatter.toUiState(snapshot, ::formatEtaTime) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NavigationUiState.idle())

    val navigationState: StateFlow<NavigationState> = repository.snapshot
        .map { it.state }
        .stateIn(viewModelScope, SharingStarted.Eagerly, NavigationState.idle())

    private val _guidanceStarted = MutableStateFlow(false)

    private val _followMode = MutableStateFlow(true)
    val followMode: StateFlow<Boolean> = _followMode.asStateFlow()

    val screenMode: StateFlow<ScreenMode> = combine(repository.snapshot, _guidanceStarted) { snapshot, started ->
        val state = snapshot.state
        when {
            snapshot.destination == null -> ScreenMode.Planning
            !started || state.route == null -> ScreenMode.Overview
            state.phase == NavigationPhase.Arrived -> ScreenMode.Arrived
            else -> ScreenMode.Guidance
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ScreenMode.Planning)

    private val _destination = MutableStateFlow<Place?>(null)
    val destination: StateFlow<Place?> = _destination.asStateFlow()

    private val _originLabel = MutableStateFlow(CURRENT_LOCATION_LABEL)
    val originLabel: StateFlow<String> = _originLabel.asStateFlow()

    private val _recentDestinations = MutableStateFlow(recentStore.recent())
    val recentDestinations: StateFlow<List<Place>> = _recentDestinations.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val search: StateFlow<SearchUiState> = _searchQuery
        .map { it.trim() }
        .distinctUntilChanged()
        // A cleared or too-short query resets instantly; a real query waits for typing to settle.
        .debounce { query -> if (query.length < MIN_QUERY_LENGTH) 0L else SEARCH_DEBOUNCE_MS }
        .mapLatest { query -> runSearch(query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState.Empty)

    private suspend fun runSearch(query: String): SearchUiState {
        if (query.length < MIN_QUERY_LENGTH) return SearchUiState.Empty
        val near = repository.snapshot.value.state.location.valueOrNull()
        return when (val result = geocodingEngine.search(query, near)) {
            is GeocodeResult.Success -> SearchUiState(result.places.map { NavigationFormatter.toSearchResult(it, near) }, null)
            is GeocodeResult.Failure -> SearchUiState(emptyList(), SEARCH_UNAVAILABLE)
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun clearSearch() {
        _searchQuery.value = ""
    }

    fun selectDestination(place: Place) {
        recentStore.record(place)
        _recentDestinations.value = recentStore.recent()
        _destination.value = place
        _guidanceStarted.value = false
        clearSearch()
        repository.setDestination(place.point)
    }

    /** A tapped point is usable immediately under a coordinate label; the name follows when known. */
    fun selectMapPoint(point: GeoPoint) {
        selectDestination(Place(point.toLabel(), point))
        viewModelScope.launch {
            val named = geocodingEngine.reverse(point) ?: return@launch
            if (_destination.value?.point != point) return@launch
            val place = named.copy(point = point)
            recentStore.record(place)
            _recentDestinations.value = recentStore.recent()
            _destination.value = place
        }
    }

    fun selectOrigin(place: Place) {
        _originLabel.value = place.name
        clearSearch()
        repository.setOrigin(place.point)
    }

    fun useCurrentLocationAsOrigin() {
        _originLabel.value = CURRENT_LOCATION_LABEL
        clearSearch()
        repository.setOrigin(null)
    }

    fun startGuidance() {
        _guidanceStarted.value = true
        _followMode.value = true
    }

    fun endNavigation() {
        _guidanceStarted.value = false
        _followMode.value = true
        _destination.value = null
        repository.setDestination(null)
    }

    fun onUserMovedMap() {
        _followMode.value = false
    }

    fun recenter() {
        _followMode.value = true
    }

    class Factory(
        private val repository: NavigationRepository,
        private val recentStore: RecentDestinationsStore,
        private val geocodingEngine: GeocodingEngine,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NavigationViewModel(repository, recentStore, geocodingEngine) as T
    }

    private companion object {
        const val CURRENT_LOCATION_LABEL = "Current location"
        const val MIN_QUERY_LENGTH = 2
        const val SEARCH_DEBOUNCE_MS = 400L
    }
}
```

`NavigationScreen.kt` and `CarDashboardApp.kt` no longer compile against `searchResults`/`destinationLabel`/`selectRecent`/`onEnd`. Make the smallest edits that compile — in `CarDashboardApp.kt` collect `search` and pass `searchResults = search.results.map { it.place }`, pass `destinationLabel = destination?.name`, `onSelectRecent = navigationViewModel::selectDestination`, `onEnd = navigationViewModel::endNavigation`; the screen is rewritten in Task 8.

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --offline -q`
Expected: PASS — whole suite, including `NavigationViewModelTest` (12) and the new formatter test; `NavigationPurityTest` still passes (no `delay(`/`while (` in `ui/navigation`).

---

### Task 7: `NavigationMap` seam additions and the MapLibre implementation

**Files:**
- Modify: `app/src/main/java/com/csjotlab/cardashboard/nav/map/NavigationMap.kt`
- Rewrite: `app/src/main/java/com/csjotlab/cardashboard/nav/map/MapLibreNavigationMap.kt`

**Interfaces:**
- Produces on `NavigationMap`: `centerOn(point: GeoPoint, zoom: Double, animateMs: Long)`, `fitRoute(route: Route, paddingPx: Int)`, `showDestination(point: GeoPoint)`, `clearDestination()`, `zoomIn()`, `zoomOut()`, `setUserGestureListener(listener: () -> Unit)`. `followHeading` keeps its signature but uses a per-map follow zoom that `zoomIn`/`zoomOut` adjust, so zooming during guidance sticks instead of being reset by the next fix.
- `MapLibreNavigationMap(map: MapLibreMap, styleProvider: MapStyleProvider, attributionTopMarginPx: Int)` — the extra parameter keeps the OSM attribution clear of the search bar.

No JVM test is possible for MapLibre; the interface change is verified by compilation, the behaviour on the emulator in Task 9.

- [ ] **Step 1: Extend `NavigationMap.kt`**

```kotlin
package com.csjotlab.cardashboard.nav.map

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Route

enum class MapStyle { Day, Night }

/**
 * The map-rendering seam. MapLibre is wrapped behind this the same way `usb-serial` is wrapped
 * behind `VehicleTransport`, so the rendering library stays replaceable and the navigation logic
 * stays testable without a map.
 */
interface NavigationMap {
    fun setStyle(style: MapStyle)
    fun showRoute(route: Route)
    fun clearRoute()
    fun showPosition(point: GeoPoint)
    fun showDestination(point: GeoPoint)
    fun clearDestination()
    /** North-up camera on [point]; used while planning so the driver sees where they are. */
    fun centerOn(point: GeoPoint, zoom: Double, animateMs: Long)
    /** Heading-up camera on [point] at the current follow zoom. */
    fun followHeading(bearingDegrees: Float, point: GeoPoint, animateMs: Long)
    /** North-up camera fitting the whole route inside [paddingPx]. */
    fun fitRoute(route: Route, paddingPx: Int)
    fun zoomIn()
    fun zoomOut()
    fun setInteractionEnabled(enabled: Boolean)
    fun setMapTapListener(listener: (GeoPoint) -> Unit)
    /** Fires at the start of any user pan / pinch / rotate — never for programmatic camera moves. */
    fun setUserGestureListener(listener: () -> Unit)
}
```

- [ ] **Step 2: Rewrite `MapLibreNavigationMap.kt`**

```kotlin
package com.csjotlab.cardashboard.nav.map

import android.view.Gravity
import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.Route
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.RotateGestureDetector
import org.maplibre.android.gestures.StandardScaleGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * MapLibre-backed [NavigationMap].
 *
 * It owns the small amount of MapLibre-specific rendering (a route line, a position dot, a
 * destination pin, and the camera) and nothing else. Heading smoothing and route/math decisions
 * live in `nav.engine`, not here.
 */
class MapLibreNavigationMap(
    private val map: MapLibreMap,
    private val styleProvider: MapStyleProvider,
    attributionTopMarginPx: Int,
) : NavigationMap {

    private var onTap: ((GeoPoint) -> Unit)? = null
    private var onUserGesture: (() -> Unit)? = null
    private var routeSource: GeoJsonSource? = null
    private var positionSource: GeoJsonSource? = null
    private var destinationSource: GeoJsonSource? = null
    private var routeToShow: Route? = null
    private var positionToShow: GeoPoint? = null
    private var destinationToShow: GeoPoint? = null
    private var currentStyle: MapStyle? = null
    private var followZoom = DEFAULT_FOLLOW_ZOOM

    init {
        map.addOnMapClickListener { latLng ->
            onTap?.invoke(GeoPoint(latLng.latitude, latLng.longitude))
            true
        }
        map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
            override fun onMoveBegin(detector: MoveGestureDetector) { onUserGesture?.invoke() }
            override fun onMove(detector: MoveGestureDetector) = Unit
            override fun onMoveEnd(detector: MoveGestureDetector) = Unit
        })
        map.addOnScaleListener(object : MapLibreMap.OnScaleListener {
            override fun onScaleBegin(detector: StandardScaleGestureDetector) { onUserGesture?.invoke() }
            override fun onScale(detector: StandardScaleGestureDetector) = Unit
            override fun onScaleEnd(detector: StandardScaleGestureDetector) = Unit
        })
        map.addOnRotateListener(object : MapLibreMap.OnRotateListener {
            override fun onRotateBegin(detector: RotateGestureDetector) { onUserGesture?.invoke() }
            override fun onRotate(detector: RotateGestureDetector) = Unit
            override fun onRotateEnd(detector: RotateGestureDetector) = Unit
        })
        // ODbL requires the OSM attribution to stay visible; the search bar covers the top-left
        // corner and the panel the bottom, so park it just under the search bar.
        map.uiSettings.attributionGravity = Gravity.TOP or Gravity.START
        map.uiSettings.setAttributionMargins(ATTRIBUTION_SIDE_MARGIN_PX, attributionTopMarginPx, 0, 0)
        map.uiSettings.logoGravity = Gravity.TOP or Gravity.START
        map.uiSettings.setLogoMargins(ATTRIBUTION_SIDE_MARGIN_PX, attributionTopMarginPx + LOGO_OFFSET_PX, 0, 0)
        map.uiSettings.isCompassEnabled = false
    }

    override fun setStyle(style: MapStyle) {
        if (currentStyle == style) return
        currentStyle = style
        map.setStyle(styleProvider.style(style)) { loadedStyle ->
            routeSource = null
            positionSource = null
            destinationSource = null
            ensureRouteLayer(loadedStyle)
            ensureDestinationLayer(loadedStyle)
            ensurePositionLayer(loadedStyle)
            routeToShow?.let { drawRoute(it) }
            destinationToShow?.let { drawDestination(it) }
            positionToShow?.let { drawPosition(it) }
        }
    }

    override fun showRoute(route: Route) {
        routeToShow = route
        map.getStyle { style ->
            ensureRouteLayer(style)
            drawRoute(route)
        }
    }

    override fun clearRoute() {
        routeToShow = null
        map.getStyle { style ->
            if (routeSource != null) {
                style.removeLayer(ROUTE_LAYER_ID)
                style.removeSource(ROUTE_SOURCE_ID)
                routeSource = null
            }
        }
    }

    override fun showPosition(point: GeoPoint) {
        positionToShow = point
        map.getStyle { style ->
            ensurePositionLayer(style)
            drawPosition(point)
        }
    }

    override fun showDestination(point: GeoPoint) {
        destinationToShow = point
        map.getStyle { style ->
            ensureDestinationLayer(style)
            drawDestination(point)
        }
    }

    override fun clearDestination() {
        destinationToShow = null
        map.getStyle { style ->
            if (destinationSource != null) {
                style.removeLayer(DESTINATION_LAYER_ID)
                style.removeSource(DESTINATION_SOURCE_ID)
                destinationSource = null
            }
        }
    }

    override fun centerOn(point: GeoPoint, zoom: Double, animateMs: Long) {
        val camera = CameraPosition.Builder()
            .target(LatLng(point.latitude, point.longitude))
            .zoom(zoom)
            .bearing(0.0)
            .tilt(0.0)
            .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(camera), animateMs.toInt())
    }

    override fun followHeading(bearingDegrees: Float, point: GeoPoint, animateMs: Long) {
        showPosition(point)
        val camera = CameraPosition.Builder()
            .target(LatLng(point.latitude, point.longitude))
            .zoom(followZoom)
            .bearing(bearingDegrees.toDouble())
            .tilt(FOLLOW_TILT)
            .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(camera), animateMs.toInt())
    }

    override fun fitRoute(route: Route, paddingPx: Int) {
        if (route.geometry.size < 2) return
        val bounds = LatLngBounds.Builder()
            .apply { route.geometry.forEach { include(LatLng(it.latitude, it.longitude)) } }
            .build()
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0.0, 0.0, paddingPx), FIT_ANIMATION_MS)
    }

    override fun zoomIn() {
        followZoom = (followZoom + 1.0).coerceAtMost(MAX_ZOOM)
        map.animateCamera(CameraUpdateFactory.zoomIn(), ZOOM_ANIMATION_MS)
    }

    override fun zoomOut() {
        followZoom = (followZoom - 1.0).coerceAtLeast(MIN_ZOOM)
        map.animateCamera(CameraUpdateFactory.zoomOut(), ZOOM_ANIMATION_MS)
    }

    override fun setInteractionEnabled(enabled: Boolean) {
        map.uiSettings.isRotateGesturesEnabled = enabled
        map.uiSettings.isScrollGesturesEnabled = enabled
        map.uiSettings.isZoomGesturesEnabled = enabled
    }

    override fun setMapTapListener(listener: (GeoPoint) -> Unit) {
        onTap = listener
    }

    override fun setUserGestureListener(listener: () -> Unit) {
        onUserGesture = listener
    }

    private fun ensureRouteLayer(style: Style) {
        if (routeSource != null) return
        val source = GeoJsonSource(ROUTE_SOURCE_ID, EMPTY_FEATURE_COLLECTION)
        style.addSource(source)
        val layer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID)
        layer.withProperties(
            PropertyFactory.lineColor(ROUTE_COLOR),
            PropertyFactory.lineWidth(ROUTE_WIDTH),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
        )
        style.addLayer(layer)
        routeSource = source
    }

    private fun drawRoute(route: Route) {
        routeSource?.setGeoJson(routeGeoJson(route))
    }

    private fun ensureDestinationLayer(style: Style) {
        if (destinationSource != null) return
        val source = GeoJsonSource(DESTINATION_SOURCE_ID, EMPTY_FEATURE_COLLECTION)
        style.addSource(source)
        val layer = CircleLayer(DESTINATION_LAYER_ID, DESTINATION_SOURCE_ID)
        layer.withProperties(
            PropertyFactory.circleColor(DESTINATION_COLOR),
            PropertyFactory.circleRadius(DESTINATION_RADIUS),
            PropertyFactory.circleStrokeWidth(DESTINATION_STROKE_WIDTH),
            PropertyFactory.circleStrokeColor(POSITION_STROKE_COLOR),
        )
        style.addLayer(layer)
        destinationSource = source
    }

    private fun drawDestination(point: GeoPoint) {
        destinationSource?.setGeoJson(pointGeoJson(point))
    }

    private fun ensurePositionLayer(style: Style) {
        if (positionSource != null) return
        val source = GeoJsonSource(POSITION_SOURCE_ID, EMPTY_FEATURE_COLLECTION)
        style.addSource(source)
        val layer = CircleLayer(POSITION_LAYER_ID, POSITION_SOURCE_ID)
        layer.withProperties(
            PropertyFactory.circleColor(POSITION_COLOR),
            PropertyFactory.circleRadius(POSITION_RADIUS),
            PropertyFactory.circleStrokeWidth(POSITION_STROKE_WIDTH),
            PropertyFactory.circleStrokeColor(POSITION_STROKE_COLOR),
        )
        style.addLayer(layer)
        positionSource = source
    }

    private fun drawPosition(point: GeoPoint) {
        positionSource?.setGeoJson(pointGeoJson(point))
    }

    private fun routeGeoJson(route: Route): String {
        val coordinates = route.geometry.joinToString(",") { "[${it.longitude},${it.latitude}]" }
        return """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coordinates]},"properties":{}}]}"""
    }

    private fun pointGeoJson(point: GeoPoint): String =
        """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[${point.longitude},${point.latitude}]},"properties":{}}]}"""

    private companion object {
        const val ROUTE_SOURCE_ID = "nav-route-source"
        const val ROUTE_LAYER_ID = "nav-route-layer"
        const val POSITION_SOURCE_ID = "nav-position-source"
        const val POSITION_LAYER_ID = "nav-position-layer"
        const val DESTINATION_SOURCE_ID = "nav-destination-source"
        const val DESTINATION_LAYER_ID = "nav-destination-layer"
        const val EMPTY_FEATURE_COLLECTION = """{"type":"FeatureCollection","features":[]}"""
        const val ROUTE_WIDTH = 6f
        const val POSITION_RADIUS = 10f
        const val POSITION_STROKE_WIDTH = 2f
        const val DESTINATION_RADIUS = 9f
        const val DESTINATION_STROKE_WIDTH = 3f
        const val DEFAULT_FOLLOW_ZOOM = 17.0
        const val MIN_ZOOM = 3.0
        const val MAX_ZOOM = 20.0
        const val FOLLOW_TILT = 0.0
        const val FIT_ANIMATION_MS = 600
        const val ZOOM_ANIMATION_MS = 200
        const val ATTRIBUTION_SIDE_MARGIN_PX = 16
        const val LOGO_OFFSET_PX = 40

        val ROUTE_COLOR = 0xFF38BDF8.toInt() // DashboardAccent
        val POSITION_COLOR = 0xFFF97316.toInt() // DashboardWarning
        val DESTINATION_COLOR = 0xFF38BDF8.toInt() // DashboardAccent
        val POSITION_STROKE_COLOR = 0xFFFFFFFF.toInt()
    }
}
```

Layer order matters: the route line is added first, then the destination pin, then the position dot, so the dot is always drawn on top.

- [ ] **Step 3: Keep `NavigationScreen.kt` compiling**

The screen's `MapLibreNavigationMap(mapLibreMap, ConfigurableMapStyleProvider(...))` call needs the new third argument; pass `attributionTopMarginPx = 0` for now (Task 8 computes the real value).

- [ ] **Step 4: Compile**

Run: `./gradlew compileDebugKotlin --offline -q`
Expected: BUILD SUCCESSFUL. If `org.maplibre.android.gestures.*` does not resolve, the gesture classes are in `org.maplibre.android.gestures` (transitive `maplibre-gestures-android`); check with `./gradlew app:dependencies --configuration debugRuntimeClasspath --offline | grep gestures`.

---

### Task 8: The full-screen map: overlays, modes, wiring, Compose tests

**Files:**
- Create: `app/src/main/java/com/csjotlab/cardashboard/ui/navigation/ManeuverGlyph.kt`
- Create: `app/src/main/java/com/csjotlab/cardashboard/ui/navigation/NavigationOverlays.kt`
- Rewrite: `app/src/main/java/com/csjotlab/cardashboard/ui/navigation/NavigationScreen.kt`
- Modify: `app/src/main/java/com/csjotlab/cardashboard/navigation/CarDashboardApp.kt:82-135`
- Test: `app/src/test/java/com/csjotlab/cardashboard/ui/navigation/ManeuverGlyphTest.kt`
- Rewrite: `app/src/androidTest/java/com/csjotlab/cardashboard/ui/navigation/NavigationScreenTest.kt`

**Interfaces:**
- Consumes: `ScreenMode`, `SearchUiState`, `SearchResultUi`, `NavigationActions`, `NavigationUiState`, `NavigationState`, `Place`, `NavigationMap` (all methods from Task 7), `BuildConfig.MAP_*_STYLE_URL`, `DashboardPanel` (internal, `ui.dashboard`).
- Produces: `fun ManeuverType.glyph(): String`; composable `NavigationScreen(uiState, navigationState, screenMode, followMode, originLabel, destination: Place?, searchQuery, search: SearchUiState, recentDestinations: List<Place>, actions: NavigationActions, modifier)`.

- [ ] **Step 1: Write the failing glyph test**

```kotlin
package com.csjotlab.cardashboard.ui.navigation

import com.csjotlab.cardashboard.nav.domain.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManeuverGlyphTest {

    private val everyType = listOf(
        ManeuverType.Depart, ManeuverType.Arrive, ManeuverType.Continue,
        ManeuverType.TurnLeft, ManeuverType.TurnRight, ManeuverType.SlightLeft, ManeuverType.SlightRight,
        ManeuverType.SharpLeft, ManeuverType.SharpRight, ManeuverType.UTurn,
        ManeuverType.Roundabout(2), ManeuverType.Exit("12"), ManeuverType.KeepLeft, ManeuverType.KeepRight,
        ManeuverType.Merge, ManeuverType.Unknown,
    )

    @Test
    fun `every maneuver type has a non-blank glyph`() {
        everyType.forEach { type -> assertTrue("$type", type.glyph().isNotBlank()) }
        // Pin the vocabulary size so a new ManeuverType forces a glyph decision here too.
        assertEquals(ManeuverType::class.sealedSubclasses.size, everyType.size)
    }

    @Test
    fun `left and right are visibly different`() {
        assertNotEquals(ManeuverType.TurnLeft.glyph(), ManeuverType.TurnRight.glyph())
        assertNotEquals(ManeuverType.SlightLeft.glyph(), ManeuverType.SlightRight.glyph())
        assertNotEquals(ManeuverType.KeepLeft.glyph(), ManeuverType.KeepRight.glyph())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --offline -q --tests "com.csjotlab.cardashboard.ui.navigation.ManeuverGlyphTest"`
Expected: compilation FAILS — `glyph` unresolved.

- [ ] **Step 3: Write `ManeuverGlyph.kt`**

```kotlin
package com.csjotlab.cardashboard.ui.navigation

import com.csjotlab.cardashboard.nav.domain.ManeuverType

/**
 * A turn arrow per maneuver, as a text glyph from the Unicode Arrows block (U+2190–U+21FF) so it
 * renders with the system font and needs no icon assets.
 */
fun ManeuverType.glyph(): String = when (this) {
    ManeuverType.Depart -> "●"
    ManeuverType.Arrive -> "◉"
    ManeuverType.Continue -> "↑"
    ManeuverType.TurnLeft -> "↰"
    ManeuverType.TurnRight -> "↱"
    ManeuverType.SlightLeft -> "↖"
    ManeuverType.SlightRight -> "↗"
    ManeuverType.SharpLeft -> "↙"
    ManeuverType.SharpRight -> "↘"
    ManeuverType.UTurn -> "↶"
    is ManeuverType.Roundabout -> "↻"
    is ManeuverType.Exit -> "⇗"
    ManeuverType.KeepLeft -> "⇖"
    ManeuverType.KeepRight -> "⇗"
    ManeuverType.Merge -> "⇑"
    ManeuverType.Unknown -> "•"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --offline -q --tests "com.csjotlab.cardashboard.ui.navigation.ManeuverGlyphTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Write `NavigationOverlays.kt`**

```kotlin
package com.csjotlab.cardashboard.ui.navigation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.csjotlab.cardashboard.nav.domain.ManeuverType
import com.csjotlab.cardashboard.nav.geocoding.Place
import com.csjotlab.cardashboard.ui.dashboard.DashboardPanel
import com.csjotlab.cardashboard.ui.theme.DashboardAccent
import com.csjotlab.cardashboard.ui.theme.DashboardSpacing
import com.csjotlab.cardashboard.ui.theme.DashboardSurface
import com.csjotlab.cardashboard.ui.theme.DashboardSurfaceHigh
import com.csjotlab.cardashboard.ui.theme.DashboardTextMuted
import com.csjotlab.cardashboard.ui.theme.DashboardWarning

private val OnAccent = Color(0xFF03111D)

// ---------------------------------------------------------------- Planning: top of the map

@Composable
internal fun SearchBar(
    query: String,
    hint: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillButton(text = "Back", accent = true, onClick = onBack)
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text(hint, color = DashboardTextMuted) },
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = RoundedCornerShape(8.dp),
        )
    }
}

@Composable
internal fun OriginRow(
    originLabel: String,
    choosingOrigin: Boolean,
    onToggleChoosingOrigin: () -> Unit,
    onUseCurrentLocation: () -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillButton(
            text = if (choosingOrigin) "Choosing start…" else "From: $originLabel",
            accent = choosingOrigin,
            onClick = onToggleChoosingOrigin,
        )
        if (choosingOrigin && originLabel != "Current location") {
            PillButton(text = "Use my current location", accent = false, onClick = onUseCurrentLocation)
        }
    }
}

@Composable
internal fun SearchResultsList(
    search: SearchUiState,
    maxHeight: Dp,
    onSelect: (Place) -> Unit,
) {
    DashboardPanel(modifier = Modifier.fillMaxWidth(), contentPadding = DashboardSpacing.small) {
        when {
            search.error != null -> Text(
                text = search.error,
                modifier = Modifier.padding(DashboardSpacing.small),
                style = MaterialTheme.typography.bodyMedium,
                color = DashboardWarning,
            )
            search.results.isEmpty() -> Text(
                text = "No places found",
                modifier = Modifier.padding(DashboardSpacing.small),
                style = MaterialTheme.typography.bodyMedium,
                color = DashboardTextMuted,
            )
            else -> LazyColumn(
                modifier = Modifier.heightIn(max = maxHeight),
                verticalArrangement = Arrangement.spacedBy(DashboardSpacing.tight),
            ) {
                items(search.results) { result ->
                    SearchResultRow(result = result, onClick = { onSelect(result.place) })
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(result: SearchResultUi, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = DashboardSurfaceHigh,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(vertical = DashboardSpacing.small, horizontal = DashboardSpacing.medium),
            horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DashboardSpacing.tight)) {
                Text(
                    text = result.place.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val secondary = listOfNotNull(result.place.category?.replace('_', ' '), result.place.address).joinToString(" · ")
                if (secondary.isNotBlank()) {
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.labelMedium,
                        color = DashboardTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            result.distanceText?.let { distance ->
                Text(
                    text = distance,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = DashboardAccent,
                )
            }
        }
    }
}

@Composable
internal fun RecentDestinationsRow(recent: List<Place>, onSelect: (Place) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(DashboardSpacing.small)) {
        Text(
            text = "Recent destinations",
            style = MaterialTheme.typography.labelMedium,
            color = DashboardTextMuted,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small),
        ) {
            recent.forEach { place ->
                PillButton(text = place.name, accent = false, onClick = { onSelect(place) })
            }
        }
    }
}

// ---------------------------------------------------------------- Overview / Guidance / Arrived: bottom panels

@Composable
internal fun OverviewPanel(
    uiState: NavigationUiState,
    destinationName: String?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    DashboardPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(DashboardSpacing.small)) {
            Text(
                text = destinationName ?: "Destination",
                style = MaterialTheme.typography.titleMedium,
                color = DashboardAccent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (uiState.hasRoute) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Readout(label = "Distance", value = uiState.remainingDistanceText)
                    Readout(label = "Time", value = uiState.remainingTimeText)
                    Readout(label = "ETA", value = uiState.etaText)
                }
            } else {
                Text(
                    text = uiState.statusLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (uiState.statusLabel == UNABLE_TO_FIND_ROUTE) DashboardWarning else DashboardTextMuted,
                )
            }
            uiState.previewLabel?.let { PreviewWarning(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.small)) {
                PillButton(text = "Start", accent = true, enabled = uiState.hasRoute, onClick = onStart)
                PillButton(text = "Cancel", accent = false, onClick = onCancel)
            }
        }
    }
}

@Composable
internal fun GuidancePanel(
    uiState: NavigationUiState,
    maneuverType: ManeuverType?,
    onEnd: () -> Unit,
) {
    DashboardPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(DashboardSpacing.small)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = uiState.statusLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (uiState.statusLabel == UNABLE_TO_REROUTE) DashboardWarning else DashboardAccent,
                )
                PillButton(text = "End", accent = false, onClick = onEnd)
            }
            uiState.previewLabel?.let { PreviewWarning(it) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(DashboardSpacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                maneuverType?.let { type ->
                    Text(
                        text = type.glyph(),
                        style = MaterialTheme.typography.displayLarge,
                        color = DashboardAccent,
                    )
                }
                uiState.maneuverText?.let { maneuver ->
                    Text(
                        text = maneuver,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Readout(label = "Distance", value = uiState.remainingDistanceText)
                Readout(label = "Time", value = uiState.remainingTimeText)
                Readout(label = "ETA", value = uiState.etaText)
            }
            Text(
                text = listOfNotNull(uiState.motionLabel, uiState.speedText).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = if (uiState.motionLabel == MOVING) DashboardAccent else DashboardTextMuted,
            )
        }
    }
}

@Composable
internal fun ArrivedPanel(destinationName: String?, onDone: () -> Unit) {
    DashboardPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(DashboardSpacing.small)) {
            Text(text = ARRIVED, style = MaterialTheme.typography.titleMedium, color = DashboardAccent)
            destinationName?.let {
                Text(text = it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            PillButton(text = "Done", accent = true, onClick = onDone)
        }
    }
}

// ---------------------------------------------------------------- Right edge: map controls

@Composable
internal fun MapControls(
    showRecenter: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onRecenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(DashboardSpacing.small)) {
        SquareButton(text = "+", contentDescription = "Zoom in", onClick = onZoomIn)
        SquareButton(text = "−", contentDescription = "Zoom out", onClick = onZoomOut)
        if (showRecenter) {
            SquareButton(text = "◎", contentDescription = "Recenter", accent = true, onClick = onRecenter)
        }
    }
}

// ---------------------------------------------------------------- Shared pieces

@Composable
private fun Readout(label: String, value: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(DashboardSpacing.tight)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = DashboardTextMuted)
        Text(
            text = value ?: UNAVAILABLE,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PreviewWarning(label: String) {
    Text(text = label, style = MaterialTheme.typography.labelMedium, color = DashboardWarning)
}

@Composable
internal fun PillButton(
    text: String,
    accent: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = when {
            !enabled -> DashboardSurface
            accent -> DashboardAccent
            else -> DashboardSurfaceHigh
        },
        enabled = enabled,
        onClick = onClick,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(vertical = DashboardSpacing.small, horizontal = DashboardSpacing.medium),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = when {
                !enabled -> DashboardTextMuted
                accent -> OnAccent
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SquareButton(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
    accent: Boolean = false,
) {
    Surface(
        modifier = Modifier
            .size(44.dp)
            .semantics { this.contentDescription = contentDescription },
        shape = RoundedCornerShape(8.dp),
        color = if (accent) DashboardAccent else DashboardSurface.copy(alpha = 0.92f),
        onClick = onClick,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(DashboardSpacing.small),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = if (accent) OnAccent else MaterialTheme.colorScheme.onSurface,
        )
    }
}
```

The square buttons carry a `contentDescription` so the Compose tests can find them with `onNodeWithContentDescription("Recenter")`.

- [ ] **Step 6: Rewrite `NavigationScreen.kt`**

```kotlin
package com.csjotlab.cardashboard.ui.navigation

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.csjotlab.cardashboard.BuildConfig
import com.csjotlab.cardashboard.nav.domain.NavigationState
import com.csjotlab.cardashboard.nav.geocoding.Place
import com.csjotlab.cardashboard.nav.map.ConfigurableMapStyleProvider
import com.csjotlab.cardashboard.nav.map.MapLibreNavigationMap
import com.csjotlab.cardashboard.nav.map.MapStyle
import com.csjotlab.cardashboard.nav.map.NavigationMap
import com.csjotlab.cardashboard.ui.theme.CarDashboardTheme
import com.csjotlab.cardashboard.ui.theme.DashboardSpacing
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

private val PlanningZoom = 15.0
private val FollowAnimationMs = 300L
private val CenterAnimationMs = 500L
private val RouteFitPadding = 80.dp
private val AttributionTop = 88.dp

/**
 * One full-screen map; the overlays depend on [screenMode]. The screen renders state and forwards
 * gestures — every decision (mode, follow, search policy) is made in [NavigationViewModel].
 */
@Composable
fun NavigationScreen(
    uiState: NavigationUiState,
    navigationState: NavigationState,
    screenMode: ScreenMode,
    followMode: Boolean,
    originLabel: String,
    destination: Place?,
    searchQuery: String,
    search: SearchUiState,
    recentDestinations: List<Place>,
    actions: NavigationActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val darkTheme = isSystemInDarkTheme()
    val currentActions by rememberUpdatedState(actions)
    var navigationMap by remember { mutableStateOf<NavigationMap?>(null) }
    val mapView = remember { MapView(context) }

    DisposableEffect(Unit) {
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        mapView.getMapAsync { mapLibreMap: MapLibreMap ->
            val wrapped = MapLibreNavigationMap(
                map = mapLibreMap,
                styleProvider = ConfigurableMapStyleProvider(BuildConfig.MAP_DAY_STYLE_URL, BuildConfig.MAP_NIGHT_STYLE_URL),
                attributionTopMarginPx = with(density) { AttributionTop.roundToPx() },
            )
            wrapped.setMapTapListener { point -> currentActions.onMapTap(point) }
            wrapped.setUserGestureListener { currentActions.onUserMovedMap() }
            navigationMap = wrapped
        }
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(navigationMap, darkTheme) {
        navigationMap?.setStyle(if (darkTheme) MapStyle.Night else MapStyle.Day)
    }

    val route = navigationState.route
    LaunchedEffect(navigationMap, route) {
        val map = navigationMap ?: return@LaunchedEffect
        if (route != null) map.showRoute(route) else map.clearRoute()
    }

    val destinationPoint = destination?.point
    LaunchedEffect(navigationMap, destinationPoint) {
        val map = navigationMap ?: return@LaunchedEffect
        if (destinationPoint != null) map.showDestination(destinationPoint) else map.clearDestination()
    }

    // Fit once per resolved route while in overview — keyed on the route object, not on fixes.
    LaunchedEffect(navigationMap, screenMode, route) {
        val map = navigationMap ?: return@LaunchedEffect
        if (screenMode == ScreenMode.Overview && route != null) {
            map.fitRoute(route, with(density) { RouteFitPadding.roundToPx() })
        }
    }

    val location = navigationState.location.valueOrNull()
    val heading = navigationState.headingDegrees.valueOrNull()
    LaunchedEffect(navigationMap, location, heading, screenMode, followMode) {
        val map = navigationMap ?: return@LaunchedEffect
        location ?: return@LaunchedEffect
        map.showPosition(location)
        if (!followMode) return@LaunchedEffect
        when (screenMode) {
            ScreenMode.Guidance ->
                if (heading != null) map.followHeading(heading, location, FollowAnimationMs)
                else map.centerOn(location, PlanningZoom, FollowAnimationMs)
            ScreenMode.Planning -> map.centerOn(location, PlanningZoom, CenterAnimationMs)
            ScreenMode.Overview, ScreenMode.Arrived -> Unit
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val resultsMaxHeight = maxHeight * 0.4f

            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            MapControls(
                showRecenter = !followMode && screenMode != ScreenMode.Overview,
                onZoomIn = { navigationMap?.zoomIn() },
                onZoomOut = { navigationMap?.zoomOut() },
                onRecenter = actions.onRecenter,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(DashboardSpacing.medium),
            )

            when (screenMode) {
                ScreenMode.Planning -> PlanningOverlay(
                    originLabel = originLabel,
                    searchQuery = searchQuery,
                    search = search,
                    recentDestinations = recentDestinations,
                    resultsMaxHeight = resultsMaxHeight,
                    actions = actions,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(DashboardSpacing.screenPadding),
                )

                ScreenMode.Overview -> Box(modifier = bottomPanelModifier()) {
                    OverviewPanel(
                        uiState = uiState,
                        destinationName = destination?.name,
                        onStart = actions.onStart,
                        onCancel = actions.onEnd,
                    )
                }

                ScreenMode.Guidance -> Box(modifier = bottomPanelModifier()) {
                    GuidancePanel(
                        uiState = uiState,
                        maneuverType = navigationState.nextManeuver?.type,
                        onEnd = actions.onEnd,
                    )
                }

                ScreenMode.Arrived -> Box(modifier = bottomPanelModifier()) {
                    ArrivedPanel(destinationName = destination?.name, onDone = actions.onEnd)
                }
            }
        }
    }
}

private fun BoxScope.bottomPanelModifier(): Modifier = Modifier
    .align(Alignment.BottomCenter)
    .fillMaxWidth()
    .padding(DashboardSpacing.screenPadding)

@Composable
private fun PlanningOverlay(
    originLabel: String,
    searchQuery: String,
    search: SearchUiState,
    recentDestinations: List<Place>,
    resultsMaxHeight: Dp,
    actions: NavigationActions,
    modifier: Modifier = Modifier,
) {
    var choosingOrigin by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(DashboardSpacing.small)) {
        SearchBar(
            query = searchQuery,
            hint = if (choosingOrigin) "Search start" else "Search destination",
            onQueryChange = actions.onSearchQueryChanged,
            onBack = actions.onBack,
        )
        OriginRow(
            originLabel = originLabel,
            choosingOrigin = choosingOrigin,
            onToggleChoosingOrigin = { choosingOrigin = !choosingOrigin },
            onUseCurrentLocation = {
                actions.onUseCurrentLocation()
                choosingOrigin = false
            },
        )
        when {
            searchQuery.isNotBlank() -> SearchResultsList(
                search = search,
                maxHeight = resultsMaxHeight,
                onSelect = { place ->
                    if (choosingOrigin) {
                        actions.onSelectOrigin(place)
                        choosingOrigin = false
                    } else {
                        actions.onSelectDestination(place)
                    }
                },
            )
            recentDestinations.isNotEmpty() -> RecentDestinationsRow(
                recent = recentDestinations,
                onSelect = actions.onSelectDestination,
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun NavigationScreenPreview() {
    CarDashboardTheme {
        NavigationScreen(
            uiState = NavigationUiState.idle(),
            navigationState = NavigationState.idle(),
            screenMode = ScreenMode.Planning,
            followMode = true,
            originLabel = "Current location",
            destination = null,
            searchQuery = "",
            search = SearchUiState.Empty,
            recentDestinations = emptyList(),
            actions = NavigationActions.None,
        )
    }
}
```

`SearchResultsList` shows "No places found" only once a query is typed and results are empty — but while the debounce is pending it would flash that message. Acceptable for v1.1: the debounce is 400 ms and the message is muted; do not add a "loading" state (YAGNI).

- [ ] **Step 7: Wire the screen in `CarDashboardApp.kt`**

Replace the block from `val uiState by navigationViewModel.uiState…` to the closing `)` of `NavigationScreen(` (lines ~112-135) with:

```kotlin
            val uiState by navigationViewModel.uiState.collectAsStateWithLifecycle()
            val navState by navigationViewModel.navigationState.collectAsStateWithLifecycle()
            val screenMode by navigationViewModel.screenMode.collectAsStateWithLifecycle()
            val followMode by navigationViewModel.followMode.collectAsStateWithLifecycle()
            val originLabel by navigationViewModel.originLabel.collectAsStateWithLifecycle()
            val destination by navigationViewModel.destination.collectAsStateWithLifecycle()
            val searchQuery by navigationViewModel.searchQuery.collectAsStateWithLifecycle()
            val search by navigationViewModel.search.collectAsStateWithLifecycle()
            val recent by navigationViewModel.recentDestinations.collectAsStateWithLifecycle()
            NavigationScreen(
                uiState = uiState,
                navigationState = navState,
                screenMode = screenMode,
                followMode = followMode,
                originLabel = originLabel,
                destination = destination,
                searchQuery = searchQuery,
                search = search,
                recentDestinations = recent,
                actions = NavigationActions(
                    onSearchQueryChanged = navigationViewModel::onSearchQueryChanged,
                    onSelectDestination = navigationViewModel::selectDestination,
                    onSelectOrigin = navigationViewModel::selectOrigin,
                    onUseCurrentLocation = navigationViewModel::useCurrentLocationAsOrigin,
                    onMapTap = navigationViewModel::selectMapPoint,
                    onStart = navigationViewModel::startGuidance,
                    onEnd = navigationViewModel::endNavigation,
                    onRecenter = navigationViewModel::recenter,
                    onUserMovedMap = navigationViewModel::onUserMovedMap,
                    onBack = { navController.popBackStack() },
                ),
            )
```

Add `import com.csjotlab.cardashboard.ui.navigation.NavigationActions`.

- [ ] **Step 8: Compile and run the JVM suite**

Run: `./gradlew compileDebugKotlin --offline -q && ./gradlew testDebugUnitTest --offline -q`
Expected: BUILD SUCCESSFUL, all unit tests PASS (including `NavigationPurityTest` — no loops/timers in `ui/navigation`).

- [ ] **Step 9: Rewrite the Compose test `NavigationScreenTest.kt`**

```kotlin
package com.csjotlab.cardashboard.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.domain.NavigationState
import com.csjotlab.cardashboard.nav.geocoding.Place
import com.csjotlab.cardashboard.ui.theme.CarDashboardTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val airport = Place("Airport", GeoPoint(23.84, 90.40), address = "Dhaka, Bangladesh", category = "aerodrome")

    private fun show(
        uiState: NavigationUiState = NavigationUiState.idle(),
        screenMode: ScreenMode = ScreenMode.Planning,
        followMode: Boolean = true,
        destination: Place? = null,
        searchQuery: String = "",
        search: SearchUiState = SearchUiState.Empty,
        recent: List<Place> = emptyList(),
        actions: NavigationActions = NavigationActions.None,
    ) {
        rule.setContent {
            CarDashboardTheme {
                NavigationScreen(
                    uiState = uiState,
                    navigationState = NavigationState.idle(),
                    screenMode = screenMode,
                    followMode = followMode,
                    originLabel = "Current location",
                    destination = destination,
                    searchQuery = searchQuery,
                    search = search,
                    recentDestinations = recent,
                    actions = actions,
                )
            }
        }
    }

    @Test
    fun planningShowsSearchAndRecents() {
        show(recent = listOf(airport))

        rule.onNodeWithText("Search destination").assertIsDisplayed()
        rule.onNodeWithText("From: Current location").assertIsDisplayed()
        rule.onNodeWithText("Recent destinations").assertIsDisplayed()
        rule.onNodeWithText("Airport").assertIsDisplayed()
    }

    @Test
    fun searchResultsShowNameAddressAndDistanceAndSelect() {
        var selected: Place? = null
        show(
            searchQuery = "air",
            search = SearchUiState(listOf(SearchResultUi(airport, "2.3 km")), null),
            actions = NavigationActions(onSelectDestination = { selected = it }),
        )

        rule.onNodeWithText("Airport").assertIsDisplayed()
        rule.onNodeWithText("aerodrome · Dhaka, Bangladesh").assertIsDisplayed()
        rule.onNodeWithText("2.3 km").assertIsDisplayed()
        rule.onNodeWithText("Airport").performClick()
        assertTrue(selected == airport)
    }

    @Test
    fun searchFailureIsVisible() {
        show(searchQuery = "air", search = SearchUiState(emptyList(), SEARCH_UNAVAILABLE))

        rule.onNodeWithText(SEARCH_UNAVAILABLE).assertIsDisplayed()
    }

    @Test
    fun overviewShowsRouteSummaryAndStart() {
        val ui = NavigationUiState.idle().copy(
            statusLabel = ROUTE_READY,
            remainingDistanceText = "12 km",
            remainingTimeText = "17 min",
            etaText = "14:32",
            hasRoute = true,
        )
        var started = false
        show(uiState = ui, screenMode = ScreenMode.Overview, destination = airport, actions = NavigationActions(onStart = { started = true }))

        rule.onNodeWithText("Airport").assertIsDisplayed()
        rule.onNodeWithText("12 km").assertIsDisplayed()
        rule.onNodeWithText("Start").performClick()
        assertTrue(started)
    }

    @Test
    fun overviewWithoutRouteShowsStatusAndDisablesStart() {
        val ui = NavigationUiState.idle().copy(statusLabel = FINDING_ROUTE)
        show(uiState = ui, screenMode = ScreenMode.Overview, destination = airport)

        rule.onNodeWithText(FINDING_ROUTE).assertIsDisplayed()
        rule.onNodeWithText("Start").assertIsNotEnabled()
    }

    @Test
    fun guidanceShowsManeuverAndRecenterWhenNotFollowing() {
        val ui = NavigationUiState.idle().copy(
            statusLabel = NAVIGATING,
            maneuverText = "Turn left in 300 m",
            remainingDistanceText = "300 m",
            speedText = "36 km/h",
            hasRoute = true,
        )
        show(uiState = ui, screenMode = ScreenMode.Guidance, followMode = false, destination = airport)

        rule.onNodeWithText("Turn left in 300 m").assertIsDisplayed()
        rule.onNodeWithText(NAVIGATING).assertIsDisplayed()
        rule.onNodeWithText("End").assertIsDisplayed()
        rule.onNodeWithContentDescription("Recenter").assertIsDisplayed()
    }

    @Test
    fun straightLinePreviewIsLabelledInGuidance() {
        val ui = NavigationUiState.idle().copy(statusLabel = NAVIGATING, previewLabel = STRAIGHT_LINE_PREVIEW, hasRoute = true)
        show(uiState = ui, screenMode = ScreenMode.Guidance, destination = airport)

        rule.onNodeWithText(STRAIGHT_LINE_PREVIEW).assertIsDisplayed()
    }

    @Test
    fun arrivedShowsDone() {
        show(screenMode = ScreenMode.Arrived, destination = airport)

        rule.onNodeWithText(ARRIVED).assertIsDisplayed()
        rule.onNodeWithText("Done").assertIsDisplayed()
    }
}
```

- [ ] **Step 10: Run the instrumented navigation tests on the emulator**

Run: `./gradlew connectedDebugAndroidTest --offline -q -Pandroid.testInstrumentationRunnerArguments.class=com.csjotlab.cardashboard.ui.navigation.NavigationScreenTest`
Expected: PASS (8 tests). If `assertIsNotEnabled` fails on the disabled Start pill, Material3 `Surface(enabled=false)` still exposes the click semantics as disabled — confirm with `rule.onNodeWithText("Start").assertHasClickAction()` replaced by `assertIsNotEnabled()`; if it still fails, the fix is to pass `Modifier.semantics { disabled() }` on the disabled branch of `PillButton`, not to loosen the test.

---

### Task 9: Emulator verification and documentation

**Files:**
- Modify: `README.md` (new "Navigation" section after "Mock mode"; test counts under "Testing")
- Modify: `docs/superpowers/specs/2026-09-13-in-app-navigation-map-design.md` (§1 table, §6 out-of-scope, status line)
- Modify: `docs/superpowers/plans/2026-09-13-in-app-navigation-map.md` (add pointer to this plan)
- Modify: `docs/superpowers/specs/2026-09-16-navigation-map-completion-design.md` (status line)
- Create: screenshots under `docs/screenshots/` — `nav-planning.png`, `nav-search.png`, `nav-overview.png`, `nav-guidance.png`

**Interfaces:** none — this task proves the work on the running emulator and records what was verified.

- [ ] **Step 1: Install and grant location**

```bash
./gradlew installDebug --offline -q
adb -s emulator-5554 shell pm grant com.csjotlab.cardashboard android.permission.ACCESS_FINE_LOCATION
adb -s emulator-5554 emu geo fix 90.4125 23.8103        # lon lat — Dhaka; emulator GPS needs a fix before the app reads one
adb -s emulator-5554 shell am start -n com.csjotlab.cardashboard/.MainActivity
```

Expected: the dashboard opens; the **Navigate** chip is visible.

- [ ] **Step 2: Planning — tiles and position**

Tap **Navigate** (`adb shell input tap` on the chip, or use the emulator window). Wait ~5 s for tiles.

```bash
adb -s emulator-5554 exec-out screencap -p > docs/screenshots/nav-planning.png
```

Verify in the screenshot: street-level OSM tiles (roads and labels, not country outlines), the orange position dot near the centre, the search bar at the top with "Search destination", "From: Current location", the + / − controls on the right, and OpenStreetMap attribution visible under the search bar. If tiles are blank, check `adb logcat -s Mbgl` for a style-load error and confirm the emulator has internet (`adb shell ping -c1 tiles.openfreemap.org`).

- [ ] **Step 3: Search — proximity-biased results with distance**

Tap the search field and type `airport` (`adb shell input text airport`). Wait ~1 s.

```bash
adb -s emulator-5554 exec-out screencap -p > docs/screenshots/nav-search.png
```

Verify: a results list with named places (e.g. "Hazrat Shahjalal International Airport"), a category/address line, and a distance such as "4.2 km" on each row; results are near Dhaka, not worldwide. Tap the first result.

- [ ] **Step 4: Overview — real road route, fit to screen**

Wait ~3 s for OSRM.

```bash
adb -s emulator-5554 exec-out screencap -p > docs/screenshots/nav-overview.png
```

Verify: the blue route follows roads (not a straight line — and **no** "Straight-line preview" label), the camera shows the whole route north-up, the destination pin sits at the airport, and the panel shows the place name, Distance / Time / ETA, **Start** and **Cancel**. If the label "Straight-line preview" appears, OSRM failed — check `adb logcat | grep -i osrm` and emulator connectivity before proceeding.

- [ ] **Step 5: Guidance — heading-up follow, maneuver, recenter**

Tap **Start**. Then simulate driving along the route with a few fixes 1 s apart (coordinates from the OSRM route towards the airport):

```bash
for p in "90.4130 23.8110" "90.4135 23.8120" "90.4140 23.8135" "90.4150 23.8150"; do
  adb -s emulator-5554 emu geo fix $p; sleep 1
done
adb -s emulator-5554 exec-out screencap -p > docs/screenshots/nav-guidance.png
```

Verify: the camera is zoomed in and rotated to the direction of travel, the panel shows a turn glyph and phrase ("Turn right in 140 m" or similar), Distance / Time / ETA and "Moving · N km/h", and **End**. Now drag the map with the mouse: the **◎ Recenter** button appears and the camera stops following; tap it and the camera snaps back. Tap **+** twice: the map zooms and stays zoomed on the next fix.

- [ ] **Step 6: End and recents**

Tap **End**. Verify: back to Planning with the route and pin cleared, and "Recent destinations" now shows the airport by name. Kill and relaunch the app (`adb shell am force-stop com.csjotlab.cardashboard` then start it again) and open Navigate: the recent is still there.

- [ ] **Step 7: Map tap**

In Planning, tap an empty spot on the map. Verify: the panel switches to Overview with a coordinate label that turns into a street/area name within a second or two, and a route resolves.

- [ ] **Step 8: Run the full instrumented suite**

Run: `./gradlew connectedDebugAndroidTest --offline -q`
Expected: PASS. Note the totals printed for the README.

- [ ] **Step 9: Update `README.md`**

Insert after the "Mock mode" section:

````markdown
## Navigation

The **Navigate** chip opens a full-screen, open-source map with search and turn-by-turn guidance.
No account, key, or server is needed:

| Concern | Service | Override |
| --- | --- | --- |
| Map tiles | [OpenFreeMap](https://openfreemap.org) (OpenStreetMap vector tiles) | `-PmapDayStyleUrl=… -PmapNightStyleUrl=…` |
| Road routing | [OSRM](https://project-osrm.org) public demo server | `-PosrmBaseUrl=…` |
| Optional routing | Self-hosted [GraphHopper](https://www.graphhopper.com/open-source/) — tried first when set | `-ProutingBaseUrl=http://<host>:8989` |
| Search / reverse | [Photon](https://photon.komoot.io) (komoot's open OSM geocoder) | `-PgeocodingBaseUrl=…` |

Map data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright) (ODbL). The
public OSRM and Photon endpoints are community services with no SLA; point the overrides at
self-hosted instances for production use.

**Flow.** Planning: type at least two characters to search (results are ranked near your GPS fix and
show their distance), pick a recent, or tap the map. Overview: the whole route is fitted on screen
with distance, time and ETA — press **Start**. Guidance: heading-up camera, a turn glyph and phrase
("Turn left in 300 m"), automatic rerouting when you leave the route, **◎** to recenter after
panning, **End** to finish. If every router is unreachable the app draws a straight line and labels it
**Straight-line preview** — it never presents a straight line as a road route.

Screenshots: `docs/screenshots/nav-planning.png`, `nav-search.png`, `nav-overview.png`, `nav-guidance.png`.

**Verified on emulator (2026-09-16):** OSM tiles render; Photon search returns nearby named places
with distances; OSRM road routes draw and fit; heading-up guidance follows simulated `adb emu geo
fix` drives; recenter after pan; recents persist across restart. **Not verified:** a real vehicle
drive with live GPS and compass.
````

Update the two counts under "Testing" with the totals from Task 9 step 8 and the JVM run (`./gradlew testDebugUnitTest --offline -q` then count with `grep -h "tests=" app/build/test-results/testDebugUnitTest/*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc`).

- [ ] **Step 10: Update the v1 spec and plan**

In `2026-09-13-in-app-navigation-map-design.md`:
- Change `Status:` to `Superseded in part by 2026-09-16-navigation-map-completion-design.md (tiles, routing provider, geocoder, screen modes)`.
- In §1 add a row: `| Tiles / routing / geocoder (v1.1) | OpenFreeMap / OSRM public + optional GraphHopper / Photon | See the v1.1 spec. |`
- In §6 remove `text search / geocoding` from the out-of-scope list and add `(v1.1 added Photon search, OSRM routing, OpenFreeMap tiles, screen modes, persistent recents.)`

In `2026-09-13-in-app-navigation-map.md` add at the end:

```markdown
## Follow-up

Completed to a usable end-to-end state by `2026-09-16-navigation-map-completion.md`.
```

In `2026-09-16-navigation-map-completion-design.md` change `Status: Approved (design)` to `Status: Implemented; emulator-verified 2026-09-16 (see README "Navigation")`.

- [ ] **Step 11: Final full verification**

Run: `./gradlew testDebugUnitTest --offline -q && ./gradlew connectedDebugAndroidTest --offline -q`
Expected: both PASS. Report the exact totals and which device checks were done and which were not.

---

## Self-review against the spec

- §1 table — tiles (T1), routing chain (T3), search (T4/T6), map-tap naming (T6), camera/follow (T7/T8), Start/Overview (T6/T8), recents (T5). ✔
- §2 stack — OpenFreeMap URLs (T1), OSRM engine (T2/T3), Photon engine (T4), attribution placement (T7), README credits (T9). ✔
- §3 routing chain — N-engine fallback, empty-GraphHopper skip, parser mapping table incl. `off ramp → Exit(null)`, `on ramp → KeepLeft/Right` (T2/T3). ✔
- §4 geocoding — `Place` fields, `search(query, near)`, `reverse`, Photon parser name/address fallbacks, Nominatim updated, ViewModel policy (≥2 chars, 400 ms, bias, distance via `formatRemainingDistance`, `SEARCH_UNAVAILABLE`, blank clears) (T4/T6). ✔ The spec named `NavigationFormatter.formatDistance`; the existing `formatRemainingDistance` is reused instead of adding a duplicate.
- §5 screen modes — mode table, zoom ±, map tap → coordinate label then name, fit once per route, Start sets follow, gestures break follow, glyphs (T6/T7/T8). ✔
- §6 state ownership — repository/engine untouched; ViewModel flags; `PersistentRecentDestinationsStore` (T5/T6). ✔
- §7 integrity rules — distance only with a fix (T6 test), reverse failure keeps label (T6 test), preview label in Overview and Guidance (T8 + Compose test). ✔
- §8 testing — parsers with realistic fixtures, fallback chain, ViewModel transitions and search policy, recents store, Compose panels, emulator drive with `adb emu geo fix` (T2–T9). ✔
- Placeholders — none; every step has code or an exact command.
- Type consistency — `NavigationMap` methods used in T8 (`centerOn`, `fitRoute`, `showDestination`, `clearDestination`, `zoomIn`, `zoomOut`, `setUserGestureListener`) match T7; `NavigationActions` fields used in T8 match T6; `SearchUiState`/`SearchResultUi` match across T6/T8; `PersistentRecentDestinationsStore(StringStorage, maxItems)` matches T5/T6 tests.
