package com.csjotlab.cardashboard.nav.data

import com.csjotlab.cardashboard.nav.domain.GeoPoint
import com.csjotlab.cardashboard.nav.geocoding.Place
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Hand-built JSON for recents (no serialization compiler plugin in this project). */
object RecentDestinationsJson {

    fun encode(places: List<Place>): String = buildJsonArray {
        places.forEach { place ->
            add(
                buildJsonObject {
                    put("name", place.name)
                    put("lat", place.point.latitude)
                    put("lon", place.point.longitude)
                    place.address?.let { put("address", it) }
                    place.category?.let { put("category", it) }
                },
            )
        }
    }.toString()

    fun decode(json: String?): List<Place> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            Json.parseToJsonElement(json).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val lat = obj["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lon = obj["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                Place(
                    name = name,
                    point = GeoPoint(lat, lon),
                    address = obj["address"]?.jsonPrimitive?.contentOrNull,
                    category = obj["category"]?.jsonPrimitive?.contentOrNull,
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }
}
