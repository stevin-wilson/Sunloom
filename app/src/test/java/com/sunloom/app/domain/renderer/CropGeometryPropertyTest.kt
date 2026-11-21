package com.sunloom.app.domain.renderer

import com.sunloom.app.Property
import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.DisplayProfile
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules that must hold for every picture and every display, not just the handful an example test picks (Article IV §2
 * and §4): a crop lies inside the picture, never stretches it, is centred on the subject as far as the edges allow,
 * and is refused or filled with a blurred frame exactly when the picture is too small for the display's tier.
 *
 * The numbers in the quality tests are the Constitution's (98% / 80% / 65% plus a 1,600 px long edge, 50% for a blurred
 * frame), written out here on purpose and not read from the code, so a drift in the code's constants fails.
 */
@Requirement("IV.2", "IV.4")
class CropGeometryPropertyTest {

    private val renderer = CropRenderer()

    private fun CropRect.insidePicture(srcW: Int, srcH: Int) =
        left >= 0 && top >= 0 && width >= 1 && height >= 1 && left + width <= srcW && top + height <= srcH

    // --- computeAspectCropBounds: the shape and position of the window ---

    @Test
    fun `a crop always lies inside the picture and fills one whole side`() {
        Property.forAll("crop inside the picture") { case ->
            val srcW = case.int(1, 8_000)
            val srcH = case.int(1, 8_000)
            val aspect = case.double(0.2, 5.0)
            val fx = case.double(-0.5, 1.5)
            val fy = case.double(-0.5, 1.5)
            case.input = "source ${srcW}x$srcH, aspect $aspect, focal ($fx, $fy)"
            val crop = renderer.computeAspectCropBounds(srcW, srcH, fx, fy, aspect)
            assertTrue("crop $crop is not inside the picture", crop.insidePicture(srcW, srcH))
            assertTrue("crop $crop leaves both sides short: it could be larger", crop.width == srcW || crop.height == srcH)
        }
    }

    @Test
    fun `a crop has the shape of the display to within a pixel of rounding, so nothing is stretched`() {
        Property.forAll("crop keeps the display's shape") { case ->
            val srcW = case.int(2, 8_000)
            val srcH = case.int(2, 8_000)
            val aspect = case.double(0.25, 4.0)
            case.input = "source ${srcW}x$srcH, aspect $aspect"
            val crop = renderer.computeAspectCropBounds(srcW, srcH, case.double(0.0, 1.0), case.double(0.0, 1.0), aspect)
            // The side that is not the whole picture is rounded from the other, so it is off by at most half a pixel.
            // (A side that would round to under one pixel is clamped; that only happens for absurdly thin shapes.)
            val widthFromHeight = crop.height * aspect
            val heightFromWidth = crop.width / aspect
            val clamped = (crop.width == 1 && widthFromHeight < 1.0) || (crop.height == 1 && heightFromWidth < 1.0)
            if (!clamped) {
                assertTrue(
                    "crop ${crop.width}x${crop.height} does not have aspect $aspect",
                    abs(crop.width - widthFromHeight) <= 0.5 + 1e-6 || abs(crop.height - heightFromWidth) <= 0.5 + 1e-6
                )
            }
        }
    }

    @Test
    fun `moving the subject right or down never moves the crop left or up`() {
        Property.forAll("crop follows the subject") { case ->
            val srcW = case.int(1, 6_000)
            val srcH = case.int(1, 6_000)
            val aspect = case.double(0.3, 4.0)
            val a = case.double(0.0, 1.0)
            val b = case.double(0.0, 1.0)
            val (lo, hi) = if (a <= b) a to b else b to a
            case.input = "source ${srcW}x$srcH, aspect $aspect, subject $lo then $hi"
            val first = renderer.computeAspectCropBounds(srcW, srcH, lo, lo, aspect)
            val second = renderer.computeAspectCropBounds(srcW, srcH, hi, hi, aspect)
            assertTrue("left went from ${first.left} to ${second.left}", second.left >= first.left)
            assertTrue("top went from ${first.top} to ${second.top}", second.top >= first.top)
        }
    }

