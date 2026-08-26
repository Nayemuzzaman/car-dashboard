package com.csjotlab.cardashboard.ui.dashboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A structural guard, not a style check. It is the standing enforcement of a global constraint —
 * *"the UI must own no data loops, no timers, no `while(true)`, no `delay()`"* — so it has to be
 * hard to slip past rather than merely hard to trip by accident.
 *
 * Two properties follow from that:
 *
 *  - it walks **every** `.kt` file under `ui/`, not just `DashboardScreen.kt`. A polling loop moved
 *    into a sibling composable, a helper file or the theme package is the same violation;
 *  - it matches on regexes, not substrings. `while(true)` without the space, `while (isActive)` and
 *    `delay (16)` are all the same defect, and a substring check for the literal `"while (true)"`
 *    would wave every one of them through.
 *
 * Deliberately **not** banned: `rememberCoroutineScope` and `LaunchedEffect`. Both have honest
 * uses in Compose (a click handler that animates a scroll; keying an effect on UI-owned state, as
 * `LaunchedEffect(selectedMode)` does). What makes either of them a violation is a *loop or a
 * timer inside them*, and the two rules below already catch that wherever it is written. Banning
 * the wrappers instead of the loops would push the next implementer into weakening this guard,
 * which is the last thing a guard should incentivise.
 */
class DashboardScreenPurityTest {

    private val uiRoot = File("src/main/java/com/csjotlab/cardashboard/ui")

    private val uiSources: List<File> =
        uiRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /**
     * A guard that reads no files passes for free. Renaming or moving the `ui` package would
     * silently disarm everything below, so the file set itself is asserted first.
     */
    @Test
    fun `the guard is actually reading the ui sources`() {
        assertTrue(
            "no .kt files found under ${uiRoot.absolutePath} — this guard is disarmed",
            uiSources.size >= 4,
        )
        assertTrue(
            "DashboardScreen.kt must be among the files this guard inspects",
            uiSources.any { it.name == "DashboardScreen.kt" },
        )
    }

    @Test
    fun `no mock data literals remain in the ui layer`() {
        val banned = listOf(
            "mockSpeedSequence",
            "mockWarningScenarios",
            "mockMetrics",
            "142.8 km",
            "38,421 km",
            "Range 420 km",
        )
        val offences = uiSources.flatMap { file ->
            matches(file) { line -> banned.filter { line.contains(it) } }
        }
        assertTrue(
            "hardcoded mock readings must be gone from the UI, not merely unused:\n" +
                offences.joinToString("\n"),
            offences.isEmpty(),
        )
    }

    @Test
    fun `no loop or timer lives in the ui layer`() {
        // `while (` also covers the tail of a do/while.
        val loop = Regex("""\bwhile\s*\(""")
        val timer = Regex("""\bdelay\s*\(""")
        // A star import cannot be read to tell whether a timer came with it, so it is banned
        // outright: every kotlinx.coroutines symbol the UI legitimately needs can be named.
        val starImport = Regex("""^\s*import\s+kotlinx\.coroutines\.(\w+\.)*\*""")

        val offences = uiSources.flatMap { file ->
            matches(file) { line ->
                buildList {
                    if (loop.containsMatchIn(line)) add("a loop belongs in the repository")
                    if (timer.containsMatchIn(line)) add("a timer belongs in the data layer")
                    if (starImport.containsMatchIn(line)) add("kotlinx.coroutines star import")
                }
            }
        }
        assertTrue(
            "the UI must own no data loops and no timers:\n" + offences.joinToString("\n"),
            offences.isEmpty(),
        )
    }

    /** Reports `path:line — reason` so a failure names the offending line rather than the file. */
    private fun matches(file: File, reasons: (String) -> List<String>): List<String> =
        file.readLines().flatMapIndexed { index, line ->
            reasons(line).map { "${file.path}:${index + 1} — $it: ${line.trim()}" }
        }
}
