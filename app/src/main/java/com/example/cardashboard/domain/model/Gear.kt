package com.example.cardashboard.domain.model

/**
 * Selected transmission position.
 *
 * [SPORT] is only reported by vehicles whose transmission exposes a sport position; sources that do
 * not support it simply never emit it.
 */
enum class Gear {
    PARK,
    REVERSE,
    NEUTRAL,
    DRIVE,
    SPORT;

    /** True when the vehicle is able to move forward or backwards in this position. */
    val allowsMotion: Boolean
        get() = this == REVERSE || this == DRIVE || this == SPORT
}
