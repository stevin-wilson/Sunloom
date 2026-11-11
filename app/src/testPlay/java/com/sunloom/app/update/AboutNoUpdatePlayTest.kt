package com.sunloom.app.update

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.ui.DesktopNavTab
import com.sunloom.app.ui.SunloomApp
import com.sunloom.app.ui.SunloomUiState
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Play build is updated by the store: it has no update button and no code that could ask GitHub. */
@Requirement("XVIII.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h2400dp")
class AboutNoUpdatePlayTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `there is no check for updates in the Play build`() {
        composeTestRule.setContent {
            SunloomApp(
                uiState = SunloomUiState(activeTab = DesktopNavTab.SAFETY_AND_DECISION_LOG, preferences = UserPreferences(isSetupCompleted = true)),
                onCompleteSetup = {}, onSelectTab = {}, onNextWallpaper = {}, onPreviousWallpaper = {},
                onToggleKeep = {}, onRejectForever = {}, onTogglePause = {}, onRestoreOriginal = {},
                onApplySpecific = {}, onToggleLegibilityOverlay = {}, onSyncOnline = {}, onToggleCategory = {},
                onToggleSource = {}, onSelectCity = {}, onUpdateScheduleMode = {}, onUpdateLockPolicy = {},
                onToggleBlurredFill = {}, onToggleWifiOnly = {}, onPickLocalPhoto = {},
                darkTheme = false
            )
        }

        composeTestRule.onNodeWithTag("about_check_updates").assertDoesNotExist()
    }

    @Test
    fun `the Play build's hook offers no feature`() {
        assertNull(updateCheckFeature())
    }
}
