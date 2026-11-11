package com.sunloom.app.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.labels.title
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Sunloom stays usable in a 400 dp window and with the system font at 200 percent (WCAG 1.4.4, 1.4.10): controls wrap
 * instead of running off the edge, and the bottom navigation keeps a name for every section when its labels would
 * not fit (Constitution Article XII §4). Robolectric measures text only roughly, so the exact fit is also on the
 * manual QA list and covered by screenshots.
 */
@Requirement("XII.4")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h900dp")
class SmallWindowLargeTextTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `navigation labels stay visible at normal text sizes and give way to names at large ones`() {
        assertTrue(navLabelsAlwaysVisible(1.0f))
        assertTrue(navLabelsAlwaysVisible(1.3f))
        assertFalse(navLabelsAlwaysVisible(1.5f))
        assertFalse(navLabelsAlwaysVisible(2.0f))
    }

    private var largeText = false

    private fun show(tab: DesktopNavTab) {
        val loaded = runBlocking { AndroidSunloomGateway(context).loadInitialSnapshot() }
        val state = loaded.copy(
            activeTab = tab,
            currentWallpaper = loaded.libraryWallpapers.first(),
            preferences = loaded.preferences.copy(isSetupCompleted = true)
        )
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeText) 2.0f else density.fontScale)
            ) {
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
    }

    @Test
    fun `at 200 percent text every section still has a name in the bottom navigation, as its label or its description`() {
        largeText = true
        show(DesktopNavTab.TODAY_CANVAS)
        composeTestRule.onNodeWithTag("workspace_nav_bar").assertExists()
        for (tab in DesktopNavTab.entries) {
            composeTestRule.onAllNodes(hasContentDescription(tab.title(context.resources)) or hasText(tab.title(context.resources))).fetchSemanticsNodes().also {
                assertTrue("${tab.name} has no accessible name", it.isNotEmpty())
            }
        }
    }

    private fun assertFits(tab: DesktopNavTab, tags: List<String>) {
        largeText = true
        show(tab)
        composeTestRule.waitForIdle()
        val width = composeTestRule.onNodeWithTag("workspace_nav_bar").fetchSemanticsNode().boundsInRoot.right
        for (tag in tags) {
            val bounds = composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag starts left of the window: $bounds", bounds.left >= 0f)
            assertTrue("$tag runs past the right edge ($width): $bounds", bounds.right <= width + 1f)
        }
    }

    @Test
    fun `Home controls fit a 400 dp window at 200 percent text`() = assertFits(
        DesktopNavTab.TODAY_CANVAS,
        listOf(
            "next_wallpaper_button", "previous_wallpaper_button", "restore_original_button",
            "fullscreen_button", "reject_wallpaper_button", "today_more_button"
        )
    )

    @Test
    fun `Reserve controls fit a 400 dp window at 200 percent text`() =
        assertFits(DesktopNavTab.RESERVE_AND_HISTORY, listOf("reserve_view_list", "reserve_view_grid"))

    @Test
    fun `Schedule controls fit a 400 dp window at 200 percent text`() =
        assertFits(DesktopNavTab.DISPLAYS_AND_SCHEDULE, listOf("restore_system_default_button"))
}
