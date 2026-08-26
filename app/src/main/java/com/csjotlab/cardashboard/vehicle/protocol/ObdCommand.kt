package com.csjotlab.cardashboard.vehicle.protocol

/**
 * Every OBD-II request this application is capable of making.
 *
 * The hierarchy is sealed and contains only read services. There is deliberately no way to express
 * mode 04 (clear DTCs), any UDS write or routine service, or a raw CAN frame — the type system, not
 * a code review, is what prevents it. See spec section 2 and ObdCommandTest.
 */
sealed interface ObdCommand {
    val request: String

    /** Service 01 — current data for one PID. */
    data class CurrentData(val pid: Int) : ObdCommand {
        init {
            require(pid in 0x00..0xFF) { "pid must fit in one byte (0x00..0xFF): $pid" }
        }
        override val request: String get() = "01%02X".format(pid)
    }

    /** Service 03 — stored DTCs. */
    data object StoredDtcs : ObdCommand {
        override val request: String get() = "03"
    }

    /** Service 07 — pending DTCs. */
    data object PendingDtcs : ObdCommand {
        override val request: String get() = "07"
    }

    /** Service 0A — permanent DTCs. */
    data object PermanentDtcs : ObdCommand {
        override val request: String get() = "0A"
    }

    /** Service 01, capability bitmask banks: 00, 20, 40, 60, 80, A0. */
    data class SupportedPids(val basePid: Int) : ObdCommand {
        init {
            require(basePid in 0x00..0xFF) { "basePid must fit in one byte (0x00..0xFF): $basePid" }
        }
        override val request: String get() = "01%02X".format(basePid)
    }
}
