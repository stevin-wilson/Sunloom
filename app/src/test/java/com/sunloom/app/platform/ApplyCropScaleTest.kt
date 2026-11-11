package com.sunloom.app.platform

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.renderer.CropRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the apply path hands to the system (Article IV §4, XII §2): exactly the display's size, uniformly scaled from a
 * crop of the picture, and the source is left for the caller to release. These pin the result so the cropping and scaling
 * can be done in a single pass (one bitmap fewer alive at the peak) without changing it.
 */
@Requirement("IV.4")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ApplyCropScaleTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val adapter get() = GoogleBookPlatformAdapter(context)

    private fun source(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF336699.toInt()) }

    @Test
    fun `a large photo is cropped to the display shape and scaled to exactly the display size`() {
        val src = source(6000, 4000)
        val display = DisplayProfile(0, "4K", 3840, 2160, densityDpi = 220)

        val out = adapter.prepareNonStretchedBitmap(src, display)

        assertEquals(3840, out.width)
        assertEquals(2160, out.height)
        assertNotSame("the source is the caller's to release", src, out)
        assertTrue("the source is not recycled by preparing", !src.isRecycled)
    }

    @Test
    fun `a photo already smaller than the display is cropped but not enlarged`() {
        val src = source(1686, 1124)
        val display = DisplayProfile(0, "Retina", 2560, 1600, densityDpi = 220)

        val out = adapter.prepareNonStretchedBitmap(src, display)

        assertTrue(out.width <= 1686 && out.height <= 1124)
        assertEquals(display.aspectRatio, out.width.toDouble() / out.height, 0.01)
    }

    @Test
    fun `sizes that do not divide evenly still come out exact`() {
        for ((sw, sh, dw, dh) in listOf(
            listOf(5472, 3648, 3440, 1440), listOf(4001, 6003, 2560, 1600), listOf(7000, 3333, 5120, 1440), listOf(9999, 7777, 2160, 3840)
        )) {
            val out = adapter.prepareNonStretchedBitmap(source(sw, sh), DisplayProfile(0, "d", dw, dh, densityDpi = 200))
            assertEquals("$sw x $sh -> $dw x $dh width", dw, out.width)
            assertEquals("$sw x $sh -> $dw x $dh height", dh, out.height)
        }
    }

    @Test
    fun `a persisted focal point still steers the crop`() {
        val src = source(6000, 4000)
        val display = DisplayProfile(0, "Wide", 3440, 1440, densityDpi = 200)
        val focal = CropRect(0, 0, 6000, 4000, 0.2, 0.8, false, 1.0)

        val out = adapter.prepareNonStretchedBitmap(src, display, cropRect = focal)

        assertEquals(3440, out.width)
        assertEquals(1440, out.height)
        val bounds = CropRenderer().computeAspectCropBounds(6000, 4000, 0.2, 0.8, display.aspectRatio)
        assertEquals("the crop follows the focal point, not the centre", true, bounds.top > 4000 / 2 - bounds.height / 2)
    }
}
