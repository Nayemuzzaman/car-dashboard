package com.example.cardashboard.domain.model

/**
 * Stored energy available to the powertrain.
 *
 * Fuel and electric vehicles share the same shape (a level, a range and a low threshold) so the
 * dashboard can render either one without duplicating the whole screen. Only the parts that are
 * genuinely different — charging and regenerative braking — live on [Battery].
 */
sealed interface EnergyState {

    /** Remaining energy as a percentage, always within `0f..100f`. */
    val levelPercent: Float

    /** Estimated remaining range in kilometres, or `null` when the vehicle does not report it. */
    val estimatedRangeKm: Float?

    /** Percentage at or below which the low-energy telltale must light up. */
    val lowLevelThresholdPercent: Float

    /** Powertrain family this state belongs to. */
    val vehicleType: VehicleType

    /** True when the driver has to be warned about the remaining energy. */
    val isLow: Boolean
        get() = levelPercent <= lowLevelThresholdPercent

    /** Liquid fuel tank of a combustion vehicle. */
    data class Fuel(
        override val levelPercent: Float,
        override val estimatedRangeKm: Float?,
        override val lowLevelThresholdPercent: Float = DEFAULT_LOW_FUEL_PERCENT
    ) : EnergyState {
        override val vehicleType: VehicleType get() = VehicleType.COMBUSTION

        companion object {
            const val DEFAULT_LOW_FUEL_PERCENT = 12f
        }
    }

    /** Traction battery of an electric vehicle. */
    data class Battery(
        override val levelPercent: Float,
        override val estimatedRangeKm: Float?,
        override val lowLevelThresholdPercent: Float = DEFAULT_LOW_CHARGE_PERCENT,
        val chargingState: ChargingState = ChargingState.NOT_CHARGING,
        /** True while the powertrain is recuperating energy. */
        val regenerativeBrakingActive: Boolean = false
    ) : EnergyState {
        override val vehicleType: VehicleType get() = VehicleType.ELECTRIC

        val isCharging: Boolean
            get() = chargingState == ChargingState.CHARGING

        companion object {
            const val DEFAULT_LOW_CHARGE_PERCENT = 15f
        }
    }
}

/** Charging status of a traction battery. */
enum class ChargingState {
    NOT_CHARGING,
    PLUGGED_IN_IDLE,
    CHARGING,
    CHARGE_COMPLETE
}
