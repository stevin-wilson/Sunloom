package com.sunloom.app.domain.evaluator

import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.DisplayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImageEvaluatorTest {

    private lateinit var evaluator: ImageEvaluator
    private val laptopDisplay = DisplayProfile(
        displayId = 0,
        name = "GoogleBook Built-in Display",
        widthPx = 2560,
        heightPx = 1600
    )

    @Before
    fun setUp() {
        evaluator = ImageEvaluator()
    }

    @Test
    fun `evaluateRgbGrid classifies calm dark image as DARK when global and clock zones are dark`() {
        val gridW = 32
        val gridH = 20
        // Deep twilight navy (0x141E30)
        val grid = IntArray(gridW * gridH) { 0x141E30 }

        val result = evaluator.evaluateRgbGrid(
            imageWidth = 3840,
            imageHeight = 2400,
            rgbGrid = grid,
            gridWidth = gridW,
            gridHeight = gridH,
            display = laptopDisplay
        )

        assertTrue(result.isEligible)
        assertEquals(BrightnessClass.DARK, result.brightnessClass)
        assertTrue(result.globalLuminance < 0.15)
        assertTrue(result.lockClockZoneLuminance < 0.15)
        assertEquals(0.0, result.glareFractionInSafeZones, 0.001)
        assertNotNull(result.cropRect)
        assertFalse(result.cropRect!!.usesBlurredFrameFill)
    }

    @Test
    fun `evaluateRgbGrid classifies airy daytime sky image as LIGHT`() {
        val gridW = 32
        val gridH = 20
        // Soft morning sky blue (0xCBE4F9)
        val grid = IntArray(gridW * gridH) { 0xCBE4F9 }

        val result = evaluator.evaluateRgbGrid(
            imageWidth = 3840,
            imageHeight = 2400,
            rgbGrid = grid,
            gridWidth = gridW,
            gridHeight = gridH,
            display = laptopDisplay
        )

        assertTrue(result.isEligible)
        assertEquals(BrightnessClass.LIGHT, result.brightnessClass)
        assertTrue(result.globalLuminance > 0.60)
    }

    @Test
    fun `evaluateRgbGrid prevents dark image with harsh white glare in clock zone from being classified DARK`() {
        val gridW = 32
        val gridH = 20
        val grid = IntArray(gridW * gridH) { 0x10141A }
        // Inject harsh white sun glare (0xFFFFFF) directly inside the lock-screen clock zone
        for (y in 2..7) {
            for (x in 8..24) {
                grid[y * gridW + x] = 0xFFFFFF
            }
        }

        val result = evaluator.evaluateRgbGrid(
            imageWidth = 3840,
            imageHeight = 2400,
            rgbGrid = grid,
            gridWidth = gridW,
            gridHeight = gridH,
            display = laptopDisplay
        )

        assertTrue(result.isEligible)
        assertTrue("Glare in clock zone should disqualify image from DARK class", result.brightnessClass != BrightnessClass.DARK)
        assertTrue(result.glareFractionInSafeZones > 0.10)
    }

    @Test
    fun `evaluateRgbGrid rejects image below 100 percent native resolution when blurred fill is disabled`() {
        val grid = IntArray(16 * 10) { 0x223344 }
        val result = evaluator.evaluateRgbGrid(
            imageWidth = 1920,
            imageHeight = 1080,
            rgbGrid = grid,
            gridWidth = 16,
            gridHeight = 10,
            display = laptopDisplay,
            allowBlurredFrameFill = false
        )

        assertFalse(result.isEligible)
        assertEquals("INSUFFICIENT_POST_CROP_RESOLUTION", result.rejectionReasonCode)
    }

    @Test
    fun `evaluateRgbGrid approves near-native image with usesBlurredFrameFill when enabled by user`() {
        val grid = IntArray(16 * 10) { 0x223344 }
        val result = evaluator.evaluateRgbGrid(
            imageWidth = 1920,
            imageHeight = 1080,
            rgbGrid = grid,
            gridWidth = 16,
            gridHeight = 10,
            display = laptopDisplay,
            allowBlurredFrameFill = true
        )

        assertTrue(result.isEligible)
        assertNotNull(result.cropRect)
        assertTrue(result.cropRect!!.usesBlurredFrameFill)
    }

    @Test
    fun `evaluateRgbGrid computes accurate contrastStdDev and zone luminances on mixed gradient grid`() {
        val gridW = 32
        val gridH = 20
        val grid = IntArray(gridW * gridH) { idx ->
            val x = idx % gridW
            val y = idx / gridW
            val r = (x * 7).coerceIn(0, 255)
            val g = (y * 10).coerceIn(0, 255)
            val b = ((x + y) * 4).coerceIn(0, 255)
            (r shl 16) or (g shl 8) or b
        }

        val result = evaluator.evaluateRgbGrid(
            imageWidth = 3840,
            imageHeight = 2400,
            rgbGrid = grid,
            gridWidth = gridW,
            gridHeight = gridH,
            display = laptopDisplay
        )

        assertTrue(result.isEligible)
        assertTrue(result.globalLuminance in 0.15..0.35)
        assertTrue(result.contrastStdDev > 0.05)
        assertTrue(result.desktopZoneLuminance > 0.0)
        assertTrue(result.lockClockZoneLuminance > 0.0)
    }

    @Test
    fun `evaluateRgbGrid explanation honestly reports high-DPI 65 percent floor instead of claiming 100 percent coverage`() {
        val retinaDisplay = DisplayProfile(
            displayId = 0,
            name = "GoogleBook Retina Display",
            widthPx = 2560,
            heightPx = 1600,
            densityDpi = 240
        )
        val grid = IntArray(32 * 20) { 0xCBE4F9 }
        // 1686 x 1124 -> 16:10 crop is 1686 x 1053 (~66% coverage, passes >= 0.65 retina floor)
        val result = evaluator.evaluateRgbGrid(
            imageWidth = 1686,
            imageHeight = 1124,
            rgbGrid = grid,
            gridWidth = 32,
            gridHeight = 20,
            display = retinaDisplay,
            allowBlurredFrameFill = false
        )

        assertTrue(result.isEligible)
        assertFalse(
            "High-DPI sub-100% crop must NOT falsely claim 100% native pixel coverage: ${result.explanation}",
            result.explanation.contains("100% native pixel coverage")
        )
        assertTrue(
            "Explanation must report actual coverage percentage and high-DPI floor: ${result.explanation}",
            result.explanation.contains("66%") && result.explanation.contains("65%")
        )
    }
}
