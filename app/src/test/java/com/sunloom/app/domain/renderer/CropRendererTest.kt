package com.sunloom.app.domain.renderer

import com.sunloom.app.domain.model.DisplayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CropRendererTest {

    private val renderer = CropRenderer()

    @Test
    fun `computeOptimalCrop preserves ultrawide 21_9 aspect ratio without stretching`() {
        val ultrawide = DisplayProfile(
            displayId = 1,
            name = "Ultrawide External Monitor",
            widthPx = 3440,
            heightPx = 1440
        )
        val grid = IntArray(32 * 24) { 0x334455 }

        val crop = renderer.computeOptimalCrop(
            sourceWidth = 4000,
            sourceHeight = 3000,
            rgbGrid = grid,
            gridWidth = 32,
            gridHeight = 24,
            display = ultrawide,
            allowBlurredFrameFill = false
        )

        assertNotNull(crop)
        val cropAspect = crop!!.width.toDouble() / crop.height.toDouble()
        assertEquals(ultrawide.aspectRatio, cropAspect, 0.002)
        assertTrue(crop.width >= ultrawide.widthPx)
        assertTrue(crop.height >= ultrawide.heightPx)
    }

    @Test
    fun `computeOptimalCrop rejects nominally 4K image when ultrawide crop drops height below 1440`() {
        val ultrawide = DisplayProfile(
            displayId = 1,
            name = "Ultrawide 3440x1440",
            widthPx = 3440,
            heightPx = 1440,
            densityDpi = 160
        )
        val grid = IntArray(16 * 16) { 0x334455 }

        val rejected = renderer.computeOptimalCrop(
            sourceWidth = 3200,
            sourceHeight = 2160,
            rgbGrid = grid,
            gridWidth = 16,
            gridHeight = 16,
            display = ultrawide,
            allowBlurredFrameFill = false
        )
        assertNull(rejected)
    }

    @Test
    fun `computeOptimalCrop shifts crop window toward high-entropy subject on the right side`() {
        val display = DisplayProfile(
            displayId = 0,
            name = "GoogleBook 16:10",
            widthPx = 2560,
            heightPx = 1600
        )
        val gridW = 40
        val gridH = 20
        val grid = IntArray(gridW * gridH) { 0x101820 }

        // Place high-contrast subject detail on the right side (x in 28..38)
        for (y in 6..16) {
            for (x in 28..38) {
                grid[y * gridW + x] = if ((x + y) % 2 == 0) 0xF0C060 else 0x182848
            }
        }

        val crop = renderer.computeOptimalCrop(
            sourceWidth = 4000,
            sourceHeight = 2000,
            rgbGrid = grid,
            gridWidth = gridW,
            gridHeight = gridH,
            display = display
        )

        assertNotNull(crop)
        assertTrue("Expected focalX > 0.60 when subject is on right, got ${crop!!.focalX}", crop.focalX > 0.60)
        assertTrue("Expected crop window shifted right (left > 400), got ${crop.left}", crop.left > 400)
        assertTrue(abs((crop.width.toDouble() / crop.height.toDouble()) - display.aspectRatio) < 0.01)
    }

    @Test
    fun `testHighDpiDisplayAccepts1686PxArticRenditionAndRejects843PxThumbnail`() {
        val highDpiLaptop = DisplayProfile(
            displayId = 0,
            name = "GoogleBook Retina 16:10",
            widthPx = 2560,
            heightPx = 1600,
            densityDpi = 320
        )
        val grid = IntArray(32 * 20) { 0x334455 }

        // 1686 x 1124 ArtIC rendition on 320dpi 2560x1600 display: coverage = 1686/2560 = 0.658 >= 0.65, long edge 1686 >= 1600
        val accepted1686 = renderer.computeOptimalCrop(
            sourceWidth = 1686,
            sourceHeight = 1124,
            rgbGrid = grid,
            gridWidth = 32,
            gridHeight = 20,
            display = highDpiLaptop,
            allowBlurredFrameFill = false
        )
        assertNotNull(accepted1686)
        assertFalse(accepted1686!!.usesBlurredFrameFill)

        // 843 x 562 thumbnail on the same display is rejected when blurred frame fill is disabled
        val rejected843 = renderer.computeOptimalCrop(
            sourceWidth = 843,
            sourceHeight = 562,
            rgbGrid = grid,
            gridWidth = 32,
            gridHeight = 20,
            display = highDpiLaptop,
            allowBlurredFrameFill = false
        )
        assertNull(rejected843)
    }

    @Test
    fun `test213DpiLaptopPanelQualifiesAsHighDensityAndAccepts1686PxMuseumRendition`() {
        val laptop213Dpi = DisplayProfile(
            displayId = 0,
            name = "GoogleBook 13-inch 2560x1600 (213 dpi)",
            widthPx = 2560,
            heightPx = 1600,
            densityDpi = 213
        )
        val grid = IntArray(32 * 20) { 0x334455 }

        val accepted = renderer.computeOptimalCrop(
            sourceWidth = 1686,
            sourceHeight = 1124,
            rgbGrid = grid,
            gridWidth = 32,
            gridHeight = 20,
            display = laptop213Dpi,
            allowBlurredFrameFill = false
        )
        assertNotNull("Expected 213dpi display (>= 210dpi per Constitution IV.2) to accept 1686px IIIF image", accepted)
        assertFalse(accepted!!.usesBlurredFrameFill)
    }

    @Test
    fun `testComputeCropFromFocalPointPreservesSubjectCenter`() {
        val display = DisplayProfile(
            displayId = 0,
            name = "GoogleBook 16:10",
            widthPx = 2560,
            heightPx = 1600
        )
        val crop = renderer.computeCropFromFocalPoint(
            sourceWidth = 4000,
            sourceHeight = 1600,
            focalX = 0.85,
            focalY = 0.50,
            display = display,
            allowBlurredFrameFill = false
        )
        assertNotNull(crop)
        assertEquals(2560, crop!!.width)
        assertEquals(1600, crop.height)
        assertTrue("Expected crop shifted toward right focal point, got left=${crop.left}", crop.left > 1000)
    }

    @Test
    fun `testComputeCropFromFocalPointClampsOutOfBoundsCoordinatesAndSupportsBlurredFill`() {
        val display = DisplayProfile(
            displayId = 0,
            name = "GoogleBook 16:10",
            widthPx = 2560,
            heightPx = 1600
        )
        val clamped = renderer.computeCropFromFocalPoint(
            sourceWidth = 4000,
            sourceHeight = 2000,
            focalX = -2.5,
            focalY = 3.5,
            display = display,
            allowBlurredFrameFill = false
        )
        assertNotNull(clamped)
        assertEquals(0.0, clamped!!.focalX, 1e-6)
        assertEquals(1.0, clamped.focalY, 1e-6)
        assertEquals(0, clamped.left)
        assertEquals(3200, clamped.width)
        assertEquals(2000, clamped.height)

        val blurred = renderer.computeCropFromFocalPoint(
            sourceWidth = 1920,
            sourceHeight = 1080,
            focalX = 0.5,
            focalY = 0.5,
            display = display,
            allowBlurredFrameFill = true
        )
        assertNotNull(blurred)
        assertTrue(blurred!!.usesBlurredFrameFill)
        assertEquals(1920, blurred.width)
        assertEquals(1080, blurred.height)
    }

    @Test
    fun `blurredFrameFill accepts 1686px IIIF fine art on UWQHD 4K 32_9 and Portrait external monitors while rejecting 843px thumbnails`() {
        val grid = IntArray(32 * 20) { 0x334455 }
        val externalDisplays = listOf(
            DisplayProfile(1, "UWQHD 21:9", 3440, 1440, densityDpi = 160),
            DisplayProfile(2, "4K UHD 16:9", 3840, 2160, densityDpi = 160),
            DisplayProfile(3, "DQHD Super-Ultrawide 32:9", 5120, 1440, densityDpi = 160),
            DisplayProfile(4, "Portrait QHD 9:16", 1440, 2560, densityDpi = 160)
        )

        for (monitor in externalDisplays) {
            // Without blurred fill, 1686x1124 must be rejected on all of these external displays
            assertNull(
                "Expected 1686x1124 to be rejected without blurred fill on ${monitor.name}",
                renderer.computeOptimalCrop(1686, 1124, grid, 32, 20, monitor, allowBlurredFrameFill = false)
            )

            // With blurred fill enabled, 1686x1124 must be accepted proportionally on all of them
            val framed = renderer.computeOptimalCrop(1686, 1124, grid, 32, 20, monitor, allowBlurredFrameFill = true)
            assertNotNull("Expected 1686x1124 to be accepted with blurred fill on ${monitor.name}", framed)
            assertTrue(framed!!.usesBlurredFrameFill)

            val framedFromFocal = renderer.computeCropFromFocalPoint(1686, 1124, 0.5, 0.5, monitor, allowBlurredFrameFill = true)
            assertNotNull("Expected computeCropFromFocalPoint to accept 1686x1124 with blurred fill on ${monitor.name}", framedFromFocal)
            assertTrue(framedFromFocal!!.usesBlurredFrameFill)

            // Tiny 843x562 thumbnails must still be rejected even with blurred fill enabled
            assertNull(
                "Expected 843x562 thumbnail to be rejected even with blurred fill on ${monitor.name}",
                renderer.computeOptimalCrop(843, 562, grid, 32, 20, monitor, allowBlurredFrameFill = true)
            )
        }
    }

    @Test
    fun `budgetedSize and canSourceSatisfyDisplay preserve aspect ratios from portrait to 32_9 and 8K`() {
        // 32:9 DQHD (7.37 MP) and 5K (14.75 MP) stay at exact native resolution
        assertEquals(5120 to 1440, CropRenderer.budgetedSize(5120, 1440))
        assertEquals(5120 to 2880, CropRenderer.budgetedSize(5120, 2880))
        assertEquals(1440 to 2560, CropRenderer.budgetedSize(1440, 2560))

        // 8K (7680x4320 = 33.17 MP) scales down uniformly without distorting 16:9 aspect ratio
        val (w8k, h8k) = CropRenderer.budgetedSize(7680, 4320)
        assertTrue(w8k.toLong() * h8k.toLong() <= CropRenderer.MAX_OUTPUT_PIXELS)
        assertEquals(16.0 / 9.0, w8k.toDouble() / h8k.toDouble(), 0.005)

        val uwqhd = DisplayProfile(1, "UWQHD", 3440, 1440, densityDpi = 160)
        val retinaLaptop = DisplayProfile(0, "Retina Laptop", 2560, 1600, densityDpi = 240)
        assertFalse(CropRenderer.canSourceSatisfyDisplay(1686, uwqhd, allowBlurredFrameFill = false))
        assertTrue(CropRenderer.canSourceSatisfyDisplay(1686, uwqhd, allowBlurredFrameFill = true))
        assertTrue(CropRenderer.canSourceSatisfyDisplay(1686, retinaLaptop, allowBlurredFrameFill = false))
    }
}
