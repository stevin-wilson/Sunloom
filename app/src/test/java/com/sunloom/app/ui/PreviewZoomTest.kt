package com.sunloom.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewZoomTest {

    private val size = IntSize(800, 500)

    @Test
    fun `wheel up zooms in and wheel down zooms out`() {
        assertTrue(scrollZoomFactor(-1f) > 1f)
        assertTrue(scrollZoomFactor(1f) < 1f)
        assertEquals(1f, scrollZoomFactor(0f), 0f)
    }

    @Test
    fun `zoom is clamped between 1x and 5x`() {
        var s = ZoomState()
        repeat(40) { s = zoomAround(s, 1.5f, Offset(400f, 250f), size) }
        assertEquals(MAX_PREVIEW_ZOOM, s.scale, 0f)
        repeat(60) { s = zoomAround(s, 0.5f, Offset(400f, 250f), size) }
        assertEquals(ZoomState(), s)
    }

    @Test
    fun `zooming keeps the point under the cursor fixed`() {
        val focal = Offset(600f, 100f)
        val s = zoomAround(ZoomState(), 2f, focal, size)
        // content point under the cursor before: focal; after: (focal - t) / scale must equal focal
        val contentPoint = (focal - s.translation) / s.scale
        assertEquals(focal.x, contentPoint.x, 0.01f)
        assertEquals(focal.y, contentPoint.y, 0.01f)
    }

    @Test
    fun `panning never reveals empty space past the edges`() {
        val zoomed = zoomAround(ZoomState(), 3f, Offset(0f, 0f), size)
        val far = panBy(zoomed, Offset(5000f, 5000f), size)
        assertEquals(0f, far.translation.x, 0f)
        assertEquals(0f, far.translation.y, 0f)
        val other = panBy(zoomed, Offset(-5000f, -5000f), size)
        assertEquals(size.width * (1f - 3f), other.translation.x, 0.01f)
        assertEquals(size.height * (1f - 3f), other.translation.y, 0.01f)
    }

    @Test
    fun `panning at 1x does nothing so the page can scroll`() {
        assertEquals(ZoomState(), panBy(ZoomState(), Offset(40f, 40f), size))
    }

    @Test
    fun `zoom keys map only with ctrl`() {
        assertEquals(ZoomKeyAction.IN, zoomKeyActionFor(Key.Equals, ctrl = true))
        assertEquals(ZoomKeyAction.IN, zoomKeyActionFor(Key.NumPadAdd, ctrl = true))
        assertEquals(ZoomKeyAction.OUT, zoomKeyActionFor(Key.Minus, ctrl = true))
        assertEquals(ZoomKeyAction.RESET, zoomKeyActionFor(Key.Zero, ctrl = true))
        assertEquals(null, zoomKeyActionFor(Key.Equals, ctrl = false))
        assertEquals(null, zoomKeyActionFor(Key.A, ctrl = true))
    }

    @Test
    fun `keyboard zoom steps about the centre and resets`() {
        val zoomed = applyZoomKey(ZoomState(), ZoomKeyAction.IN, size)
        assertEquals(KEY_ZOOM_STEP, zoomed.scale, 0.0001f)
        val centre = Offset(size.width / 2f, size.height / 2f)
        val contentPoint = (centre - zoomed.translation) / zoomed.scale
        assertEquals(centre.x, contentPoint.x, 0.01f)
        assertEquals(centre.y, contentPoint.y, 0.01f)
        assertEquals(ZoomState(), applyZoomKey(zoomed, ZoomKeyAction.OUT, size))
        assertEquals(ZoomState(), applyZoomKey(zoomed, ZoomKeyAction.RESET, size))
    }

    @Test
    fun `arrow keys produce pan deltas and other keys do not`() {
        assertTrue(panDeltaForKey(Key.DirectionLeft, size)!!.x > 0f)
        assertTrue(panDeltaForKey(Key.DirectionDown, size)!!.y < 0f)
        assertEquals(null, panDeltaForKey(Key.Enter, size))
    }
}
