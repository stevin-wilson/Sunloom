package com.sunloom.app.domain.solar

import com.sunloom.app.Property
import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article III §§1 to 3: sunrise and sunset worked out on the device, and the next change scheduled from them.
 * The facts used here come from astronomy, not from the code: the day is about twelve hours long at the equator, the
 * middle of the day is solar noon (within the equation of time, at most a quarter of an hour), the two hemispheres are
 * mirror images, days lengthen towards the pole in summer and shorten in winter, polar day and night start poleward of
 * the polar circles. Then the schedule has to agree with the brightness it is scheduling for.
 */
@Requirement("III.1", "III.2", "III.3")
class SolarCalculatorPropertyTest {

    private val calc = SolarCalculator()

    private fun minutes(t: LocalTime) = t.hour * 60 + t.minute

    /** Length of the day in minutes, and the sun's window, for [lat]/[lon] on [date] in local mean solar time (no clock offset to confuse it). */
    private fun window(date: LocalDate, lat: Double, lon: Double): DaylightWindow =
        calc.calculateDaylightWindow(date, lat, lon, utcOffsetHours = lon / 15.0)

    private fun dayLength(w: DaylightWindow): Int = minutes(w.sunsetTime) - minutes(w.sunriseTime)

    private fun Property.Case.date(): LocalDate = LocalDate.ofYearDay(2026, int(1, 365))

    // --- Where the sun is up, and for how long ---

    @Requirement("III.2")
    @Test
    fun `on the equator the day is about twelve hours long all year`() {
        Property.forAll("equator day length") { case ->
            val date = case.date()
            val lon = case.double(-180.0, 180.0)
            case.input = "$date lon=$lon"
            val length = dayLength(window(date, 0.0, lon))
            // 12 h plus the few minutes the atmosphere bends the sun above the horizon.
            assertTrue("day length $length min", length in 705..745)
        }
    }

    @Requirement("III.2")
    @Test
    fun `solar noon falls near twelve o'clock, off by no more than the equation of time`() {
        Property.forAll("middle of the day is noon") { case ->
            val date = case.date()
            val lat = case.double(-60.0, 60.0)
            val lon = case.double(-180.0, 180.0)
            case.input = "$date lat=$lat lon=$lon"
            val w = window(date, lat, lon)
            val middle = (minutes(w.sunriseTime) + minutes(w.sunsetTime)) / 2.0
            // The equation of time never exceeds about 16.5 minutes; rounding each time to the minute adds one.
            assertTrue("the middle of the day is at $middle min", abs(middle - 720.0) <= 18.0)
        }
    }

    @Requirement("III.2")
    @Test
    fun `the two hemispheres are mirror images, so their days add up to a full day`() {
        Property.forAll("hemisphere symmetry") { case ->
            val date = case.date()
            val lat = case.double(0.0, 55.0)
            val lon = case.double(-180.0, 180.0)
            case.input = "$date lat=$lat lon=$lon"
            val sum = dayLength(window(date, lat, lon)) + dayLength(window(date, -lat, lon))
            // The air bends the light so both days are a little longer than a true mirror image: by about twice 17 minutes in
            // the short, low-sun days at 55 degrees. Allow 45 minutes in all, which still catches a hemisphere the wrong way round.
            assertTrue("the two days add up to $sum min, not 1440", sum in 1440..1485)
        }
    }

