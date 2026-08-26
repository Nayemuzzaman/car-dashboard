package com.csjotlab.cardashboard.vehicle.source

import com.csjotlab.cardashboard.vehicle.domain.Clock
import com.csjotlab.cardashboard.vehicle.domain.DiagnosticCode
import com.csjotlab.cardashboard.vehicle.domain.DoorPosition
import com.csjotlab.cardashboard.vehicle.domain.DoorState
import com.csjotlab.cardashboard.vehicle.domain.Gear
import com.csjotlab.cardashboard.vehicle.domain.SeatPosition
import com.csjotlab.cardashboard.vehicle.domain.SeatbeltState
import com.csjotlab.cardashboard.vehicle.domain.TirePosition
import com.csjotlab.cardashboard.vehicle.domain.VehicleConnectionState
import com.csjotlab.cardashboard.vehicle.domain.VehicleSourceId
import com.csjotlab.cardashboard.vehicle.domain.allSignals
import com.csjotlab.cardashboard.vehicle.domain.isLiveData
import com.csjotlab.cardashboard.vehicle.domain.isValue
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MockVehicleDataSourceTest {

    private val scriptDurationMs = MockVehicleDataSource.DEMO_SCRIPT.sumOf { it.delayMs }

    /** Cumulative virtual time at which each scripted step lands. */
    private val stepTimesMs = MockVehicleDataSource.DEMO_SCRIPT.runningFold(0L) { acc, step ->
        acc + step.delayMs
    }.drop(1)

    private fun TestScope.newSource() = MockVehicleDataSource(Clock { currentTime }, backgroundScope)

    /**
     * Collects exactly what `VehicleRepository` collects: one `combine` across all three flows.
     * If the source ran the script three times the frames here would be incoherent, so every
     * assertion below is also an assertion that the timeline is shared.
     */
    private fun TestScope.collectFrames(source: MockVehicleDataSource): Pair<List<MockFrame>, Job> {
        val frames = mutableListOf<MockFrame>()
        val job = backgroundScope.launch {
            combine(source.vehicleState, source.diagnostics, source.connectionState, ::MockFrame)
                .collect { frames += it }
        }
        runCurrent()
        return frames to job
    }

    private fun TestScope.collectBeats(source: MockVehicleDataSource): Pair<List<Long>, Job> {
        val beats = mutableListOf<Long>()
        val job = backgroundScope.launch { source.liveness.collect { beats += it } }
        runCurrent()
        return beats to job
    }

    private suspend fun TestScope.runWholeScript(source: MockVehicleDataSource): List<MockFrame> {
        val (frames, job) = collectFrames(source)
        source.start().activate()
        runCurrent()
        advanceTimeBy(scriptDurationMs + 1)
        runCurrent()
        job.cancel()
        return frames.toList()
    }

    @Test
    fun `identifies itself as the mock source`() = runTest {
        assertEquals(VehicleSourceId.MOCK, newSource().id)
    }

    @Test
    fun `the script does not advance until start is called`() = runTest {
        val source = newSource()
        val (frames, job) = collectFrames(source)

        advanceTimeBy(scriptDurationMs + 1)
        runCurrent()
        job.cancel()

        assertEquals("nothing may happen before start()", 1, frames.size)
        assertEquals(VehicleConnectionState.Disconnected, frames.single().connection)
    }

    @Test
    fun `preparation alone causes no frame transition or liveness beat`() = runTest {
        val source = newSource()
        val (frames, framesJob) = collectFrames(source)
        val (beats, beatsJob) = collectBeats(source)

        source.start()
        advanceTimeBy(scriptDurationMs + 1)
        runCurrent()

        assertEquals(listOf(VehicleConnectionState.Disconnected), frames.map { it.connection })
        assertTrue(beats.isEmpty())
        source.stop()
        framesJob.cancel()
        beatsJob.cancel()
    }

    @Test
    fun `activation is one shot while running and after natural completion`() = runTest {
        val source = newSource()
        val (frames, framesJob) = collectFrames(source)
        val (beats, beatsJob) = collectBeats(source)
        val activation = source.start()

        activation.activate()
        activation.activate()
        advanceTimeBy(scriptDurationMs + 1)
        runCurrent()
        val framesAfterCompletion = frames.toList()
        val beatsAfterCompletion = beats.toList()
        assertTrue(beatsAfterCompletion.isNotEmpty())

        activation.activate()
        advanceTimeBy(scriptDurationMs + 1)
        runCurrent()

        assertEquals("a completed lifecycle cannot transition frames twice", framesAfterCompletion, frames)
        assertEquals("a completed lifecycle cannot beat twice", beatsAfterCompletion, beats)
        source.stop()
        framesJob.cancel()
        beatsJob.cancel()
    }

    @Test
    fun `stop before activation makes that handle inert`() = runTest {
        val source = newSource()
        val (frames, framesJob) = collectFrames(source)
        val (beats, beatsJob) = collectBeats(source)
        val activation = source.start()

        source.stop()
        activation.activate()
        advanceTimeBy(scriptDurationMs + 1)
        runCurrent()

        assertEquals(listOf(VehicleConnectionState.Disconnected), frames.map { it.connection })
        assertTrue(beats.isEmpty())
        framesJob.cancel()
        beatsJob.cancel()
    }

    @Test
    fun `stale handle after stop cannot affect a fresh lifecycle`() = runTest {
        val source = newSource()
        val (frames, framesJob) = collectFrames(source)
        val (beats, beatsJob) = collectBeats(source)
        val stale = source.start()
        stale.activate()
        advanceTimeBy(stepTimesMs[2] + 1)
        runCurrent()
        assertTrue("the old lifecycle must genuinely have run before stop", beats.isNotEmpty())

        source.stop()
        runCurrent()
        val fresh = source.start()
        val framesBeforeStaleActivation = frames.toList()
        val beatsBeforeStaleActivation = beats.toList()

        stale.activate()
        advanceTimeBy(scriptDurationMs + 1)
        runCurrent()

        assertEquals(
            "a stale handle alone cannot transition the fresh lifecycle",
            framesBeforeStaleActivation,
            frames,
        )
        assertEquals(
            "a stale handle alone cannot beat in the fresh lifecycle",
            beatsBeforeStaleActivation,
            beats,
        )

        fresh.activate()
        advanceTimeBy(scriptDurationMs + 1)
        runCurrent()

        assertEquals(
            "the fresh lifecycle still walks its complete connection sequence",
            listOf(
                VehicleConnectionState.Connecting,
                VehicleConnectionState.Connected(
                    com.csjotlab.cardashboard.vehicle.domain.AdapterIdentity(
                        "MOCK SOURCE",
                        elmCompatible = false,
                    ),
                    protocol = "Simulated",
                ),
                VehicleConnectionState.Reading,
                VehicleConnectionState.ConnectionLost("Simulated cable removal"),
            ),
            frames.drop(framesBeforeStaleActivation.size).map { it.connection }.distinct(),
        )
        assertTrue(
            "the fresh lifecycle must produce new proof-of-contact beats",
            beats.size > beatsBeforeStaleActivation.size,
        )
        source.stop()
        framesJob.cancel()
        beatsJob.cancel()
    }

    @Test
    fun `demo script reaches the specified telemetry values`() = runTest {
        val frames = runWholeScript(newSource())

        assertTrue("the first frame knows nothing", frames.first().state.allSignals.none { it.isValue })

        val driving = frames.first { it.state.speedKph.valueOrNull() == 60f }.state
        assertEquals(60f, driving.speedKph.valueOrNull())
        assertEquals(2100, driving.engineRpm.valueOrNull())
        assertEquals(62f, driving.fuelLevelPercent.valueOrNull())
        assertEquals(91, driving.coolantTemperatureCelsius.valueOrNull())
        assertEquals(Gear.Drive, driving.gear.valueOrNull())
    }

    @Test
    fun `demo script opens the rear left door then lowers the front right tyre`() = runTest {
        val frames = runWholeScript(newSource())

        val doorIndex = frames.indexOfFirst {
            it.state.doors.valueOrNull()?.get(DoorPosition.RearLeft) == DoorState.Open
        }
        val tyreIndex = frames.indexOfFirst {
            (it.state.tirePressuresKpa.valueOrNull()?.get(TirePosition.FrontRight)
                ?: Float.MAX_VALUE) < 180f
        }

        assertTrue("rear-left door should open in the demo script", doorIndex >= 0)
        assertTrue("front-right tyre should go low in the demo script", tyreIndex >= 0)
        assertTrue("the door must open before the tyre goes low", doorIndex < tyreIndex)
    }

    @Test
    fun `demo script raises an engine diagnostic code`() = runTest {
        val codes = runWholeScript(newSource())
            .flatMap { it.diagnostics.issues }
            .mapNotNull { (it.code as? DiagnosticCode.Dtc)?.code }

        assertTrue("demo script should report a DTC, got $codes", codes.contains("P0301"))
    }

    @Test
    fun `demo script ends disconnected with every value cleared`() = runTest {
        val last = runWholeScript(newSource()).last()

        assertTrue(
            "the script must end in ConnectionLost, got ${last.connection}",
            last.connection is VehicleConnectionState.ConnectionLost,
        )
        assertTrue(
            "no telemetry may survive the simulated cable removal",
            last.state.allSignals.none { it.isValue },
        )
        assertTrue(
            "no diagnostics may survive the simulated cable removal",
            last.diagnostics.issues.isEmpty(),
        )
    }

    @Test
    fun `all three flows walk one shared coherent timeline`() = runTest {
        val frames = runWholeScript(newSource())

        assertEquals(
            "the connection must walk the scripted sequence exactly once",
            listOf(
                VehicleConnectionState.Disconnected,
                VehicleConnectionState.Connecting,
                VehicleConnectionState.Connected(
                    com.csjotlab.cardashboard.vehicle.domain.AdapterIdentity(
                        "MOCK SOURCE",
                        elmCompatible = false,
                    ),
                    protocol = "Simulated",
                ),
                VehicleConnectionState.Reading,
                VehicleConnectionState.ConnectionLost("Simulated cable removal"),
            ),
            frames.map { it.connection }.distinct(),
        )
        assertTrue(
            "a DTC must never be visible in a frame whose MIL is off",
            frames.none {
                it.diagnostics.issues.isNotEmpty() &&
                    it.state.malfunctionIndicatorLampOn.valueOrNull() != true
            },
        )
        assertTrue(
            "telemetry must never be visible in a frame whose connection is not live",
            frames.none { !it.connection.isLiveData && it.state.allSignals.any { s -> s.isValue } },
        )
        assertEquals(
            "one shared script means one frame per step, plus the initial one",
            MockVehicleDataSource.DEMO_SCRIPT.size + 1,
            frames.size,
        )
    }

    @Test
    fun `a late subscriber joins the running script instead of starting its own`() = runTest {
        val source = newSource()
        val (frames, job) = collectFrames(source)
        source.start().activate()
        runCurrent()
        // Advance in slices until the driving values are on the wire, so this test does not depend
        // on where in the script that step happens to sit. (`while`, not `repeat` + `return@repeat`
        // — the latter continues rather than breaks and would run the script to its end.)
        var slices = 0
        while (frames.none { it.state.speedKph.valueOrNull() == 60f } && slices++ < 200) {
            advanceTimeBy(100L)
            runCurrent()
        }
        assertTrue(
            "the driving values must be on the wire before the late subscriber joins",
            frames.any { it.state.speedKph.valueOrNull() == 60f },
        )

        val late = mutableListOf<VehicleConnectionState>()
        val lateState = mutableListOf<Float?>()
        val lateJob = backgroundScope.launch {
            combine(source.connectionState, source.vehicleState) { connection, state ->
                connection to state.speedKph.valueOrNull()
            }.collect { (connection, speed) -> late += connection; lateState += speed }
        }
        runCurrent()

        assertEquals(
            "a second subscriber must join the one running script, not restart it",
            VehicleConnectionState.Reading,
            late.first(),
        )
        assertEquals(60f, lateState.first())

        lateJob.cancel()
        job.cancel()
    }

    @Test
    fun `readings are stamped from the injected clock, not a constant`() = runTest {
        val frames = runWholeScript(newSource())

        // Frame n comes from step n-1, so the step's scheduled time is what its values must carry.
        val drivingFrame = frames.indexOfFirst { it.state.speedKph.valueOrNull() == 60f }
        val drivingAtMs = stepTimesMs[drivingFrame - 1]
        val driving = frames[drivingFrame].state
        assertEquals(drivingAtMs, driving.lastUpdatedMs)
        assertEquals(drivingAtMs, (driving.speedKph as com.csjotlab.cardashboard.vehicle.domain.Signal.Value).timestampMs)

        val dtcFrame = frames.indexOfFirst { it.diagnostics.issues.isNotEmpty() }
        val dtc = frames[dtcFrame].diagnostics
        assertEquals(stepTimesMs[dtcFrame - 1], dtc.lastScanMs)
        assertEquals(stepTimesMs[dtcFrame - 1], dtc.issues.single().firstSeenMs)

        assertTrue(
            "timestamps must advance across the script, not repeat a constant",
            frames.mapNotNull { it.state.lastUpdatedMs }.distinct().size >= 3,
        )
    }

    @Test
    fun `proof of contact is reported only while the vehicle is actually answering`() = runTest {
        val source = newSource()
        val beats = mutableListOf<Long>()
        val beatJob = backgroundScope.launch { source.liveness.collect { beats += it } }
        val (frames, framesJob) = collectFrames(source)

        source.start().activate()
        runCurrent()
        advanceTimeBy(scriptDurationMs + 1)
        runCurrent()
        beatJob.cancel()
        framesJob.cancel()

        // Frame n comes from step n-1. A beat means the vehicle answered, so there must be exactly
        // one per step that leaves the connection live — and none at all while disconnected,
        // connecting or after the cable is pulled, because nothing could have answered then.
        val liveStepTimes = frames.withIndex()
            .filter { (index, frame) -> index > 0 && frame.connection.isLiveData }
            .map { (index, _) -> stepTimesMs[index - 1] }

        assertEquals(liveStepTimes, beats)

        val firstLiveAtMs = stepTimesMs[frames.indexOfFirst { it.connection.isLiveData } - 1]
        assertTrue(
            "no beat may predate the connection: nothing had answered yet",
            beats.none { it < firstLiveAtMs },
        )
        assertTrue("the script must actually produce beats", beats.isNotEmpty())
    }

    @Test
    fun `the healthy portion of the script instruments every seat, door and tyre`() = runTest {
        val frames = runWholeScript(newSource())

        // The healthy window is the frame that first publishes the driving values, not "some frame
        // somewhere in the script" — asserting the latter would be satisfied by the coverage-restored
        // step even if the healthy window itself were threadbare.
        val healthy = frames.first { it.state.speedKph.valueOrNull() == 60f }.state

        assertEquals(
            "a simulator has authority over its own vehicle: the healthy window reports all 5 seats",
            SeatPosition.entries.toSet(),
            healthy.seatbelts.valueOrNull()?.keys,
        )
        assertEquals(
            "...all 6 doors",
            DoorPosition.entries.toSet(),
            healthy.doors.valueOrNull()?.keys,
        )
        assertEquals(
            "...and all 4 tyres",
            TirePosition.entries.toSet(),
            healthy.tirePressuresKpa.valueOrNull()?.keys,
        )
        assertTrue(
            "every belt in the healthy window is buckled",
            healthy.seatbelts.valueOrNull()!!.values.all { it == SeatbeltState.Buckled },
        )
    }

    @Test
    fun `the script keeps one window of deliberately partial coverage`() = runTest {
        val frames = runWholeScript(newSource())

        val partial = frames.filter { frame ->
            frame.connection.isLiveData &&
                (frame.state.seatbelts.valueOrNull()?.size ?: 0) in 1 until SeatPosition.entries.size
        }

        assertTrue(
            "the NotReported path must still be reachable on a device: keep a window where the " +
                "source reports only some of the seats",
            partial.isNotEmpty(),
        )
        assertTrue(
            "partial coverage means fewer keys, never invented ones",
            partial.all { frame ->
                SeatPosition.entries.containsAll(frame.state.seatbelts.valueOrNull()!!.keys)
            },
        )
        // ...and it must be a window, not the end state: full coverage comes back afterwards.
        val lastPartial = frames.indexOfLast { it in partial }
        assertTrue(
            "coverage must be restored after the partial window",
            frames.drop(lastPartial + 1).any {
                it.state.seatbelts.valueOrNull()?.keys == SeatPosition.entries.toSet()
            },
        )
    }

    @Test
    fun `no proof of contact is emitted before start or after stop`() = runTest {
        val source = newSource()
        val beats = mutableListOf<Long>()
        val job = backgroundScope.launch { source.liveness.collect { beats += it } }
        runCurrent()

        advanceTimeBy(scriptDurationMs + 1)
        runCurrent()
        assertEquals("a beat before start() would be proof of a contact that never happened", 0, beats.size)

        source.start().activate()
        runCurrent()
        advanceTimeBy(stepTimesMs[2] + 1)
        runCurrent()
        assertTrue(beats.isNotEmpty())

        val beatsAtStop = beats.size
        source.stop()
        runCurrent()
        advanceTimeBy(scriptDurationMs + 1)
        runCurrent()
        job.cancel()

        assertEquals("a stopped source has nothing to report contact with", beatsAtStop, beats.size)
    }

    @Test
    fun `stop halts the script and resets the frame`() = runTest {
        val source = newSource()
        val (frames, job) = collectFrames(source)

        source.start().activate()
        runCurrent()
        advanceTimeBy(stepTimesMs[2] + 1)   // through to Reading
        runCurrent()
        assertEquals(VehicleConnectionState.Reading, frames.last().connection)

        source.stop()
        runCurrent()
        assertEquals(VehicleConnectionState.Disconnected, frames.last().connection)

        val sizeAtStop = frames.size
        advanceTimeBy(scriptDurationMs + 1)
        runCurrent()
        job.cancel()

        assertEquals("a stopped script must not keep running", sizeAtStop, frames.size)
    }
}
