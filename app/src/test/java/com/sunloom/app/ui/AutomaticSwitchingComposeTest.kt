package com.sunloom.app.ui

import android.app.Application
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.R
import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Someone who switched automatic wallpapers off in setup must be able to switch them on again, and the Setup Guide
 * button must reopen setup rather than silently finishing it (Constitution Article XII §1).
 */
@Requirement("XII.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h2000dp")
class AutomaticSwitchingComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun schedule(prefs: UserPreferences, reopened: Boolean = false) =
        SunloomUiState(activeTab = DesktopNavTab.DISPLAYS_AND_SCHEDULE, preferences = prefs, isSetupReopened = reopened)

    private fun setApp(
        prefs: UserPreferences,
        reopened: Boolean = false,
        onToggleAutoSwitch: (Boolean) -> Unit = {},
        onReopenSetup: () -> Unit = {},
        onCompleteSetup: (UserPreferences) -> Unit = {}
    ) = composeTestRule.setContent {
        SunloomApp(
            uiState = schedule(prefs, reopened),
            onCompleteSetup = onCompleteSetup, onSelectTab = {}, onNextWallpaper = {}, onPreviousWallpaper = {},
            onToggleKeep = {}, onRejectForever = {}, onTogglePause = {}, onRestoreOriginal = {},
            onApplySpecific = {}, onToggleLegibilityOverlay = {}, onSyncOnline = {}, onToggleCategory = {},
            onToggleSource = {}, onSelectCity = {}, onUpdateScheduleMode = {}, onUpdateLockPolicy = {},
            onToggleBlurredFill = {}, onToggleWifiOnly = {}, onPickLocalPhoto = {},
            onToggleAutoSwitch = onToggleAutoSwitch, onReopenSetup = onReopenSetup,
            darkTheme = false
        )
    }

    @Test
    fun `schedule pane shows automatic switching off and turns it on`() {
        var requested: Boolean? = null
        setApp(
            UserPreferences(isSetupCompleted = true, autoChangeEnabled = false, scheduleMode = ScheduleMode.SUNRISE_SUNSET),
            onToggleAutoSwitch = { requested = it }
        )
        composeTestRule.onNodeWithTag("auto_switch").assertIsOff()

        composeTestRule.onNodeWithTag("auto_switch").performClick()

        assertEquals(true, requested)
    }

    @Test
    fun `schedule pane shows automatic switching on and turns it off`() {
        var requested: Boolean? = null
        setApp(
            UserPreferences(isSetupCompleted = true, autoChangeEnabled = true, scheduleMode = ScheduleMode.SUNRISE_SUNSET),
            onToggleAutoSwitch = { requested = it }
        )
        composeTestRule.onNodeWithTag("auto_switch").assertIsOn()

        composeTestRule.onNodeWithTag("auto_switch").performClick()

        assertEquals(false, requested)
    }

    @Test
    fun `setup guide reopens setup instead of completing it`() {
        var reopened = false
        var completed = false
        setApp(
            UserPreferences(isSetupCompleted = true),
            onReopenSetup = { reopened = true },
            onCompleteSetup = { completed = true }
        )

        composeTestRule.onNodeWithText(context.getString(R.string.setup_guide)).performScrollTo().performClick()

        assertTrue("Setup Guide must ask to reopen setup", reopened)
        assertTrue("and must not complete it", !completed)
    }

    @Test
    fun `reopened setup shows the setup screen even though setup was completed`() {
        setApp(UserPreferences(isSetupCompleted = true), reopened = true)

        composeTestRule.onNodeWithText(context.getString(R.string.welcome_to_sunloom)).assertExists()
        composeTestRule.onNodeWithTag("auto_switch").assertDoesNotExist()
    }

    @Test
    fun `switch explains why nothing changes while paused`() {
        setApp(UserPreferences(isSetupCompleted = true, autoChangeEnabled = true, isPaused = true,
            scheduleMode = ScheduleMode.SUNRISE_SUNSET))

        composeTestRule.onNodeWithText(context.getString(R.string.automatic_switching_paused_note)).assertExists()
    }

    @Test
    fun `switch explains why nothing changes in manual mode`() {
        setApp(UserPreferences(isSetupCompleted = true, autoChangeEnabled = true, scheduleMode = ScheduleMode.MANUAL_ONLY))

        composeTestRule.onNodeWithText(context.getString(R.string.automatic_switching_manual_note)).assertExists()
    }

    @Test
    fun `no explanation is shown while automatic switching really runs`() {
        setApp(UserPreferences(isSetupCompleted = true, autoChangeEnabled = true, scheduleMode = ScheduleMode.SUNRISE_SUNSET))

        composeTestRule.onNodeWithText(context.getString(R.string.automatic_switching_paused_note)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.automatic_switching_manual_note)).assertDoesNotExist()
    }
}
