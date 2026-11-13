package com.sunloom.app.domain.evaluator

import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.DisplayQualityTier
import com.sunloom.app.domain.model.ImageEvaluationResult
import com.sunloom.app.domain.renderer.CropRenderer
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Article I.2, IV.2, IV.3, V.3:
 * Deterministic Image Evaluator measuring native resolution survivability,
 * relative luminance (Y = 0.2126R + 0.7152G + 0.0722B), legibility zones
 * (top status bar, bottom shelf/taskbar, left desktop icon column, lock-screen clock zone),
 * glare fraction, and contrast standard deviation.
 */
class ImageEvaluator(
    private val cropRenderer: CropRenderer = CropRenderer()
) {
    /**
     * Evaluates an RGB pixel grid (0xRRGGBB ints) of dimensions [imageWidth] x [imageHeight]
     * sampled at [gridWidth] x [gridHeight] against a target [display].
     */
    fun evaluateRgbGrid(
        imageWidth: Int,
        imageHeight: Int,
        rgbGrid: IntArray,
        gridWidth: Int,
        gridHeight: Int,
        display: DisplayProfile,
        allowBlurredFrameFill: Boolean = false
    ): ImageEvaluationResult {
        if (!isUsableSample(imageWidth, imageHeight, rgbGrid, gridWidth, gridHeight)) {
            return rejected(
                "INVALID_DIMENSIONS",
                "Image dimensions ($imageWidth × $imageHeight) or pixel sample grid are invalid."
            )
        }

        // Museum scans and photos often come with a mat or frame. The renderers place the crop from the focal
        // point on the whole image, so the border cannot be cut away here; it is, however, not picture, and
        // must not skew the brightness and contrast that are measured (a black frame around a bright
        // photograph would otherwise darken it).
        val margins = detectMargins(rgbGrid, gridWidth, gridHeight)

        val visibleFraction = CropRenderer.visibleAreaFraction(imageWidth, imageHeight, display)
        val cropsTooMuch = visibleFraction < CropRenderer.MIN_VISIBLE_AREA
        if (cropsTooMuch && !allowBlurredFrameFill) {
            return rejected(
                "COMPOSITION_CROPS_TOO_MUCH",
                "Fitting $imageWidth × $imageHeight to ${display.resolutionLabel} would keep only " +
                    "${(visibleFraction * 100).roundToInt()}% of the picture; enable blurred frame fill to show it whole."
            )
        }

        val plannedCrop: CropRect = cropRenderer.computeOptimalCrop(
            sourceWidth = imageWidth,
            sourceHeight = imageHeight,
            rgbGrid = rgbGrid,
            gridWidth = gridWidth,
            gridHeight = gridHeight,
            display = display,
            allowBlurredFrameFill = allowBlurredFrameFill,
            preferFullFrame = cropsTooMuch
        ) ?: return rejected(
            "INSUFFICIENT_POST_CROP_RESOLUTION",
            "After cropping to ${display.resolutionLabel} aspect ratio, the source image ($imageWidth × $imageHeight) " +
                "does not meet the ${floorDescription(display)} without enlargement."
        )
        val cropRect = centredOnWindow(plannedCrop, imageWidth, imageHeight)

        val stats = measureCrop(rgbGrid, gridWidth, gridHeight, margins, cropRect, imageWidth, imageHeight)
        val brightnessClass = classify(stats)
        val warmth = ((stats.meanR - stats.meanB) / 255.0).coerceIn(-1.0, 1.0)
        val hueBucket = hueBucketOf(stats.meanR, stats.meanG, stats.meanB)

        val explanation = buildString {
            append("Classified as ${brightnessClass.displayName}: ")
            append("overall luminance=${"%.2f".format(stats.globalLum)}, ")
            append("clock zone=${"%.2f".format(stats.clockLum)}, ")
            append("desktop bar/icon zone=${"%.2f".format(stats.desktopLum)}, ")
            append("glare=${"%.1f".format(stats.glareFraction * 100)}%, ")
            append("label contrast=${"%.1f".format(stats.desktopContrast)}:1. ")
            if (!margins.isEmpty) append("Measured inside a uniform border (mat or frame). ")
            append(fillNote(cropRect, display))
        }

        return ImageEvaluationResult(
            isEligible = true,
            rejectionReasonCode = null,
            globalLuminance = stats.globalLum,
            desktopZoneLuminance = stats.desktopLum,
            lockClockZoneLuminance = stats.clockLum,
            glareFractionInSafeZones = stats.glareFraction,
            contrastStdDev = stats.stdDev,
            brightnessClass = brightnessClass,
            cropRect = cropRect,
            explanation = explanation,
            lightnessP50 = stats.medianLightness,
            desktopLabelContrast = stats.desktopContrast,
            warmth = warmth,
            hueBucket = hueBucket
        )
    }

    private fun isUsableSample(imageWidth: Int, imageHeight: Int, rgbGrid: IntArray, gridWidth: Int, gridHeight: Int): Boolean {
        val sizesValid = imageWidth > 0 && imageHeight > 0 && gridWidth > 0 && gridHeight > 0
        return sizesValid && rgbGrid.size >= gridWidth * gridHeight
    }

    private fun floorDescription(display: DisplayProfile) = when (display.qualityTier) {
        DisplayQualityTier.HIGH_65 -> "65% high-DPI floor (>= 1600px long edge)"
        DisplayQualityTier.MID_80 -> "80% mid-PPI floor (120–209 PPI)"
        DisplayQualityTier.STANDARD_100 -> "98% native pixel floor"
    }

    /**
     * The renderers place the crop from the focal point alone (centre of the window on the full image), so
     * the stored focal point is the centre of the window chosen here: that carries over the thirds
     * placement and the clock/shelf avoidance. A blurred fill shows the whole frame.
     */
    private fun centredOnWindow(plannedCrop: CropRect, imageWidth: Int, imageHeight: Int): CropRect =
        if (plannedCrop.usesBlurredFrameFill) {
            plannedCrop
        } else {
            plannedCrop.copy(
                focalX = ((plannedCrop.left + plannedCrop.width / 2.0) / imageWidth).coerceIn(0.0, 1.0),
                focalY = ((plannedCrop.top + plannedCrop.height / 2.0) / imageHeight).coerceIn(0.0, 1.0)
            )
        }

    /** What was measured inside the cropped region, ignoring any mat or frame. */
    private class CropStats(
        val globalLum: Double,
        val desktopLum: Double,
        val clockLum: Double,
        val glareFraction: Double,
        val stdDev: Double,
        val medianLightness: Double,
        val desktopContrast: Double,
        val clockContrast: Double,
        val meanR: Double,
        val meanG: Double,
        val meanB: Double
    )

    private fun measureCrop(
        rgbGrid: IntArray,
        gridWidth: Int,
        gridHeight: Int,
        margins: Margins,
        cropRect: CropRect,
        imageWidth: Int,
        imageHeight: Int
    ): CropStats {
        // Perceptual statistics of the cropped region
        val xStartNorm = (cropRect.left.toDouble() / imageWidth.toDouble()).coerceIn(0.0, 0.99)
        val yStartNorm = (cropRect.top.toDouble() / imageHeight.toDouble()).coerceIn(0.0, 0.99)
        val xEndNorm = ((cropRect.left + cropRect.width).toDouble() / imageWidth.toDouble()).coerceIn(xStartNorm + 0.01, 1.0)
        val yEndNorm = ((cropRect.top + cropRect.height).toDouble() / imageHeight.toDouble()).coerceIn(yStartNorm + 0.01, 1.0)

        val acc = CropAccumulator(gridWidth * gridHeight)
        for (gy in 0 until gridHeight) {
            if (gy < margins.top || gy >= gridHeight - margins.bottom) continue
            val vGlobal = (gy + 0.5) / gridHeight.toDouble()
            if (vGlobal < yStartNorm || vGlobal > yEndNorm) continue
            val vCrop = ((vGlobal - yStartNorm) / (yEndNorm - yStartNorm)).coerceIn(0.0, 1.0)

            for (gx in 0 until gridWidth) {
                if (gx < margins.left || gx >= gridWidth - margins.right) continue
                val uGlobal = (gx + 0.5) / gridWidth.toDouble()
                if (uGlobal < xStartNorm || uGlobal > xEndNorm) continue
                val uCrop = ((uGlobal - xStartNorm) / (xEndNorm - xStartNorm)).coerceIn(0.0, 1.0)
                acc.add(rgbGrid[gy * gridWidth + gx], uCrop, vCrop)
            }
        }
        return acc.finish()
    }

    /** Running totals over the cells inside the crop: Welford mean and variance, the zone buffers and glare counts. */
    private class CropAccumulator(cellCapacity: Int) {
        private var count = 0
        private var mean = 0.0
        private var m2 = 0.0
        private val lightnessValues = DoubleBuffer(cellCapacity)
        private val desktopLuminances = DoubleBuffer(cellCapacity)
        private val clockLuminances = DoubleBuffer(cellCapacity)
        private var desktopSum = 0.0
        private var clockSum = 0.0
        private var safeZoneTotalPixels = 0
        private var safeZoneGlarePixels = 0
        private var sumR = 0.0
        private var sumG = 0.0
        private var sumB = 0.0

        /** Adds one cell at crop-relative position ([uCrop], [vCrop]), each in 0..1. */
        fun add(rgb: Int, uCrop: Double, vCrop: Double) {
            val lum = relativeLuminance(rgb)
            count++
            val delta = lum - mean
            mean += delta / count.toDouble()
            m2 += delta * (lum - mean)
            lightnessValues.add(lightnessOf(lum))
            sumR += (rgb shr 16) and 0xFF
            sumG += (rgb shr 8) and 0xFF
            sumB += rgb and 0xFF

            // Desktop Legibility Zones: top bar [0..0.10], bottom shelf [0.88..1.0], left icon column [0..0.15]
            val inDesktopZone = vCrop <= 0.10 || vCrop >= 0.88 || uCrop <= 0.15
            // Lock-Screen Clock Zone: upper center x in [0.25..0.75], y in [0.10..0.38]
            val inClockZone = uCrop in 0.25..0.75 && vCrop in 0.10..0.38

            if (inDesktopZone) {
                desktopSum += lum
                desktopLuminances.add(lum)
            }
            if (inClockZone) {
                clockSum += lum
                clockLuminances.add(lum)
            }
            if (inDesktopZone || inClockZone) {
                safeZoneTotalPixels++
                if (lum > 0.85) {
                    safeZoneGlarePixels++
                }
            }
        }

        fun finish(): CropStats {
            val globalLum = if (count > 0) mean else 0.5
            val desktopLum = if (desktopLuminances.size > 0) desktopSum / desktopLuminances.size else globalLum
            val clockLum = if (clockLuminances.size > 0) clockSum / clockLuminances.size else globalLum
            val glareFraction = if (safeZoneTotalPixels > 0) {
                safeZoneGlarePixels.toDouble() / safeZoneTotalPixels.toDouble()
            } else {
                0.0
            }
            val variance = if (count > 0) (m2 / count.toDouble()).coerceAtLeast(0.0) else 0.0

            // Perceptual lightness (CIE L*) of the median pixel, not the linear mean: a small bright moon or a
            // few lit windows must not turn a night scene into "neutral" (Article V §3).
            val medianLightness = percentile(lightnessValues, 0.5, fallback = lightnessOf(globalLum))
            // Legibility: white desktop labels need WCAG contrast over the busiest 10% of their zones
            // (4.5:1 for the icon column, shelf and top bar; 3:1 for the large clock).
            val desktopContrast = labelContrast(percentile(desktopLuminances, 0.9, fallback = desktopLum))
            val clockContrast = labelContrast(percentile(clockLuminances, 0.9, fallback = clockLum))

            return CropStats(
                globalLum = globalLum,
                desktopLum = desktopLum,
                clockLum = clockLum,
                glareFraction = glareFraction,
                stdDev = sqrt(variance),
                medianLightness = medianLightness,
                desktopContrast = desktopContrast,
                clockContrast = clockContrast,
                meanR = if (count > 0) sumR / count else 0.0,
                meanG = if (count > 0) sumG / count else 0.0,
                meanB = if (count > 0) sumB / count else 0.0
            )
        }
    }

    private fun classify(stats: CropStats): BrightnessClass {
        return when {
            stats.medianLightness <= DARK_MEDIAN_LIGHTNESS &&
                stats.desktopContrast >= DESKTOP_LABEL_CONTRAST &&
                stats.clockContrast >= CLOCK_LABEL_CONTRAST &&
                stats.glareFraction <= 0.06 -> BrightnessClass.DARK
            stats.medianLightness >= LIGHT_MEDIAN_LIGHTNESS && stats.stdDev <= 0.38 -> BrightnessClass.LIGHT
            else -> BrightnessClass.NEUTRAL
        }
    }

    private fun fillNote(cropRect: CropRect, display: DisplayProfile): String {
        val coveragePct = (cropRect.pixelCoverageRatio * 100.0).roundToInt()
        return when {
            cropRect.usesBlurredFrameFill ->
                "Prepared with user-approved blurred frame extension (${coveragePct}% native core)."
            cropRect.pixelCoverageRatio >= 1.0 - 1e-4 ->
                "100% native pixel coverage (${cropRect.width} × ${cropRect.height} crop for ${display.resolutionLabel})."
            display.qualityTier == DisplayQualityTier.STANDARD_100 ->
                "${coveragePct}% native pixel coverage (${cropRect.width} × ${cropRect.height} crop for ${display.resolutionLabel}; passes >= 98% native floor)."
            display.qualityTier == DisplayQualityTier.MID_80 ->
                "${coveragePct}% native pixel coverage (${cropRect.width} × ${cropRect.height} crop for ${display.resolutionLabel}; passes >= 80% mid-PPI floor)."
            else ->
                "${coveragePct}% native pixel coverage (${cropRect.width} × ${cropRect.height} crop for ${display.resolutionLabel}; passes >= 65% high-DPI floor)."
        }
    }

    private fun rejected(code: String, explanation: String) = ImageEvaluationResult(
        isEligible = false,
        rejectionReasonCode = code,
        globalLuminance = 0.0,
        desktopZoneLuminance = 0.0,
        lockClockZoneLuminance = 0.0,
        glareFractionInSafeZones = 0.0,
        contrastStdDev = 0.0,
        brightnessClass = BrightnessClass.NEUTRAL,
        cropRect = null,
        explanation = explanation
    )

    private data class Margins(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val isEmpty: Boolean get() = left == 0 && top == 0 && right == 0 && bottom == 0
    }

    /**
     * Finds the mat, frame or scanner border around a picture. A band only counts when it is
     * - uniform and clearly different from the picture next to it (a night sky whose edge is merely a little
     *   darker is kept whole),
     * - neutral in colour (black, white, grey or cream), so a flat blue sky or a coloured wall is not mistaken
     *   for a border, and
     * - present on both opposite sides, as a mat or frame is (a lone band along one edge is usually sky,
     *   water or a studio backdrop).
     * At most 15% per side (never more than 40% in total) is trimmed.
     */
    private fun detectMargins(grid: IntArray, w: Int, h: Int): Margins {
        if (w < 16 || h < 10) return Margins(0, 0, 0, 0)
        val lum = DoubleArray(w * h) { relativeLuminance(grid[it]) }

        fun columnStats(x: Int): Pair<Double, Double> = bandStats((0 until h).map { lum[it * w + x] })
        fun rowStats(y: Int): Pair<Double, Double> = bandStats((0 until w).map { lum[y * w + it] })
        fun columnNeutral(x: Int) = isNeutral((0 until h).map { grid[it * w + x] })
        fun rowNeutral(y: Int) = isNeutral((0 until w).map { grid[y * w + it] })

        fun measure(count: Int, maxBands: Int, stats: (Int) -> Pair<Double, Double>, neutral: (Int) -> Boolean): Int {
            val first = stats(0)
            if (first.second > MARGIN_UNIFORM_STD || !neutral(0)) return 0
            var bands = 1
            while (bands < maxBands && bands < count) {
                val next = stats(bands)
                if (next.second > MARGIN_UNIFORM_STD || abs(next.first - first.first) > MARGIN_SAME_TONE) break
                bands++
            }
            // The picture next to the band must differ from it (or be clearly textured).
            val interior = (bands until minOf(bands + 3, count)).map(stats)
            if (interior.isEmpty()) return 0
            val interiorMean = interior.map { it.first }.average()
            val interiorStd = interior.map { it.second }.average()
            val differs = abs(interiorMean - first.first) >= MARGIN_MIN_CONTRAST ||
                (interiorStd >= MARGIN_TEXTURED_STD && abs(interiorMean - first.first) >= MARGIN_MIN_CONTRAST / 2)
            return if (differs) bands else 0
        }

        val maxCols = (w * MAX_MARGIN_PER_SIDE).toInt()
        val maxRows = (h * MAX_MARGIN_PER_SIDE).toInt()
        var left = measure(w, maxCols, ::columnStats, ::columnNeutral)
        var right = measure(w, maxCols, { columnStats(w - 1 - it) }, { columnNeutral(w - 1 - it) })
        var top = measure(h, maxRows, ::rowStats, ::rowNeutral)
        var bottom = measure(h, maxRows, { rowStats(h - 1 - it) }, { rowNeutral(h - 1 - it) })
        if (left == 0 || right == 0) { left = 0; right = 0 }
        if (top == 0 || bottom == 0) { top = 0; bottom = 0 }
        val keptW = (w - left - right).toDouble() / w
        val keptH = (h - top - bottom).toDouble() / h
        return if (keptW < MIN_KEPT_FRACTION || keptH < MIN_KEPT_FRACTION) Margins(0, 0, 0, 0) else Margins(left, top, right, bottom)
    }

    /** Black, white, grey or a pale cream: low colour saturation (or too dark to have a colour). */
    private fun isNeutral(pixels: List<Int>): Boolean {
        val r = pixels.sumOf { (it shr 16) and 0xFF } / pixels.size.toDouble()
        val g = pixels.sumOf { (it shr 8) and 0xFF } / pixels.size.toDouble()
        val b = pixels.sumOf { it and 0xFF } / pixels.size.toDouble()
        val max = maxOf(r, g, b)
        return max < 30.0 || (max - minOf(r, g, b)) / max <= MARGIN_MAX_SATURATION
    }

    private fun bandStats(values: List<Double>): Pair<Double, Double> {
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return mean to sqrt(variance)
    }

    companion object {
        // Perceptual lightness (CIE L*) thresholds; L* 52 equals the old linear-luminance 0.20 line.
        const val DARK_MEDIAN_LIGHTNESS = 40.0
        const val LIGHT_MEDIAN_LIGHTNESS = 52.0
        // WCAG contrast of white labels: 4.5:1 for small text (icons, shelf), 3:1 for large text (clock).
        const val DESKTOP_LABEL_CONTRAST = 4.5
        const val CLOCK_LABEL_CONTRAST = 3.0
        private const val MAX_MARGIN_PER_SIDE = 0.15
        private const val MIN_KEPT_FRACTION = 0.6
        private const val MARGIN_UNIFORM_STD = 0.02
        private const val MARGIN_SAME_TONE = 0.03
        private const val MARGIN_MIN_CONTRAST = 0.05
        private const val MARGIN_TEXTURED_STD = 0.04
        private const val MARGIN_MAX_SATURATION = 0.25

        /** CIE L* (0..100) from relative luminance Y (0..1). */
        fun lightnessOf(y: Double): Double {
            val f = if (y > 0.008856) Math.cbrt(y) else 7.787 * y + 16.0 / 116.0
            return (116.0 * f - 16.0).coerceIn(0.0, 100.0)
        }

        /** WCAG contrast ratio of a white label over a background of relative luminance [y]. */
        fun labelContrast(y: Double): Double = 1.05 / (y + 0.05)

        private fun percentile(values: DoubleBuffer, fraction: Double, fallback: Double): Double {
            if (values.size == 0) return fallback
            val sorted = values.sortedCopy()
            return sorted[((sorted.size - 1) * fraction).roundToInt().coerceIn(0, sorted.size - 1)]
        }

        /** A growable-free primitive buffer: avoids boxing one Double per grid cell for every evaluation. */
        private class DoubleBuffer(capacity: Int) {
            private val data = DoubleArray(capacity.coerceAtLeast(1))
            var size = 0
                private set
            fun add(value: Double) { data[size++] = value }
            fun sortedCopy(): DoubleArray = data.copyOf(size).also { it.sort() }
        }

        /** Dominant hue of the mean colour in 30° steps, or -1 when it is (nearly) grey, black or white. */
        fun hueBucketOf(r: Double, g: Double, b: Double): Int {
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val delta = max - min
            if (max < 20.0 || delta / max < 0.12) return -1
            val hue = when (max) {
                r -> 60.0 * (((g - b) / delta) % 6.0)
                g -> 60.0 * ((b - r) / delta + 2.0)
                else -> 60.0 * ((r - g) / delta + 4.0)
            }.let { if (it < 0) it + 360.0 else it }
            return ((hue / 30.0).toInt()).coerceIn(0, 11)
        }

        fun relativeLuminance(rgb: Int): Double {
            val rSrgb = ((rgb shr 16) and 0xFF) / 255.0
            val gSrgb = ((rgb shr 8) and 0xFF) / 255.0
            val bSrgb = (rgb and 0xFF) / 255.0

            val r = linearizeChannel(rSrgb)
            val g = linearizeChannel(gSrgb)
            val b = linearizeChannel(bSrgb)
            return (0.2126 * r + 0.7152 * g + 0.0722 * b).coerceIn(0.0, 1.0)
        }

        private fun linearizeChannel(c: Double): Double {
            return if (c <= 0.04045) {
                c / 12.92
            } else {
                ((c + 0.055) / 1.055).pow(2.4)
            }
        }
    }
}
