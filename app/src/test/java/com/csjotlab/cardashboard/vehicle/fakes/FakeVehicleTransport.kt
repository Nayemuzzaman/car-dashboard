package com.csjotlab.cardashboard.vehicle.fakes

import com.csjotlab.cardashboard.vehicle.transport.TransportEvent
import com.csjotlab.cardashboard.vehicle.transport.VehicleTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * An in-memory [VehicleTransport] that answers written commands with canned ELM327 replies, so
 * the protocol layer above it is testable without real hardware.
 *
 * This is a pure byte pipe: it knows about bytes, chunks, delays, silence and failure, and
 * nothing about OBD-II, ELM327 command semantics, or USB. Fault injection is opt-in per command
 * (or, for [goSilent], per scenario going forward) — configuring one command's behaviour never
 * changes another command's, and the default canned-reply behaviour used by existing tests is
 * unaffected unless a test explicitly reaches for one of the fault-injection methods below.
 */
class FakeVehicleTransport(
    responses: Map<String, String>,
    private val defaultResponse: String = "NO DATA",
) : VehicleTransport {

    /** How [write] responds to one specific command. */
    private sealed interface ReplyPlan {
        /** The default: reply with [reply] plus the ELM327 prompt, in a single emission. */
        data class Immediate(val reply: String) : ReplyPlan

        /** Reply with each element of [chunks] as its own emission, in order. */
        data class Chunked(val chunks: List<String>) : ReplyPlan

        /** Reply with [reply] plus the ELM327 prompt, after [delayMs] on [scope]. */
        data class Delayed(val reply: String, val delayMs: Long, val scope: CoroutineScope) : ReplyPlan

        /** Reply with exactly [bytes] — no `\r\r>` framing added, for malformed-output tests. */
        data class Raw(val bytes: ByteArray) : ReplyPlan

        /** Never reply to this command. */
        data object Withheld : ReplyPlan
    }

    private val plans = mutableMapOf<String, ReplyPlan>().apply {
        responses.forEach { (command, reply) -> put(command, ReplyPlan.Immediate(reply)) }
    }

    /** Once set, every subsequent [write] is silently dropped, regardless of any [ReplyPlan]. */
    private var silentFromNow = false

    private val incomingBytes = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)
    private val transportEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 16)

    /**
     * Emits [TransportEvent]s raised by [open], [detach] and [fail]. Backed by a `replay = 0`
     * [SharedFlow][kotlinx.coroutines.flow.SharedFlow]: a collector must be subscribed *before*
     * the call that raises the event, or the event is silently missed — a state change is not
     * buffered for a late-arriving observer, exactly as with real hardware.
     */
    override val events: Flow<TransportEvent> = transportEvents

    private val _written = mutableListOf<String>()
    val written: List<String> get() = _written

    override suspend fun open() {
        transportEvents.emit(TransportEvent.Opened)
    }

    override suspend fun write(bytes: ByteArray) {
        val command = String(bytes).trimEnd('\r')
        _written.add(command)
        if (silentFromNow) return

        when (val plan = plans[command] ?: ReplyPlan.Immediate(defaultResponse)) {
            is ReplyPlan.Immediate -> incomingBytes.emit("${plan.reply}\r\r>".toByteArray())
            is ReplyPlan.Chunked -> plan.chunks.forEach { chunk -> incomingBytes.emit(chunk.toByteArray()) }
            is ReplyPlan.Delayed -> plan.scope.launch {
                delay(plan.delayMs)
                incomingBytes.emit("${plan.reply}\r\r>".toByteArray())
            }
            is ReplyPlan.Raw -> incomingBytes.emit(plan.bytes)
            ReplyPlan.Withheld -> Unit
        }
    }

    /**
     * Emits every reply byte sequence produced by [write]. Backed by a `replay = 0`
     * [SharedFlow][kotlinx.coroutines.flow.SharedFlow]: a collector must be subscribed *before*
     * the [write] call that triggers the emission, or the reply is silently missed — exactly as
     * a real serial port would drop bytes nobody was reading. This is a real trap when driving
     * this fake from a session that writes and then subscribes; subscribe first.
     */
    override fun incoming(): Flow<ByteArray> = incomingBytes

    override suspend fun close() {
        // Nothing to release for an in-memory fake.
    }

    fun detach() {
        transportEvents.tryEmit(TransportEvent.Detached)
    }

    fun fail(reason: String) {
        transportEvents.tryEmit(TransportEvent.Failed(reason))
    }

    /** Sets (or replaces) the canned single-emission reply for [command]. Existing behaviour. */
    fun respondTo(command: String, reply: String) {
        plans[command] = ReplyPlan.Immediate(reply)
    }

    /**
     * Withholds the reply to this specific [command]: [write] will record it in [written] but
     * [incoming] will never emit anything for it. Exercises a caller's timeout path without
     * silencing every other command.
     */
    fun silence(command: String) {
        plans[command] = ReplyPlan.Withheld
    }

    /**
     * From this call onward, every [write] — for any command, configured or not — produces no
     * emission on [incoming]. Models an adapter that has gone silent for the rest of the
     * scenario. A reply already scheduled by [respondAfterDelay] before this call still arrives;
     * only writes issued after [goSilent] are affected.
     */
    fun goSilent() {
        silentFromNow = true
    }

    /**
     * Delivers the reply to [command] as a separate [incoming] emission for each element of
     * [chunks], in order, with no added framing — the caller controls exactly where each chunk
     * boundary falls, including splitting the terminating `>` into its own later emission or
     * splitting a token across two emissions. Use this to prove an accumulation loop truly
     * accumulates rather than assuming one write produces one complete reply.
     */
    fun respondInChunks(command: String, chunks: List<String>) {
        plans[command] = ReplyPlan.Chunked(chunks)
    }

    /**
     * Delivers `"$reply\r\r>"` for [command] after [delayMs] have elapsed, scheduled on [scope]
     * so a test can drive it with `kotlinx-coroutines-test` virtual time (pass `backgroundScope`
     * from `runTest`). [write] itself returns immediately — the delay models the reply arriving
     * late, not the write call blocking — so a test can prove a reply arriving just under a
     * caller's timeout succeeds and just over it times out.
     */
    fun respondAfterDelay(command: String, reply: String, delayMs: Long, scope: CoroutineScope) {
        plans[command] = ReplyPlan.Delayed(reply, delayMs, scope)
    }

    /**
     * Delivers exactly [bytes] for [command], bypassing the canned `\r\r>` framing entirely, so
     * a test can supply bytes that are not valid ELM327 output at all and drive the protocol
     * layer's parser-error path.
     */
    fun respondWithRawBytes(command: String, bytes: ByteArray) {
        plans[command] = ReplyPlan.Raw(bytes)
    }
}
