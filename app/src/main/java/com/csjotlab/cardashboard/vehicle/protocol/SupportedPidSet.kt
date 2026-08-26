package com.csjotlab.cardashboard.vehicle.protocol

/**
 * Decodes a mode-01 capability bitmask. This is the authority on what a vehicle can report — see
 * spec section 2.1.
 *
 * The return type is deliberately `Set<Int>?`, not `Set<Int>`: `null` means the frame was
 * undecodable (discovery failed), while a non-null empty set means the frame decoded successfully
 * and reported zero supported PIDs. These are not interchangeable — a consumer that treats "PID
 * not in the set" as `Signal.Unsupported` must never do that off the back of a garbled discovery
 * response, or one bad frame would silently mark every field unsupported. Discovery failure must
 * map to `Signal.Unknown`, not `Signal.Unsupported`. See global constraints, "discovery failure is
 * not capability", and task-15 review item 1.
 */
object SupportedPidSet {

    /** The six mode-01 capability bitmask banks, plus 0xC0 for vehicles reporting PIDs past 0xC0. */
    private val VALID_BASE_PIDS = setOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)

    fun decode(basePid: Int, data: List<Int>): Set<Int>? {
        require(basePid in VALID_BASE_PIDS) {
            "basePid must be a mode-01 capability bank base (one of $VALID_BASE_PIDS): $basePid"
        }
        if (data.size < 4) return null

        val mask = ((data[0] and 0xFF).toLong() shl 24) or
            ((data[1] and 0xFF).toLong() shl 16) or
            ((data[2] and 0xFF).toLong() shl 8) or
            (data[3] and 0xFF).toLong()

        // Bit 31 is the first PID after the base; bit 0 is the 32nd.
        return (0 until 32)
            .filter { index -> ((mask shr (31 - index)) and 1L) == 1L }
            .map { index -> basePid + 1 + index }
            .toSet()
    }
}
