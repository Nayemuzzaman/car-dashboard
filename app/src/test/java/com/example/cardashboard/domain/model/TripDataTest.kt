package com.example.cardashboard.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripDataTest {

    @Test
    fun `average speed is distance over driving time`() {
        val trip = TripData(distanceKm = 90.0, drivingTimeMillis = 3_600_000)

        assertEquals(90.0, trip.averageSpeedKmh, 0.001)
    }

    @Test
    fun `average speed is zero before any driving time has accrued`() {
        assertEquals(0.0, TripData(distanceKm = 12.0).averageSpeedKmh, 0.0)
    }

    @Test
    fun `consumption is reported per hundred kilometres`() {
        val trip = TripData(distanceKm = 50.0, energyUsed = 3.6)

        assertEquals(7.2, trip.consumptionPer100Km!!, 0.001)
    }

    @Test
    fun `consumption is withheld until the trip is long enough to mean anything`() {
        val trip = TripData(distanceKm = 0.02, energyUsed = 0.1)

        assertNull(trip.consumptionPer100Km)
    }

    @Test
    fun `consumption is absent when the vehicle does not report energy use`() {
        assertNull(TripData(distanceKm = 120.0, energyUsed = null).consumptionPer100Km)
    }

    @Test
    fun `the empty trip reads as all zeroes`() {
        assertEquals(0.0, TripData.EMPTY.distanceKm, 0.0)
        assertEquals(0L, TripData.EMPTY.drivingTimeMillis)
        assertEquals(0.0, TripData.EMPTY.averageSpeedKmh, 0.0)
    }
}
