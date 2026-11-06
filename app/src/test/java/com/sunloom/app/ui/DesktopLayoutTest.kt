package com.sunloom.app.ui

import android.app.Application
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.Requirement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp")
@Requirement("XII.4")
class DesktopLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun activeState() = SunloomUiState(
        activeTab = DesktopNavTab.TODAY_CANVAS,
        preferences = UserPreferences(isSetupCompleted = true, scheduleMode = ScheduleMode.SUNRISE_SUNSET)
    )

    private fun setApp(state: SunloomUiState) {
        composeTestRule.setContent {
            SunloomApp(
                uiState = state,
                onCompleteSetup = {},
                onSelectTab = {},
                onNextWallpaper = {},
                onPreviousWallpaper = {},
                onToggleKeep = {},
                onRejectForever = {},
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
                onPickLocalPhoto = {}
            )
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `width class boundaries`() {
        assertEquals(WorkspaceWidthClass.Compact, WorkspaceWidthClass.from(0.dp))
        assertEquals(WorkspaceWidthClass.Compact, WorkspaceWidthClass.from(599.dp))
        assertEquals(WorkspaceWidthClass.Medium, WorkspaceWidthClass.from(600.dp))
        assertEquals(WorkspaceWidthClass.Medium, WorkspaceWidthClass.from(839.dp))
        assertEquals(WorkspaceWidthClass.Expanded, WorkspaceWidthClass.from(840.dp))
        assertEquals(WorkspaceWidthClass.Expanded, WorkspaceWidthClass.from(2560.dp))
    }

    @Test
    fun `expanded window shows labelled rail and side by side canvas`() {
        setApp(activeState())
        composeTestRule.onNodeWithTag("workspace_nav_rail").assertExists()
        composeTestRule.onNodeWithTag("workspace_nav_bar").assertDoesNotExist()
        composeTestRule.onNodeWithText("Reserve").assertExists()
        composeTestRule.onNodeWithTag("today_side_by_side").assertExists()
    }

    @Test
    @Config(qualifiers = "w700dp-h800dp")
    fun `medium window shows icon only rail and stacks the canvas`() {
        setApp(activeState())
        composeTestRule.onNodeWithTag("workspace_nav_rail").assertExists()
        composeTestRule.onNodeWithTag("workspace_nav_bar").assertDoesNotExist()
        composeTestRule.onNodeWithText("Reserve").assertDoesNotExist()
        composeTestRule.onNodeWithTag("today_side_by_side").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w500dp-h800dp")
    fun `narrow window uses a bottom navigation bar`() {
        setApp(activeState())
        composeTestRule.onNodeWithTag("workspace_nav_bar").assertExists()
        composeTestRule.onNodeWithTag("workspace_nav_rail").assertDoesNotExist()
        composeTestRule.onNodeWithTag("next_wallpaper_button").assertExists()
    }

    @Test
    fun `no restriction notice by default`() {
        setApp(activeState())
        composeTestRule.onNodeWithTag("host_restriction_notice").assertDoesNotExist()
    }

    @Test
    fun `restricted host shows the notice and disables wallpaper changes`() {
        val base = activeState()
        setApp(base.copy(capabilities = base.capabilities.copy(isSetWallpaperAllowed = false)))
        composeTestRule.onNodeWithTag("host_restriction_notice").assertExists()
        composeTestRule.onNodeWithTag("next_wallpaper_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("restore_original_button").assertIsNotEnabled()
        // Browsing/curation controls stay available.
        composeTestRule.onNodeWithTag("keep_wallpaper_button").assertExists()
    }

    @Test
    fun `unsupported wallpaper manager also shows the notice`() {
        val base = activeState()
        setApp(base.copy(capabilities = base.capabilities.copy(isWallpaperSupported = false)))
        composeTestRule.onNodeWithTag("host_restriction_notice").assertExists()
    }

    @Test
    fun `restriction is not reported while the initial load is still running`() {
        val base = activeState()
        setApp(
            base.copy(
                isInitialLoading = true,
                capabilities = base.capabilities.copy(isSetWallpaperAllowed = false)
            )
        )
        composeTestRule.onNodeWithTag("host_restriction_notice").assertDoesNotExist()
    }
}
