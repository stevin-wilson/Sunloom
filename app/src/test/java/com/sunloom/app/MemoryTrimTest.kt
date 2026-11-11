package com.sunloom.app

import android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
import android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
import android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
import android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.ui.PreviewBitmapCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article X §2 / XII §2: the app gives memory back when the system asks. Hiding the window frees what only the screen
 * needs; real pressure, or the app moving to the background, frees every cache. They all rebuild on demand.
 */
@Requirement("XII.2")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = SunloomApplication::class)
class MemoryTrimTest {

    // --- what each level frees ---

    @Test
    fun `mild pressure while running frees nothing`() {
        assertEquals(MemoryTrim.Plan(preview = false, seedRenders = false, httpResponses = false), MemoryTrim.planFor(TRIM_MEMORY_RUNNING_MODERATE))
    }

    @Test
    fun `hiding the window frees only what the screen needed`() {
        val plan = MemoryTrim.planFor(TRIM_MEMORY_UI_HIDDEN)
        assertTrue(plan.preview)
        assertFalse("a background sync may still be using these", plan.seedRenders || plan.httpResponses)
    }

    @Test
    fun `real pressure while running frees every cache`() {
        for (level in listOf(TRIM_MEMORY_RUNNING_LOW, TRIM_MEMORY_RUNNING_CRITICAL)) {
            assertEquals("level $level", MemoryTrim.Plan(preview = true, seedRenders = true, httpResponses = true), MemoryTrim.planFor(level))
        }
    }

    @Test
    fun `being cached in the background frees every cache`() {
        for (level in listOf(TRIM_MEMORY_BACKGROUND, TRIM_MEMORY_MODERATE, TRIM_MEMORY_COMPLETE)) {
            assertEquals("level $level", MemoryTrim.Plan(preview = true, seedRenders = true, httpResponses = true), MemoryTrim.planFor(level))
        }
    }

    @Test
    fun `an unknown low level frees nothing`() {
        assertEquals(MemoryTrim.Plan(false, false, false), MemoryTrim.planFor(0))
    }

    // --- it actually frees them ---

    private fun fillPreviewCache() {
        PreviewBitmapCache.put("/x/a.jpg", 640, 480, Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888))
        assertTrue("the cache holds something", PreviewBitmapCache.sizeBytes() > 0)
    }

    @Test
    fun `trimming at the hidden level empties the preview cache`() {
        PreviewBitmapCache.clear()
        fillPreviewCache()

        MemoryTrim.trim(TRIM_MEMORY_UI_HIDDEN)

        assertEquals(0, PreviewBitmapCache.sizeBytes())
    }

    @Test
    fun `the application trims when the system asks`() {
        PreviewBitmapCache.clear()
        fillPreviewCache()
        val app = ApplicationProvider.getApplicationContext<SunloomApplication>()

        app.onTrimMemory(TRIM_MEMORY_BACKGROUND)

        assertEquals(0, PreviewBitmapCache.sizeBytes())
    }

    @Test
    fun `a mild signal leaves the caches alone`() {
        PreviewBitmapCache.clear()
        fillPreviewCache()

        MemoryTrim.trim(TRIM_MEMORY_RUNNING_MODERATE)

        assertTrue(PreviewBitmapCache.sizeBytes() > 0)
        PreviewBitmapCache.clear()
    }
}
