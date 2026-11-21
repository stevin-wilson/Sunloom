package com.sunloom.app.domain.solar

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the next sunrise or sunset change fires, and which time zone a chosen city means (Article III §§1-2).
 * The astronomy itself is checked in [SolarCalculatorTest]; these tests check the scheduling around it: which
 * transition is "next", what happens at the edges (the 30-second guard, midnight sun, the day the clocks change)
 * and that a city name maps to its zone.
 */
@Requirement("III.1", "III.2")
class SolarCalculatorScheduleTest {

    private val calculator = SolarCalculator()
    private val sanFrancisco = ZoneId.of("America/Los_Angeles")
    private val svalbard = ZoneId.of("Arctic/Longyearbyen")

    private val sunriseSunset = UserPreferences(
        autoChangeEnabled = true,
        isPaused = false,
        scheduleMode = ScheduleMode.SUNRISE_SUNSET,
        latitude = 37.7749,
        longitude = -122.4194
    )

    private fun sf(date: LocalDate, hour: Int, minute: Int = 0): ZonedDateTime = date.atTime(hour, minute).atZone(sanFrancisco)

    private fun sfWindow(date: LocalDate) = calculator.calculateDaylightWindow(
        date = date,
        latitude = sunriseSunset.latitude,
        longitude = sunriseSunset.longitude,
        utcOffsetHours = calculator.resolveUtcOffsetHours(date, sanFrancisco)
    )

    private val november4 = LocalDate.of(2026, 11, 4)

    // --- The next transition in sunrise-and-sunset mode ---

    @Requirement("III.1.a")
    @Test
    fun `at midday the next change is that day's sunset`() {
        val next = calculator.computeNextTransitionZonedTime(sunriseSunset, sf(november4, 12))
        assertEquals(november4.atTime(sfWindow(november4).sunsetTime).atZone(sanFrancisco), next)
    }

    @Requirement("III.1.a")
    @Test
    fun `after sunset the next change is tomorrow's sunrise`() {
        val next = calculator.computeNextTransitionZonedTime(sunriseSunset, sf(november4, 21))
        val tomorrow = november4.plusDays(1)
        assertEquals(tomorrow.atTime(sfWindow(tomorrow).sunriseTime).atZone(sanFrancisco), next)
    }

    @Requirement("III.1.a")
    @Test
    fun `before sunrise the next change is that morning's sunrise`() {
        val next = calculator.computeNextTransitionZonedTime(sunriseSunset, sf(november4, 3))
        assertEquals(november4.atTime(sfWindow(november4).sunriseTime).atZone(sanFrancisco), next)
    }

    @Requirement("III.3")
    @Test
    fun `a transition just ahead is still the next one, so a re-arm just before it cannot lose it`() {
        val sunset = november4.atTime(sfWindow(november4).sunsetTime).atZone(sanFrancisco)
        val tomorrow = november4.plusDays(1)
        val tomorrowsSunrise = tomorrow.atTime(sfWindow(tomorrow).sunriseTime).atZone(sanFrancisco)

        // However close it is, a transition ahead is the next change: it fires a minute or so later, after it has passed.
        listOf(1L, 20L, 30L, 31L, 600L).forEach { secondsBefore ->
            assertEquals("$secondsBefore s before", sunset, calculator.computeNextTransitionZonedTime(sunriseSunset, sunset.minusSeconds(secondsBefore)))
        }
        assertTrue("and the wait is never under a minute", calculator.computeNextTransitionDelayMs(sunriseSunset, sunset.minusSeconds(10))!! >= 60_000L)
        // Right at the transition: never returns the moment that is already here.
        assertEquals(tomorrowsSunrise, calculator.computeNextTransitionZonedTime(sunriseSunset, sunset))
        assertEquals(tomorrowsSunrise, calculator.computeNextTransitionZonedTime(sunriseSunset, sunset.plusSeconds(1)))
    }

    @Test
    fun `whatever the time of day the next change is later than now and within a day`() {
        var now = sf(november4, 0)
        val end = now.plusDays(1)
        while (now.isBefore(end)) {
            val next = calculator.computeNextTransitionZonedTime(sunriseSunset, now)
            assertNotNull("no next change at $now", next)
            assertTrue("$next is not after $now plus the guard", next!!.isAfter(now.plusSeconds(30)))
            assertTrue("$next is more than a day after $now", next.isBefore(now.plusHours(24)))
            now = now.plusMinutes(37)
        }
    }

    @Test
    fun `the day the clocks go forward sunrise is worked out with the summer offset`() {
        // Clocks go forward on 2026-03-08 in California. San Francisco sunrise is about 06:27 on standard time and
        // 07:27 on daylight time; ignoring the change would put the wallpaper change an hour early.
        val next = calculator.computeNextTransitionZonedTime(sunriseSunset, sf(LocalDate.of(2026, 3, 7), 21))
        assertNotNull(next)
        assertEquals(LocalDate.of(2026, 3, 8), next!!.toLocalDate())
        val time = next.toLocalTime()
        assertTrue("sunrise $time looks like standard time, not the summer offset", time.isAfter(LocalTime.of(7, 0)) && time.isBefore(LocalTime.of(7, 50)))
    }

    // --- Midnight sun and polar night ---

    private val svalbardPrefs = sunriseSunset.copy(latitude = 78.22, longitude = 15.63)

