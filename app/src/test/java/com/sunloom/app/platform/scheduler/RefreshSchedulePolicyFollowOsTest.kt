package com.sunloom.app.platform.scheduler

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.ScheduleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Article X §1: the decisions behind a cheap Follow-OS poll, as plain rules. */
@Requirement("X.1")
class RefreshSchedulePolicyFollowOsTest {
    private val follow = ScheduleMode.FOLLOW_OS_THEME

    @Test
    fun `the verified key names the theme and the wallpaper`() {
        assertEquals("LIGHT:w1", RefreshSchedulePolicy.followOsVerifiedKey(BrightnessClass.LIGHT, "w1"))
        assertEquals("DARK:@system", RefreshSchedulePolicy.followOsVerifiedKey(BrightnessClass.DARK, null))
        assertNotEquals(
            RefreshSchedulePolicy.followOsVerifiedKey(BrightnessClass.LIGHT, "w1"),
            RefreshSchedulePolicy.followOsVerifiedKey(BrightnessClass.DARK, "w1")
        )
    }

    @Test
    fun `a poll is skipped only in follow-OS mode and only for exactly what was verified`() {
        val key = RefreshSchedulePolicy.followOsVerifiedKey(BrightnessClass.LIGHT, "w1")

        assertTrue(RefreshSchedulePolicy.canSkipFollowOsPoll(follow, key, BrightnessClass.LIGHT, "w1"))
        assertFalse("another wallpaper", RefreshSchedulePolicy.canSkipFollowOsPoll(follow, key, BrightnessClass.LIGHT, "w2"))
        assertFalse("another theme", RefreshSchedulePolicy.canSkipFollowOsPoll(follow, key, BrightnessClass.DARK, "w1"))
        assertFalse("nothing verified", RefreshSchedulePolicy.canSkipFollowOsPoll(follow, null, BrightnessClass.LIGHT, "w1"))
        assertFalse("other modes have their own timing", RefreshSchedulePolicy.canSkipFollowOsPoll(ScheduleMode.FIXED_CLOCK, key, BrightnessClass.LIGHT, "w1"))
    }

    @Test
    fun `follow-OS has no backstop alarm because it already looks every fifteen minutes`() {
        assertFalse(RefreshSchedulePolicy.usesBackstopAlarm(follow))
        assertTrue(RefreshSchedulePolicy.usesBackstopAlarm(ScheduleMode.SUNRISE_SUNSET))
        assertTrue(RefreshSchedulePolicy.usesBackstopAlarm(ScheduleMode.FIXED_CLOCK))
    }

    @Test
    fun `a theme change is reacted to at once, but only in follow-OS mode and only after a first reading`() {
        assertTrue(RefreshSchedulePolicy.shouldReactToThemeChange(follow, previousNight = false, nowNight = true))
        assertTrue(RefreshSchedulePolicy.shouldReactToThemeChange(follow, previousNight = true, nowNight = false))
        assertFalse("no change", RefreshSchedulePolicy.shouldReactToThemeChange(follow, previousNight = true, nowNight = true))
        assertFalse("nothing to compare with yet", RefreshSchedulePolicy.shouldReactToThemeChange(follow, previousNight = null, nowNight = true))
        assertFalse("not following the OS", RefreshSchedulePolicy.shouldReactToThemeChange(ScheduleMode.SUNRISE_SUNSET, false, true))
    }
}
