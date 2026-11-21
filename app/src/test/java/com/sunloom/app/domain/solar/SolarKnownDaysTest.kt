package com.sunloom.app.domain.solar

import com.sunloom.app.Requirement
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article III §2: sunrise and sunset worked out on the device have to be the times a person would see. These are
 * well-known published times (the solstices in four hemispheres and climates), checked to within three minutes, which is
 * what the simple formula the app uses can promise. The properties in [SolarCalculatorPropertyTest] check the shape of the
 * answer; these pin its size.
 */
class SolarKnownDaysTest {

    private val calc = SolarCalculator()

    private class Day(val place: String, val lat: Double, val lon: Double, val zone: String, val date: LocalDate, val sunrise: String, val sunset: String)

    private val days = listOf(
        Day("New York", 40.7128, -74.0060, "America/New_York", LocalDate.of(2026, 6, 21), "05:25", "20:31"),
        Day("London", 51.5074, -0.1278, "Europe/London", LocalDate.of(2026, 6, 21), "04:43", "21:21"),
        Day("Sydney", -33.8688, 151.2093, "Australia/Sydney", LocalDate.of(2026, 12, 21), "05:41", "20:05"),
        Day("Tokyo", 35.6762, 139.6503, "Asia/Tokyo", LocalDate.of(2026, 12, 21), "06:47", "16:32"),
        Day("New York in winter", 40.7128, -74.0060, "America/New_York", LocalDate.of(2026, 12, 21), "07:17", "16:32"),
        Day("Sydney in winter", -33.8688, 151.2093, "Australia/Sydney", LocalDate.of(2026, 6, 21), "07:00", "16:54")
    )

    private fun minutes(text: String) = LocalTime.parse(text).let { it.hour * 60 + it.minute }

    @Requirement("III.2")
    @Test
    fun `well-known days give the published sunrise and sunset to within three minutes`() {
        days.forEach { day ->
            val zone = ZoneId.of(day.zone)
            val window = calc.calculateDaylightWindow(day.date, day.lat, day.lon, calc.resolveUtcOffsetHours(day.date, zone))
            val rise = window.sunriseTime.hour * 60 + window.sunriseTime.minute
            val set = window.sunsetTime.hour * 60 + window.sunsetTime.minute
            assertTrue("${day.place} ${day.date}: sunrise ${window.sunriseTime}, published ${day.sunrise}", abs(rise - minutes(day.sunrise)) <= 3)
            assertTrue("${day.place} ${day.date}: sunset ${window.sunsetTime}, published ${day.sunset}", abs(set - minutes(day.sunset)) <= 3)
        }
    }
}