    @Requirement("III.2")
    @Test
    fun `in summer the day lengthens towards the pole and in winter it shortens`() {
        Property.forAll("day length against latitude") { case ->
            val a = case.double(0.0, 60.0)
            val b = case.double(a, 60.0)
            val lon = case.double(-180.0, 180.0)
            val summer = LocalDate.ofYearDay(2026, case.int(100, 250)) // the northern sun is north of the equator from March to September
            val winter = LocalDate.ofYearDay(2026, if (case.boolean()) case.int(1, 55) else case.int(300, 365))
            case.input = "lat $a and $b, lon=$lon, summer $summer, winter $winter"
            val slack = 2 // each time is rounded to the minute
            assertTrue("summer: ${dayLength(window(summer, b, lon))} at $b should be at least ${dayLength(window(summer, a, lon))} at $a",
                dayLength(window(summer, b, lon)) >= dayLength(window(summer, a, lon)) - slack)
            assertTrue("winter: ${dayLength(window(winter, b, lon))} at $b should be at most ${dayLength(window(winter, a, lon))} at $a",
                dayLength(window(winter, b, lon)) <= dayLength(window(winter, a, lon)) + slack)
        }
    }

    @Requirement("III.2")
    @Test
    fun `from the winter solstice to the summer solstice the days only get longer, and then shorter`() {
        Property.forAll("day length against date") { case ->
            val lat = case.double(5.0, 60.0)
            val lon = case.double(-180.0, 180.0)
            val growing = case.int(1, 169)
            val shrinking = case.int(176, 349)
            case.input = "lat=$lat lon=$lon, day $growing and day $shrinking"
            val slack = 2
            val today = dayLength(window(LocalDate.ofYearDay(2026, growing), lat, lon))
            val tomorrow = dayLength(window(LocalDate.ofYearDay(2026, growing + 1), lat, lon))
            assertTrue("day $growing: $today then $tomorrow min", tomorrow >= today - slack)
            val later = dayLength(window(LocalDate.ofYearDay(2026, shrinking), lat, lon))
            val after = dayLength(window(LocalDate.ofYearDay(2026, shrinking + 1), lat, lon))
            assertTrue("day $shrinking: $later then $after min", after <= later + slack)
            // And a day is never a big jump from the one before: at most a few minutes anywhere south of the polar circle.
            assertTrue("a jump of ${abs(tomorrow - today)} min in one day", abs(tomorrow - today) <= 6)
        }
    }

    // --- The midnight sun and the polar night ---

    @Requirement("III.2")
    @Test
    fun `there is no polar day or night within 60 degrees of the equator, on any date`() {
        Property.forAll("no polar day or night in the tropics and mid-latitudes") { case ->
            val date = case.date()
            val lat = case.double(-60.0, 60.0)
            case.input = "$date lat=$lat"
            val w = calc.calculateDaylightWindow(date, lat, case.double(-180.0, 180.0), 0.0)
            assertTrue("polar flags at $lat", !w.isPolarDay && !w.isPolarNight)
        }
    }

    @Requirement("III.2")
    @Test
    fun `at and beyond 70 degrees the sun does not set at midsummer or rise at midwinter`() {
        Property.forAll("polar day in summer and polar night in winter") { case ->
            val north = case.boolean()
            val lat = case.double(70.0, 89.9) * if (north) 1 else -1
            val june = LocalDate.of(2026, 6, case.int(15, 27))
            val december = LocalDate.of(2026, 12, case.int(15, 27))
            case.input = "lat=$lat june=$june december=$december"
            val inJune = calc.calculateDaylightWindow(june, lat, 10.0, 0.0)
            val inDecember = calc.calculateDaylightWindow(december, lat, 10.0, 0.0)
            assertEquals("polar day in June at $lat", north, inJune.isPolarDay)
            assertEquals("polar night in June at $lat", !north, inJune.isPolarNight)
            assertEquals("polar day in December at $lat", !north, inDecember.isPolarDay)
            assertEquals("polar night in December at $lat", north, inDecember.isPolarNight)
        }
    }

