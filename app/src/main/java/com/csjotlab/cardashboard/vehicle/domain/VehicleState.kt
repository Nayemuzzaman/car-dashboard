package com.csjotlab.cardashboard.vehicle.domain

/**
 * A complete snapshot of what the active source currently knows about the vehicle.
 *
 * Every field is a [Signal]; none has a numeric default. Construct via [unavailable] and `copy`
 * the fields a source genuinely reported.
 */
data class VehicleState(
    val speedKph: Signal<Float>,
    val engineRpm: Signal<Int>,
    val fuelLevelPercent: Signal<Float>,
    val coolantTemperatureCelsius: Signal<Int>,
    val gear: Signal<Gear>,
    val odometerKm: Signal<Double>,
    val tripDistanceKm: Signal<Double>,
    val estimatedRangeKm: Signal<Double>,
    val seatbelts: Signal<Map<SeatPosition, SeatbeltState>>,
    val doors: Signal<Map<DoorPosition, DoorState>>,
    val tirePressuresKpa: Signal<Map<TirePosition, Float>>,
    val malfunctionIndicatorLampOn: Signal<Boolean>,
    val source: VehicleSourceId,
    val lastUpdatedMs: Long?,
) {
    companion object {
        /** Nothing is known yet. Used on startup, on disconnect, and on discovery failure. */
        fun unavailable(source: VehicleSourceId): VehicleState = VehicleState(
            speedKph = Signal.Unknown,
            engineRpm = Signal.Unknown,
            fuelLevelPercent = Signal.Unknown,
            coolantTemperatureCelsius = Signal.Unknown,
            gear = Signal.Unknown,
            odometerKm = Signal.Unknown,
            tripDistanceKm = Signal.Unknown,
            estimatedRangeKm = Signal.Unknown,
            seatbelts = Signal.Unknown,
            doors = Signal.Unknown,
            tirePressuresKpa = Signal.Unknown,
            malfunctionIndicatorLampOn = Signal.Unknown,
            source = source,
            lastUpdatedMs = null,
        )
    }
}

/** Every telemetry signal, for invariant checks such as "no value survived a disconnect". */
val VehicleState.allSignals: List<Signal<*>>
    get() = listOf(
        speedKph, engineRpm, fuelLevelPercent, coolantTemperatureCelsius,
        gear, odometerKm, tripDistanceKm, estimatedRangeKm,
        seatbelts, doors, tirePressuresKpa, malfunctionIndicatorLampOn,
    )
