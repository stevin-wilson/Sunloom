package com.sunloom.app.platform

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.renderer.CropRenderer
import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Golden check for Article IV §4: cropping and scaling in one pass (one bitmap fewer alive at the peak) must give the
 * same pixels as the earlier two-step crop-then-scale. A high-frequency image shows any difference in the filter.
 */
@RunWith(AndroidJUnit4::class)
class ApplyPipelineEquivalenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Fine checks plus deterministic noise: every pixel differs from its neighbours, so a resampling change shows. */
    private fun busyImage(w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h)
        var seed = 12345L
        for (y in 0 until h) for (x in 0 until w) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val noise = ((seed ushr 33) and 0x3F).toInt()
            val check = if (((x / 3) + (y / 3)) % 2 == 0) 40 else 200
            val v = (check + noise).coerceIn(0, 255)
            pixels[y * w + x] = (0xFF shl 24) or (v shl 16) or ((255 - v) shl 8) or ((v * 7) and 0xFF)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun maxChannelDiff(a: Bitmap, b: Bitmap, border: Int): Int {
        var worst = 0
        for (y in border until a.height - border) for (x in border until a.width - border) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            for (shift in intArrayOf(0, 8, 16)) {
                worst = max(worst, abs(((p ushr shift) and 0xFF) - ((q ushr shift) and 0xFF)))
            }
        }
        return worst
    }

    @Test
    fun singlePassCropAndScaleMatchesCropThenScale() {
        val source = busyImage(3000, 2000)
        val display = DisplayProfile(0, "Full HD", 1920, 1080, densityDpi = 160)
        val bounds = CropRenderer().computeAspectCropBounds(3000, 2000, 0.5, 0.5, display.aspectRatio)

        val oldWay = Bitmap.createBitmap(source, bounds.left, bounds.top, bounds.width, bounds.height).scale(1920, 1080)
        val newWay = GoogleBookPlatformAdapter(context).prepareNonStretchedBitmap(source, display)

        assertEquals(oldWay.width, newWay.width)
        assertEquals(oldWay.height, newWay.height)
        // The outermost pixels may sample just outside the crop; the interior must match to rounding.
        val diff = maxChannelDiff(oldWay, newWay, border = 2)
        assertTrue("single-pass differs from two-step by $diff levels", diff <= 1)
    }
}
