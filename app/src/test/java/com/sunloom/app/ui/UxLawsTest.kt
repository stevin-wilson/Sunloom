package com.sunloom.app.ui

import com.sunloom.app.domain.solar.SolarCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UxLawsTest {

    @Test
    fun `blank query keeps every bundled city`() {
        val all = SolarCalculator.BUNDLED_CITIES
        assertEquals(all.size, filterCities("", all).size)
        assertEquals(all.size, filterCities("   ", all).size)
    }

    @Test
    fun `city filter is case insensitive and requires every word`() {
        val all = SolarCalculator.BUNDLED_CITIES
        val tokyo = filterCities("TOKYO", all)
        assertTrue(tokyo.isNotEmpty())
        assertTrue(tokyo.all { it.dropdownLabel.lowercase().contains("tokyo") })
        assertTrue(filterCities("tokyo zzzz-no-such-word", all).isEmpty())
        assertTrue(filterCities("zzzz-no-such-city", all).isEmpty())
    }

    @Test
    fun `coordinate validation explains bad input instead of ignoring it`() {
        assertNull(coordinateError("", -90.0, 90.0))
        assertNull(coordinateError("37.77", -90.0, 90.0))
        assertNull(coordinateError(" -122.4 ", -180.0, 180.0))
        assertNotNull(coordinateError("abc", -90.0, 90.0))
        assertNotNull(coordinateError("95", -90.0, 90.0))
        assertNotNull(coordinateError("-181", -180.0, 180.0))
        assertNotNull(coordinateError("NaN", -90.0, 90.0))
    }
}
