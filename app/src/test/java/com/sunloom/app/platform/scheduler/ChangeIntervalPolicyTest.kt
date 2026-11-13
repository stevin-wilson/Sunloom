package com.sunloom.app.platform.scheduler

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article III §1 / X §1 (v0.58): an optional "Change every" interval adds changes inside a light or dark period.
 * The next look is the earlier of the next light/dark transition and the moment the interval after the last change
 * runs out. Off (0 hours) leaves today's behaviour untouched.
 */
@Requirement("X.1")
class ChangeIntervalPolicyTest {
    private val hour = 3_600_000L
    private val now = 1_000_000_000_000L

    @Test
    fun `off keeps the transition delay exactly`() {
        assertEquals(5 * hour, RefreshSchedulePolicy.nextChangeDelayMs(5 * hour, 0, now - hour, now))
    }

    @Test
    fun `off with no transition returns null so the caller uses its fallback`() {
        assertEquals(null, RefreshSchedulePolicy.nextChangeDelayMs(null, 0, now - hour, now))
    }

    @Test
    fun `interval earlier than the transition wins and counts from the last change`() {
        // Last change 30 minutes ago, every 3 hours: 2.5 hours remain, the transition is 5 hours away.
        val delay = RefreshSchedulePolicy.nextChangeDelayMs(5 * hour, 3, now - hour / 2, now)
        assertEquals(2 * hour + hour / 2, delay)
    }

    @Test
    fun `transition earlier than the interval wins`() {
        assertEquals(hour, RefreshSchedulePolicy.nextChangeDelayMs(hour, 6, now, now))
    }

    @Test
    fun `an interval already overdue is due in one minute, not at once`() {
        val delay = RefreshSchedulePolicy.nextChangeDelayMs(5 * hour, 3, now - 10 * hour, now)
        assertEquals(RefreshSchedulePolicy.MIN_CHANGE_DELAY_MS, delay)
    }

    @Test
    fun `a transition closer than the minimum is not stretched`() {
        assertEquals(10_000L, RefreshSchedulePolicy.nextChangeDelayMs(10_000L, 3, now - 10 * hour, now))
    }

    @Test
    fun `with no transition the interval alone sets the delay`() {
        assertEquals(hour, RefreshSchedulePolicy.nextChangeDelayMs(null, 3, now - 2 * hour, now))
    }

    @Test
    fun `a last change in the future from a clock step is treated as just now`() {
        assertEquals(3 * hour, RefreshSchedulePolicy.nextChangeDelayMs(null, 3, now + 5 * hour, now))
    }

    @Test
    fun `with no known last change the full interval applies`() {
        assertEquals(3 * hour, RefreshSchedulePolicy.nextChangeDelayMs(null, 3, null, now))
    }

    @Test
    fun `only the offered intervals are valid and anything else means off`() {
        assertEquals(listOf(0, 3, 6, 12), UserPreferences.CHANGE_EVERY_CHOICES_HOURS)
        assertEquals(3, UserPreferences.sanitizeChangeEveryHours(3))
        assertEquals(0, UserPreferences.sanitizeChangeEveryHours(1))
        assertEquals(0, UserPreferences.sanitizeChangeEveryHours(5))
        assertEquals(0, UserPreferences.sanitizeChangeEveryHours(-1))
        assertEquals(0, UserPreferences.sanitizeChangeEveryHours(24))
        assertTrue(UserPreferences.CHANGE_EVERY_CHOICES_HOURS.first() == 0)
    }

    @Test
    fun `the interval only applies when automation runs and the mode changes by itself`() {
        assertTrue(RefreshSchedulePolicy.intervalApplies(3, scheduleChanges = true))
        assertEquals(false, RefreshSchedulePolicy.intervalApplies(3, scheduleChanges = false))
        assertEquals(false, RefreshSchedulePolicy.intervalApplies(0, scheduleChanges = true))
    }

    // --- when the interval is due, and what the delay is ---

