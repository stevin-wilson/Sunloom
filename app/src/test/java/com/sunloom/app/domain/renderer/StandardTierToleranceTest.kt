package com.sunloom.app.domain.renderer

import com.sunloom.app.Requirement
import com.sunloom.app.domain.evaluator.ImageEvaluator
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.DisplayQualityTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article IV §2 (v0.15): the strict tier (< 120 ppi) asks for 98% pixel coverage, not 100%. A source whose largest
 * rendition is a few pixels short of the display (Cleveland's prints are 3400 px wide, a 3440 px ultrawide needs
 * 3440) is enlarged by at most 2%, which cannot be seen, instead of being turned away entirely.
 */
@Requirement("IV.2")
class StandardTierToleranceTest {

    private val ultrawide = DisplayProfile(0, "3440x1440", 3440, 1440, densityDpi = 149)
    private val gridW = 32
    private val gridH = 20
    private fun grid(rgb: Int = 0x6A7A8A) = IntArray(gridW * gridH) { rgb }

    private fun evaluate(w: Int, h: Int) =
        ImageEvaluator().evaluateRgbGrid(w, h, grid(), gridW, gridH, ultrawide, false)

    @Test
    fun `the ultrawide is in the strict tier`() {
        assertEquals(DisplayQualityTier.STANDARD_100, ultrawide.qualityTier)
    }

    @Test
    fun `the strict tier accepts 98 percent coverage and refuses less`() {
        assertTrue(CropRenderer.meetsNativeQualityTier(1.0, 3440, ultrawide))
        assertTrue(CropRenderer.meetsNativeQualityTier(0.988, 3400, ultrawide))
        assertTrue(CropRenderer.meetsNativeQualityTier(0.98, 3371, ultrawide))
        assertFalse(CropRenderer.meetsNativeQualityTier(0.975, 3354, ultrawide))
        assertFalse(CropRenderer.meetsNativeQualityTier(0.959, 3300, ultrawide))
    }

    @Test
    fun `the other tiers are unchanged`() {
        val mid = DisplayProfile(0, "mid", 2560, 1600, densityDpi = 200)
        val high = DisplayProfile(0, "high", 3840, 2160, densityDpi = 240)
        assertEquals(DisplayQualityTier.MID_80, mid.qualityTier)
        assertEquals(DisplayQualityTier.HIGH_65, high.qualityTier)
        assertTrue(CropRenderer.meetsNativeQualityTier(0.80, 2048, mid))
        assertFalse(CropRenderer.meetsNativeQualityTier(0.79, 2023, mid))
        assertTrue(CropRenderer.meetsNativeQualityTier(0.65, 2496, high))
        assertFalse(CropRenderer.meetsNativeQualityTier(0.64, 2457, high))
    }

    @Test
    fun `a 3400 px print is usable on a 3440 px ultrawide but a 3300 px one is not`() {
        assertTrue(CropRenderer.canSourceSatisfyDisplay(3400, ultrawide, false))
        assertFalse(CropRenderer.canSourceSatisfyDisplay(3300, ultrawide, false))
        assertNull(CropRenderer.advertisedSizeRejection(3400, 2200, ultrawide, false))
        assertEquals("INSUFFICIENT_ADVERTISED_RESOLUTION", CropRenderer.advertisedSizeRejection(3300, 2200, ultrawide, false))
    }

    @Test
    fun `the evaluator admits the 3400 px print and says which floor it passed`() {
        val result = evaluate(3400, 2200)
        assertTrue(result.explanation, result.isEligible)
        assertTrue(result.explanation, result.explanation.contains("passes >= 98% native floor"))
        assertFalse("must not borrow the high-DPI wording: ${result.explanation}", result.explanation.contains("65%"))
        assertFalse("must not claim 100%: ${result.explanation}", result.explanation.contains("100% native pixel coverage"))
    }

    @Test
    fun `the evaluator still rejects a source below the floor and names the 98 percent floor`() {
        val result = evaluate(3300, 2200)
        assertFalse(result.isEligible)
        assertEquals("INSUFFICIENT_POST_CROP_RESOLUTION", result.rejectionReasonCode)
        assertTrue(result.explanation, result.explanation.contains("98% native pixel floor"))
    }

    @Test
    fun `a source at full coverage still reports 100 percent`() {
        val result = evaluate(3440, 2200)
        assertTrue(result.explanation.contains("100% native pixel coverage"))
    }
}
