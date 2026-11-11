package com.sunloom.app.platform.scheduler

import com.sunloom.app.Requirement
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
        val delay = RefreshSchedulePolicy.nextChangeDelayMs(5 * hour, 1, now - 10 * hour, now)
        assertEquals(RefreshSchedulePolicy.MIN_CHANGE_DELAY_MS, delay)
    }

    @Test
    fun `a transition closer than the minimum is not stretched`() {
        assertEquals(10_000L, RefreshSchedulePolicy.nextChangeDelayMs(10_000L, 1, now - 10 * hour, now))
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
        assertEquals(listOf(0, 1, 3, 6, 12), RefreshSchedulePolicy.CHANGE_EVERY_CHOICES_HOURS)
        assertEquals(3, RefreshSchedulePolicy.sanitizeChangeEveryHours(3))
        assertEquals(0, RefreshSchedulePolicy.sanitizeChangeEveryHours(5))
        assertEquals(0, RefreshSchedulePolicy.sanitizeChangeEveryHours(-1))
        assertEquals(0, RefreshSchedulePolicy.sanitizeChangeEveryHours(24))
        assertTrue(RefreshSchedulePolicy.CHANGE_EVERY_CHOICES_HOURS.first() == 0)
    }

    @Test
    fun `the interval only applies when automation runs and the mode changes by itself`() {
        assertTrue(RefreshSchedulePolicy.intervalApplies(3, scheduleChanges = true))
        assertEquals(false, RefreshSchedulePolicy.intervalApplies(3, scheduleChanges = false))
        assertEquals(false, RefreshSchedulePolicy.intervalApplies(0, scheduleChanges = true))
    }
}
