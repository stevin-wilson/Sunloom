package com.sunloom.app.update

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
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
import com.sunloom.app.ui.DesktopNavTab
import com.sunloom.app.ui.SunloomApp
import com.sunloom.app.ui.SunloomUiState
import com.sunloom.app.ui.UpdateCheckUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The GitHub build shows "Check for updates" in About, and reports what it found in plain words. */
@Requirement("XVIII.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h2400dp")
class AboutUpdateGithubTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var checks = 0
    private val opened = mutableListOf<String>()

    private fun show(update: UpdateCheckUiState) = composeTestRule.setContent {
        CompositionLocalProvider(LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { opened += uri } }) {
            SunloomApp(
                uiState = SunloomUiState(
                    activeTab = DesktopNavTab.SAFETY_AND_DECISION_LOG,
                    preferences = UserPreferences(isSetupCompleted = true),
                    updateCheck = update
                ),
                onCompleteSetup = {}, onSelectTab = {}, onNextWallpaper = {}, onPreviousWallpaper = {},
                onToggleKeep = {}, onRejectForever = {}, onTogglePause = {}, onRestoreOriginal = {},
                onApplySpecific = {}, onToggleLegibilityOverlay = {}, onSyncOnline = {}, onToggleCategory = {},
                onToggleSource = {}, onSelectCity = {}, onUpdateScheduleMode = {}, onUpdateLockPolicy = {},
                onToggleBlurredFill = {}, onToggleWifiOnly = {}, onPickLocalPhoto = {},
                onCheckForUpdates = { checks++ },
                darkTheme = false
            )
        }
    }

    @Test
    fun `the button is there and asks for a check`() {
        show(UpdateCheckUiState.Idle)

        composeTestRule.onNodeWithTag("about_check_updates").performScrollTo().performClick()

        assertEquals(1, checks)
    }

    @Test
    fun `an available update names the version and links to its page`() {
        show(UpdateCheckUiState.Done(UpdateCheckResult.Available("2.1.0", "https://github.com/stevin-wilson/Sunloom/releases/tag/v2.1.0")))

        composeTestRule.onNodeWithText(context.getString(R.string.update_available, "2.1.0")).assertExists()
        composeTestRule.onNodeWithTag("about_view_release").performScrollTo().performClick()

        assertEquals(listOf("https://github.com/stevin-wilson/Sunloom/releases/tag/v2.1.0"), opened)
    }

    @Test
    fun `being up to date and a failed check are each said plainly`() {
        show(UpdateCheckUiState.Done(UpdateCheckResult.UpToDate("2.0.0")))
        composeTestRule.onNodeWithText(context.getString(R.string.update_up_to_date, "2.0.0")).assertExists()
    }

    @Test
    fun `a failed check says it could not be done, not that Sunloom is up to date`() {
        show(UpdateCheckUiState.Done(UpdateCheckResult.Failed))
        composeTestRule.onNodeWithText(context.getString(R.string.update_check_failed)).assertExists()
    }

    @Test
    fun `while checking the button is replaced by a progress message`() {
        show(UpdateCheckUiState.Checking)
        composeTestRule.onNodeWithText(context.getString(R.string.update_checking)).assertExists()
    }
}

/** The GitHub build offers the weekly update check once, on the last step of setup, and off by default. */
@Requirement("XVIII.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h2400dp")
class SetupUpdateOptInGithubTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var completed: UserPreferences? = null

    private fun toLastStep() {
        composeTestRule.setContent {
            SunloomApp(
                uiState = SunloomUiState(preferences = UserPreferences(isSetupCompleted = false)),
                onCompleteSetup = { completed = it }, onSelectTab = {}, onNextWallpaper = {}, onPreviousWallpaper = {},
                onToggleKeep = {}, onRejectForever = {}, onTogglePause = {}, onRestoreOriginal = {},
                onApplySpecific = {}, onToggleLegibilityOverlay = {}, onSyncOnline = {}, onToggleCategory = {},
                onToggleSource = {}, onSelectCity = {}, onUpdateScheduleMode = {}, onUpdateLockPolicy = {},
                onToggleBlurredFill = {}, onToggleWifiOnly = {}, onPickLocalPhoto = {}, darkTheme = false
            )
        }
        composeTestRule.onNodeWithTag("setup_customize_button").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("setup_next_button").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("setup_next_button").performScrollTo().performClick()
    }

    @Test
    fun `the last step offers it, off, and what is chosen is saved`() {
        toLastStep()

        composeTestRule.onNodeWithTag("setup_update_opt_in").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("enable_sunloom_button").performScrollTo().performClick()

        assertTrue(completed!!.weeklyUpdateCheck)
    }

    @Test
    fun `left alone it stays off`() {
        toLastStep()

        composeTestRule.onNodeWithTag("enable_sunloom_button").performScrollTo().performClick()

        assertFalse(completed!!.weeklyUpdateCheck)
    }
}
