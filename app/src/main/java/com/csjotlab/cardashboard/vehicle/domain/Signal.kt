package com.csjotlab.cardashboard.vehicle.domain

/**
 * A single vehicle measurement, or the reason there isn't one.
 *
 * There is deliberately no numeric default anywhere in the vehicle domain: a field can only hold a
 * number because a source actually reported one. See spec section 2.1.
 */
sealed interface Signal<out T> {

    /** A reading that genuinely came from the active source. */
    data class Value<T>(val value: T, val timestampMs: Long) : Signal<T>

    /** The source may be able to provide this, but no reading has arrived yet. */
    data object Unknown : Signal<Nothing>

    /**
     * The *currently selected source* has determined it cannot provide this field.
     * This is not a claim about the vehicle: a future OEM/enhanced source may supply it.
     */
    data object Unsupported : Signal<Nothing>
}

fun <T> Signal<T>.valueOrNull(): T? = when (this) {
    is Signal.Value -> value
    Signal.Unknown, Signal.Unsupported -> null
}

val Signal<*>.isValue: Boolean get() = this is Signal.Value
