package com.csjotlab.cardashboard.vehicle.protocol

data class MonitorStatus(val milOn: Boolean, val dtcCount: Int)

/**
 * Decodes SAE J1979 mode-01 PID responses into typed readings.
 *
 * Every decoder returns null on a short or malformed frame — never a zero. A zero is a valid
 * reading (0 km/h, 0 RPM); manufacturing one from an absent byte would turn a truncated response
 * into a plausible-looking lie. See spec section 2.1 rule 3.
 */
object ObdPid {
    const val MONITOR_STATUS = 0x01
    const val COOLANT = 0x05
    const val RPM = 0x0C
    const val SPEED = 0x0D
    const val RUN_TIME = 0x1F
    const val DISTANCE_WITH_MIL = 0x21
    const val FUEL_LEVEL = 0x2F
    const val DISTANCE_SINCE_CLEAR = 0x31
    const val AMBIENT_TEMP = 0x46
    const val ODOMETER = 0xA6

    fun decodeSpeedKph(data: List<Int>): Float? =
        data.getOrNull(0)?.let { (it and 0xFF).toFloat() }

    fun decodeRpm(data: List<Int>): Int? {
        if (data.size < 2) return null
        return (((data[0] and 0xFF) * 256) + (data[1] and 0xFF)) / 4
    }

    fun decodeCoolantCelsius(data: List<Int>): Int? =
        data.getOrNull(0)?.let { (it and 0xFF) - 40 }

    /**
     * PID 0x46, ambient air temperature. Same wire format as [decodeCoolantCelsius] (A - 40), but
     * kept as its own named function: a caller reaching for "the ambient temperature decoder"
     * should not have to know it happens to share coolant's formula, and a method named for one
     * PID must never be the thing another PID's reading quietly flows through. See task-15 review.
     */
    fun decodeAmbientCelsius(data: List<Int>): Int? =
        data.getOrNull(0)?.let { (it and 0xFF) - 40 }

    fun decodeFuelPercent(data: List<Int>): Float? =
        data.getOrNull(0)?.let { (100f / 255f) * (it and 0xFF) }

    fun decodeMonitorStatus(data: List<Int>): MonitorStatus? {
        if (data.size < 4) return null
        val a = data[0] and 0xFF
        return MonitorStatus(milOn = (a and 0x80) != 0, dtcCount = a and 0x7F)
    }

    fun decodeDistanceKm(data: List<Int>): Int? {
        if (data.size < 2) return null
        return ((data[0] and 0xFF) * 256) + (data[1] and 0xFF)
    }

    /**
     * PID 0x1F, run time since engine start, in seconds. Same sixteen-bit wire format as
     * [decodeDistanceKm], but kept as its own named function: a caller reaching for the
     * nearest-fitting existing decoder must not silently get seconds back from a method named
     * "...Km". See task-15 review.
     */
    fun decodeRunTimeSeconds(data: List<Int>): Int? {
        if (data.size < 2) return null
        return ((data[0] and 0xFF) * 256) + (data[1] and 0xFF)
    }

    fun decodeOdometerKm(data: List<Int>): Double? {
        if (data.size < 4) return null
        val raw = ((data[0] and 0xFF).toLong() shl 24) or
            ((data[1] and 0xFF).toLong() shl 16) or
            ((data[2] and 0xFF).toLong() shl 8) or
            (data[3] and 0xFF).toLong()
        return raw / 10.0
    }
}
