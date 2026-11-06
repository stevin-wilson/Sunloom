package com.sunloom.app.domain.solar

import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class SolarCalculatorTest {

    private val calculator = SolarCalculator()

    @Test
    fun `calculateDaylightWindow computes realistic sunrise and sunset for San Francisco in November`() {
        val window = calculator.calculateDaylightWindow(
            date = LocalDate.of(2026, 11, 4),
            latitude = 37.7749,
            longitude = -122.4194,
            utcOffsetHours = -8.0
        )

        // In early November in SF (PST), sunrise is ~6:35 AM and sunset is ~5:05 PM
        assertTrue(
            "Sunrise expected between 06:00 and 07:15, got ${window.sunriseTime}",
            window.sunriseTime.isAfter(LocalTime.of(6, 0)) && window.sunriseTime.isBefore(LocalTime.of(7, 15))
        )
        assertTrue(
            "Sunset expected between 16:40 and 17:40, got ${window.sunsetTime}",
            window.sunsetTime.isAfter(LocalTime.of(16, 40)) && window.sunsetTime.isBefore(LocalTime.of(17, 40))
        )
    }

    @Test
    fun `resolveTargetBrightness switches between LIGHT and DARK across SunriseSunset, FixedClock, and FollowOs modes`() {
        val prefsSunrise = UserPreferences(
            scheduleMode = ScheduleMode.SUNRISE_SUNSET,
            latitude = 37.7749,
            longitude = -122.4194
        )
        val date = LocalDate.of(2026, 11, 4)

        assertEquals(
            BrightnessClass.LIGHT,
            calculator.resolveTargetBrightness(prefsSunrise, date, LocalTime.of(12, 0), -8.0, isSystemInDarkTheme = true)
        )
        assertEquals(
            BrightnessClass.DARK,
            calculator.resolveTargetBrightness(prefsSunrise, date, LocalTime.of(21, 30), -8.0, isSystemInDarkTheme = false)
        )

        val prefsFixed = UserPreferences(
            scheduleMode = ScheduleMode.FIXED_CLOCK,
            fixedLightHour = 8,
            fixedDarkHour = 20
        )
        assertEquals(
            BrightnessClass.DARK,
            calculator.resolveTargetBrightness(prefsFixed, date, LocalTime.of(7, 30), -8.0, isSystemInDarkTheme = false)
        )
        assertEquals(
            BrightnessClass.LIGHT,
            calculator.resolveTargetBrightness(prefsFixed, date, LocalTime.of(9, 0), -8.0, isSystemInDarkTheme = true)
        )

        val prefsOs = UserPreferences(scheduleMode = ScheduleMode.FOLLOW_OS_THEME)
        assertEquals(
            BrightnessClass.DARK,
            calculator.resolveTargetBrightness(prefsOs, date, LocalTime.of(12, 0), -8.0, isSystemInDarkTheme = true)
        )
        assertEquals(
            BrightnessClass.LIGHT,
            calculator.resolveTargetBrightness(prefsOs, date, LocalTime.of(23, 0), -8.0, isSystemInDarkTheme = false)
        )
    }

    @Test
    fun `BUNDLED_CITIES contains diverse global coverage sorted by UTC offset and alphabetical city name`() {
        val cities = SolarCalculator.BUNDLED_CITIES
        assertTrue(cities.size >= 30)
        val regions = cities.map { it.region }.toSet()
        assertTrue(regions.contains("Americas"))
        assertTrue(regions.contains("Europe"))
        assertTrue(regions.contains("Asia & Pacific"))
        assertTrue(regions.contains("Africa & Middle East"))

        for (i in 0 until cities.size - 1) {
            val curr = cities[i]
            val next = cities[i + 1]
            assertTrue(
                "Expected ${curr.cityName} (${curr.utcOffsetHours}) <= ${next.cityName} (${next.utcOffsetHours})",
                curr.utcOffsetHours < next.utcOffsetHours ||
                    (curr.utcOffsetHours == next.utcOffsetHours && curr.cityName <= next.cityName)
            )
            assertTrue(curr.dropdownLabel.contains(curr.countryName))
            assertTrue(curr.dropdownLabel.contains(curr.timeZoneCode))
            assertNotNull(ZoneId.of(curr.zoneId))
        }
    }

    @Test
    fun `testDstOffsetUsesZoneIdRulesInSummerAndWinter`() {
        val nyZone = ZoneId.of("America/New_York")
        val winterOffset = calculator.resolveUtcOffsetHours(LocalDate.of(2026, 1, 15), nyZone)
        val summerOffset = calculator.resolveUtcOffsetHours(LocalDate.of(2026, 7, 15), nyZone)

        assertEquals(-5.0, winterOffset, 0.01)
        assertEquals(-4.0, summerOffset, 0.01)
    }

    @Test
    fun `testPolarDayAndPolarNightHandledWithoutSilentLatitudeClamp`() {
        // Tromsø / Svalbard latitude (78.22° N) in June (polar day) and December (polar night)
        val summerWindow = calculator.calculateDaylightWindow(
            date = LocalDate.of(2026, 6, 21),
            latitude = 78.22,
            longitude = 15.63,
            utcOffsetHours = 2.0
        )
        assertTrue(summerWindow.isPolarDay)

        val winterWindow = calculator.calculateDaylightWindow(
            date = LocalDate.of(2026, 12, 21),
            latitude = 78.22,
            longitude = 15.63,
            utcOffsetHours = 1.0
        )
        assertTrue(winterWindow.isPolarNight)
    }

    @Test
    fun `testFixedClockHandlesMidnightWrapAround`() {
        // Night-shift user: Light from 20:00 to 04:00 (wraps past midnight)
        val prefs = UserPreferences(
            scheduleMode = ScheduleMode.FIXED_CLOCK,
            fixedLightHour = 20,
            fixedDarkHour = 4
        )
        val date = LocalDate.of(2026, 11, 4)

        assertEquals(
            BrightnessClass.LIGHT,
            calculator.resolveTargetBrightness(prefs, date, LocalTime.of(22, 30), 0.0, false)
        )
        assertEquals(
            BrightnessClass.LIGHT,
            calculator.resolveTargetBrightness(prefs, date, LocalTime.of(2, 15), 0.0, false)
        )
        assertEquals(
            BrightnessClass.DARK,
            calculator.resolveTargetBrightness(prefs, date, LocalTime.of(12, 0), 0.0, false)
        )
    }

    @Test
    fun `testComputeNextTransitionDelayComputesNextFixedClockTransition`() {
        val zone = ZoneId.of("America/Los_Angeles")
        val prefs = UserPreferences(
            autoChangeEnabled = true,
            scheduleMode = ScheduleMode.FIXED_CLOCK,
            fixedLightHour = 7,
            fixedDarkHour = 19
        )
        val now = ZonedDateTime.of(LocalDate.of(2026, 11, 4), LocalTime.of(4, 0), zone)
        val nextZoned = calculator.computeNextTransitionZonedTime(prefs, now)

        assertNotNull(nextZoned)
        assertEquals(LocalTime.of(7, 0), nextZoned!!.toLocalTime())
    }

    @Test
    fun `resolveSolarSnapshot computes zone daylight window and target brightness in single pass`() {
        val prefs = UserPreferences(
            scheduleMode = ScheduleMode.SUNRISE_SUNSET,
            selectedCityName = "San Francisco, United States",
            latitude = 37.7749,
            longitude = -122.4194
        )
        val sfZone = ZoneId.of("America/Los_Angeles")
        val noonOverride = ZonedDateTime.of(LocalDate.of(2026, 11, 4), LocalTime.of(12, 0), sfZone)

        val noonSnapshot = calculator.resolveSolarSnapshot(
            preferences = prefs,
            isSystemInDarkTheme = true,
            nowZonedOverride = noonOverride
        )
        assertEquals(sfZone, noonSnapshot.nowZoned.zone)
        assertEquals(BrightnessClass.LIGHT, noonSnapshot.targetBrightness)
        assertTrue(noonSnapshot.daylightWindow.sunriseTime.isAfter(LocalTime.of(6, 0)))

        val nightOverride = ZonedDateTime.of(LocalDate.of(2026, 11, 4), LocalTime.of(22, 0), sfZone)
        val nightSnapshot = calculator.resolveSolarSnapshot(
            preferences = prefs,
            isSystemInDarkTheme = false,
            nowZonedOverride = nightOverride
        )
        assertEquals(BrightnessClass.DARK, nightSnapshot.targetBrightness)

        // Also verify default (non-overridden) clock path resolves city ZoneId cleanly
        val liveSnapshot = calculator.resolveSolarSnapshot(
            preferences = prefs,
            isSystemInDarkTheme = false
        )
        assertEquals(sfZone, liveSnapshot.nowZoned.zone)
    }
}
