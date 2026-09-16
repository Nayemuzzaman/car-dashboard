package com.csjotlab.cardashboard.nav

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guard for the navigation subsystem, in the spirit of `DashboardScreenPurityTest`.
 *
 * Two properties have to stay true and neither is visible from a passing feature test:
 *  - `nav/domain` and `nav/engine` are pure Kotlin (no Android imports), so they stay JVM-testable;
 *  - `ui/navigation` owns no data loops or timers, the same rule the dashboard UI already enforces.
 */
class NavigationPurityTest {

    private val navRoot = File("src/main/java/com/csjotlab/cardashboard/nav")
    private val navUiRoot = File("src/main/java/com/csjotlab/cardashboard/ui/navigation")

    private val pureSources: List<File> = listOf("domain", "engine")
        .flatMap { File(navRoot, it).walkTopDown() }
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    @Test
    fun `the guard is actually reading the nav sources`() {
        assertTrue("no pure nav sources found", pureSources.size >= 8)
        assertTrue(File(navRoot, "domain/GeoPoint.kt").isFile)
        assertTrue(File(navRoot, "engine/NavigationEngine.kt").isFile)
        assertTrue(
            "ui/navigation sources not found",
            navUiRoot.walkTopDown().count { it.isFile && it.extension == "kt" } >= 1,
        )
    }

    @Test
    fun `nav domain and engine import no Android classes`() {
        val offenders = pureSources.flatMap { file ->
            file.readLines()
                .filter { it.trimStart().startsWith("import android") }
                .map { "${file.path}: $it" }
        }
        assertTrue(
            "nav/domain and nav/engine must be pure Kotlin:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `nav ui owns no data loops or timers`() {
        val loop = Regex("""\bwhile\s*\(""")
        val timer = Regex("""\bdelay\s*\(""")
        val offenders = navUiRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    when {
                        loop.containsMatchIn(line) -> "${file.path}:${index + 1} — a loop belongs in the data layer"
                        timer.containsMatchIn(line) -> "${file.path}:${index + 1} — a timer belongs in the data layer"
                        else -> null
                    }
                }
            }
            .toList()
        assertTrue(
            "ui/navigation must own no data loops and no timers:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
