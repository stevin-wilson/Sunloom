package com.sunloom.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Covers the last desktop-badge gaps: Today max width, apply retry, keyboard zoom, no-browser notice. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w2600dp-h1000dp")
class DesktopGapsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun state(retryId: String? = null) = SunloomUiState(
        activeTab = DesktopNavTab.TODAY_CANVAS,
        preferences = UserPreferences(isSetupCompleted = true, scheduleMode = ScheduleMode.SUNRISE_SUNSET),
        retryApplyRecordId = retryId
    )

    private fun setApp(state: SunloomUiState, onApplySpecific: (String) -> Unit = {}) {
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
                onApplySpecific = onApplySpecific,
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
    fun `today content is capped on an ultra wide window`() {
        setApp(state())
        val bounds = composeTestRule.onNodeWithTag("today_side_by_side").getUnclippedBoundsInRoot()
        val width = bounds.right - bounds.left
        assertTrue("content was $width", width <= TodayCanvasMaxWidth)
        assertTrue("content was $width", width > TodayCanvasStackBreakpoint)
    }

    @Test
    fun `retry button appears only after a retryable failure and re-applies that record`() {
        val applied = mutableListOf<String>()
        setApp(state(retryId = "rec-7"), onApplySpecific = { applied += it })
        composeTestRule.onNodeWithTag("retry_apply_button").performClick()
        assertEquals(listOf("rec-7"), applied)
    }

    @Test
    fun `no retry button without a failed apply`() {
        setApp(state())
        composeTestRule.onNodeWithTag("retry_apply_button").assertDoesNotExist()
    }

    @Test
    fun `missing browser is reported instead of failing silently`() {
        val messages = mutableListOf<String>()
        val failing = object : UriHandler {
            override fun openUri(uri: String) = throw android.content.ActivityNotFoundException("no browser")
        }
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalUriHandler provides failing,
                LocalWallpaperActions provides WallpaperActions(showMessage = { messages += it })
            ) {
                ProvenanceLinkRow(label = "Source page", displayText = "example.org", url = "https://example.org/a")
            }
        }
        composeTestRule.onNodeWithTag("source_page_link").performClick()
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("browser", ignoreCase = true))
    }

    @Test
    fun `keyboard zooms the focused preview in out and resets`() {
        val zoom = mutableStateOf(ZoomState())
        composeTestRule.setContent {
            Box(modifier = Modifier.size(400.dp, 250.dp).zoomablePreview(zoom).testTag("zoom_box")) {
                Box(modifier = Modifier.fillMaxSize().applyZoom(zoom.value))
            }
        }
        composeTestRule.onNodeWithTag("zoom_box").requestFocus()
        composeTestRule.onNodeWithTag("zoom_box").performKeyInput {
            keyDown(Key.CtrlLeft); keyDown(Key.Equals); keyUp(Key.Equals); keyUp(Key.CtrlLeft)
        }
        assertEquals(KEY_ZOOM_STEP, zoom.value.scale, 0.001f)
        composeTestRule.onNodeWithTag("zoom_box").performKeyInput {
            keyDown(Key.CtrlLeft); keyDown(Key.Minus); keyUp(Key.Minus); keyUp(Key.CtrlLeft)
        }
        assertEquals(1f, zoom.value.scale, 0.001f)
        composeTestRule.onNodeWithTag("zoom_box").performKeyInput {
            keyDown(Key.CtrlLeft); keyDown(Key.Equals); keyUp(Key.Equals)
            keyDown(Key.Equals); keyUp(Key.Equals); keyUp(Key.CtrlLeft)
        }
        assertTrue(zoom.value.scale > KEY_ZOOM_STEP)
        composeTestRule.onNodeWithTag("zoom_box").performKeyInput {
            keyDown(Key.CtrlLeft); keyDown(Key.Zero); keyUp(Key.Zero); keyUp(Key.CtrlLeft)
        }
        assertEquals(ZoomState(), zoom.value)
    }
}
