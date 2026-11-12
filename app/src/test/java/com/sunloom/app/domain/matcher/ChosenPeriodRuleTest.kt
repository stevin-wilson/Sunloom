package com.sunloom.app.domain.matcher

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.platform.scheduler.RefreshSchedulePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article III §1 (v0.59): a chosen picture is applied when its period starts, not enforced all through it. Inside a
 * period nothing replaces it (the "Change every" interval leaves that side alone), and the other side keeps changing.
 */
@Requirement("III.1")
class ChosenPeriodRuleTest {
    private val light = BrightnessClass.LIGHT
    private val dark = BrightnessClass.DARK

    // --- when does a period start? ---

    @Test
    fun `a flip from the other side starts a period`() {
        assertTrue(ChosenWallpaper.periodStarted(lastTargetSeen = dark, target = light))
        assertTrue(ChosenWallpaper.periodStarted(lastTargetSeen = light, target = dark))
    }

    @Test
    fun `the same side again is not a new period`() {
        assertFalse(ChosenWallpaper.periodStarted(lastTargetSeen = light, target = light))
        assertFalse(ChosenWallpaper.periodStarted(lastTargetSeen = dark, target = dark))
    }

    @Test
    fun `a first run, a reinstall or a reset with no side remembered starts a period`() {
        assertTrue(ChosenWallpaper.periodStarted(lastTargetSeen = null, target = light))
        assertTrue(ChosenWallpaper.periodStarted(lastTargetSeen = null, target = dark))
    }

    // --- Follow OS ---

    @Test
    fun `a Follow-OS poll does not call a wallpaper redundant when a chosen picture is due at a flip`() {
        val follow = ScheduleMode.FOLLOW_OS_THEME
        assertTrue(RefreshSchedulePolicy.isRedundantFollowOsChange(follow, currentSuitsTarget = true))
        assertFalse(RefreshSchedulePolicy.isRedundantFollowOsChange(follow, currentSuitsTarget = true, chosenDue = true))
    }

    @Test
    fun `a chosen picture being due changes nothing about modes that never call a change redundant`() {
        assertFalse(
            RefreshSchedulePolicy.isRedundantFollowOsChange(ScheduleMode.SUNRISE_SUNSET, currentSuitsTarget = true, chosenDue = true)
        )
    }
}
