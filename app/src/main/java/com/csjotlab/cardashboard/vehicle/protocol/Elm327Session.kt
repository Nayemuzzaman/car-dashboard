package com.csjotlab.cardashboard.vehicle.protocol

import com.csjotlab.cardashboard.vehicle.domain.AdapterIdentity
import com.csjotlab.cardashboard.vehicle.transport.TransportEvent
import com.csjotlab.cardashboard.vehicle.transport.VehicleTransport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The outcome of one OBD-II request.
 *
 * There is deliberately no "value or zero" member. A malformed, short, mismatched or absent reply
 * is an error, never a reading — a decoder that received a fabricated byte would return a
 * plausible number and nothing downstream could tell it apart from the truth. See spec section
 * 2.1 rule 3.
 */
sealed interface ObdResult {
    /** The payload bytes *after* the echoed mode (and PID) bytes. `bytes[0]` is data byte A. */
    data class Data(val bytes: List<Int>) : ObdResult

    /** The adapter reached the bus and nothing answered the request. */
    data object NoData : ObdResult

    /** The adapter answered but could not talk to the vehicle. */
    data class BusError(val reason: String) : ObdResult

    /** The adapter itself misbehaved, or answered something we refuse to interpret. */
    data class AdapterError(
        val reason: String,
        /** True only when the byte stream itself proved it can no longer deliver replies. */
        val transportFailure: Boolean = false,
    ) : ObdResult

    /** Nothing arrived within the command budget. */
    data object Timeout : ObdResult
}

sealed interface HandshakeResult {
    /**
     * The adapter identified itself as ELM327-compatible and capability discovery decoded.
     *
     * [supportedPids] is authoritative for the banks that were actually decoded. [undiscoveredBanks]
     * lists bank base PIDs whose bitmask never came back decodably, plus every bank after them: once
     * the chain breaks, the bitmask that would have advertised the later banks never arrived either,
     * so nothing is known about any of them. A PID in one of those banks' ranges is *unknown*, not
     * unsupported. Absence from [supportedPids] only means "unsupported" for a bank that was itself
     * decoded. See global constraints, "discovery failure is not capability".
     */
    data class Ready(
        val identity: AdapterIdentity,
        val protocol: String?,
        val supportedPids: Set<Int>,
        val undiscoveredBanks: Set<Int>,
    ) : HandshakeResult

    /** [identity] is whatever the device said, verbatim; we do not guess at unknown hardware. */
    data class NotElmCompatible(val identity: String) : HandshakeResult

    /** The adapter answers but the vehicle bus does not, or the adapter never answered at all. */
    data class BusUnavailable(val reason: String) : HandshakeResult

    /**
     * An ELM327 adapter that answered the first capability request with something undecodable.
     *
     * This is distinct from [Ready] with an empty [Ready.supportedPids]: that means "decoded, the
     * vehicle supports nothing", which licenses `Signal.Unsupported`. This means "we do not know",
     * which must become `Signal.Unknown`. Collapsing the two would let one garbled frame mark
     * every field permanently unsupported.
     */
    data class CapabilityDiscoveryFailed(
        val identity: AdapterIdentity,
        val reason: String,
    ) : HandshakeResult
}

/**
 * The outcome of a DTC read.
 *
 * [Codes] means the scan *completed*: every responding ECU was heard and understood. Only that
 * licenses a caller to retire a fault it is no longer seeing. Everything else — an error from any
 * responder, a truncated frame, a timeout, or nothing answering at all — is [Failed], and a caller
 * must keep the codes it already holds.
 */
sealed interface DtcReadResult {
    /** The scan completed. An empty list means every responder reported a count of zero codes. */
    data class Codes(val codes: List<String>) : DtcReadResult

    /** The scan did not complete; [cause] is the transport/protocol outcome that stopped it. */
    data class Failed(val cause: ObdResult) : DtcReadResult
}

