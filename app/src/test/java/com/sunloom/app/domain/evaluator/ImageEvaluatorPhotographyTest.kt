package com.sunloom.app.domain.evaluator

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.DisplayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Photographic criteria (Articles IV and V, v0.14): perceptual lightness instead of a linear mean,
 * trimming of mats and frames, a minimum visible fraction of the picture, thirds-aware framing and
 * the per-image features used for variety.
 */
@Requirement("IV.3", "IV.5")
class ImageEvaluatorPhotographyTest {

    private val evaluator = ImageEvaluator()
    private val display = DisplayProfile(0, "Laptop", 2560, 1600, densityDpi = 220)
    private val gridW = 64
    private val gridH = 40

    private fun solid(rgb: Int) = IntArray(gridW * gridH) { rgb }

    private fun evaluate(
        grid: IntArray,
        width: Int = 4000,
        height: Int = 2500,
        allowBlur: Boolean = false
    ) = evaluator.evaluateRgbGrid(width, height, grid, gridW, gridH, display, allowBlur)

    @Test
    fun `a dark scene with a modest bright patch is still classified DARK by median lightness`() {
        val grid = solid(0x0B1020)
        // 15% of the picture is lit (moon, lit towers) away from the clock and desktop zones.
        for (y in 18..29) for (x in 16..47) grid[y * gridW + x] = 0xFFFFFF
        val result = evaluate(grid)
        assertTrue(result.isEligible)
        assertEquals(BrightnessClass.DARK, result.brightnessClass)
        assertTrue("median lightness ${result.lightnessP50}", result.lightnessP50 in 0.0..20.0)
    }

    @Test
    fun `a mostly bright scene with a dark patch is LIGHT and a mid-tone scene is NEUTRAL`() {
        val mostlyLight = solid(0xD8E6F0)
        for (y in 20..27) for (x in 20..30) mostlyLight[y * gridW + x] = 0x101010
        assertEquals(BrightnessClass.LIGHT, evaluate(mostlyLight).brightnessClass)
        assertEquals(BrightnessClass.NEUTRAL, evaluate(solid(0x6A6A6A).also { it[0] = 0x6A6A6A }).brightnessClass)
    }

    @Test
    fun `desktop label contrast is reported against white labels`() {
        assertTrue(evaluate(solid(0x000000)).desktopLabelContrast > 15.0)
        assertTrue(evaluate(solid(0xFFFFFF)).desktopLabelContrast < 1.5)
    }

    @Test
    fun `warm and cool scenes get opposite warmth and sensible hue buckets`() {
        val orange = evaluate(solid(0xF0A030))
        val blue = evaluate(solid(0x4A90D9))
        assertTrue("orange warmth ${orange.warmth}", orange.warmth > 0.3)
        assertTrue("blue warmth ${blue.warmth}", blue.warmth < -0.3)
        assertEquals(1, orange.hueBucket)
        assertEquals(7, blue.hueBucket)
        assertEquals(-1, evaluate(solid(0x808080)).hueBucket)
    }

    @Test
    fun `a black frame around a bright picture does not darken what is measured`() {
        val grid = IntArray(gridW * gridH) { idx ->
            val x = idx % gridW
            when {
                x < 8 || x >= gridW - 8 -> 0x000000 // black frame, 12.5% each side
                else -> 0xB8C8D8 + ((x * 13 + idx / gridW * 7) % 24) * 0x010101 // bright, lightly textured picture
            }
        }
        val framed = evaluate(grid, width = 4000, height = 2500)
        val unframed = evaluate(IntArray(gridW * gridH) { idx -> 0xB8C8D8 + (((idx % gridW) * 13 + idx / gridW * 7) % 24) * 0x010101 }, width = 4000, height = 2500)
        assertEquals(unframed.globalLuminance, framed.globalLuminance, 0.05)
        assertEquals(BrightnessClass.LIGHT, framed.brightnessClass)
        assertTrue(framed.explanation, framed.explanation.contains("border", ignoreCase = true))
    }

    @Test
    fun `dark edges of a dark scene are content and are not trimmed`() {
        val grid = IntArray(gridW * gridH) { idx ->
            val x = idx % gridW
            if (x < 4) 0x000000 else 0x05070C // night sky: edge barely darker than the sky
        }
        val crop = evaluate(grid, width = 4000, height = 2500).cropRect!!
        assertEquals(0, crop.left)
        assertEquals(4000, crop.width)
    }