    @Test
    fun `the interval is due once the wait since the countdown started has passed`() {
        assertEquals(false, RefreshSchedulePolicy.isIntervalDue(3, true, now - 2 * hour, now))
        assertEquals(true, RefreshSchedulePolicy.isIntervalDue(3, true, now - 3 * hour, now))
        assertEquals(true, RefreshSchedulePolicy.isIntervalDue(3, true, now - 9 * hour, now))
    }

    @Test
    fun `the interval is never due when off, when changes are not scheduled, or before a countdown exists`() {
        assertEquals(false, RefreshSchedulePolicy.isIntervalDue(0, true, now - 9 * hour, now))
        assertEquals(false, RefreshSchedulePolicy.isIntervalDue(3, false, now - 9 * hour, now))
        assertEquals(false, RefreshSchedulePolicy.isIntervalDue(3, true, null, now))
    }

    @Test
    fun `a Follow-OS poll that would be skipped is not skipped when the interval is due`() {
        val key = RefreshSchedulePolicy.followOsVerifiedKey(com.sunloom.app.domain.model.BrightnessClass.LIGHT, "w1")
        val follow = com.sunloom.app.domain.model.ScheduleMode.FOLLOW_OS_THEME
        val light = com.sunloom.app.domain.model.BrightnessClass.LIGHT
        assertTrue(RefreshSchedulePolicy.canSkipFollowOsPoll(follow, key, light, "w1", intervalDue = false))
        assertEquals(false, RefreshSchedulePolicy.canSkipFollowOsPoll(follow, key, light, "w1", intervalDue = true))
    }

    @Test
    fun `a wallpaper that suits the theme is not left alone when the interval is due`() {
        val follow = com.sunloom.app.domain.model.ScheduleMode.FOLLOW_OS_THEME
        assertTrue(RefreshSchedulePolicy.isRedundantFollowOsChange(follow, currentSuitsTarget = true, intervalDue = false))
        assertEquals(false, RefreshSchedulePolicy.isRedundantFollowOsChange(follow, currentSuitsTarget = true, intervalDue = true))
    }

    @Test
    fun `the armed delay is the transition when the interval is off`() {
        val mode = com.sunloom.app.domain.model.ScheduleMode.SUNRISE_SUNSET
        assertEquals(5 * hour, RefreshSchedulePolicy.changeDelayMs(mode, 5 * hour, 0, true, now - hour, now))
    }

    @Test
    fun `the armed delay is the earlier of the transition and the interval`() {
        val mode = com.sunloom.app.domain.model.ScheduleMode.SUNRISE_SUNSET
        assertEquals(2 * hour, RefreshSchedulePolicy.changeDelayMs(mode, 5 * hour, 3, true, now - hour, now))
        assertEquals(hour, RefreshSchedulePolicy.changeDelayMs(mode, hour, 6, true, now, now))
    }

    @Test
    fun `without a transition the mode's own fallback is what the interval is compared with`() {
        val follow = com.sunloom.app.domain.model.ScheduleMode.FOLLOW_OS_THEME
        // Follow OS looks every 15 minutes; a 3 hour interval does not make it look less often.
        assertEquals(15 * 60_000L, RefreshSchedulePolicy.changeDelayMs(follow, null, 3, true, now, now))
        // Shortly before the interval is due, the look is brought forward to meet it.
        assertEquals(10 * 60_000L, RefreshSchedulePolicy.changeDelayMs(follow, null, 3, true, now - 3 * hour + 10 * 60_000L, now))
        // No transition and a fixed mode: the 6 hour fallback is shortened by a 3 hour interval.
        assertEquals(3 * hour, RefreshSchedulePolicy.changeDelayMs(com.sunloom.app.domain.model.ScheduleMode.FIXED_CLOCK, null, 3, true, now, now))
    }

    @Test
    fun `the armed delay ignores the interval when changes are not scheduled`() {
        val mode = com.sunloom.app.domain.model.ScheduleMode.SUNRISE_SUNSET
        assertEquals(5 * hour, RefreshSchedulePolicy.changeDelayMs(mode, 5 * hour, 3, false, now - 9 * hour, now))
    }
}
