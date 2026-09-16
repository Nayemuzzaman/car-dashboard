package com.csjotlab.cardashboard.nav.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoPointLabelTest {
    @Test
    fun `label is lat comma lon with four decimals in us locale`() {
        assertEquals("23.8103, 90.4125", GeoPoint(23.81031, 90.41249).toLabel())
        assertEquals("-0.7594, 52.0000", GeoPoint(-0.7594, 52.0).toLabel())
    }
}
