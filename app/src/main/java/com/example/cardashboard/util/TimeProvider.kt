package com.example.cardashboard.util

import java.time.LocalDateTime
import java.time.ZoneId

/** Reads the wall clock. Abstracted so the clock readout can be tested without waiting for time. */
interface TimeProvider {
    fun now(): LocalDateTime
}

/** Uses the device clock in the device's current time zone. */
object SystemTimeProvider : TimeProvider {
    override fun now(): LocalDateTime = LocalDateTime.now(ZoneId.systemDefault())
}
