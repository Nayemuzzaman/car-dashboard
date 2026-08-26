package com.csjotlab.cardashboard.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticDetailRouteTest {

    @Test
    fun `the pattern declares the argument it reads back`() {
        assertEquals("issueId", DiagnosticDetailRoute.ARG)
        assertEquals("diagnostics/{issueId}", DiagnosticDetailRoute.route)
    }

    @Test
    fun `an issue id is escaped into a single path segment`() {
        assertEquals("diagnostics/dtc%3AP0301", DiagnosticDetailRoute.of("dtc:P0301"))
    }

    /**
     * A separator that survives into the route would split the path and stop the destination
     * matching at all, so the id has to be escaped rather than interpolated.
     */
    @Test
    fun `a separator inside an id never reaches the route as a separator`() {
        val encoded = DiagnosticDetailRoute.of("signal:Door/RearLeft")
        assertEquals("diagnostics/signal%3ADoor%2FRearLeft", encoded)
        assertEquals(0, encoded.removePrefix("diagnostics/").count { it == '/' })
    }

    /**
     * Navigation unescapes the captured path argument with `Uri.decode`, which treats `+` as a
     * literal plus rather than as a space. `URLEncoder` alone emits `+` for a space, so a space in
     * an id would come back as a plus. Escaping has to be `%20`.
     */
    @Test
    fun `a space is escaped as percent-twenty and never as a plus`() {
        val encoded = DiagnosticDetailRoute.of("signal:Rear left")
        assertEquals("diagnostics/signal%3ARear%20left", encoded)
        assertFalse(encoded.contains('+'))
    }
}
