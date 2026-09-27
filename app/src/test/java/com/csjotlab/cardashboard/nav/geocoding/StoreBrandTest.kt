package com.csjotlab.cardashboard.nav.geocoding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoreBrandTest {

    @Test
    fun `chains are recognised from english and japanese brand tags`() {
        assertEquals(StoreBrand.FamilyMart, StoreBrand.match("FamilyMart", null))
        assertEquals(StoreBrand.FamilyMart, StoreBrand.match("ファミリーマート", null))
        assertEquals(StoreBrand.Lawson, StoreBrand.match("ローソン", null))
        assertEquals(StoreBrand.SevenEleven, StoreBrand.match("7-Eleven", null))
        assertEquals(StoreBrand.SevenEleven, StoreBrand.match("セブン-イレブン", null))
        assertEquals(StoreBrand.Ministop, StoreBrand.match("MINISTOP", null))
        assertEquals(StoreBrand.DailyYamazaki, StoreBrand.match("デイリーヤマザキ", null))
    }

    @Test
    fun `without a brand tag the place name decides, ignoring case, spacing and branch suffixes`() {
        assertEquals(StoreBrand.Lawson, StoreBrand.match(null, "ローソン 八幡本城店"))
        assertEquals(StoreBrand.SevenEleven, StoreBrand.match(null, "seven eleven Kurosaki"))
        assertEquals(StoreBrand.FamilyMart, StoreBrand.match("", "Family Mart Orio"))
    }

    @Test
    fun `the brand tag wins over the name and unknown stores get no badge`() {
        assertEquals(StoreBrand.Lawson, StoreBrand.match("Lawson", "FamilyMart opposite"))
        assertNull(StoreBrand.match(null, "Yamada Liquor"))
        assertNull(StoreBrand.match(null, null))
        assertNull(StoreBrand.match("   ", "---"))
    }
}