/**
 * Speaks ELM327 over a [VehicleTransport]: frames one request/response exchange at a time,
 * configures the adapter, and turns replies into typed results.
 *
 * Three properties matter more than anything else here.
 *
 * *Nothing but a fixed command set reaches the wire.* The only strings this class can transmit are
 * [ObdCommand.request] values — a sealed, read-only hierarchy — and the seven configuration
 * commands in [AtCommand]. There is deliberately no method that takes a caller-supplied string, so
 * mode 04, any UDS service, the VIN read and raw CAN transmission are unreachable by construction
 * rather than by convention. See spec section 2 and the global safety boundary.
 *
 * *Payload alignment is enforced here, not below.* The mode-01 decoders tolerate over-length
 * frames because ISO 15765 pads CAN frames, so they cannot notice a payload that starts one byte
 * early; a leftover PID echo would turn `41 0D 3C` into a confident "0 km/h". This class therefore
 * refuses to produce [ObdResult.Data] at all unless the reply's leading bytes are exactly the
 * response header the request demands, and it strips exactly those bytes and no others.
 *
 * *Every line of a reply is accounted for.* An OBD request is a functional request: on a vehicle
 * with several ECUs, each responder prints its own line. Reading the first line and dropping the
 * rest would report a *completed* mode 03 scan finding nothing on a car that is storing a
 * transmission fault. Nothing here silently discards a line: data lines are aggregated, and a line
 * that is neither a recognised word reply nor a correctly headed frame fails the whole reply.
 */
