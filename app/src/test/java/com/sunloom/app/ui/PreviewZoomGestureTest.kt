package com.sunloom.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article XII §4(m): the Today preview zooms with Ctrl and the wheel or with a pinch, and a double click puts it back.
 * `PreviewZoomTest` checks the arithmetic; this checks that the gestures reach it.
 */
@OptIn(ExperimentalTestApi::class)
@Requirement("XII.4.m")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp")
class PreviewZoomGestureTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val zoom = mutableStateOf(ZoomState())

    private fun setPreview() {
        composeTestRule.setContent {
            Box(Modifier.size(400.dp, 300.dp).testTag("preview").zoomablePreview(zoom))
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `Ctrl and the wheel zoom in and out, and the wheel alone does nothing`() {
        setPreview()
        composeTestRule.onNodeWithTag("preview").performMouseInput { scroll(-3f) }
        assertEquals("the wheel alone leaves the page to scroll", 1f, zoom.value.scale, 0f)

        composeTestRule.onNodeWithTag("preview").performKeyInput { keyDown(Key.CtrlLeft) }
        composeTestRule.onNodeWithTag("preview").performMouseInput { scroll(-3f) }
        val zoomedIn = zoom.value.scale
        assertTrue("Ctrl and wheel up zooms in: $zoomedIn", zoomedIn > 1f)

        composeTestRule.onNodeWithTag("preview").performMouseInput { scroll(3f) }
        composeTestRule.onNodeWithTag("preview").performKeyInput { keyUp(Key.CtrlLeft) }
        assertTrue("wheel down zooms back out: ${zoom.value.scale}", zoom.value.scale < zoomedIn)
    }

    @Test
    fun `two fingers pinching apart zoom in`() {
        setPreview()
        composeTestRule.onNodeWithTag("preview").performTouchInput {
            pinch(
                start0 = Offset(centerX - 20f, centerY), end0 = Offset(centerX - 120f, centerY),
                start1 = Offset(centerX + 20f, centerY), end1 = Offset(centerX + 120f, centerY)
            )
        }
        assertTrue("a pinch outwards zooms in: ${zoom.value.scale}", zoom.value.scale > 1f)
    }

    @Test
    fun `a double click puts a zoomed preview back to its normal size`() {
        zoom.value = ZoomState(scale = 3f)
        setPreview()
        composeTestRule.onNodeWithTag("preview").performMouseInput { doubleClick() }
        composeTestRule.waitForIdle()
        assertEquals(ZoomState(), zoom.value)
    }
}
