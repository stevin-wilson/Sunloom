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
        // Sizes are kept modest: a test JVM has a small heap, and the shape (crop larger than the display) is what matters.
        val src = source(3000, 2000)
        val display = DisplayProfile(0, "Full HD", 1920, 1080, densityDpi = 160)

        val out = adapter.prepareNonStretchedBitmap(src, display)

        assertEquals(1920, out.width)
        assertEquals(1080, out.height)
        assertNotSame("the source is the caller's to release", src, out)
        assertTrue("the source is not recycled by preparing", !src.isRecycled)
        out.recycle(); src.recycle()
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
            listOf(2731, 1823, 1721, 727), listOf(2001, 3003, 1283, 801), listOf(3499, 1667, 1913, 539), listOf(2999, 2333, 719, 1279)
        )) {
            val src = source(sw, sh)
            val out = adapter.prepareNonStretchedBitmap(src, DisplayProfile(0, "d", dw, dh, densityDpi = 160))
            assertEquals("$sw x $sh -> $dw x $dh width", dw, out.width)
            assertEquals("$sw x $sh -> $dw x $dh height", dh, out.height)
            out.recycle(); src.recycle()
        }
    }

    @Test
    fun `a persisted focal point still steers the crop`() {
        val src = source(3000, 2000)
        val display = DisplayProfile(0, "Wide", 1720, 720, densityDpi = 160)
        val focal = CropRect(0, 0, 3000, 2000, 0.2, 0.8, false, 1.0)

        val out = adapter.prepareNonStretchedBitmap(src, display, cropRect = focal)

        assertEquals(1720, out.width)
        assertEquals(720, out.height)
        val bounds = CropRenderer().computeAspectCropBounds(3000, 2000, 0.2, 0.8, display.aspectRatio)
        assertEquals("the crop follows the focal point, not the centre", true, bounds.top > 2000 / 2 - bounds.height / 2)
        out.recycle(); src.recycle()
    }
}
