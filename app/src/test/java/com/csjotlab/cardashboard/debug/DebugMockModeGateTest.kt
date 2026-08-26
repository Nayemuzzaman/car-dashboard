package com.csjotlab.cardashboard.debug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A structural guard over the mock-mode affordance, in the spirit of `DashboardScreenPurityTest`.
 *
 * Three properties have to stay true and none of them is visible from a passing feature test:
 *
 *  1. the affordance lives in the `debug` source set, so it cannot be compiled into a release
 *     build — a runtime `if (BuildConfig.DEBUG)` in `main` would be weaker and is what this test
 *     exists to stop someone substituting;
 *  2. nothing in `main` calls `setMockModeEnabled`, so the only way to set the toggle half of the
 *     gate is through that debug-only affordance;
 *  3. the affordance never caches the container. `VehicleContainer` is rebuilt across a
 *     `shutdown()` boundary, so a remembered reference would drive a dead graph.
 */
class DebugMockModeGateTest {

    private val mainRoot = File("src/main/java/com/csjotlab/cardashboard")
    private val debugToggle = File("src/debug/java/com/csjotlab/cardashboard/debug/DebugMockModeToggle.kt")
    private val releaseToggle = File("src/release/java/com/csjotlab/cardashboard/debug/DebugMockModeToggle.kt")

    @Test
    fun `the affordance exists in the debug source set and is stubbed out in the release one`() {
        assertTrue(
            "the debug-only toggle is missing: ${debugToggle.absolutePath}",
            debugToggle.isFile,
        )
        assertTrue(
            "the release variant needs a same-signature no-op or it will not compile: " +
                releaseToggle.absolutePath,
            releaseToggle.isFile,
        )
        assertTrue(
            "the debug affordance must actually drive the container toggle",
            debugToggle.readText().contains("setMockModeEnabled("),
        )
        assertFalse(
            "the release stub must not be able to enable mock mode",
            releaseToggle.readText().contains("setMockModeEnabled("),
        )
    }

    /**
     * A walk over a directory that does not exist yields nothing, and "nothing" reads as "no
     * violations" — so the guard below would pass for free the moment the package moves. The
     * `com.example.cardashboard` -> `com.csjotlab.cardashboard` migration is exactly that move, and
     * it disarmed this guard silently while the two `isFile` assertions above failed loudly. Assert
     * the file set first, the way `DashboardScreenPurityTest` already does for the ui package.
     */
    @Test
    fun `the guard is actually reading the main sources`() {
        val mainSources = mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(
            "no .kt files found under ${mainRoot.absolutePath} — this guard is disarmed",
            mainSources.size >= 20,
        )
        assertTrue(
            "VehicleContainer.kt must be among the files this guard inspects",
            mainSources.any { it.name == "VehicleContainer.kt" },
        )
    }

    @Test
    fun `nothing in the main source set can enable mock mode`() {
        val callers = mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            // The declaration itself lives in main, on VehicleContainer. Only *calls* are banned.
            .filter { it.name != "VehicleContainer.kt" }
            .filter { it.readText().contains("setMockModeEnabled(") }
            .map { it.path }
            .toList()

        assertTrue(
            "mock mode may only be enabled from the debug source set, not from main:\n" +
                callers.joinToString("\n"),
            callers.isEmpty(),
        )
    }

    @Test
    fun `the affordance holds no reference to the container across a frame`() {
        val source = debugToggle.readText()

        assertFalse(
            "no remember: a remembered VehicleContainer survives shutdown() and drives a dead graph",
            Regex("""\bremember\w*\s*[({]""").containsMatchIn(source),
        )
        // Both reads have to go back through the Application: the one that renders the state and
        // the one inside the click handler.
        assertTrue(
            "the container must be read through the Application, not stored",
            Regex("""application\.container""").findAll(source).count() >= 2,
        )
    }
}
