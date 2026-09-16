package com.csjotlab.cardashboard.ui.navigation

const val RECENT_GLYPH = "🕓"

/** A glyph per OSM feature value (Photon `osm_value`), so search rows read at a glance. */
fun placeGlyph(category: String?): String = when (category) {
    "aerodrome", "airport", "terminal", "helipad" -> "✈"
    "station", "train_station", "halt", "tram_stop", "subway_entrance", "railway_station" -> "🚉"
    "bus_stop", "bus_station", "platform" -> "🚏"
    "restaurant", "cafe", "fast_food", "food_court", "bar", "pub", "bakery" -> "🍴"
    "fuel", "charging_station" -> "⛽"
    "parking", "parking_entrance" -> "🅿"
    "hospital", "clinic", "doctors", "pharmacy", "dentist" -> "🏥"
    "hotel", "motel", "hostel", "guest_house", "apartment" -> "🏨"
    "supermarket", "mall", "marketplace", "convenience", "department_store" -> "🛒"
    "school", "university", "college", "kindergarten" -> "🎓"
    "bank", "atm" -> "🏦"
    "place_of_worship", "mosque", "church", "temple", "synagogue" -> "🛐"
    "city", "town", "village", "suburb", "neighbourhood", "quarter", "locality", "hamlet" -> "🏙"
    "residential", "primary", "secondary", "tertiary", "trunk", "motorway", "living_street",
    "unclassified", "service", "road", "pedestrian", "footway", "path", "motorway_link",
    "trunk_link", "primary_link", "secondary_link", "tertiary_link" -> "🛣"
    else -> "📍"
}
