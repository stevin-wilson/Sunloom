package com.sunloom.app.ui

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * WCAG 2.1.4: a shortcut made of one character key can be turned off, so speech-input and mistyped keys do not
 * change the wallpaper (Constitution Article XII §4).
 */
@Requirement("XII.4")
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h2400dp")
class SingleKeyShortcutsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var nexts = 0
    private var rejects = 0

    private fun show() = composeTestRule.setContent {
        SunloomApp(
            uiState = SunloomUiState(
                activeTab = DesktopNavTab.SAFETY_AND_DECISION_LOG,
                preferences = UserPreferences(isSetupCompleted = true)
            ),
            onCompleteSetup = {}, onSelectTab = {}, onNextWallpaper = { nexts++ }, onPreviousWallpaper = {},
            onToggleKeep = {}, onRejectForever = { rejects++ }, onTogglePause = {}, onRestoreOriginal = {},
            onApplySpecific = {}, onToggleLegibilityOverlay = {}, onSyncOnline = {}, onToggleCategory = {},
            onToggleSource = {}, onSelectCity = {}, onUpdateScheduleMode = {}, onUpdateLockPolicy = {},
            onToggleBlurredFill = {}, onToggleWifiOnly = {}, onPickLocalPhoto = {},
            darkTheme = false
        )
    }

    private fun press(key: Key) {
        composeTestRule.onNodeWithTag("workspace_root").performKeyInput { pressKey(key) }
    }

    @Test
    fun `single keys work by default`() {
        show()
        composeTestRule.waitForIdle()
        press(Key.N)
        assertEquals(1, nexts)
    }

    @Test
    fun `turning the setting off stops N and Delete, and turning it on brings them back`() {
        show()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("single_key_shortcuts_switch").performScrollTo().performClick()
        press(Key.N)
        press(Key.Delete)
        assertEquals("N does nothing while the setting is off", 0, nexts)
        assertEquals("Delete does nothing while the setting is off", 0, rejects)

        composeTestRule.onNodeWithTag("single_key_shortcuts_switch").performScrollTo().performClick()
        press(Key.N)
        assertEquals(1, nexts)
    }

    @Test
    fun `the choice is remembered`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        UiPrefs.setSingleKeyShortcuts(context, false)
        show()
        composeTestRule.waitForIdle()
        press(Key.N)
        assertEquals(0, nexts)
    }
}