class Elm327Session(
    private val transport: VehicleTransport,
    private val commandTimeoutMs: Long = 1_000L,
    private val onSuccessfulVehicleResponse: suspend () -> Unit = {},
) {

    /**
     * One exchange at a time. The transport is a single byte pipe with no request/response
     * correlation of its own, so two overlapping exchanges would each be able to read the other's
     * reply off the wire. Serialising is what makes "the value came from the response to that
     * field's own request" true. It is also what makes the pipe bookkeeping below single-threaded.
     */
    private val exchangeLock = Mutex()

    /**
     * Bytes received but not yet consumed, including any partial reply left behind when an
     * exchange was abandoned. Only ever touched while [exchangeLock] is held.
     */
    private var residual = StringBuilder()

    /**
     * Commands written whose reply never arrived. The adapter answers in order, so the next reply
     * to appear on the wire belongs to the oldest of these, not to the command we are about to
     * send: that many prompt-terminated blocks are read and discarded before a reply is accepted.
     * Without this, a request that timed out and a retry of the *same* PID are indistinguishable,
     * and the stale frame is served as a fresh reading.
     */
    private var unacknowledgedWrites = 0

    /** Issues [command] and classifies the reply. Never throws for a protocol-level problem. */
    suspend fun request(command: ObdCommand): ObdResult {
        val result = when (val outcome = requestFrames(command)) {
            is FramesOutcome.Other -> outcome.result
            is FramesOutcome.Frames -> {
                val distinct = outcome.frames.distinct()
                if (distinct.size == 1) {
                    ObdResult.Data(distinct.single())
                } else {
                    // Several ECUs answered with different payloads. There is no rule that makes
                    // one of them the reading, so there is no reading.
                    ObdResult.AdapterError(
                        "responding ECUs disagreed about ${command.request}: ${outcome.frames}",
                    )
                }
            }
        }
        if (result is ObdResult.Data) onSuccessfulVehicleResponse()
        return result
    }

    /**
     * Configures the adapter and discovers what the vehicle can report.
     *
     * Sends `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH0`, `ATSP0`, `ATI` and then walks the mode-01
     * capability banks. Every one of those is configuration or a read; none of them can affect the
     * vehicle.
     */
    suspend fun handshake(): HandshakeResult {
        var banner = ""
        for (at in AtCommand.entries) {
            when (val reply = exchange(at.request)) {
                Reply.TimedOut ->
                    return HandshakeResult.BusUnavailable("the adapter did not answer ${at.request}")
                is Reply.TransportFailed ->
                    return HandshakeResult.BusUnavailable("the transport failed during ${at.request}: ${reply.reason}")
                is Reply.Received ->
                    if (at == AtCommand.Identify) banner = reply.lines.joinToString(" ")
            }
        }

        if (!banner.uppercase().contains(ELM_SIGNATURE)) return HandshakeResult.NotElmCompatible(banner)
        val identity = AdapterIdentity(rawIdentity = banner, elmCompatible = true)

        return discoverCapabilities(identity)
    }

    /**
     * Reads one DTC service across every responding ECU, or reports that the scan did not complete.
     *
     * [command] must be [ObdCommand.StoredDtcs], [ObdCommand.PendingDtcs] or
     * [ObdCommand.PermanentDtcs]; the response header is checked against that service, so a mode
     * 07 reply can never be filed as a stored code.
     */
    suspend fun readDtcResult(command: ObdCommand): DtcReadResult {
        require(command is ObdCommand.StoredDtcs || command is ObdCommand.PendingDtcs || command is ObdCommand.PermanentDtcs) {
            "readDtcResult is only defined for the DTC services 03, 07 and 0A: $command"
        }

        val result = when (val outcome = requestFrames(command)) {
            // NoData reaches here too: nothing answered, so this is not a scan that found nothing.
            is FramesOutcome.Other -> DtcReadResult.Failed(outcome.result)
            is FramesOutcome.Frames -> {
                val perEcu = outcome.frames.map { decodeCodes(it) }
                if (perEcu.any { it == null }) {
                    DtcReadResult.Failed(
                        ObdResult.AdapterError("a ${command.request} response frame was not a count plus whole code pairs"),
                    )
                } else {
                    DtcReadResult.Codes(perEcu.filterNotNull().flatten().distinct())
                }
            }
        }
        if (result is DtcReadResult.Codes) onSuccessfulVehicleResponse()
        return result
    }

    /**
     * Convenience over [readDtcResult] for callers that only ever act on codes that are present.
     *
     * A failed scan yields an empty list here, indistinguishable from a vehicle with no faults.
     */
    @Deprecated(
        message = "Lossy: a failed scan is indistinguishable from a clean one, so this must not be " +
            "used to reconcile a fault list across scans.",
        replaceWith = ReplaceWith("readDtcResult(command)"),
    )
    suspend fun readDtcs(command: ObdCommand): List<String> =
        when (val result = readDtcResult(command)) {
            is DtcReadResult.Codes -> result.codes
            is DtcReadResult.Failed -> emptyList()
        }

    // -- capability discovery ---------------------------------------------------------------

    private suspend fun discoverCapabilities(identity: AdapterIdentity): HandshakeResult {
        val supported = mutableSetOf<Int>()
        val undiscovered = mutableSetOf<Int>()

        for ((index, bank) in CAPABILITY_BANKS.withIndex()) {
            val outcome = requestFrames(ObdCommand.SupportedPids(bank))
            val decoded = decodeBank(bank, outcome)

            if (decoded == null) {
                if (bank == FIRST_CAPABILITY_BANK) {
                    return firstBankFailure(identity, (outcome as? FramesOutcome.Other)?.result)
                }
                // This bank is unknown, and so is every bank after it: the bitmask that would have
                // said whether they exist is the one that just failed to arrive.
                undiscovered += CAPABILITY_BANKS.drop(index)
                break
            }

            onSuccessfulVehicleResponse()
            supported += decoded
            val nextBank = CAPABILITY_BANKS.getOrNull(index + 1) ?: break
            if (nextBank !in decoded) break
        }

        return HandshakeResult.Ready(
            identity = identity,
            // ATSP0 leaves protocol selection to the adapter and we do not interrogate it, so the
            // active protocol is genuinely unknown at this layer rather than assumed.
            protocol = null,
            supportedPids = supported,
            undiscoveredBanks = undiscovered,
        )
    }

    /**
     * Unions one bank's bitmask across every responding ECU, or returns null if the bank is not
     * fully known.
     *
     * `SupportedPidSet.decode` returns null for "undecodable", which is never "supports nothing":
     * if any responder's bitmask fails to decode, the bank as a whole is unknown. Keeping the
     * ECUs that did decode would quietly shrink the capability set and turn another ECU's PIDs
     * into `Unsupported`.
     */
    private fun decodeBank(bank: Int, outcome: FramesOutcome): Set<Int>? = when (outcome) {
        is FramesOutcome.Other -> null
        is FramesOutcome.Frames -> {
            val perEcu = outcome.frames.map { SupportedPidSet.decode(bank, it) }
            if (perEcu.any { it == null }) null else perEcu.filterNotNull().flatten().toSet()
        }
    }

    /** [result] is null when the bank answered with frames that would not decode. */
    private fun firstBankFailure(identity: AdapterIdentity, result: ObdResult?): HandshakeResult = when (result) {
        ObdResult.NoData ->
            HandshakeResult.BusUnavailable("the vehicle returned NO DATA for the capability request")
        is ObdResult.BusError ->
            HandshakeResult.BusUnavailable(result.reason)
        ObdResult.Timeout ->
            HandshakeResult.BusUnavailable("the vehicle did not answer the capability request")
        is ObdResult.AdapterError ->
            if (result.transportFailure) {
                HandshakeResult.BusUnavailable(result.reason)
            } else {
                HandshakeResult.CapabilityDiscoveryFailed(identity, result.reason)
            }
        is ObdResult.Data, null ->
            HandshakeResult.CapabilityDiscoveryFailed(identity, "the bank 0x00 capability bitmask could not be decoded")
    }

    // -- DTC payloads -----------------------------------------------------------------------

    /**
     * Decodes one ECU's mode 03/07/0A payload, or returns null if it is not shaped like one.
     *
     * The framing is explicit rather than guessed: these services answer with a DTC count byte
     * followed by whole two-byte codes, so an odd number of bytes after the count is a truncated
     * or foreign frame. Inferring the framing from the payload length instead would read the count
     * byte as half of a code pair whenever the total happened to come out even, manufacturing
     * codes out of a truncated frame.
     *
     * The count must then match the codes exactly, in **both** directions. Padding pairs (0x0000)
     * mean "no code in this slot" and are not codes, so a well-formed frame carries precisely as
     * many non-padding pairs as it declares. Trimming to the declared count instead would let
     * `43 00 03 01` — a count of zero carrying P0301 — report a completed scan that found nothing
     * on a fault-bearing frame.
     */
    private fun decodeCodes(payload: List<Int>): List<String>? {
        val declaredCount = payload.first()
        val codeBytes = payload.drop(1)
        if (codeBytes.size % 2 != 0) return null

        val codes = codeBytes.chunked(2).mapNotNull { DtcDecoder.decodePair(it[0], it[1]) }
        if (codes.size != declaredCount) return null

        return codes
    }

    // -- reply interpretation ---------------------------------------------------------------

    private sealed interface FramesOutcome {
        /** One payload per responding ECU, each already stripped to its first data byte. */
        data class Frames(val frames: List<List<Int>>) : FramesOutcome
        data class Other(val result: ObdResult) : FramesOutcome
    }

    private suspend fun requestFrames(command: ObdCommand): FramesOutcome =
        when (val reply = exchange(command.request)) {
            Reply.TimedOut -> FramesOutcome.Other(ObdResult.Timeout)
            is Reply.TransportFailed -> FramesOutcome.Other(
                ObdResult.AdapterError(
                    reason = "the transport failed: ${reply.reason}",
                    transportFailure = true,
                ),
            )
            is Reply.Received -> interpret(command, reply.lines)
        }

    /**
     * Classifies a reply line by line.
     *
     * Word replies are matched per line, never against the lines joined together: one ECU saying
     * `NO DATA` while another answers is a reading, not a `NO DATA`. A hard error from any
     * responder fails the whole reply even if another answered, because half a scan is not a scan.
     */
    private fun interpret(command: ObdCommand, lines: List<String>): FramesOutcome {
        if (lines.isEmpty()) return other(ObdResult.AdapterError("the adapter returned an empty reply"))

        val words = lines.mapNotNull { wordReplyFor(it) }
        words.firstOrNull { it !is ObdResult.NoData }?.let { return other(it) }

        val header = responseHeaderFor(command)
        val frames = mutableListOf<List<Int>>()
        for (line in lines) {
            if (wordReplyFor(line) != null) continue
            when (val frame = parseFrame(command, header, line)) {
                is ParsedFrame.Payload -> frames += frame.bytes
                is ParsedFrame.Rejected -> return other(ObdResult.AdapterError(frame.reason))
            }
        }

        if (frames.isEmpty()) {
            // Every line was a word reply, and none of them was fatal, so they were all NO DATA.
            return if (words.isNotEmpty()) {
                other(ObdResult.NoData)
            } else {
                other(ObdResult.AdapterError("the adapter returned no usable lines"))
            }
        }
        if (words.isNotEmpty() && demandsEveryResponderUnderstood(command)) {
            // A NO DATA line is survivable for a value request — one ECU has nothing for this PID
            // while another answers — but not for a fault scan, whose whole meaning is that every
            // responder was heard. Same rule as a hard error: half a scan is not a scan.
            return other(
                ObdResult.AdapterError(
                    "a responder did not answer ${command.request} while another did: ${lines.joinToString(" | ")}",
                ),
            )
        }
        return FramesOutcome.Frames(frames)
    }

    /**
     * True for the DTC services, whose result is a claim about the whole vehicle: absence of a
     * code is only meaningful if every ECU was heard and understood.
     */
    private fun demandsEveryResponderUnderstood(command: ObdCommand): Boolean =
        command is ObdCommand.StoredDtcs ||
            command is ObdCommand.PendingDtcs ||
            command is ObdCommand.PermanentDtcs

    private fun other(result: ObdResult): FramesOutcome = FramesOutcome.Other(result)

    private sealed interface ParsedFrame {
        data class Payload(val bytes: List<Int>) : ParsedFrame
        data class Rejected(val reason: String) : ParsedFrame
    }

    /**
     * Turns one reply line into its payload, or rejects it. A line is never skipped: a line that
     * is not a word reply and not a correctly headed frame fails the whole reply, so a frame
     * belonging to another request or another mode cannot ride along unnoticed beside a good one.
     */
    private fun parseFrame(command: ObdCommand, header: String, line: String): ParsedFrame {
        val hex = stripFrameHeader(line).replace(" ", "").uppercase()

        if (!hex.startsWith(header)) {
            return ParsedFrame.Rejected("expected a $header response to ${command.request}, got: $line")
        }
        if (hex.any { it !in HEX_DIGITS }) {
            return ParsedFrame.Rejected("the reply to ${command.request} is not hexadecimal: $hex")
        }
        if (hex.length % 2 != 0) {
            return ParsedFrame.Rejected("the reply to ${command.request} is not a whole number of bytes: $hex")
        }

        val bytes = hex.chunked(2).map { it.toInt(16) }
        // Exactly the header bytes are dropped, so bytes[0] below is data byte A and nothing else.
        val payload = bytes.drop(header.length / 2)
        if (payload.isEmpty()) {
            return ParsedFrame.Rejected("the reply to ${command.request} carried no data bytes")
        }
        return ParsedFrame.Payload(payload)
    }

    /** The documented ELM327 word replies, matched against one line and nothing else. */
    private fun wordReplyFor(line: String): ObdResult? {
        val compact = line.uppercase().replace(" ", "")
        return when {
            compact.contains("NODATA") -> ObdResult.NoData
            compact.contains("UNABLETOCONNECT") -> ObdResult.BusError("UNABLE TO CONNECT")
            compact.contains("CANERROR") -> ObdResult.BusError("CAN ERROR")
            compact.contains("BUSINIT") -> ObdResult.BusError(line)
            compact.contains("STOPPED") -> ObdResult.AdapterError("STOPPED")
            compact.contains("BUFFERFULL") -> ObdResult.AdapterError("BUFFER FULL")
            compact.contains("?") -> ObdResult.AdapterError("the adapter did not understand the command")
            else -> null
        }
    }

    /** The positive-response header a reply must begin with: the request's mode plus 0x40. */
    private fun responseHeaderFor(command: ObdCommand): String = when (command) {
        is ObdCommand.CurrentData -> "41%02X".format(command.pid)
        is ObdCommand.SupportedPids -> "41%02X".format(command.basePid)
        ObdCommand.StoredDtcs -> "43"
        ObdCommand.PendingDtcs -> "47"
        ObdCommand.PermanentDtcs -> "4A"
    }

    /**
     * Removes a CAN frame header (`7E8 03 41 0D 3C`) left in place by an adapter that ignored
     * `ATH0`.
     *
     * The header is only removed when the line proves it is one: a first token that is a CAN
     * identifier width (three or eight hex digits, neither of which a data byte can be), followed
     * by a single-frame PCI byte whose declared length matches the number of tokens that actually
     * follow. Anything short of that is left alone and will fail the header check instead — a
     * guess here would silently shift the payload, which is the one failure this whole class
     * exists to prevent.
     */
    private fun stripFrameHeader(line: String): String {
        val tokens = line.trim().split(WHITESPACE)
        if (tokens.size < 3) return line

        val identifier = tokens[0].uppercase()
        val pci = tokens[1].uppercase()
        if (!CAN_IDENTIFIER.matches(identifier)) return line
        if (!SINGLE_FRAME_PCI.matches(pci)) return line
        if (pci.toInt(16) != tokens.size - 2) return line

        return tokens.drop(2).joinToString(" ")
    }

    // -- framing ----------------------------------------------------------------------------

    private sealed interface Reply {
        /** The adapter's reply, split into non-empty lines with echo and `SEARCHING` removed. */
        data class Received(val lines: List<String>) : Reply
        data object TimedOut : Reply
        data class TransportFailed(val reason: String) : Reply
    }

    /**
     * Writes one command and reads back everything up to the ELM327 prompt (`>`), which is the
     * only reliable end-of-reply marker: a reply can arrive in any number of chunks, split
     * anywhere, so the prompt — not the arrival of *some* bytes — is what completes an exchange.
     *
     * The reply collector is subscribed *before* the write, and started undispatched so the
     * subscription is in place by the time [VehicleTransport.write] returns. `incoming()` has no
     * replay: subscribing afterwards would silently miss the reply and every exchange would time
     * out.
     */
    private suspend fun exchange(request: String): Reply = exchangeLock.withLock {
        val abandoned = unacknowledgedWrites
        var skipped = 0
        var written = false
        var outcome: Reply? = null

        try {
            // The timeout is inside the lock, so a queued command is not charged for the time it
            // spent waiting its turn, and it wraps the read, so a silent adapter expires rather
            // than parking the caller forever.
            outcome = withTimeoutOrNull(commandTimeoutMs) {
                coroutineScope {
                    val reply = async(start = CoroutineStart.UNDISPATCHED) {
                        var remainingToSkip = abandoned
                        var block = readUntilPrompt()
                        while (block is Block.Complete && remainingToSkip > 0) {
                            skipped++
                            remainingToSkip--
                            block = readUntilPrompt()
                        }
                        block
                    }
                    val failure = async(start = CoroutineStart.UNDISPATCHED) {
                        transport.events.filterIsInstance<TransportEvent.Failed>().firstOrNull()
                        // A transport whose event stream simply ends has not failed; it must never
                        // win this race, so wait to be cancelled instead of completing with nothing.
                            ?: awaitCancellation()
                    }

                    written = true
                    transport.write("$request\r".toByteArray(Charsets.ISO_8859_1))

                    val result: Reply = select {
                        reply.onAwait { block ->
                            when (block) {
                                is Block.Complete -> Reply.Received(clean(block.text, request))
                                Block.StreamEnded -> Reply.TransportFailed("the transport stopped delivering bytes")
                                Block.Overflowed -> Reply.TransportFailed(
                                    "the adapter sent more than $MAX_UNFRAMED_CHARS characters with no prompt",
                                )
                            }
                        }
                        failure.onAwait { Reply.TransportFailed(it.reason) }
                    }
                    reply.cancel()
                    failure.cancel()
                    result
                }
            } ?: Reply.TimedOut
            outcome
        } finally {
            // In a finally, because the caller can be cancelled between the write and here — a
            // polling loop being shut down mid-exchange does exactly that. The adapter still owes
            // a reply to the command that was written, and forgetting it is the one direction that
            // hurts: the next exchange for the same PID would accept that frame as fresh data.
            // A reply means the pipe is synchronised again: this command and every skipped block
            // are accounted for. Otherwise this command's reply joins whatever was outstanding and
            // not skipped. `written` is set before the write, so an interrupted write counts as
            // owed — over-counting costs a discarded frame, under-counting serves a stale one.
            unacknowledgedWrites = when {
                outcome is Reply.Received -> 0
                written -> (abandoned - skipped) + 1
                else -> abandoned - skipped
            }
        }
    }

    /** One prompt-terminated block, or the reason there will not be one. */
    private sealed interface Block {
        data class Complete(val text: String) : Block
        data object StreamEnded : Block
        data object Overflowed : Block
    }

    /**
     * Returns the next prompt-terminated block.
     *
     * Accumulation is into [residual], which survives this call: bytes that arrived after the
     * prompt belong to the next block, and bytes accumulated before a cancellation are the start
     * of a block the next exchange will finish reading and discard. Losing either would leave the
     * parser cutting reply frames in the wrong place.
     *
     * The buffer is bounded. A source that never sends a prompt would otherwise grow it for the
     * life of the session, so past [MAX_UNFRAMED_CHARS] the whole buffer is dropped and the
     * exchange fails. Dropping is the only safe response: keeping a tail and splicing later bytes
     * onto it could reconstitute a frame that never existed, and this layer must never invent one.
     */
    private suspend fun readUntilPrompt(): Block {
        if (!residual.contains(PROMPT)) {
            transport.incoming()
                .map { chunk -> residual.append(String(chunk, Charsets.ISO_8859_1)).toString() }
                // firstOrNull, not first: a transport whose stream completes must yield a result,
                // not throw NoSuchElementException out of a suspend function documented not to.
                .firstOrNull { it.contains(PROMPT) || it.length > MAX_UNFRAMED_CHARS }
                ?: return Block.StreamEnded

            if (!residual.contains(PROMPT)) {
                residual = StringBuilder()
                return Block.Overflowed
            }
        }

        val received = residual.toString()
        val promptAt = received.indexOf(PROMPT)
        residual = StringBuilder(received.substring(promptAt + 1))
        return Block.Complete(received.substring(0, promptAt))
    }

    /**
     * Turns one raw block into the meaningful lines of the reply: the command echo (an adapter
     * that ignored `ATE0`) and the `SEARCHING...` preamble are not part of the answer.
     */
    private fun clean(raw: String, request: String): List<String> =
        raw.split('\r', '\n')
            .map { it.trim() }
            .map { SEARCHING.replace(it, "").trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.replace(" ", "").equals(request, ignoreCase = true) }

    /**
     * The complete set of adapter commands this application can transmit. Every one is ELM327
     * configuration or an identity query; none of them touch the vehicle. Declaration order is
     * the order the handshake sends them in.
     */
    private enum class AtCommand(val request: String) {
        /** Reset to a known state. */
        Reset("ATZ"),

        /** Echo off — otherwise every reply is prefixed with the command. */
        EchoOff("ATE0"),

        /** Linefeeds off. */
        LinefeedsOff("ATL0"),

        /** Spaces off, so replies arrive as compact hex. */
        SpacesOff("ATS0"),

        /** Headers off, so replies contain only mode/PID echo plus data. */
        HeadersOff("ATH0"),

        /** Automatic protocol selection. */
        AutoProtocol("ATSP0"),

        /** Identify the adapter. */
        Identify("ATI"),
    }

    private companion object {
        const val PROMPT = '>'
        const val ELM_SIGNATURE = "ELM327"
        const val HEX_DIGITS = "0123456789ABCDEF"
        const val FIRST_CAPABILITY_BANK = 0x00

        /**
         * How much unframed output to hold before giving up on it. An ELM327 reply, even a
         * multi-line multi-ECU one, is a few hundred characters; this is generous enough that no
         * real reply reaches it and small enough that a babbling source cannot exhaust memory.
         */
        const val MAX_UNFRAMED_CHARS = 4_096

        /** Mode-01 capability bitmask banks, in walk order. */
        val CAPABILITY_BANKS = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0)

        val WHITESPACE = Regex("\\s+")
        val SEARCHING = Regex("^SEARCHING\\.*", RegexOption.IGNORE_CASE)

        /** An 11-bit or 29-bit CAN identifier. A data byte is always two digits, never three. */
        val CAN_IDENTIFIER = Regex("^[0-9A-F]{3}$|^[0-9A-F]{8}$")

        /** An ISO-TP single-frame PCI byte: high nibble 0, low nibble the payload length. */
        val SINGLE_FRAME_PCI = Regex("^0[0-9A-F]$")
    }
}