    @Test
    fun `during the midnight sun the schedule rechecks at local midnight`() {
        val date = LocalDate.of(2026, 6, 21)
        assertTrue("precondition: polar day", calculator.calculateDaylightWindow(date, 78.22, 15.63, 2.0).isPolarDay)
        val now = date.atTime(12, 0).atZone(svalbard)
        assertEquals(date.plusDays(1).atStartOfDay(svalbard), calculator.computeNextTransitionZonedTime(svalbardPrefs, now))
    }

    @Test
    fun `during the polar night the schedule rechecks at local midnight`() {
        val date = LocalDate.of(2026, 12, 21)
        assertTrue("precondition: polar night", calculator.calculateDaylightWindow(date, 78.22, 15.63, 1.0).isPolarNight)
        val now = date.atTime(12, 0).atZone(svalbard)
        assertEquals(date.plusDays(1).atStartOfDay(svalbard), calculator.computeNextTransitionZonedTime(svalbardPrefs, now))
    }

    @Test
    fun `the target look is light all day in the midnight sun and dark all day in the polar night`() {
        val summer = LocalDate.of(2026, 6, 21)
        val winter = LocalDate.of(2026, 12, 21)
        listOf(LocalTime.of(0, 30), LocalTime.of(12, 0), LocalTime.of(23, 30)).forEach { time ->
            assertEquals(BrightnessClass.LIGHT, calculator.resolveTargetBrightness(svalbardPrefs, summer, time, 2.0, isSystemInDarkTheme = true))
            assertEquals(BrightnessClass.DARK, calculator.resolveTargetBrightness(svalbardPrefs, winter, time, 1.0, isSystemInDarkTheme = false))
        }
    }

    // --- Modes with no schedule of their own ---

    @Requirement("III.1.a")
    @Test
    fun `there is no next change when automation is off, paused, or the look follows the system`() {
        val now = sf(november4, 12)
        assertNull(calculator.computeNextTransitionZonedTime(sunriseSunset.copy(autoChangeEnabled = false), now))
        assertNull(calculator.computeNextTransitionZonedTime(sunriseSunset.copy(isPaused = true), now))
        assertNull(calculator.computeNextTransitionZonedTime(sunriseSunset.copy(scheduleMode = ScheduleMode.FOLLOW_OS_THEME), now))
        assertNull(calculator.computeNextTransitionZonedTime(sunriseSunset.copy(scheduleMode = ScheduleMode.MANUAL_ONLY), now))
    }

    @Requirement("III.1.a")
    @Test
    fun `following the system or manual mode takes the look from the system theme`() {
        listOf(ScheduleMode.FOLLOW_OS_THEME, ScheduleMode.MANUAL_ONLY).forEach { mode ->
            val prefs = sunriseSunset.copy(scheduleMode = mode)
            assertEquals(BrightnessClass.DARK, calculator.resolveTargetBrightness(prefs, november4, LocalTime.NOON, -8.0, isSystemInDarkTheme = true))
            assertEquals(BrightnessClass.LIGHT, calculator.resolveTargetBrightness(prefs, november4, LocalTime.MIDNIGHT, -8.0, isSystemInDarkTheme = false))
        }
    }

    // --- Which time zone a chosen city means ---

    private val utc = ZoneId.of("UTC")

    @Test
    fun `a chosen city maps to its own time zone, whatever the case`() {
        assertEquals(ZoneId.of("Asia/Tokyo"), calculator.resolveZoneId(sunriseSunset.copy(selectedCityName = "Tokyo, Japan"), utc))
        assertEquals(ZoneId.of("Asia/Tokyo"), calculator.resolveZoneId(sunriseSunset.copy(selectedCityName = "tokyo, japan"), utc))
    }

    @Test
    fun `a city that is not bundled, or no name at all, falls back to the given zone`() {
        assertEquals(utc, calculator.resolveZoneId(sunriseSunset.copy(selectedCityName = "Atlantis, Nowhere"), utc))
        assertEquals(utc, calculator.resolveZoneId(sunriseSunset.copy(selectedCityName = ""), utc))
        assertEquals(sanFrancisco, calculator.resolveZoneId(sunriseSunset.copy(selectedCityName = "Atlantis"), sanFrancisco))
    }

    // --- A window that starts and ends at the same moment ---

    @Requirement("III.1.a")
    @Test
    fun `a window with equal start and end covers the whole day`() {
        val noon = LocalTime.NOON
        listOf(LocalTime.MIDNIGHT, LocalTime.of(6, 0), noon, LocalTime.of(23, 59)).forEach {
            assertTrue("$it should be inside a window of zero length", calculator.isTimeWithinWindow(it, noon, noon))
        }
    }

    @Requirement("III.1.a")
    @Test
    fun `a window includes its start, excludes its end, and wraps past midnight`() {
        val seven = LocalTime.of(7, 0)
        val nineteen = LocalTime.of(19, 0)
        assertTrue(calculator.isTimeWithinWindow(seven, seven, nineteen))
        assertFalse(calculator.isTimeWithinWindow(nineteen, seven, nineteen))
        // Overnight: 19:00 to 07:00.
        assertTrue(calculator.isTimeWithinWindow(LocalTime.of(23, 0), nineteen, seven))
        assertTrue(calculator.isTimeWithinWindow(LocalTime.of(3, 0), nineteen, seven))
        assertFalse(calculator.isTimeWithinWindow(LocalTime.NOON, nineteen, seven))
    }
}
