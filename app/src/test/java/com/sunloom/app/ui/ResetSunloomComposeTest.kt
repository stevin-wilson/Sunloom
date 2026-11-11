package com.sunloom.app.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.R
import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Reset Sunloom erases everything and cannot be undone, so it is one deliberate step away and needs a confirmation that
 * names what will go (Constitution Article XII §3: irreversible resets require confirmation).
 */
@Requirement("XII.3")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h2000dp")
class ResetSunloomComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private var resets = 0

    private fun show() = composeTestRule.setContent {
        SunloomApp(
            uiState = SunloomUiState(
                activeTab = DesktopNavTab.SAFETY_AND_DECISION_LOG,
                preferences = UserPreferences(isSetupCompleted = true)
            ),
            onCompleteSetup = {}, onSelectTab = {}, onNextWallpaper = {}, onPreviousWallpaper = {},
            onToggleKeep = {}, onRejectForever = {}, onTogglePause = {}, onRestoreOriginal = {},
            onApplySpecific = {}, onToggleLegibilityOverlay = {}, onSyncOnline = {}, onToggleCategory = {},
            onToggleSource = {}, onSelectCity = {}, onUpdateScheduleMode = {}, onUpdateLockPolicy = {},
            onToggleBlurredFill = {}, onToggleWifiOnly = {}, onPickLocalPhoto = {},
            onResetSunloom = { resets++ },
            darkTheme = false
        )
    }

    @Test
    fun `reset asks first and does nothing until confirmed`() {
        show()

        composeTestRule.onNodeWithTag("reset_sunloom_button").performScrollTo().performClick()

        composeTestRule.onNodeWithText(context.getString(R.string.reset_sunloom_message)).assertExists()
        assertEquals("nothing is erased by asking", 0, resets)
    }

    @Test
    fun `confirming resets once`() {
        show()
        composeTestRule.onNodeWithTag("reset_sunloom_button").performScrollTo().performClick()

        composeTestRule.onNodeWithTag("confirm_reset_sunloom_button").performClick()

        assertEquals(1, resets)
        composeTestRule.onNodeWithTag("confirm_reset_sunloom_button").assertDoesNotExist()
    }

    @Test
    fun `cancelling resets nothing`() {
        show()
        composeTestRule.onNodeWithTag("reset_sunloom_button").performScrollTo().performClick()

        composeTestRule.onNodeWithText(context.getString(R.string.cancel)).performClick()

        assertEquals(0, resets)
        composeTestRule.onNodeWithTag("confirm_reset_sunloom_button").assertDoesNotExist()
    }
}