    @Test
    fun `a subject at the mirror position gives the mirror crop to within a pixel`() {
        Property.forAll("crop is symmetric") { case ->
            val srcW = case.int(1, 6_000)
            val srcH = case.int(1, 6_000)
            val aspect = case.double(0.3, 4.0)
            val fx = case.double(0.0, 1.0)
            val fy = case.double(0.0, 1.0)
            case.input = "source ${srcW}x$srcH, aspect $aspect, subject ($fx, $fy)"
            val crop = renderer.computeAspectCropBounds(srcW, srcH, fx, fy, aspect)
            val mirror = renderer.computeAspectCropBounds(srcW, srcH, 1 - fx, 1 - fy, aspect)
            assertEquals("same size", crop.width to crop.height, mirror.width to mirror.height)
            assertTrue("left $crop vs $mirror", abs((srcW - crop.width - crop.left) - mirror.left) <= 1)
            assertTrue("top $crop vs $mirror", abs((srcH - crop.height - crop.top) - mirror.top) <= 1)
        }
    }

    @Test
    fun `away from the edges the crop is centred on the subject to within half a pixel`() {
        Property.forAll("crop is centred on the subject") { case ->
            val srcW = case.int(50, 8_000)
            val srcH = case.int(50, 8_000)
            val aspect = case.double(0.5, 3.0)
            val fx = case.double(0.0, 1.0)
            val fy = case.double(0.0, 1.0)
            case.input = "source ${srcW}x$srcH, aspect $aspect, subject ($fx, $fy)"
            val crop = renderer.computeAspectCropBounds(srcW, srcH, fx, fy, aspect)
            if (crop.left > 0 && crop.left + crop.width < srcW) {
                assertTrue("centre x ${crop.left + crop.width / 2.0} vs ${fx * srcW}", abs(crop.left + crop.width / 2.0 - fx * srcW) <= 0.5 + 1e-6)
            }
            if (crop.top > 0 && crop.top + crop.height < srcH) {
                assertTrue("centre y ${crop.top + crop.height / 2.0} vs ${fy * srcH}", abs(crop.top + crop.height / 2.0 - fy * srcH) <= 0.5 + 1e-6)
            }
        }
    }

    @Test
    fun `a subject position outside the picture is clamped to its edge and recorded as such`() {
        Property.forAll("subject position is clamped") { case ->
            val fx = case.double(-3.0, 4.0)
            val fy = case.double(-3.0, 4.0)
            case.input = "subject ($fx, $fy)"
            val crop = renderer.computeAspectCropBounds(1_920, 1_080, fx, fy, 16.0 / 9.0)
            assertEquals(fx.coerceIn(0.0, 1.0), crop.focalX, 0.0)
            assertEquals(fy.coerceIn(0.0, 1.0), crop.focalY, 0.0)
        }
    }

    @Test
    fun `an unusable shape or size still gives a crop inside the picture`() {
        // Zero or negative sizes are read as one pixel; a shape that is not a positive number falls back to 16:10.
        listOf(0, -5, Int.MIN_VALUE).forEach { size ->
            val crop = renderer.computeAspectCropBounds(size, size, 0.5, 0.5, 1.6)
            assertEquals(CropRect(0, 0, 1, 1, 0.5, 0.5, false, 1.0), crop)
        }
        listOf(0.0, -1.0, Double.NaN, Double.NEGATIVE_INFINITY).forEach { shape ->
            val crop = renderer.computeAspectCropBounds(1_600, 1_000, 0.5, 0.5, shape)
            assertEquals("shape $shape", 1_600 to 1_000, crop.width to crop.height)
        }
        val infinite = renderer.computeAspectCropBounds(1_600, 1_000, 0.5, 0.5, Double.POSITIVE_INFINITY)
        assertTrue("infinite shape: $infinite", infinite.insidePicture(1_600, 1_000))
    }

    @Test
    fun `a corrupt subject position does not crash`() {
        // A saved focal point could be read back as not-a-number or infinite; cropping must still give a crop.
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { bad ->
            val crop = renderer.computeAspectCropBounds(1_920, 1_080, bad, bad, 1.6)
            assertTrue("subject $bad gave $crop", crop.insidePicture(1_920, 1_080))
        }
    }

    // --- budgetedSize: the 16 MP cap on what is handed to the wallpaper service ---

