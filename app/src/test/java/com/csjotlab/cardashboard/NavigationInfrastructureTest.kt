package com.csjotlab.cardashboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the routing-parsing dependency resolves and runs on the JVM before any routing code uses
 * it. MapLibre itself is an Android library and cannot be exercised here; it is verified by
 * `assembleDebug` instead.
 */
class NavigationInfrastructureTest {

    @Test
    fun `kotlinx serialization json is available for routing parsing`() {
        val element = Json.parseToJsonElement("""{"type":"turn-left","distance":300}""")
        val obj = element.jsonObject

        assertEquals("turn-left", obj.getValue("type").jsonPrimitive.content)
        assertEquals(300, obj.getValue("distance").jsonPrimitive.content.toInt())
    }
}
