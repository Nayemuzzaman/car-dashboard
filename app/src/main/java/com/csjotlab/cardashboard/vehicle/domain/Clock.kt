package com.csjotlab.cardashboard.vehicle.domain

/** Injected so no test depends on wall-clock time. */
fun interface Clock {
    fun nowMs(): Long
}

object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
