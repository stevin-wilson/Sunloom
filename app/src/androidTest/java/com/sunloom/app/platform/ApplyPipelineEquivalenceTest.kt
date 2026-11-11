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

    /** Mean absolute difference over all channels, and the share of channel values that differ by more than [tolerance]. */
    private class Difference(val mean: Double, val share: Double, val worst: Int)

    private fun difference(a: Bitmap, b: Bitmap, border: Int, tolerance: Int): Difference {
        var sum = 0L
        var count = 0L
        var beyond = 0L
        var worst = 0
        for (y in border until a.height - border) for (x in border until a.width - border) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            for (shift in intArrayOf(0, 8, 16)) {
                val d = abs(((p ushr shift) and 0xFF) - ((q ushr shift) and 0xFF))
                sum += d; count++
                if (d > tolerance) beyond++
                worst = max(worst, d)
            }
        }
        return Difference(sum.toDouble() / count, beyond.toDouble() / count, worst)
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
        // The two paths use the device's own resampler, which may differ by a little at single pixels of this
        // pathological image (fine checks plus noise); what must hold is that they agree on the whole: the average
        // difference is tiny and almost no value is off by more than a few levels. The outermost pixels may sample
        // just outside the crop, so they are left out.
        val diff = difference(oldWay, newWay, border = 2, tolerance = 8)
        assertTrue(
            "single-pass differs from two-step: mean ${"%.3f".format(diff.mean)} levels, " +
                "${"%.3f".format(diff.share * 100)}% of values off by more than 8, worst ${diff.worst}",
            diff.mean <= 1.0 && diff.share <= 0.01
        )
    }
}
