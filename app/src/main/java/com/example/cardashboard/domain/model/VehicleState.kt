package com.example.cardashboard.domain.model

/**
 * Complete snapshot of everything the dashboard draws, in metric units.
 *
 * Instances coming from a data source are not trusted: call [sanitized] before handing a snapshot
 * to the UI. The repository does this in one place so no consumer has to repeat it.
 */
data class VehicleState(
    val speedKmh: Float = 0f,
    val rpm: Int = 0,
    val gear: Gear = Gear.PARK,
    val driveMode: DriveMode = DriveMode.NORMAL,
    val odometerKm: Double = 0.0,
    val trip: TripData = TripData.EMPTY,
    val energy: EnergyState = EnergyState.Fuel(levelPercent = 0f, estimatedRangeKm = null),
    val temperatures: Temperatures = Temperatures.UNKNOWN,
    val indicators: Indicators = Indicators.NONE
) {
    val vehicleType: VehicleType get() = energy.vehicleType

    companion object {
        const val MAX_SPEED_KMH = 320f
        const val MAX_RPM = 9_000
    }
}

/**
 * Clamps every value into a range the cluster can actually display and derives the telltales that
 * follow from other readings (low energy, overheating).
 *
 * Values that are impossible rather than merely extreme — a NaN speed, a temperature of 900 °C —
 * are replaced by a safe substitute or by `null`, so a misbehaving source degrades the display
 * instead of crashing it.
 */
fun VehicleState.sanitized(): VehicleState {
    val safeSpeed = speedKmh.orZeroIfNotFinite().coerceIn(0f, VehicleState.MAX_SPEED_KMH)
    val safeRpm = rpm.coerceIn(0, VehicleState.MAX_RPM)
    val safeOdometer = odometerKm.orZeroIfNotFinite().coerceAtLeast(0.0)
    val safeEnergy = energy.sanitized()
    val safeTemperatures = temperatures.sanitized()
    val safeTrip = trip.sanitized()

    val derivedIndicators = indicators.copy(
        lowEnergy = indicators.lowEnergy || safeEnergy.isLow,
        temperatureWarning = indicators.temperatureWarning ||
            (safeTemperatures.powertrainCelsius ?: 0f) >= Temperatures.POWERTRAIN_WARNING_CELSIUS
    )

    return copy(
        speedKmh = safeSpeed,
        rpm = safeRpm,
        odometerKm = safeOdometer,
        trip = safeTrip,
        energy = safeEnergy,
        temperatures = safeTemperatures,
        indicators = derivedIndicators
    )
}

private fun EnergyState.sanitized(): EnergyState {
    val safeLevel = levelPercent.orZeroIfNotFinite().coerceIn(0f, 100f)
    val safeRange = estimatedRangeKm?.takeIf { it.isFinite() && it >= 0f }
    return when (this) {
        is EnergyState.Fuel -> copy(levelPercent = safeLevel, estimatedRangeKm = safeRange)
        is EnergyState.Battery -> copy(levelPercent = safeLevel, estimatedRangeKm = safeRange)
    }
}

private fun Temperatures.sanitized(): Temperatures = Temperatures(
    outsideCelsius = outsideCelsius?.takeIf { it.isPlausibleTemperature() },
    powertrainCelsius = powertrainCelsius?.takeIf { it.isPlausibleTemperature() }
)

private fun TripData.sanitized(): TripData = copy(
    distanceKm = distanceKm.orZeroIfNotFinite().coerceAtLeast(0.0),
    drivingTimeMillis = drivingTimeMillis.coerceAtLeast(0L),
    energyUsed = energyUsed?.takeIf { it.isFinite() && it >= 0.0 }
)

private fun Float.isPlausibleTemperature(): Boolean =
    isFinite() && this in Temperatures.PLAUSIBLE_RANGE_CELSIUS

private fun Float.orZeroIfNotFinite(): Float = if (isFinite()) this else 0f

private fun Double.orZeroIfNotFinite(): Double = if (isFinite()) this else 0.0
