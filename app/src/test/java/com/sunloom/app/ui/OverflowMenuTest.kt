package com.sunloom.app.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Right-click is not the only way in: a visible "More" button on the current wallpaper offers Save as, Copy attribution
 * and About this image to someone using touch, a keyboard or a screen reader (Constitution Article XII §4).
 */
@Requirement("XII.4")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h2000dp")
class OverflowMenuTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun show(withWallpaper: Boolean = true) {
        val loaded = runBlocking { AndroidSunloomGateway(context).loadInitialSnapshot() }
        val state = loaded.copy(
            activeTab = DesktopNavTab.TODAY_CANVAS,
            currentWallpaper = if (withWallpaper) loaded.libraryWallpapers.first() else null,
            preferences = loaded.preferences.copy(isSetupCompleted = true)
        )
        composeTestRule.setContent {
            SunloomApp(
                uiState = state,
                onCompleteSetup = {}, onSelectTab = {}, onNextWallpaper = {}, onPreviousWallpaper = {},
                onToggleKeep = {}, onRejectForever = {}, onTogglePause = {}, onRestoreOriginal = {},
                onApplySpecific = {}, onToggleLegibilityOverlay = {}, onSyncOnline = {}, onToggleCategory = {},
                onToggleSource = {}, onSelectCity = {}, onUpdateScheduleMode = {}, onUpdateLockPolicy = {},
                onToggleBlurredFill = {}, onToggleWifiOnly = {}, onPickLocalPhoto = {},
                darkTheme = false
            )
        }
    }

    @Test
    fun `the More button opens a menu with save, copy attribution and about`() {
        show()
        composeTestRule.onNodeWithTag("today_more_button").performClick()

        composeTestRule.onNodeWithTag("more_save_as").assertExists()
        composeTestRule.onNodeWithTag("more_copy_attribution").assertExists()
        composeTestRule.onNodeWithTag("more_about").assertExists()
    }

    @Test
    fun `About this image from the menu shows where the picture came from`() {
        show()
        composeTestRule.onNodeWithTag("today_more_button").performClick()
        composeTestRule.onNodeWithTag("more_about").performClick()

        composeTestRule.onNodeWithTag("wallpaper_about_dialog").assertExists()
    }

    @Test
    fun `there is nothing to open when no wallpaper is showing`() {
        show(withWallpaper = false)
        composeTestRule.onNodeWithTag("today_more_button").assertDoesNotExist()
    }
}
