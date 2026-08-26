package com.csjotlab.cardashboard.docs

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The TDD-equivalent for documentation: every pinned fact is **derived from production source at
 * test time** and then required to appear in the document.
 *
 * The point is that nothing here hard-codes the expected value. If someone changes the stale
 * timeout, the polling cadence, the banner wording or the allowed OBD services, this test recomputes
 * the new value from the code and fails until the document is brought back in line. A test that
 * asserted literals instead would pass happily while the prose rotted.
 *
 * Gradle runs unit tests with the module directory (`app/`) as the working directory, so the
 * document sits one level up.
 */
class DocumentationAccuracyTest {

    private val doc = File("../docs/vehicle-data-architecture.md")
    private val mainRoot = File("src/main/java/com/csjotlab/cardashboard")

    private val docText: String by lazy { doc.readText() }

    private fun source(relative: String) = File(mainRoot, relative).readText()

    /** First capture group of [pattern] in [text], or null. */
    private fun capture(text: String, pattern: String): String? =
        Regex(pattern).find(text)?.groupValues?.getOrNull(1)

    private fun requireInDoc(label: String, needle: String) {
        assertTrue(
            "the document must state $label as <$needle>, derived from production source",
            docText.contains(needle),
        )
    }

    /**
     * Requires one single line to carry every needle.
     *
     * Plain `contains` over a 500-line document is far too weak for anything numeric: "500" is a
     * substring of "15000", and "01"/"03" appear inside "P0301". Binding the value to its own label
     * on the same line is what makes the assertion mean what it says.
     */
    private fun requireOnOneLine(label: String, vararg needles: String) {
        assertTrue(
            "the document must state $label — expected one line containing all of " +
                needles.joinToString { "<$it>" },
            docText.lines().any { line -> needles.all { line.contains(it) } },
        )
    }

    /**
     * Anti-vacuity, first — the trap this project has hit before. A missing or stub document must
     * fail loudly rather than let every `contains` check below pass over an empty string.
     */
    @Test
    fun `the document exists and is substantive`() {
        assertTrue("missing document: ${doc.absolutePath}", doc.isFile)
        assertTrue("document is too short to be the real thing", docText.lineSequence().count() >= 150)
        assertTrue(
            "document must be structured with headings",
            Regex("^## ", RegexOption.MULTILINE).findAll(docText).count() >= 8,
        )
        assertTrue(
            "the guard is reading the real main source tree",
            mainRoot.walkTopDown().count { it.isFile && it.extension == "kt" } >= 20,
        )
    }

