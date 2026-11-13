package com.sunloom.app.data.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CleanedStringsTest {

    @Test
    fun `cleanedOrNull trims a real value`() {
        assertEquals("oil", "  oil \n".cleanedOrNull())
    }

    @Test
    fun `cleanedOrNull drops blank and the literal null string`() {
        assertNull("".cleanedOrNull())
        assertNull("   ".cleanedOrNull())
        assertNull(" null ".cleanedOrNull())
    }

    @Test
    fun `cleanedOrNull keeps text that merely contains null`() {
        assertEquals("nullified", "nullified".cleanedOrNull())
        assertEquals("NULL", "NULL".cleanedOrNull())
    }

    @Test
    fun `cleanStrings keeps order and drops blank, null and JSON null entries`() {
        val arr = SimpleJson.JsonArr(
            listOf(" sky ", "", "null", org.json.JSONObject.NULL, "sea", "   ")
        )
        assertEquals(listOf("sky", "sea"), arr.cleanStrings())
    }

    @Test
    fun `containsAnyOf is true when any term occurs and false when none does`() {
        assertEquals(true, "creative commons by-nc 4.0".containsAnyOf("by-nd", "by-nc"))
        assertEquals(false, "creative commons by 4.0".containsAnyOf("by-nd", "by-nc"))
        assertEquals(false, "anything".containsAnyOf())
    }

    @Test
    fun `containsAnyOf is case sensitive like contains`() {
        assertEquals(false, "Getty Images".containsAnyOf("getty"))
    }
}
