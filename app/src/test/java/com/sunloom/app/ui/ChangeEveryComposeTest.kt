package com.sunloom.app.ui

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.R
import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article III §1 / XII (v0.58): "Change every" adds changes inside each daytime or evening period. It is one row of
 * choices in the Schedule, off by default, and when it cannot work it says why instead of disappearing.
 */
@Requirement("XII.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h2000dp")
class ChangeEveryComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun prefs(hours: Int = 0, auto: Boolean = true, mode: ScheduleMode = ScheduleMode.SUNRISE_SUNSET) =
        UserPreferences(isSetupCompleted = true, autoChangeEnabled = auto, scheduleMode = mode, changeEveryHours = hours)

    private fun setApp(p: UserPreferences, onChoose: (Int) -> Unit = {}) = composeTestRule.setContent {
        SunloomApp(
            uiState = SunloomUiState(activeTab = DesktopNavTab.DISPLAYS_AND_SCHEDULE, preferences = p),
            onCompleteSetup = {}, onSelectTab = {}, onNextWallpaper = {}, onPreviousWallpaper = {},
            onToggleKeep = {}, onRejectForever = {}, onTogglePause = {}, onRestoreOriginal = {},
            onApplySpecific = {}, onToggleLegibilityOverlay = {}, onSyncOnline = {}, onToggleCategory = {},
            onToggleSource = {}, onSelectCity = {}, onUpdateScheduleMode = {}, onUpdateLockPolicy = {},
            onToggleBlurredFill = {}, onToggleWifiOnly = {}, onPickLocalPhoto = {},
            onUpdateChangeEvery = onChoose,
            darkTheme = false
        )
    }

    private fun chip(hours: Int) = composeTestRule.onNodeWithTag("change_every_$hours").also { it.performScrollTo() }

    @Test
    fun `every choice is offered and off is selected by default`() {
        setApp(prefs())

        chip(0).assertIsSelected()
        listOf(3, 6, 12).forEach { chip(it).assertIsNotSelected() }
    }

    @Test
    fun `the chosen interval is the selected one`() {
        setApp(prefs(hours = 6))

        chip(6).assertIsSelected()
        chip(0).assertIsNotSelected()
    }

    @Test
    fun `choosing an interval reports it`() {
        var chosen: Int? = null
        setApp(prefs()) { chosen = it }

        chip(3).performClick()

        assertEquals(3, chosen)
    }

    @Test
    fun `choosing off reports zero`() {
        var chosen: Int? = null
        setApp(prefs(hours = 12)) { chosen = it }

        chip(0).performClick()

        assertEquals(0, chosen)
    }

    @Test
    fun `the label says about every and never promises an exact time`() {
        setApp(prefs(hours = 3))

        composeTestRule.onNodeWithTag("change_every_hint").performScrollTo()
            .assertTextContains(context.resources.getQuantityString(R.plurals.change_every_hint_on, 3, 3), substring = true)
    }

    @Test
    fun `off explains what the setting would do`() {
        setApp(prefs())

        composeTestRule.onNodeWithTag("change_every_hint").performScrollTo()
            .assertTextContains(context.getString(R.string.change_every_hint_off), substring = true)
    }

    @Test
    fun `with automatic switching off the choices are disabled and say why`() {
        setApp(prefs(auto = false))

        listOf(0, 3, 6, 12).forEach { chip(it).assertIsNotEnabled() }
        composeTestRule.onNodeWithTag("change_every_note").performScrollTo()
            .assertTextContains(context.getString(R.string.change_every_needs_automatic), substring = true)
    }

    @Test
    fun `with the manual schedule the choices are disabled and say why`() {
        setApp(prefs(mode = ScheduleMode.MANUAL_ONLY))

        listOf(0, 3, 6, 12).forEach { chip(it).assertIsNotEnabled() }
        composeTestRule.onNodeWithTag("change_every_note").performScrollTo()
            .assertTextContains(context.getString(R.string.change_every_needs_schedule), substring = true)
    }

    @Test
    fun `when it can work the choices are enabled and no reason is shown`() {
        setApp(prefs())

        listOf(0, 3, 6, 12).forEach { chip(it).assertIsEnabled() }
        composeTestRule.onNodeWithTag("change_every_note").assertDoesNotExist()
    }

    @Test
    fun `the reason is decided by automatic switching first, then the schedule`() {
        assertNull(changeEveryUnavailableNote(prefs()))
        assertNull("paused still lets the person choose", changeEveryUnavailableNote(prefs().copy(isPaused = true)))
        assertEquals(R.string.change_every_needs_automatic, changeEveryUnavailableNote(prefs(auto = false)))
        assertEquals(
            R.string.change_every_needs_automatic,
            changeEveryUnavailableNote(prefs(auto = false, mode = ScheduleMode.MANUAL_ONLY))
        )
        assertEquals(R.string.change_every_needs_schedule, changeEveryUnavailableNote(prefs(mode = ScheduleMode.MANUAL_ONLY)))
    }
}