    @Test
    fun `a flat sky band along one edge is picture and not a border`() {
        val sky = IntArray(gridW * gridH) { idx ->
            val y = idx / gridW
            if (y < 8) 0x87B8F0 else 0x4A5A3A + (idx % 64) * 0x010101 // flat blue sky above textured land
        }
        val crop = evaluate(sky, width = 4000, height = 3000).cropRect!!
        assertEquals("sky must stay in the frame", 0, crop.top.coerceAtMost(0))
        val whiteSky = IntArray(gridW * gridH) { idx ->
            val y = idx / gridW
            if (y < 8) 0xF4F4F4 else 0x3A4A2A + (idx % 64) * 0x010101
        }
        val result = evaluate(whiteSky, width = 4000, height = 3000)
        assertFalse(result.explanation, result.explanation.contains("border", ignoreCase = true))
    }

    @Test
    fun `a coloured band on both sides is picture and not a mat`() {
        val grid = IntArray(gridW * gridH) { idx ->
            val x = idx % gridW
            if (x < 6 || x >= gridW - 6) 0x2255AA else 0x805030 + (idx % 64) * 0x010101
        }
        val result = evaluate(grid, width = 4000, height = 2500)
        assertFalse(result.explanation, result.explanation.contains("border", ignoreCase = true))
    }

    @Test
    fun `a portrait picture that would lose most of itself is rejected unless blurred fill is allowed`() {
        val portrait = solid(0x6B8E5A)
        val strict = evaluate(portrait, width = 3000, height = 4000)
        assertFalse(strict.isEligible)
        assertEquals("COMPOSITION_CROPS_TOO_MUCH", strict.rejectionReasonCode)

        val withFill = evaluate(portrait, width = 3000, height = 4000, allowBlur = true)
        assertTrue(withFill.isEligible)
        assertTrue(withFill.cropRect!!.usesBlurredFrameFill)

        val fourThree = evaluate(portrait, width = 4000, height = 3000)
        assertTrue(fourThree.isEligible)
    }

    @Test
    fun `framing prefers a third line for the subject over dead centre`() {
        // 6000x2400 panorama on a 16:10 display: the crop slides horizontally. A bright, detailed subject
        // sits at 70% of the width, below the clock zone.
        val grid = IntArray(gridW * gridH) { 0x203040 }
        for (y in 22..31) for (x in 41..46) grid[y * gridW + x] = if ((x + y) % 2 == 0) 0xF0F0F0 else 0x101010
        val result = evaluate(grid, width = 6000, height = 2400)
        val crop = result.cropRect!!
        val subjectX = 0.7 * 6000
        val relative = (subjectX - crop.left) / crop.width
        assertNotNull(crop)
        assertTrue("subject at $relative of the crop should sit near a third, not the centre", relative in 0.58..0.76)
    }

    @Test
    fun `the stored focal point reproduces the chosen window when the renderer centres on it`() {
        val framed = IntArray(gridW * gridH) { idx ->
            val x = idx % gridW
            if (x < 6 || x >= gridW - 6) 0x000000 else 0x305078 + ((x * 13 + idx / gridW * 7) % 40) * 0x010101
        }
        val panorama = IntArray(gridW * gridH) { 0x203040 }
        for (y in 22..31) for (x in 41..46) panorama[y * gridW + x] = if ((x + y) % 2 == 0) 0xF0F0F0 else 0x101010
        for ((grid, size) in listOf(framed to (4000 to 2500), panorama to (6000 to 2400))) {
            val crop = evaluate(grid, width = size.first, height = size.second).cropRect!!
            // The renderers place the crop from the focal point alone (GoogleBookPlatformAdapter).
            val rendered = com.sunloom.app.domain.renderer.CropRenderer().computeAspectCropBounds(
                sourceWidth = size.first, sourceHeight = size.second,
                focalX = crop.focalX, focalY = crop.focalY, targetAspect = display.aspectRatio
            )
            assertTrue("left ${rendered.left} vs ${crop.left}", kotlin.math.abs(rendered.left - crop.left) <= 2)
            assertTrue("top ${rendered.top} vs ${crop.top}", kotlin.math.abs(rendered.top - crop.top) <= 2)
        }
    }
}
