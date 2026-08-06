package com.example.cardashboard.domain.model

/**
 * Resettable trip computer values.
 *
 * The trip is independent of the odometer: resetting it never touches total distance.
 */
data class TripData(
    val distanceKm: Double = 0.0,
    /** Wall-clock time spent with the vehicle able to move, in milliseconds. */
    val drivingTimeMillis: Long = 0L,
    /**
     * Energy used since the last reset — litres on a combustion vehicle, kWh on an electric one.
     * `null` when the vehicle does not report consumption.
     */
    val energyUsed: Double? = null
) {
    /** Distance divided by driving time, in km/h. Zero until the trip has run for a moment. */
    val averageSpeedKmh: Double
        get() = if (drivingTimeMillis <= 0L) 0.0 else distanceKm / (drivingTimeMillis / 3_600_000.0)

    /**
     * Consumption per 100 km (L/100km or kWh/100km), or `null` when it cannot be computed yet.
     * Requires a meaningful distance so the value does not swing wildly at the start of a trip.
     */
    val consumptionPer100Km: Double?
        get() {
            val used = energyUsed ?: return null
            if (distanceKm < MIN_DISTANCE_FOR_CONSUMPTION_KM) return null
            return used / distanceKm * 100.0
        }

    companion object {
        val EMPTY = TripData()
        const val MIN_DISTANCE_FOR_CONSUMPTION_KM = 0.1
    }
}
