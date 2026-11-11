package com.sunloom.app.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.BuildConfig
import com.sunloom.app.R
import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Someone who installs Sunloom from GitHub has no store page to learn from: the app itself says what version it is, what
 * it is licensed under, what it is built from, and where the privacy policy and the code are - first on the screen,
 * not under a long log (Constitution Article XII §1).
 */
@Requirement("XII.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h2400dp")
class AboutSectionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val opened = mutableListOf<String>()

    private val recordingUris = object : UriHandler {
        override fun openUri(uri: String) { opened += uri }
    }

    private fun show() = composeTestRule.setContent {
        CompositionLocalProvider(LocalUriHandler provides recordingUris) {
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
    }

    private fun top(tag: String) = composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `the about card names the version, the build and the licence`() {
        show()

        composeTestRule.onNodeWithText(context.getString(R.string.about_sunloom)).assertExists()
        composeTestRule.onNodeWithTag("about_version").assertTextContains(BuildConfig.VERSION_NAME, substring = true)
        composeTestRule.onNodeWithTag("about_version").assertTextContains(BuildConfig.FLAVOR, substring = true)
        composeTestRule.onNodeWithText(context.getString(R.string.about_licence_line)).assertExists()
    }

    @Test
    fun `about and privacy come first, above the diagnostics`() {
        show()

        val about = top("about_card")
        val privacy = top("privacy_card")
        val diagnostics = top("diag_header")
        assertTrue("about ($about) above privacy ($privacy)", about < privacy)
        assertTrue("privacy ($privacy) above diagnostics ($diagnostics)", privacy < diagnostics)
    }

    @Test
    fun `the engineering detail sits under its own Technical details heading`() {
        show()

        composeTestRule.onNodeWithTag("diag_technical_header").performScrollTo().assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.technical_details)).assertExists()
        assertTrue("technical details come after the plain sections", top("diag_header") < top("diag_technical_header"))
    }

    @Test
    fun `the privacy policy and the source code open in the browser`() {
        show()

        composeTestRule.onNodeWithTag("about_privacy_policy").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("about_source_code").performScrollTo().performClick()

        assertEquals(listOf(ProjectLinks.PRIVACY_POLICY, ProjectLinks.SOURCE), opened)
        assertTrue(ProjectLinks.PRIVACY_POLICY.startsWith("https://github.com/stevin-wilson/Sunloom/"))
    }

    @Test
    fun `the open-source licenses are shown in the app and can be closed`() {
        show()

        composeTestRule.onNodeWithTag("about_licenses").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("licenses_text").assertExists()
        composeTestRule.onNodeWithTag("licenses_text").assertTextContains("GPL-3.0-or-later", substring = true)
        composeTestRule.onNodeWithTag("licenses_text").assertTextContains("androidx.core:core-ktx", substring = true)

        composeTestRule.onNodeWithText(context.getString(R.string.close)).performClick()
        composeTestRule.onNodeWithTag("licenses_text").assertDoesNotExist()
    }
}