    @Test
    fun `a size scaled down to the cap never goes over it, never grows, and keeps its shape to within a pixel`() {
        val cap = 16_000_000L
        // Rounding both sides separately overshot the cap for these (by 3,008 and 134 pixels).
        listOf(7_000 to 4_000, 9_000 to 6_000).forEach { (w, h) ->
            val (bw, bh) = CropRenderer.budgetedSize(w, h)
            assertTrue("${w}x$h became ${bw}x$bh = ${bw.toLong() * bh} pixels", bw.toLong() * bh <= cap)
        }
        Property.forAll("budgeted size") { case ->
            val w = case.int(1, 12_000)
            val h = case.int(1, 12_000)
            case.input = "size ${w}x$h"
            val (bw, bh) = CropRenderer.budgetedSize(w, h)
            assertTrue("${bw}x$bh is over the cap", bw.toLong() * bh <= cap)
            assertTrue("${bw}x$bh is larger than ${w}x$h", bw in 1..w && bh in 1..h)
            if (w.toLong() * h <= cap) {
                assertEquals("a size that already fits is left alone", w to h, bw to bh)
            } else {
                assertTrue("shape changed: ${bw}x$bh from ${w}x$h", abs(bw.toLong() * h - bh.toLong() * w) <= max(w, h) + 1)
                assertTrue("${bw}x$bh uses far less than the cap", bw.toLong() * bh >= cap - (bw + bh) - 1)
            }
        }
    }

    // --- blurredFillLayout: the whole picture stays visible on every display (Article IV section 4) ---

    @Test
    fun `a blurred-frame wallpaper shows the whole picture, unstretched, on every display`() {
        Property.forAll("blurred frame layout") { case ->
            val srcW = case.int(8, 9_000)
            val srcH = case.int(8, 9_000)
            val ds = (1..case.int(1, 4)).map { i -> display(case).copy(displayId = i) }
            case.input = "source ${srcW}x$srcH, displays " + ds.joinToString { "${it.widthPx}x${it.heightPx}" }
            val l = CropRenderer.blurredFillLayout(srcW, srcH, ds)

            assertTrue("canvas ${l.canvasWidth}x${l.canvasHeight} is over 16 MP", l.canvasWidth.toLong() * l.canvasHeight <= 16_000_000L)
            assertTrue("picture $l is not inside the canvas", l.fgLeft >= 0 && l.fgTop >= 0 && l.fgWidth >= 1 && l.fgHeight >= 1 &&
                l.fgLeft + l.fgWidth <= l.canvasWidth && l.fgTop + l.fgHeight <= l.canvasHeight)
            // Not stretched: the picture keeps its shape to within the rounding of its two sides.
            assertTrue("picture ${l.fgWidth}x${l.fgHeight} is not the shape of ${srcW}x$srcH",
                abs(l.fgWidth.toLong() * srcH - l.fgHeight.toLong() * srcW) <= max(srcW, srcH) + 1)
            // Centred: the margins on opposite sides differ by at most the odd pixel.
            assertTrue("picture $l is off centre horizontally", abs((l.canvasWidth - l.fgWidth - l.fgLeft) - l.fgLeft) <= 1)
            assertTrue("picture $l is off centre vertically", abs((l.canvasHeight - l.fgHeight - l.fgTop) - l.fgTop) <= 1)

            // Every display is shown the centred slice of the canvas that has its own shape; the picture must be inside it.
            ds.forEach { d ->
                val aspect = d.widthPx.toDouble() / d.heightPx
                val sliceW = if (l.canvasWidth.toDouble() / l.canvasHeight >= aspect) l.canvasHeight * aspect else l.canvasWidth.toDouble()
                val sliceH = if (l.canvasWidth.toDouble() / l.canvasHeight >= aspect) l.canvasHeight.toDouble() else l.canvasWidth / aspect
                val left = (l.canvasWidth - sliceW) / 2
                val top = (l.canvasHeight - sliceH) / 2
                val tolerance = 1.5
                assertTrue(
                    "on ${d.widthPx}x${d.heightPx} the slice ($left, $top, $sliceW x $sliceH) cuts off the picture $l",
                    l.fgLeft >= left - tolerance && l.fgLeft + l.fgWidth <= left + sliceW + tolerance &&
                        l.fgTop >= top - tolerance && l.fgTop + l.fgHeight <= top + sliceH + tolerance
                )
            }
            if (ds.size == 1) {
                // The slice the display shows is the canvas to within the pixels lost when the canvas size was rounded to the cap:
                // a pixel of canvas height is `aspect` pixels of width on a wide display (and the reverse on a tall one).
                val d = ds.single()
                val aspect = d.widthPx.toDouble() / d.heightPx
                val slack = ceil(max(aspect, 1 / aspect)).toInt() + 1
                assertTrue(
                    "on one display the picture is the largest that fits the canvas: $l",
                    l.fgWidth >= l.canvasWidth - slack || l.fgHeight >= l.canvasHeight - slack
                )
            }
        }
    }

    // --- computeCropFromFocalPoint: the quality tiers of Article IV section 2 ---

