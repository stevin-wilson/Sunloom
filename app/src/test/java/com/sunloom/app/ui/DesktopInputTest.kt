package com.sunloom.app.ui

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp")
class DesktopInputTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class Counters {
        var next = 0
        var previous = 0
        var keep = 0
        var reject = 0
        var fullscreen = 0
    }

    private fun setApp(state: SunloomUiState, counters: Counters, isFullscreen: Boolean = false) {
        composeTestRule.setContent {
            SunloomApp(
                uiState = state,
                onCompleteSetup = {},
                onSelectTab = {},
                onNextWallpaper = { counters.next++ },
                onPreviousWallpaper = { counters.previous++ },
                onToggleKeep = { counters.keep++ },
                onRejectForever = { counters.reject++ },
                onTogglePause = {},
                onRestoreOriginal = {},
                onApplySpecific = {},
                onToggleLegibilityOverlay = {},
                onSyncOnline = {},
                onToggleCategory = {},
                onToggleSource = {},
                onSelectCity = {},
                onUpdateScheduleMode = {},
                onUpdateLockPolicy = {},
                onToggleBlurredFill = {},
                onToggleWifiOnly = {},
                onPickLocalPhoto = {},
                isFullscreen = isFullscreen,
                onToggleFullscreen = { counters.fullscreen++ }
            )
        }
    }

    private fun activeState(tab: DesktopNavTab = DesktopNavTab.TODAY_CANVAS) = SunloomUiState(
        activeTab = tab,
        preferences = UserPreferences(
            isSetupCompleted = true,
            scheduleMode = ScheduleMode.SUNRISE_SUNSET
        )
    )

    @Test
    fun `plain N P K keys fire accelerators when the workspace root has focus`() {
        val counters = Counters()
        setApp(activeState(), counters)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("next_wallpaper_button").performKeyInput {
            pressKey(Key.N)
            pressKey(Key.P)
            pressKey(Key.K)
        }

        assertEquals(1, counters.next)
        assertEquals(1, counters.previous)
        assertEquals(1, counters.keep)
    }

    @Test
    fun `accelerators do not fire while a coordinate text field is focused`() {
        val counters = Counters()
        setApp(activeState(DesktopNavTab.DISPLAYS_AND_SCHEDULE), counters)
        composeTestRule.onNodeWithText("Custom coordinates").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("custom_lat_input").performScrollTo().performClick()

        composeTestRule.onNodeWithTag("custom_lat_input").performKeyInput {
            pressKey(Key.N)
            pressKey(Key.P)
            pressKey(Key.K)
            pressKey(Key.Delete)
        }

        assertEquals(0, counters.next)
        assertEquals(0, counters.previous)
        assertEquals(0, counters.keep)
    }

    @Test
    fun `Ctrl chords are not hijacked by accelerators`() {
        val counters = Counters()
        setApp(activeState(), counters)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("next_wallpaper_button").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.N)
            pressKey(Key.K)
            keyUp(Key.CtrlLeft)
        }

        assertEquals(0, counters.next)
        assertEquals(0, counters.keep)
    }

    @Test
    @Requirement("XII.3")
    fun `Delete dislikes immediately and relies on the undo snackbar instead of a blocking dialog`() {
        val counters = Counters()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val snapshot = kotlinx.coroutines.runBlocking { AndroidSunloomGateway(context).loadInitialSnapshot() }
        val state = snapshot.copy(
            activeTab = DesktopNavTab.TODAY_CANVAS,
            currentWallpaper = snapshot.currentWallpaper ?: snapshot.libraryWallpapers.first(),
            preferences = snapshot.preferences.copy(isSetupCompleted = true)
        )
        setApp(state, counters)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("next_wallpaper_button").performKeyInput { pressKey(Key.Delete) }
        composeTestRule.waitForIdle()

        assertEquals(1, counters.reject)
        composeTestRule.onNodeWithTag("confirm_reject_dialog_button").assertDoesNotExist()
    }

    @Test
    fun `F11 toggles fullscreen and the toolbar button does too`() {
        val counters = Counters()
        setApp(activeState(), counters)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("next_wallpaper_button").performKeyInput { pressKey(Key.F11) }
        assertEquals(1, counters.fullscreen)

        composeTestRule.onNodeWithTag("fullscreen_button").performClick()
        assertEquals(2, counters.fullscreen)
    }

    @Test
    fun `Escape exits fullscreen while fullscreen`() {
        val counters = Counters()
        setApp(activeState(), counters, isFullscreen = true)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("next_wallpaper_button").performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, counters.fullscreen)
    }

    @Test
    fun `Escape does nothing while windowed`() {
        val windowed = Counters()
        setApp(activeState(), windowed, isFullscreen = false)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("next_wallpaper_button").performKeyInput { pressKey(Key.Escape) }
        assertEquals(0, windowed.fullscreen)
    }

    @Test
    fun `focus ring colours meet 3 to 1 non-text contrast on their surfaces`() {
        val white = Color(0xFFFFFFFF)
        assertEquals(21f, contrastRatio(Color.Black, white), 0.01f)
        // Light theme primary on the light surface passes.
        assertTrue(contrastRatio(Color(0xFF0369A1), white) >= 4.5f)
        // Dark theme primary on the dark surface passes.
        assertTrue(contrastRatio(Color(0xFF38BDF8), Color(0xFF131C31)) >= 3f)
        // The checklist's suggested ring colour would fail on the light theme.
        assertFalse(contrastRatio(Color(0xFF38BDF8), white) >= 3f)
    }

    @Test
    fun `shortcut tooltip text appends the shortcut only when present`() {
        assertEquals("Next wallpaper (N)", shortcutTooltipText("Next wallpaper", "N"))
        assertEquals("Pause", shortcutTooltipText("Pause", null))
        assertEquals("Pause", shortcutTooltipText("Pause", " "))
    }

    @Test
    fun `ctrl digit shortcuts map to tabs in order and ignore other keys`() {
        assertEquals(DesktopNavTab.TODAY_CANVAS, tabForCtrlShortcut(Key.One))
        assertEquals(DesktopNavTab.RESERVE_AND_HISTORY, tabForCtrlShortcut(Key.Two))
        assertEquals(DesktopNavTab.SOURCES_AND_CATEGORIES, tabForCtrlShortcut(Key.Three))
        assertEquals(DesktopNavTab.DISPLAYS_AND_SCHEDULE, tabForCtrlShortcut(Key.Four))
        assertEquals(DesktopNavTab.SAFETY_AND_DECISION_LOG, tabForCtrlShortcut(Key.Five))
        assertEquals(null, tabForCtrlShortcut(Key.Six))
        assertEquals(null, tabForCtrlShortcut(Key.N))
    }
}

/** Article XII §4: Back steps out of a section to Home before it leaves the app, like Esc steps out of a context. */
class BackNavigationTest {

    @Test
    fun `back on any section other than Home goes to Home`() {
        for (tab in DesktopNavTab.entries.filter { it != DesktopNavTab.TODAY_CANVAS }) {
            org.junit.Assert.assertEquals(DesktopNavTab.TODAY_CANVAS, tabAfterBack(tab, workspaceReady = true))
        }
    }

    @Test
    fun `back on Home is left to the system so it can leave the app`() {
        org.junit.Assert.assertNull(tabAfterBack(DesktopNavTab.TODAY_CANVAS, workspaceReady = true))
    }

    @Test
    fun `back during loading or first-run setup is left to the system`() {
        org.junit.Assert.assertNull(tabAfterBack(DesktopNavTab.RESERVE_AND_HISTORY, workspaceReady = false))
    }
}
