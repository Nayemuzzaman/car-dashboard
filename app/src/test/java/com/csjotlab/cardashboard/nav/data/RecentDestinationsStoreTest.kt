package com.csjotlab.cardashboard.nav.data

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.fakes.InMemoryStringStorage
import com.csjotlab.cardashboard.nav.geocoding.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentDestinationsStoreTest {

    private val airport = Place("Airport", GeoPoint(23.8431, 90.4053), address = "Dhaka, Bangladesh", category = "aerodrome")
    private val station = Place("Station", GeoPoint(23.8523, 90.4082))

    @Test
    fun `json round-trips every place field and tolerates missing input`() {
        val encoded = RecentDestinationsJson.encode(listOf(airport, station))

        assertEquals(listOf(airport, station), RecentDestinationsJson.decode(encoded))
        assertTrue(RecentDestinationsJson.decode(null).isEmpty())
        assertTrue(RecentDestinationsJson.decode("garbage").isEmpty())
    }

    @Test
    fun `recording puts the newest first and dedups by point`() {
        val store = PersistentRecentDestinationsStore(InMemoryStringStorage())

        store.record(airport)
        store.record(station)
        store.record(airport.copy(name = "Airport (renamed)"))

        assertEquals(listOf("Airport (renamed)", "Station"), store.recent().map { it.name })
    }

    @Test
    fun `the list is capped at max items`() {
        val store = PersistentRecentDestinationsStore(InMemoryStringStorage(), maxItems = 2)

        store.record(airport)
        store.record(station)
        store.record(Place("Third", GeoPoint(1.0, 1.0)))

        assertEquals(listOf("Third", "Station"), store.recent().map { it.name })
    }

    @Test
    fun `recents survive a new store instance over the same storage`() {
        val storage = InMemoryStringStorage()
        PersistentRecentDestinationsStore(storage).record(airport)

        val reloaded = PersistentRecentDestinationsStore(storage)

        assertEquals(listOf(airport), reloaded.recent())
    }
}