    private val standardSizes = listOf(1280 to 800, 1920 to 1080, 2560 to 1440, 2560 to 1600, 3440 to 1440, 3840 to 2160, 1080 to 1920)

    private fun display(case: Property.Case): DisplayProfile {
        val (w, h) = if (case.boolean()) case.oneOf(standardSizes) else case.int(640, 7_680) to case.int(480, 4_320)
        return DisplayProfile(displayId = 1, name = "Display", widthPx = w, heightPx = h, physicalPpi = case.double(80.0, 330.0))
    }

    /** The Constitution's coverage floor and minimum long edge for a display of this density (Article IV §2). */
    private fun floorFor(ppi: Double): Pair<Double, Int> = when {
        ppi >= 210.0 -> 0.65 to 1_600
        ppi >= 120.0 -> 0.80 to 0
        else -> 0.98 to 0
    }

    /** A crop is whole pixels, so its coverage can fall short of the floor by at most one pixel's worth of the display. */
    private fun roundingSlack(d: DisplayProfile): Double = 1.0 / min(d.widthPx, d.heightPx)

    /** Coverage of the largest display-shaped window that fits the picture, worked out without rounding. */
    private fun idealCrop(srcW: Int, srcH: Int, d: DisplayProfile): Triple<Double, Double, Double> {
        val aspect = d.widthPx.toDouble() / d.heightPx
        val (cw, ch) = if (srcW.toDouble() / srcH >= aspect) srcH * aspect to srcH.toDouble() else srcW.toDouble() to srcW / aspect
        return Triple(cw, ch, min(cw / d.widthPx, ch / d.heightPx))
    }

    @Test
    fun `a crop that is returned meets its display's floor and never stretches the picture`() {
        Property.forAll("returned crops meet the tier") { case ->
            val srcW = case.int(200, 9_000)
            val srcH = case.int(200, 9_000)
            val d = display(case)
            val allowBlur = case.boolean()
            case.input = "source ${srcW}x$srcH, display ${d.widthPx}x${d.heightPx} @ ${d.physicalPpi} ppi, blurred fill allowed=$allowBlur"
            val crop = renderer.computeCropFromFocalPoint(srcW, srcH, case.double(0.0, 1.0), case.double(0.0, 1.0), d, allowBlur) ?: return@forAll
            val (floor, longEdge) = floorFor(d.physicalPpi!!)
            if (crop.usesBlurredFrameFill) {
                assertTrue("a blurred frame was used although it was not allowed", allowBlur)
                assertEquals("a blurred frame shows the whole picture", CropRect(0, 0, srcW, srcH, crop.focalX, crop.focalY, true, crop.pixelCoverageRatio), crop)
                // The 50% floor is met by the crop's coverage or, for a large enough picture, by how much of the display
                // the whole picture fills when fitted inside it (the second route is documented in passesBlurredFillFloor;
                // the Constitution says only "down to 0.50 coverage").
                val fitted = max(srcW.toDouble() / d.widthPx, srcH.toDouble() / d.heightPx)
                val byCrop = crop.pixelCoverageRatio >= 0.50 - 1e-9
                val byFit = fitted >= 0.50 - 1e-9 && max(srcW, srcH) >= 1_600
                assertTrue("neither the crop (${crop.pixelCoverageRatio}) nor the fitted picture ($fitted) reaches the 50% blurred-frame floor", byCrop || byFit)
            } else {
                assertTrue("crop $crop is not inside the picture", crop.insidePicture(srcW, srcH))
                assertTrue("coverage ${crop.pixelCoverageRatio} is under the $floor floor", crop.pixelCoverageRatio >= floor - roundingSlack(d))
                assertTrue("long edge ${max(crop.width, crop.height)} is under $longEdge", max(crop.width, crop.height) >= longEdge)
                val aspect = d.widthPx.toDouble() / d.heightPx
                assertTrue(
                    "crop ${crop.width}x${crop.height} is not the display's shape $aspect",
                    abs(crop.width - crop.height * aspect) <= 0.5 + 1e-6 || abs(crop.height - crop.width / aspect) <= 0.5 + 1e-6
                )
            }
        }
    }