    @Requirement("III.2")
    @Test
    fun `polar day and night spread towards the pole and never shrink back`() {
        Property.forAll("polar flags are monotone in latitude") { case ->
            val date = case.date()
            val a = case.double(0.0, 89.9)
            val b = case.double(a, 89.9)
            val sign = if (case.boolean()) 1 else -1
            case.input = "$date latitudes ${sign * a} and ${sign * b}"
            val near = calc.calculateDaylightWindow(date, sign * a, 0.0, 0.0)
            val far = calc.calculateDaylightWindow(date, sign * b, 0.0, 0.0)
            if (near.isPolarDay) assertTrue("polar day at $a but not at $b", far.isPolarDay)
            if (near.isPolarNight) assertTrue("polar night at $a but not at $b", far.isPolarNight)
        }
    }

    // --- Corrupt input ---

    @Requirement("III.2")
    @Test
    fun `corrupt coordinates never crash the calculation`() {
        val corrupt = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e300, -1e300, Double.MAX_VALUE)
        Property.forAll("any coordinates at all", cases = 200) { case ->
            val lat = if (case.boolean()) case.oneOf(corrupt) else case.double(-90.0, 90.0)
            val lon = if (case.boolean()) case.oneOf(corrupt) else case.double(-180.0, 180.0)
            case.input = "lat=$lat lon=$lon"
            val date = case.date()
            val w = calc.calculateDaylightWindow(date, lat, lon, if (case.boolean()) case.oneOf(corrupt) else 0.0)
            assertNotNull(w.sunriseTime)
            val prefs = UserPreferences(scheduleMode = ScheduleMode.SUNRISE_SUNSET, latitude = lat, longitude = lon)
            val snapshot = calc.resolveSolarSnapshot(prefs, isSystemInDarkTheme = false, nowZonedOverride = date.atTime(12, 0).atZone(ZoneId.of("UTC")))
            assertNotNull(snapshot.targetBrightness)
        }
    }

    // --- The schedule agrees with the brightness it schedules (Article III §3) ---

    private val zones = listOf(
        "America/New_York", "America/Los_Angeles", "Europe/London", "Europe/Berlin", "Asia/Kolkata", "Asia/Kathmandu",
        "Australia/Sydney", "Pacific/Auckland", "Pacific/Chatham", "America/Sao_Paulo", "Asia/Tokyo", "Africa/Cairo"
    ).map { ZoneId.of(it) }

    private class Moment(val prefs: UserPreferences, val now: ZonedDateTime) {
        override fun toString() = "${prefs.scheduleMode} lat=${prefs.latitude} lon=${prefs.longitude} light=${prefs.fixedLightHour}h dark=${prefs.fixedDarkHour}h now=$now"
    }

    private fun Property.Case.moment(mode: ScheduleMode): Moment {
        val zone = oneOf(zones)
        val now = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone).plusSeconds(random.nextLong(0, 4L * 365 * 86_400))
        val prefs = UserPreferences(
            scheduleMode = mode,
            autoChangeEnabled = true,
            isPaused = false,
            latitude = double(-60.0, 60.0),
            longitude = double(-180.0, 180.0),
            fixedLightHour = int(0, 23),
            fixedDarkHour = int(0, 23)
        )
        return Moment(prefs, now.withNano(0)).also { input = it.toString() }
    }

    private fun brightnessAt(prefs: UserPreferences, at: ZonedDateTime): BrightnessClass {
        val date = at.toLocalDate()
        return calc.resolveTargetBrightness(prefs, date, at.toLocalTime(), calc.resolveUtcOffsetHours(date, at.zone), isSystemInDarkTheme = false)
    }

    /** True when the clocks change anywhere in the days around [now] and [next], where a local time can repeat or not exist. */
    private fun nearClockChange(now: ZonedDateTime, next: ZonedDateTime): Boolean {
        val from = now.minusHours(6).toInstant()
        val change = now.zone.rules.nextTransition(from) ?: return false
        return change.instant.isBefore(next.plusHours(6).toInstant())
    }

    @Requirement("III.1", "III.3")
    @Test
    fun `the next change is a real moment ahead, never more than a day or so away, and never due at once`() {
        Property.forAll("the next transition is ahead") { case ->
            val mode = case.oneOf(listOf(ScheduleMode.SUNRISE_SUNSET, ScheduleMode.FIXED_CLOCK))
            val m = case.moment(mode)
            val next = calc.computeNextTransitionZonedTime(m.prefs, m.now)
            assertNotNull("a change should be scheduled", next)
            assertTrue("$next is not after ${m.now.plusSeconds(30)}", next!!.isAfter(m.now.plusSeconds(30)))
            assertTrue("$next is more than 27 hours after ${m.now}", Duration.between(m.now, next).toHours() < 27)
            val delay = calc.computeNextTransitionDelayMs(m.prefs, m.now)
            assertNotNull(delay)
            assertTrue("a delay of $delay ms is under a minute, so a change could be asked for again at once", delay!! >= 60_000L)
        }
    }

    @Requirement("III.1")
    @Test
    fun `nothing is scheduled when automation is off or paused, or when the look follows the system`() {
        Property.forAll("no schedule without automation") { case ->
            val mode = case.oneOf(ScheduleMode.entries)
            val m = case.moment(mode)
            val off = m.prefs.copy(autoChangeEnabled = false)
            val paused = m.prefs.copy(isPaused = true)
            assertNull("automation off", calc.computeNextTransitionZonedTime(off, m.now))
            assertNull("paused", calc.computeNextTransitionZonedTime(paused, m.now))
            assertNull("no delay while automation is off", calc.computeNextTransitionDelayMs(off, m.now))
            if (mode == ScheduleMode.FOLLOW_OS_THEME || mode == ScheduleMode.MANUAL_ONLY) {
                assertNull("$mode has no clock transition", calc.computeNextTransitionZonedTime(m.prefs, m.now))
            }
        }
    }

    @Requirement("III.1", "III.3")
    @Test
    fun `the look does not change before the next transition, and changes at it`() {
        Property.forAll("the schedule and the brightness agree") { case ->
            val mode = case.oneOf(listOf(ScheduleMode.SUNRISE_SUNSET, ScheduleMode.FIXED_CLOCK))
            val m = case.moment(mode)
            if (mode == ScheduleMode.FIXED_CLOCK && m.prefs.fixedLightHour == m.prefs.fixedDarkHour) return@forAll // one look all day
            val next = calc.computeNextTransitionZonedTime(m.prefs, m.now)!!
            if (nearClockChange(m.now, next)) return@forAll // a local time can repeat or not exist
            // A transition less than 30 seconds ahead is skipped on purpose (so one never fires twice), while the look is
            // still judged at this moment: inside that short window the next change is the one after. It is a known edge
            // (SolarCalculatorScheduleTest pins the skip), so it is left out here.
            if (brightnessAt(m.prefs, m.now) != brightnessAt(m.prefs, m.now.plusSeconds(31))) return@forAll
            val before = next.minusMinutes(1)
            val after = next.plusMinutes(1)
            val nearMidnight = { t: ZonedDateTime -> t.toLocalTime().isBefore(LocalTime.of(0, 3)) || t.toLocalTime().isAfter(LocalTime.of(23, 56)) }
            if (nearMidnight(next)) return@forAll // the two sides would be judged by two different days' windows
            val windowToday = calc.calculateDaylightWindow(next.toLocalDate(), m.prefs.latitude, m.prefs.longitude, calc.resolveUtcOffsetHours(next.toLocalDate(), next.zone))
            if (mode == ScheduleMode.SUNRISE_SUNSET && (windowToday.isPolarDay || windowToday.isPolarNight || dayLength(windowToday) !in 10..1430)) return@forAll
            if (before.toLocalDate() == m.now.toLocalDate()) {
                assertEquals("the look changed before the next transition", brightnessAt(m.prefs, m.now), brightnessAt(m.prefs, before))
            }
            assertTrue("the look did not change at $next", brightnessAt(m.prefs, before) != brightnessAt(m.prefs, after))
        }
    }
}
