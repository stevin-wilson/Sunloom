package com.sunloom.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.data.sources.DefaultHttpsClient
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.LockWorkspacePolicy
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.StringReader

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class EfficiencyTest {

    @Test
    fun `sample size shrinks to the requested box, not a fixed hero size`() {
        assertEquals(1, PreviewBitmapCache.sampleSizeFor(800, 500, 960, 600))
        assertEquals(4, PreviewBitmapCache.sampleSizeFor(3840, 2400, 960, 600))
        // A 120dp thumbnail decode target needs a much coarser sample than the hero preview.
        assertTrue(
            PreviewBitmapCache.sampleSizeFor(3840, 2400, 480, 480) >
                PreviewBitmapCache.sampleSizeFor(3840, 2400, 960, 600)
        )
    }

    @Test
    fun `preview cache is keyed by size and bounded by bytes`() {
        PreviewBitmapCache.clear()
        val small = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        PreviewBitmapCache.put("/a.png", 480, 480, small)
        assertNotNull(PreviewBitmapCache.get("/a.png", 480, 480))
        assertNull(PreviewBitmapCache.get("/a.png", 960, 600))
        assertNotEquals(PreviewBitmapCache.key("/a.png", 480, 480), PreviewBitmapCache.key("/a.png", 960, 600))

        // ~4 MB each: well past the 24 MB budget after 10 inserts.
        repeat(10) { i ->
            PreviewBitmapCache.put("/big$i.png", 960, 600, Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888))
        }
        assertTrue(PreviewBitmapCache.sizeBytes() <= PreviewBitmapCache.MAX_BYTES)
        PreviewBitmapCache.clear()
    }

    @Test
    fun `light preference edits do not require a reserve reconcile`() {
        val base = UserPreferences()
        assertFalse(base.copy(scheduleMode = ScheduleMode.FIXED_CLOCK).affectsReserve(base))
        assertFalse(base.copy(isPaused = true, wifiOnlyDownloads = true).affectsReserve(base))
        assertFalse(base.copy(latitude = 10.0, longitude = 20.0, selectedCityName = "X").affectsReserve(base))
        assertFalse(base.copy(lockWorkspacePolicy = LockWorkspacePolicy.WORKSPACE_ONLY).affectsReserve(base))
        assertFalse(base.copy(deferOnBatterySaver = false).affectsReserve(base))
    }

    @Test
    fun `reserve-affecting preference edits trigger the reconcile`() {
        val base = UserPreferences()
        assertTrue(base.copy(enabledCategories = setOf(SafeCategory.LANDSCAPES_NATURE)).affectsReserve(base))
        assertTrue(base.copy(enabledSources = setOf("seed_reserve")).affectsReserve(base))
        assertTrue(base.copy(displayPolicy = DisplayPolicy.OPTIMIZE_PRIMARY_SHARPEST).affectsReserve(base))
        assertTrue(base.copy(allowBlurredFrameFill = true).affectsReserve(base))
        assertTrue(base.copy(maxCacheBytes = 100L * 1024 * 1024).affectsReserve(base))
        assertTrue(base.copy(isSetupCompleted = true).affectsReserve(base))
    }

    @Test
    fun `bounded text reader accepts small and rejects oversized bodies`() {
        assertEquals("hello", DefaultHttpsClient.readTextBounded(StringReader("hello"), 10))
        val result = runCatching { DefaultHttpsClient.readTextBounded(StringReader("x".repeat(100)), 10) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
