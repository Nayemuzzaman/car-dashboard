package com.example.cardashboard.domain.model

/**
 * State of every telltale on the cluster.
 *
 * Everything defaults to `false`: a lamp is only ever lit because a data source reported it, never
 * because the dashboard needed something to show.
 */
data class Indicators(
    val leftTurnSignal: Boolean = false,
    val rightTurnSignal: Boolean = false,
    val hazardLights: Boolean = false,
    val highBeam: Boolean = false,
    val lowBeam: Boolean = false,
    val parkingBrake: Boolean = false,
    val seatBeltUnfastened: Boolean = false,
    val doorOpen: Boolean = false,
    val engineWarning: Boolean = false,
    val lowEnergy: Boolean = false,
    val tirePressureWarning: Boolean = false,
    val temperatureWarning: Boolean = false,
    val absWarning: Boolean = false,
    val tractionControlWarning: Boolean = false
) {
    /** Telltales that mean "something is wrong", as opposed to informational lamps. */
    val activeFaultCount: Int
        get() = listOf(
            seatBeltUnfastened,
            doorOpen,
            engineWarning,
            lowEnergy,
            tirePressureWarning,
            temperatureWarning,
            absWarning,
            tractionControlWarning
        ).count { it }

    companion object {
        /** All telltales off — the correct state when no data has been received yet. */
        val NONE = Indicators()
    }
}
