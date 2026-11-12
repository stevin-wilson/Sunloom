package com.sunloom.app.domain.matcher

import com.sunloom.app.Requirement
import com.sunloom.app.data.testWallpaper
import com.sunloom.app.domain.matcher.ChosenWallpaper.Choice
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.model.WallpaperStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article III §1 (v0.59): what one change run does about the chosen picture, in one place the worker calls. It joins
 * the slot, the usability check and the period-start rule, and says when the side may be remembered as handled.
 */
@Requirement("III.1")
class ChosenChoiceTest {
    private val light = BrightnessClass.LIGHT
    private val dark = BrightnessClass.DARK
    private val d = testWallpaper("d", localFilePath = "/tmp/d.jpg")
    private val e = testWallpaper("e", localFilePath = "/tmp/e.jpg")
    private val both = UserPreferences(daytimePickId = "d", eveningPickId = "e")

    private fun choose(
        prefs: UserPreferences,
        target: BrightnessClass,
        last: BrightnessClass?,
        records: List<WallpaperRecord> = listOf(d, e),
        exists: (String) -> Boolean = { true }
    ): Choice = ChosenWallpaper.choose(prefs, target, last, lookup = { id -> records.firstOrNull { it.id == id } }, fileExists = exists)

    // --- choosing ---

    @Test
    fun `at a flip to the daytime the daytime picture is chosen`() {
        assertEquals(Choice.Chosen(d), choose(both, light, last = dark))
    }

    @Test
    fun `at a flip to the evening the evening picture is chosen`() {
        assertEquals(Choice.Chosen(e), choose(both, dark, last = light))
    }

    @Test
    fun `on a first run with nothing remembered the picture for the current side is chosen`() {
        assertEquals(Choice.Chosen(d), choose(both, light, last = null))
    }

    @Test
    fun `inside a period a usable chosen picture is left alone`() {
        assertEquals(Choice.LeaveAlone, choose(both, light, last = light))
    }

    @Test
    fun `the side with no chosen picture stays automatic at a flip and inside a period`() {
        val dayOnly = UserPreferences(daytimePickId = "d")
        assertEquals(Choice.Automatic, choose(dayOnly, dark, last = light))
        assertEquals(Choice.Automatic, choose(dayOnly, dark, last = dark))
    }

    @Test
    fun `a chosen picture that cannot be used leaves the run automatic`() {
        assertEquals(Choice.Automatic, choose(both, light, last = dark, records = emptyList()))
        assertEquals(Choice.Automatic, choose(both, light, last = dark, exists = { false }))
        assertEquals(
            Choice.Automatic,
            choose(both, light, last = dark, records = listOf(testWallpaper("d", localFilePath = "/tmp/d.jpg", isRejected = true), e))
        )
        assertEquals(
            Choice.Automatic,
            choose(both, light, last = dark, records = listOf(d.copy(status = WallpaperStatus.DISABLED_BY_USER), e))
        )
    }

    @Test
    fun `an unusable chosen picture inside a period is automatic too, so the interval still rotates that side`() {
        assertEquals(Choice.Automatic, choose(both, light, last = light, records = emptyList()))
    }

    @Test
    fun `a balanced target is automatic`() {
        assertEquals(Choice.Automatic, choose(both, BrightnessClass.NEUTRAL, last = light))
    }

    // --- remembering the side ---

    @Test
    fun `the side is remembered once the chosen picture is on screen`() {
        assertTrue(ChosenWallpaper.shouldRememberTarget(Choice.Chosen(d), WallpaperStatus.APPLIED_SUCCESSFULLY))
    }

    @Test
    fun `the side is remembered when another picture took the place of a chosen one that failed, so it is not retried every look`() {
        assertTrue(ChosenWallpaper.shouldRememberTarget(Choice.Chosen(d), WallpaperStatus.APPLIED_SUCCESSFULLY))
    }

    @Test
    fun `the side is not remembered when nothing at all reached the screen`() {
        assertFalse(ChosenWallpaper.shouldRememberTarget(Choice.Chosen(d), WallpaperStatus.FAILED_TEMPORARILY))
    }

    @Test
    fun `an automatic run remembers the side when it applied something`() {
        assertTrue(ChosenWallpaper.shouldRememberTarget(Choice.Automatic, WallpaperStatus.APPLIED_SUCCESSFULLY))
        assertFalse(ChosenWallpaper.shouldRememberTarget(Choice.Automatic, WallpaperStatus.FAILED_TEMPORARILY))
        assertFalse(ChosenWallpaper.shouldRememberTarget(Choice.Automatic, null))
    }

    @Test
    fun `leaving a chosen picture alone keeps the side remembered`() {
        assertTrue(ChosenWallpaper.shouldRememberTarget(Choice.LeaveAlone, null))
    }
}
