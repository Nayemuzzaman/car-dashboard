package com.csjotlab.cardashboard.nav.data

import com.csjotlab.cardashboard.nav.geocoding.Place

/** Recently chosen destinations, newest first, with the name the user saw when choosing them. */
interface RecentDestinationsStore {
    fun recent(): List<Place>
    fun record(place: Place)
}

/**
 * Keeps recents in a [StringStorage] slot as JSON. Two records at the same point are one entry —
 * a map tap first lands as a coordinate label and is then renamed by reverse geocoding.
 */
class PersistentRecentDestinationsStore(
    private val storage: StringStorage,
    private val maxItems: Int = 8,
) : RecentDestinationsStore {

    private val items: MutableList<Place> = RecentDestinationsJson.decode(storage.read()).toMutableList()

    override fun recent(): List<Place> = items.toList()

    override fun record(place: Place) {
        items.removeAll { it.point == place.point }
        items.add(0, place)
        while (items.size > maxItems) items.removeAt(items.lastIndex)
        storage.write(RecentDestinationsJson.encode(items))
    }
}
