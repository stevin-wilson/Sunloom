package com.sunloom.app.platform.scheduler

import com.sunloom.app.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article X §1 (v0.14): fetching new images is separate from changing the wallpaper. It must keep running for
 * people who change the wallpaper by hand (manual mode, automatic changes off), and often enough to matter.
 */
@Requirement("X.1")
class RefreshSchedulePolicyTest {

    @Test
    fun `automatic mode schedules both wallpaper changes and fetching`() {
        val plan = RefreshSchedulePolicy.plan(autoEnabled = true, isPaused = false, isManualMode = false)
        assertTrue(plan.scheduleChanges)
        assertTrue(plan.scheduleRefresh)
    }

    @Test
    fun `manual mode and switched-off automation still fetch in the background`() {
        val manual = RefreshSchedulePolicy.plan(autoEnabled = true, isPaused = false, isManualMode = true)
        assertFalse(manual.scheduleChanges)
        assertTrue("manual users still want fresh images", manual.scheduleRefresh)

        val off = RefreshSchedulePolicy.plan(autoEnabled = false, isPaused = false, isManualMode = false)
        assertFalse(off.scheduleChanges)
        assertTrue(off.scheduleRefresh)
    }

    @Test
    fun `pausing automation stops both`() {
        val plan = RefreshSchedulePolicy.plan(autoEnabled = true, isPaused = true, isManualMode = false)
        assertFalse(plan.scheduleChanges)
        assertFalse(plan.scheduleRefresh)
    }

    @Test
    fun `the refresh interval is frequent but respects the WorkManager minimum`() {
        assertEquals(3L, RefreshSchedulePolicy.REFRESH_INTERVAL_HOURS)
        assertTrue(RefreshSchedulePolicy.REFRESH_INTERVAL_HOURS * 60 >= 15)
    }

    @Test
    fun `a background top up runs a few rounds at most`() {
        assertTrue(RefreshSchedulePolicy.MAX_BACKGROUND_ROUNDS in 2..5)
    }
}