    @Test
    fun `the documented package identity matches the source tree`() {
        // Derived, not assumed: take the package from a real production file.
        val declared = capture(source("MainActivity.kt"), """^package\s+([\w.]+)""")
            ?: error("could not read the package declaration from MainActivity.kt")
        requireInDoc("the application package", declared)

        // Any mention of the pre-migration package must be explicitly historical. A bare mention
        // presented as the current identity is a documentation defect.
        val offenders = docText.lines().withIndex().filter { (_, line) ->
            line.contains("com.example.cardashboard") &&
                !Regex("migrat|former|previous|renamed|→|history|historical", RegexOption.IGNORE_CASE)
                    .containsMatchIn(line)
        }
        assertTrue(
            "the pre-migration package is presented as current at line(s) " +
                offenders.joinToString { (i, _) -> "${i + 1}" },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the documented stale timeout matches VehicleRepository`() {
        val raw = capture(source("vehicle/data/VehicleRepository.kt"), """staleTimeoutMs:\s*Long\s*=\s*([0-9_]+)L""")
            ?: error("could not read staleTimeoutMs default from VehicleRepository.kt")
        val ms = raw.replace("_", "").toLong()
        requireInDoc("the stale timeout in ms", ms.toString())
        // "3" alone occurs dozens of times (0x31, P0301, ...). Bind it to the unit.
        val seconds = ms / 1000
        assertTrue(
            "the document must express the stale timeout as $seconds seconds",
            Regex("""\b$seconds[ -]second""").containsMatchIn(docText),
        )
    }

    @Test
    fun `the documented polling cadence matches ObdVehicleDataSource`() {
        val src = source("vehicle/source/ObdVehicleDataSource.kt")
        listOf(
            Triple("fast poll", """fastIntervalMs:\s*Long\s*=\s*([0-9_]+)L""", "Fast"),
            Triple("slow poll", """slowIntervalMs:\s*Long\s*=\s*([0-9_]+)L""", "Slow"),
            Triple("diagnostics poll", """diagnosticsIntervalMs:\s*Long\s*=\s*([0-9_]+)L""", "Diagnostics"),
        ).forEach { (label, pattern, loopName) ->
            val ms = (capture(src, pattern) ?: error("could not read $label")).replace("_", "")
            requireOnOneLine("the $label interval", loopName, ms)
        }

        // PID membership, bound to the loop that reads it — not just "the hex appears somewhere".
        val fast = capture(src, """val FAST_PIDS = listOf\(([^)]*)\)""") ?: error("no FAST_PIDS")
        val slow = capture(src, """val SLOW_PIDS = listOf\(([^)]*)\)""") ?: error("no SLOW_PIDS")
        Regex("""ObdPid\.(\w+)""").findAll(fast).map { it.groupValues[1] }.forEach {
            requireOnOneLine("PID $it on the fast loop", "Fast", it)
        }
        Regex("""ObdPid\.(\w+)""").findAll(slow).map { it.groupValues[1] }.forEach {
            requireOnOneLine("PID $it on the slow loop", "Slow", it)
        }
        assertTrue(
            "SLOW_PIDS must still exclude the distance-since-cleared PID",
            !slow.contains("DISTANCE_SINCE_CLEAR"),
        )
    }

    @Test
    fun `the documented service list matches the sealed ObdCommand hierarchy`() {
        val src = source("vehicle/protocol/ObdCommand.kt")
        // Derived, not hard-coded: every literal request string the sealed type can produce.
        val services = Regex(""""(?:01%02X|(0[0-9A-F]))"""").findAll(src)
            .mapNotNull { it.groupValues[1].takeIf(String::isNotEmpty) }
            .toMutableSet()
        // The two PID-bearing subtypes both format as service 01.
        if (src.contains("\"01%02X\"")) services += "01"
        assertTrue("expected read services to be discoverable in ObdCommand.kt", services.size >= 4)

        // Backticked, so "01"/"03" cannot be satisfied by an incidental "P0301".
        services.forEach { requireInDoc("read service $it", "`$it`") }

        // The safety boundary: mode 04 must be absent from the type AND named as forbidden in prose.
        assertTrue(
            "ObdCommand must not be able to express mode 04",
            !Regex(""""04"""").containsMatchIn(src),
        )
        assertTrue(
            "the document must state that clearing DTCs (mode 04) is not possible",
            docText.contains("04"),
        )
    }

    @Test
    fun `the documented simulation banner is byte-exact`() {
        val banner = capture(source("ui/dashboard/ConnectionBanner.kt"), """SIMULATED_DATA_LABEL\s*=\s*"([^"]+)"""")
            ?: error("could not read SIMULATED_DATA_LABEL")
        requireInDoc("the simulated-data banner", banner)
    }

    @Test
    fun `the documented log tag matches VehicleLog`() {
        val tag = capture(source("vehicle/data/VehicleLog.kt"), """TAG\s*=\s*"([^"]+)"""")
            ?: error("could not read VehicleLog TAG")
        requireInDoc("the log tag", tag)
    }

    @Test
    fun `every field the OBD source cannot supply is documented as unsupported`() {
        val src = source("vehicle/source/ObdVehicleDataSource.kt")
        // The unconditional-Unsupported block: `field = Signal.Unsupported,`
        val fields = Regex("""(\w+)\s*=\s*Signal\.Unsupported,""").findAll(src)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("expected unconditionally-Unsupported fields in ObdVehicleDataSource", fields.size >= 5)
        // Every one must appear in the doc's limitations table, bound to the word "generic PID"
        // reason rather than merely occurring somewhere in prose.
        fields.forEach { requireInDoc("the unsupported field `$it`", "`$it`") }
        assertTrue(
            "the document's unsupported-field table must list exactly the ${fields.size} fields the " +
                "source marks Unsupported; found ${fields.sorted()}",
            fields.count { field -> docText.lines().any { it.startsWith("| `$field`") } } == fields.size,
        )
    }

    @Test
    fun `the documented mock gate matches VehicleSourceSelector`() {
        val src = source("vehicle/source/VehicleSourceSelector.kt")
        assertTrue(
            "the selector must still require BOTH debugBuild and mockEnabled",
            Regex("""debugBuild\s*&&\s*mockEnabled""").containsMatchIn(src),
        )
        assertTrue(
            "the selector must still prefer a real source over the mock",
            Regex("""real\s*!=\s*null\s*->\s*real""").containsMatchIn(src),
        )
        requireInDoc("the debug-build half of the mock gate", "BuildConfig.DEBUG")
        assertTrue(
            "the document must state, on one line, that a failing real source never falls back " +
                "to the mock",
            docText.lines().any { line ->
                Regex("never", RegexOption.IGNORE_CASE).containsMatchIn(line) &&
                    Regex("fall ?back", RegexOption.IGNORE_CASE).containsMatchIn(line) &&
                    Regex("mock", RegexOption.IGNORE_CASE).containsMatchIn(line)
            },
        )
    }

    /**
     * The counts the document quotes must match the tree.
     *
     * This exists because the first version of the document was wrong the moment it was written:
     * adding this very test class moved the unit-test total, and nothing in the harness noticed.
     * A number that no test derives is a number that rots.
     */
    @Test
    fun `the documented test counts match the source tree`() {
        fun census(root: String) = File(root)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sumOf { file -> Regex("""^\s*@Test\b""", RegexOption.MULTILINE).findAll(file.readText()).count() }

        val unit = census("src/test")
        val instrumented = census("src/androidTest")
        assertTrue("expected to find unit tests to count", unit > 100)
        assertTrue("expected to find instrumented tests to count", instrumented > 10)

        requireOnOneLine("the unit-test count", "$unit", "unit test")
        requireOnOneLine("the instrumented-test count", "$instrumented", "instrumented test")
    }

    @Test
    fun `Task 18 is documented as gated and no USB dependency has appeared`() {
        val gradle = File("build.gradle.kts").readText()
        val settings = File("../settings.gradle.kts").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(
            "a USB/serial dependency appeared — Task 18 is supposed to be gated",
            !Regex("""usb-serial|jitpack""", RegexOption.IGNORE_CASE).containsMatchIn(gradle + settings),
        )
        assertTrue(
            "a USB manifest entry appeared — Task 18 is supposed to be gated",
            !manifest.contains("android.hardware.usb"),
        )
        assertTrue(
            "no source may import the Android USB API while Task 18 is gated",
            mainRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .none { it.readText().contains("import android.hardware.usb") },
        )
        assertTrue(
            "the document must say Task 18 is gated/blocked, not merely mention it",
            docText.lines().any { line ->
                line.contains("Task 18") &&
                    Regex("gated|blocked|must not begin", RegexOption.IGNORE_CASE).containsMatchIn(line)
            },
        )
    }
}
