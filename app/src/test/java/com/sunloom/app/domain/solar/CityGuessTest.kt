package com.sunloom.app.domain.solar

import com.sunloom.app.Requirement
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Article III / XII §1: sunrise and sunset need a place, and Sunloom never asks for the location. The device's time zone is a
 * good first guess at the city, so setup starts there instead of at San Francisco for everyone.
 */
@Requirement("XII.1")
class CityGuessTest {
    private val summer = ZonedDateTime.of(2026, 7, 1, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant()
    private fun guess(zone: String) = CityGuess.forZone(ZoneId.of(zone), summer)

    @Test
    fun `a zone that a bundled city lives in gives that city`() {
        assertEquals("Los Angeles", guess("America/Los_Angeles")?.cityName)
        assertEquals("London", guess("Europe/London")?.cityName)
        assertEquals("Tokyo", guess("Asia/Tokyo")?.cityName)
        assertEquals("Auckland", guess("Pacific/Auckland")?.cityName)
        assertEquals("Berlin", guess("Europe/Berlin")?.cityName)
    }

    @Test
    fun `India gets its capital rather than the first city listed in the zone`() {
        assertEquals("New Delhi", guess("Asia/Kolkata")?.cityName)
    }

    @Test
    fun `a zone with no city of its own gets a city on the same clock`() {
        val oslo = guess("Europe/Oslo")!! // UTC+1 in winter, like Berlin, Paris and Rome
        assertEquals(1.0, oslo.utcOffsetHours, 0.0)
    }

    @Test
    fun `a zone between two clocks gets the nearest`() {
        val kathmandu = guess("Asia/Kathmandu")!! // UTC+5:45, nearest listed is India's +5:30
        assertEquals(5.5, kathmandu.utcOffsetHours, 0.0)
    }

    @Test
    fun `the plain UTC zone still gets a city`() {
        assertNotNull(guess("UTC"))
        assertEquals(0.0, guess("UTC")!!.utcOffsetHours, 0.0)
    }

    @Test
    fun `the guess uses the standard clock, so summer time does not move it`() {
        val winter = ZonedDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant()
        assertEquals(CityGuess.forZone(ZoneId.of("Europe/Oslo"), summer), CityGuess.forZone(ZoneId.of("Europe/Oslo"), winter))
    }

    @Test
    fun `nothing is guessed from nothing`() {
        assertNull(CityGuess.forZoneId("Not/AZone", summer))
        assertNull(CityGuess.forZoneId("", summer))
    }
}
