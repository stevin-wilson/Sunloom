package com.sunloom.app.platform.scheduler

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.solar.SolarCalculator
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Article X §1 (v0.58): the delay the scheduler arms is the schedule's own wait, brought forward to meet the
 * "Change every" interval. This is the decision behind `syncPeriodicSchedule`, without WorkManager.
 */
@Requirement("X.1")
class ArmedDelayTest {
    private val solar = SolarCalculator()
    private val hour = 3_600_000L
    private val minute = 60_000L

    // Noon: with the light hour at 7 and the dark hour at 19, the next transition is seven hours away.
    private val noon = ZonedDateTime.of(2026, 6, 15, 12, 0, 0, 0, ZoneId.of("UTC"))
    private val nowMs = noon.toInstant().toEpochMilli()

    private fun prefs(mode: ScheduleMode, hours: Int) = UserPreferences(
        scheduleMode = mode, fixedLightHour = 7, fixedDarkHour = 19, changeEveryHours = hours
    )

    @Test
    fun `with the interval off the next transition is used as before`() {
        val delay = SunloomWallpaperScheduler.armedDelayMs(solar, prefs(ScheduleMode.FIXED_CLOCK, 0), noon, nowMs - 9 * hour)
        assertEquals(7 * hour, delay)
    }

    @Test
    fun `a nearer interval brings the next change forward`() {
        val delay = SunloomWallpaperScheduler.armedDelayMs(solar, prefs(ScheduleMode.FIXED_CLOCK, 3), noon, nowMs - 3 * hour + 10 * minute)
        assertEquals(10 * minute, delay)
    }

    @Test
    fun `a nearer transition is not pushed back by a long interval`() {
        val atSix = noon.withHour(18)
        val delay = SunloomWallpaperScheduler.armedDelayMs(
            solar, prefs(ScheduleMode.FIXED_CLOCK, 12), atSix, atSix.toInstant().toEpochMilli()
        )
        assertEquals(hour, delay)
    }

    @Test
    fun `follow OS keeps looking every fifteen minutes`() {
        val delay = SunloomWallpaperScheduler.armedDelayMs(solar, prefs(ScheduleMode.FOLLOW_OS_THEME, 3), noon, nowMs)
        assertEquals(15 * minute, delay)
    }

    @Test
    fun `without preferences the generic fallback applies`() {
        assertEquals(6 * hour, SunloomWallpaperScheduler.armedDelayMs(solar, null, noon, null))
    }

    @Test
    fun `manual mode ignores the interval`() {
        // Manual mode never reaches arming in production; the rule must still not invent a short wait for it.
        val delay = SunloomWallpaperScheduler.armedDelayMs(solar, prefs(ScheduleMode.MANUAL_ONLY, 3), noon, nowMs)
        assertEquals(6 * hour, delay)
    }
}
