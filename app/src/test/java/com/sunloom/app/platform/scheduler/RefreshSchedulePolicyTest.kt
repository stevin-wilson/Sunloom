package com.sunloom.app.platform.scheduler

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.WallpaperStatus
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

    @Test
    fun `a change that failed temporarily is not re-armed by the running worker, so its retry survives`() {
        // Re-enqueueing the change work under its own unique name (REPLACE) cancels the running job and its retry.
        assertFalse(RefreshSchedulePolicy.shouldRearmAfterChange(WallpaperStatus.FAILED_TEMPORARILY))
    }

    @Test
    fun `every other outcome re-arms the next transition`() {
        assertTrue(RefreshSchedulePolicy.shouldRearmAfterChange(WallpaperStatus.APPLIED_SUCCESSFULLY))
        assertTrue(RefreshSchedulePolicy.shouldRearmAfterChange(WallpaperStatus.UNSUPPORTED_BY_SYSTEM))
        assertTrue(RefreshSchedulePolicy.shouldRearmAfterChange(null))
    }

    @Test
    fun `follow-OS mode polls every 15 minutes, other modes keep the long fallback`() {
        assertEquals(15L * 60_000L, RefreshSchedulePolicy.fallbackChangeDelayMs(ScheduleMode.FOLLOW_OS_THEME))
        assertEquals(6L * 3_600_000L, RefreshSchedulePolicy.fallbackChangeDelayMs(ScheduleMode.SUNRISE_SUNSET))
        assertEquals(6L * 3_600_000L, RefreshSchedulePolicy.fallbackChangeDelayMs(null))
    }

    @Test
    fun `a follow-OS poll changes nothing while the wallpaper already suits the theme`() {
        assertTrue(RefreshSchedulePolicy.isRedundantFollowOsChange(ScheduleMode.FOLLOW_OS_THEME, currentSuitsTarget = true))
        assertFalse(RefreshSchedulePolicy.isRedundantFollowOsChange(ScheduleMode.FOLLOW_OS_THEME, currentSuitsTarget = false))
        assertFalse("no wallpaper yet", RefreshSchedulePolicy.isRedundantFollowOsChange(ScheduleMode.FOLLOW_OS_THEME, currentSuitsTarget = null))
        assertFalse("timed modes always change", RefreshSchedulePolicy.isRedundantFollowOsChange(ScheduleMode.SUNRISE_SUNSET, currentSuitsTarget = true))
    }

    @Test
    fun `the backstop alarm never replaces a change that is already running`() {
        assertFalse(RefreshSchedulePolicy.shouldReplaceChangeOnAlarm(changeRunning = true))
        assertTrue(RefreshSchedulePolicy.shouldReplaceChangeOnAlarm(changeRunning = false))
    }
}