    @Test
    fun `a picture that clears the floor is accepted and one that misses it is refused or framed`() {
        Property.forAll("tier decision") { case ->
            val srcW = case.int(200, 9_000)
            val srcH = case.int(200, 9_000)
            val d = display(case)
            val allowBlur = case.boolean()
            val (cw, ch, coverage) = idealCrop(srcW, srcH, d)
            val (floor, longEdge) = floorFor(d.physicalPpi!!)
            case.input = "source ${srcW}x$srcH, display ${d.widthPx}x${d.heightPx} @ ${d.physicalPpi} ppi, coverage $coverage, floor $floor/$longEdge, blur=$allowBlur"
            val crop = renderer.computeCropFromFocalPoint(srcW, srcH, 0.5, 0.5, d, allowBlur)
            // Margins keep rounding of the crop size from deciding a case that is within a hair of the floor.
            val clearly = coverage >= floor + 0.02 && max(cw, ch) >= longEdge + 4
            val clearlyNot = coverage < floor - 0.02 || max(cw, ch) < longEdge - 4
            if (clearly) {
                assertNotNull("a picture that clears the floor was turned away", crop)
                assertTrue("a picture that clears the floor was framed instead of cropped: $crop", !crop!!.usesBlurredFrameFill)
            }
            if (clearlyNot) {
                assertTrue("a picture below the floor was cropped: $crop", crop == null || crop.usesBlurredFrameFill)
                // With a frame allowed, a picture that clearly reaches the 50% frame floor is not turned away.
                val fitted = max(srcW.toDouble() / d.widthPx, srcH.toDouble() / d.heightPx)
                if (allowBlur && (coverage >= 0.52 || (fitted >= 0.55 && max(srcW, srcH) >= 1_700))) {
                    assertNotNull("a picture that reaches the frame floor was turned away", crop)
                }
            }
        }
    }

    @Test
    fun `a blurred frame is never used unless it was allowed`() {
        Property.forAll("no blurred frame unless allowed") { case ->
            val srcW = case.int(200, 6_000)
            val srcH = case.int(200, 6_000)
            val d = display(case)
            case.input = "source ${srcW}x$srcH, display ${d.widthPx}x${d.heightPx} @ ${d.physicalPpi} ppi"
            val crop = renderer.computeCropFromFocalPoint(srcW, srcH, 0.3, 0.7, d, allowBlurredFrameFill = false)
            assertTrue("framed although not allowed: $crop", crop == null || !crop.usesBlurredFrameFill)
        }
    }

    @Test
    fun `the cropper that picks the subject itself obeys the same rules`() {
        Property.forAll("optimal crop obeys the rules") { case ->
            val srcW = case.int(200, 6_000)
            val srcH = case.int(200, 6_000)
            val d = display(case)
            val gridW = case.int(4, 24)
            val gridH = case.int(4, 24)
            val grid = IntArray(gridW * gridH) { case.random.nextInt() }
            val allowBlur = case.boolean()
            case.input = "source ${srcW}x$srcH, display ${d.widthPx}x${d.heightPx} @ ${d.physicalPpi} ppi, grid ${gridW}x$gridH, blur=$allowBlur"
            val crop = renderer.computeOptimalCrop(srcW, srcH, grid, gridW, gridH, d, allowBlurredFrameFill = allowBlur) ?: return@forAll
            assertTrue("focal point (${crop.focalX}, ${crop.focalY}) is outside the picture", crop.focalX in 0.0..1.0 && crop.focalY in 0.0..1.0)
            if (crop.usesBlurredFrameFill) {
                assertTrue("framed although not allowed", allowBlur)
            } else {
                assertTrue("crop $crop is not inside the picture", crop.insidePicture(srcW, srcH))
                val (floor, longEdge) = floorFor(d.physicalPpi!!)
                assertTrue("coverage ${crop.pixelCoverageRatio} is under the $floor floor", crop.pixelCoverageRatio >= floor - roundingSlack(d))
                assertTrue("long edge is under $longEdge", max(crop.width, crop.height) >= longEdge)
            }
        }
    }

    @Test
    fun `a display with no size, or a picture with no size, gets no crop`() {
        val flat = DisplayProfile(1, "Flat", 0, 1_080)
        val thin = DisplayProfile(1, "Thin", 1_920, -1)
        listOf(flat, thin).forEach { d ->
            assertNull(renderer.computeCropFromFocalPoint(4_000, 3_000, 0.5, 0.5, d, true))
        }
        val normal = DisplayProfile(1, "Normal", 1_920, 1_080, physicalPpi = 140.0)
        assertNull(renderer.computeCropFromFocalPoint(0, 3_000, 0.5, 0.5, normal, true))
        assertNull(renderer.computeCropFromFocalPoint(4_000, -1, 0.5, 0.5, normal, true))
    }
}
