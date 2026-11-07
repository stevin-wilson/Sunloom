package com.sunloom.app.domain.renderer

import com.sunloom.app.domain.evaluator.ImageEvaluator
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.DisplayQualityTier
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Article IV.2, IV.4 & IV.5: Crop before stretch & Subject-aware deterministic cropping.
 * Never distorts an image. Slides an aspect-ratio-preserving crop window to maximize
 * local Shannon entropy + edge energy while avoiding placing harsh visual clutter
 * directly beneath the lock-screen clock zone.
 */
class CropRenderer {

    fun computeOptimalCrop(
        sourceWidth: Int,
        sourceHeight: Int,
        rgbGrid: IntArray,
        gridWidth: Int,
        gridHeight: Int,
        display: DisplayProfile,
        allowBlurredFrameFill: Boolean = false,
        preferFullFrame: Boolean = false
    ): CropRect? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || display.widthPx <= 0 || display.heightPx <= 0) {
            return null
        }

        val targetAspect = display.aspectRatio
        val sourceAspect = sourceWidth.toDouble() / sourceHeight.toDouble()

        val cropWidth: Int
        val cropHeight: Int
        if (sourceAspect >= targetAspect) {
            cropHeight = sourceHeight
            cropWidth = (sourceHeight * targetAspect).roundToInt().coerceIn(1, sourceWidth)
        } else {
            cropWidth = sourceWidth
            cropHeight = (sourceWidth / targetAspect).roundToInt().coerceIn(1, sourceHeight)
        }

        val widthCoverage = cropWidth.toDouble() / display.widthPx.toDouble()
        val heightCoverage = cropHeight.toDouble() / display.heightPx.toDouble()
        val minCoverage = min(widthCoverage, heightCoverage)
        val cropLongEdge = max(cropWidth, cropHeight)

        // Compute deterministic importance map (edge energy + local luminance contrast)
        val importance = computeImportanceGrid(rgbGrid, gridWidth, gridHeight)
        val (focalX, focalY) = findFocalCenter(importance, gridWidth, gridHeight)

        // Article IV §2 (Constitution v0.6): Tiered physical-PPI floor
        val passesNativeFloor = meetsNativeQualityTier(minCoverage, cropLongEdge, display)

        // A crop that discards most of the picture is a poor composition; show the whole frame instead
        // (blurred fill, only when the user allows it) rather than a sliver of it.
        if (!passesNativeFloor || (preferFullFrame && allowBlurredFrameFill)) {
            if (!allowBlurredFrameFill || !passesBlurredFillFloor(sourceWidth, sourceHeight, minCoverage, display)) {
                return null
            }
            return CropRect(
                left = 0,
                top = 0,
                width = sourceWidth,
                height = sourceHeight,
                focalX = focalX,
                focalY = focalY,
                usesBlurredFrameFill = true,
                pixelCoverageRatio = minCoverage
            )
        }

        // Slide the crop box along the free axis (horizontal or vertical) across 9 candidate positions
        var bestLeft = ((sourceWidth - cropWidth) * focalX).roundToInt().coerceIn(0, sourceWidth - cropWidth)
        var bestTop = ((sourceHeight - cropHeight) * focalY).roundToInt().coerceIn(0, sourceHeight - cropHeight)
        var bestScore = Double.NEGATIVE_INFINITY

        val steps = 9
        for (i in 0 until steps) {
            val t = i.toDouble() / (steps - 1).toDouble()
            val candidateLeft = ((sourceWidth - cropWidth) * t).roundToInt().coerceIn(0, sourceWidth - cropWidth)
            val candidateTop = ((sourceHeight - cropHeight) * t).roundToInt().coerceIn(0, sourceHeight - cropHeight)

            val score = scoreCropWindow(
                left = candidateLeft,
                top = candidateTop,
                cropWidth = cropWidth,
                cropHeight = cropHeight,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                importance = importance,
                gridWidth = gridWidth,
                gridHeight = gridHeight,
                focalX = focalX,
                focalY = focalY
            )
            if (score > bestScore) {
                bestScore = score
                bestLeft = candidateLeft
                bestTop = candidateTop
            }
        }

        return CropRect(
            left = bestLeft,
            top = bestTop,
            width = cropWidth,
            height = cropHeight,
            focalX = focalX,
            focalY = focalY,
            usesBlurredFrameFill = false,
            pixelCoverageRatio = minCoverage
        )
    }

    /**
     * Computes an aspect-ratio-preserving crop window centered as closely as possible on
     * normalized `(focalX, focalY)` without non-uniform scaling.
     */
    fun computeAspectCropBounds(
        sourceWidth: Int,
        sourceHeight: Int,
        focalX: Double,
        focalY: Double,
        targetAspect: Double
    ): CropRect {
        val srcW = sourceWidth.coerceAtLeast(1)
        val srcH = sourceHeight.coerceAtLeast(1)
        val safeAspect = if (targetAspect > 0.0) targetAspect else 16.0 / 10.0
        val sourceAspect = srcW.toDouble() / srcH.toDouble()

        val cropWidth: Int
        val cropHeight: Int
        if (sourceAspect >= safeAspect) {
            cropHeight = srcH
            cropWidth = (srcH * safeAspect).roundToInt().coerceIn(1, srcW)
        } else {
            cropWidth = srcW
            cropHeight = (srcW / safeAspect).roundToInt().coerceIn(1, srcH)
        }

        val safeFocalX = focalX.coerceIn(0.0, 1.0)
        val safeFocalY = focalY.coerceIn(0.0, 1.0)
        val desiredCenterX = safeFocalX * srcW
        val desiredCenterY = safeFocalY * srcH
        val left = (desiredCenterX - cropWidth * 0.5).roundToInt().coerceIn(0, (srcW - cropWidth).coerceAtLeast(0))
        val top = (desiredCenterY - cropHeight * 0.5).roundToInt().coerceIn(0, (srcH - cropHeight).coerceAtLeast(0))

        return CropRect(
            left = left,
            top = top,
            width = cropWidth.coerceIn(1, srcW - left),
            height = cropHeight.coerceIn(1, srcH - top),
            focalX = safeFocalX,
            focalY = safeFocalY,
            usesBlurredFrameFill = false,
            pixelCoverageRatio = 1.0
        )
    }

    /**
     * Recomputes an aspect-ratio-preserving crop window anchored around a persisted
     * normalized focal point `(focalX, focalY)` for the current [display].
     */
    fun computeCropFromFocalPoint(
        sourceWidth: Int,
        sourceHeight: Int,
        focalX: Double,
        focalY: Double,
        display: DisplayProfile,
        allowBlurredFrameFill: Boolean = false
    ): CropRect? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || display.widthPx <= 0 || display.heightPx <= 0) {
            return null
        }
        val baseBounds = computeAspectCropBounds(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            focalX = focalX,
            focalY = focalY,
            targetAspect = display.aspectRatio
        )

        val widthCoverage = baseBounds.width.toDouble() / display.widthPx.toDouble()
        val heightCoverage = baseBounds.height.toDouble() / display.heightPx.toDouble()
        val minCoverage = min(widthCoverage, heightCoverage)
        val cropLongEdge = max(baseBounds.width, baseBounds.height)

        val passesNativeFloor = meetsNativeQualityTier(minCoverage, cropLongEdge, display)

        if (!passesNativeFloor) {
            if (!allowBlurredFrameFill || !passesBlurredFillFloor(sourceWidth, sourceHeight, minCoverage, display)) {
                return null
            }
            return CropRect(
                left = 0,
                top = 0,
                width = sourceWidth,
                height = sourceHeight,
                focalX = baseBounds.focalX,
                focalY = baseBounds.focalY,
                usesBlurredFrameFill = true,
                pixelCoverageRatio = minCoverage
            )
        }

        return baseBounds.copy(pixelCoverageRatio = minCoverage)
    }

    private fun passesBlurredFillFloor(
        sourceWidth: Int,
        sourceHeight: Int,
        minCoverage: Double,
        display: DisplayProfile
    ): Boolean {
        if (minCoverage >= BLURRED_FILL_MIN_COVERAGE) return true
        // When blurred frame fill is enabled, the uncropped foreground image is scaled proportionally
        // to fit inside the display, covering 100% along its constraining axis and max(srcW/dispW, srcH/dispH)
        // of the display's dimensions. Accept museum/archive renditions >= 1600px on their long edge
        // when their fitted coverage meets BLURRED_FILL_MIN_COVERAGE.
        val fittedCoverage = max(
            sourceWidth.toDouble() / display.widthPx.coerceAtLeast(1).toDouble(),
            sourceHeight.toDouble() / display.heightPx.coerceAtLeast(1).toDouble()
        )
        val sourceLongEdge = max(sourceWidth, sourceHeight)
        return fittedCoverage >= BLURRED_FILL_MIN_COVERAGE && sourceLongEdge >= HIGH_DPI_MIN_LONG_EDGE_PX
    }

    private fun computeImportanceGrid(rgbGrid: IntArray, gridWidth: Int, gridHeight: Int): DoubleArray {
        val lum = DoubleArray(gridWidth * gridHeight) { idx ->
            ImageEvaluator.relativeLuminance(rgbGrid[idx])
        }
        val importance = DoubleArray(gridWidth * gridHeight)

        // Global mean colour, for "colour rarity": a cell unlike the rest of the picture draws the eye.
        var meanR = 0.0
        var meanG = 0.0
        var meanB = 0.0
        for (i in 0 until gridWidth * gridHeight) {
            val px = rgbGrid[i]
            meanR += (px shr 16) and 0xFF
            meanG += (px shr 8) and 0xFF
            meanB += px and 0xFF
        }
        val cells = (gridWidth * gridHeight).coerceAtLeast(1).toDouble()
        meanR /= cells * 255.0
        meanG /= cells * 255.0
        meanB /= cells * 255.0

        for (y in 0 until gridHeight) {
            for (x in 0 until gridWidth) {
                val left = lum[y * gridWidth + (x - 1).coerceAtLeast(0)]
                val right = lum[y * gridWidth + (x + 1).coerceAtMost(gridWidth - 1)]
                val up = lum[(y - 1).coerceAtLeast(0) * gridWidth + x]
                val down = lum[(y + 1).coerceAtMost(gridHeight - 1) * gridWidth + x]

                val edgeEnergy = abs(right - left) + abs(down - up)

                // Local 3x3 quantized Shannon entropy (8 luminance bins)
                val bins = IntArray(8)
                var count = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = (x + dx).coerceIn(0, gridWidth - 1)
                        val ny = (y + dy).coerceIn(0, gridHeight - 1)
                        val bin = (lum[ny * gridWidth + nx] * 7.999).toInt().coerceIn(0, 7)
                        bins[bin]++
                        count++
                    }
                }
                var entropy = 0.0
                for (b in bins) {
                    if (b > 0) {
                        val p = b.toDouble() / count.toDouble()
                        entropy -= p * ln(p)
                    }
                }

                // Centre-surround contrast: how much this cell stands out from its 5x5 neighbourhood.
                var surround = 0.0
                var surroundCount = 0
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        if (dx == 0 && dy == 0) continue
                        surround += lum[(y + dy).coerceIn(0, gridHeight - 1) * gridWidth + (x + dx).coerceIn(0, gridWidth - 1)]
                        surroundCount++
                    }
                }
                val centreSurround = abs(lum[y * gridWidth + x] - surround / surroundCount)

                val px = rgbGrid[y * gridWidth + x]
                val dr = ((px shr 16) and 0xFF) / 255.0 - meanR
                val dg = ((px shr 8) and 0xFF) / 255.0 - meanG
                val db = (px and 0xFF) / 255.0 - meanB
                val colourRarity = kotlin.math.sqrt(dr * dr + dg * dg + db * db) / kotlin.math.sqrt(3.0)

                importance[y * gridWidth + x] =
                    edgeEnergy * 1.6 + entropy * 0.4 + centreSurround * 1.2 + colourRarity * 0.5
            }
        }
        return importance
    }

    private fun findFocalCenter(importance: DoubleArray, gridWidth: Int, gridHeight: Int): Pair<Double, Double> {
        var totalWeight = 0.0
        var sumX = 0.0
        var sumY = 0.0
        for (y in 0 until gridHeight) {
            for (x in 0 until gridWidth) {
                val w = importance[y * gridWidth + x]
                totalWeight += w
                sumX += ((x + 0.5) / gridWidth.toDouble()) * w
                sumY += ((y + 0.5) / gridHeight.toDouble()) * w
            }
        }
        return if (totalWeight > 1e-6) {
            (sumX / totalWeight).coerceIn(0.0, 1.0) to (sumY / totalWeight).coerceIn(0.0, 1.0)
        } else {
            0.5 to 0.5
        }
    }

    private fun scoreCropWindow(
        left: Int,
        top: Int,
        cropWidth: Int,
        cropHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        importance: DoubleArray,
        gridWidth: Int,
        gridHeight: Int,
        focalX: Double,
        focalY: Double
    ): Double {
        val x0 = left.toDouble() / sourceWidth.toDouble()
        val y0 = top.toDouble() / sourceHeight.toDouble()
        val x1 = (left + cropWidth).toDouble() / sourceWidth.toDouble()
        val y1 = (top + cropHeight).toDouble() / sourceHeight.toDouble()

        var capturedSubject = 0.0
        var clockZoneClutter = 0.0
        var shelfClutter = 0.0

        for (gy in 0 until gridHeight) {
            val v = (gy + 0.5) / gridHeight.toDouble()
            if (v < y0 || v > y1) continue
            val vRel = (v - y0) / (y1 - y0).coerceAtLeast(1e-6)

            for (gx in 0 until gridWidth) {
                val u = (gx + 0.5) / gridWidth.toDouble()
                if (u < x0 || u > x1) continue
                val uRel = (u - x0) / (x1 - x0).coerceAtLeast(1e-6)

                val imp = importance[gy * gridWidth + gx]
                capturedSubject += imp

                // Penalize placing high-clutter edges directly under the lock-screen clock zone
                if (uRel in 0.25..0.75 && vRel in 0.10..0.35) {
                    clockZoneClutter += imp * 0.25
                }
                // Subject hidden behind the desktop icon column or the bottom shelf is wasted
                if (uRel <= 0.15 || vRel >= 0.88) {
                    shelfClutter += imp * 0.3
                }
            }
        }

        val centerU = (x0 + x1) * 0.5
        val centerV = (y0 + y1) * 0.5
        // A weak centring prior, so a subject is not pushed to an edge ...
        val focalDistancePenalty = (abs(centerU - focalX) + abs(centerV - focalY)) * 0.5

        // ... and a stronger pull of the subject towards a rule-of-thirds power point of the window.
        val relFocalU = ((focalX - x0) / (x1 - x0).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
        val relFocalV = ((focalY - y0) / (y1 - y0).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
        val thirdsDistance = POWER_POINTS_U.minOf { pu ->
            POWER_POINTS_V.minOf { pv -> abs(relFocalU - pu) + abs(relFocalV - pv) }
        }

        return capturedSubject - clockZoneClutter - shelfClutter - focalDistancePenalty - thirdsDistance * THIRDS_WEIGHT
    }

    companion object {
        /** Weight of the rule-of-thirds term relative to the captured-subject score. */
        private const val THIRDS_WEIGHT = 1.5
        private val POWER_POINTS_U = doubleArrayOf(1.0 / 3.0, 2.0 / 3.0)
        private val POWER_POINTS_V = doubleArrayOf(1.0 / 3.0, 2.0 / 3.0)

        /** Share of the picture a crop must keep; below it the whole frame is shown (blurred fill) or the image skipped. */
        const val MIN_VISIBLE_AREA = 0.5

        /** Fraction of the source picture's area that a display-shaped crop keeps. */
        fun visibleAreaFraction(sourceWidth: Int, sourceHeight: Int, display: DisplayProfile): Double {
            if (sourceWidth <= 0 || sourceHeight <= 0 || display.heightPx <= 0) return 0.0
            val sourceAspect = sourceWidth.toDouble() / sourceHeight.toDouble()
            val targetAspect = display.aspectRatio
            return if (sourceAspect >= targetAspect) targetAspect / sourceAspect else sourceAspect / targetAspect
        }

        /**
         * Why a picture advertised at [advertisedWidth] x [advertisedHeight] should not be downloaded for [display]:
         * `COMPOSITION_CROPS_TOO_MUCH` (a display-shaped crop keeps under half of it, unless blurred fill is allowed)
         * or `INSUFFICIENT_ADVERTISED_RESOLUTION` (too few pixels for the display's quality tier); null when it may be
         * downloaded or the size is unknown. Shape is judged first.
         */
        fun advertisedSizeRejection(
            advertisedWidth: Int,
            advertisedHeight: Int,
            display: DisplayProfile,
            allowBlurredFrameFill: Boolean
        ): String? {
            if (advertisedWidth <= 0 || advertisedHeight <= 0) return null
            if (!allowBlurredFrameFill &&
                visibleAreaFraction(advertisedWidth, advertisedHeight, display) < MIN_VISIBLE_AREA
            ) {
                return "COMPOSITION_CROPS_TOO_MUCH"
            }
            if (!canSourceSatisfyDisplay(max(advertisedWidth, advertisedHeight), display, allowBlurredFrameFill)) {
                return "INSUFFICIENT_ADVERTISED_RESOLUTION"
            }
            return null
        }

        /** Upper bound on pixels in any bitmap handed to WallpaperManager (~64 MB as ARGB_8888). */
        const val MAX_OUTPUT_PIXELS = 16_000_000

        /** Canvas and foreground rectangle for a blurred-fill wallpaper; see [blurredFillLayout]. */
        data class BlurredFillLayout(
            val canvasWidth: Int,
            val canvasHeight: Int,
            val fgLeft: Int,
            val fgTop: Int,
            val fgWidth: Int,
            val fgHeight: Int
        )

        /**
         * Lays out a blurred-fill wallpaper (Article IV §4) so the whole picture stays visible on every display.
         * One shared canvas serves all [displays]; the system shows each display a centred slice of it, so the
         * picture is fitted inside the part of the canvas that every slice contains. On a single display that is
         * the whole canvas. The picture is never stretched, and it is smaller on a mixed set: the price of
         * showing all of it on every screen.
         */
        fun blurredFillLayout(sourceWidth: Int, sourceHeight: Int, displays: List<DisplayProfile>): BlurredFillLayout {
            val valid = displays.filter { it.widthPx > 0 && it.heightPx > 0 }
            require(valid.isNotEmpty()) { "blurredFillLayout needs at least one display" }
            val rMin = valid.minOf { it.aspectRatio }
            val rMax = valid.maxOf { it.aspectRatio }
            val canvasAspect = sqrt(rMin * rMax)
            val rawWidth = valid.maxOf { it.widthPx }.coerceAtLeast(64)
            val rawHeight = if (valid.size == 1) {
                valid.first().heightPx.coerceAtLeast(64)
            } else {
                (rawWidth / canvasAspect).roundToInt().coerceAtLeast(64)
            }
            val (canvasW, canvasH) = budgetedSize(rawWidth, rawHeight)

            var boxW = canvasW
            var boxH = canvasH
            for (d in valid) {
                val sliceW: Int
                val sliceH: Int
                if (canvasW.toDouble() / canvasH >= d.aspectRatio) {
                    sliceW = (canvasH * d.aspectRatio).roundToInt().coerceIn(1, canvasW)
                    sliceH = canvasH
                } else {
                    sliceW = canvasW
                    sliceH = (canvasW / d.aspectRatio).roundToInt().coerceIn(1, canvasH)
                }
                boxW = min(boxW, sliceW)
                boxH = min(boxH, sliceH)
            }
            val scale = min(boxW.toDouble() / sourceWidth.coerceAtLeast(1), boxH.toDouble() / sourceHeight.coerceAtLeast(1))
            val fgW = (sourceWidth * scale).roundToInt().coerceIn(1, boxW)
            val fgH = (sourceHeight * scale).roundToInt().coerceIn(1, boxH)
            return BlurredFillLayout(canvasW, canvasH, (canvasW - fgW) / 2, (canvasH - fgH) / 2, fgW, fgH)
        }

        /**
         * Uniformly scales [width] x [height] down (never up, never changing aspect ratio) so the
         * total pixel count stays within [maxPixels]. Works for any display shape, from portrait
         * monitors to 32:9 super-ultrawide and 8K panels.
         */
        fun budgetedSize(width: Int, height: Int, maxPixels: Int = MAX_OUTPUT_PIXELS): Pair<Int, Int> {
            val w = width.coerceAtLeast(1)
            val h = height.coerceAtLeast(1)
            val pixels = w.toLong() * h.toLong()
            if (pixels <= maxPixels.toLong()) return w to h
            val scale = kotlin.math.sqrt(maxPixels.toDouble() / pixels.toDouble())
            return (w * scale).roundToInt().coerceAtLeast(1) to (h * scale).roundToInt().coerceAtLeast(1)
        }

        const val MID_PPI_COVERAGE_FLOOR = 0.80
        const val HIGH_DPI_COVERAGE_FLOOR = 0.65
        const val HIGH_DPI_MIN_LONG_EDGE_PX = 1600
        const val BLURRED_FILL_MIN_COVERAGE = 0.50

        fun meetsNativeQualityTier(
            minCoverage: Double,
            cropLongEdgePx: Int,
            display: DisplayProfile
        ): Boolean {
            return when (display.qualityTier) {
                DisplayQualityTier.STANDARD_100 -> minCoverage >= DisplayQualityTier.STANDARD_100.minCoverageRatio - 1e-4
                DisplayQualityTier.MID_80 -> minCoverage >= MID_PPI_COVERAGE_FLOOR - 1e-4
                DisplayQualityTier.HIGH_65 ->
                    minCoverage >= HIGH_DPI_COVERAGE_FLOOR - 1e-4 &&
                        cropLongEdgePx >= HIGH_DPI_MIN_LONG_EDGE_PX
            }
        }

        fun canSourceSatisfyDisplay(
            maxSourceEdgePx: Int,
            display: DisplayProfile,
            allowBlurredFrameFill: Boolean
        ): Boolean {
            if (maxSourceEdgePx <= 0 || display.widthPx <= 0 || display.heightPx <= 0) return false
            val displayLongEdge = max(display.widthPx, display.heightPx).toDouble()
            val displayShortEdge = min(display.widthPx, display.heightPx).toDouble()
            val bestMinCoverage = maxSourceEdgePx.toDouble() / displayLongEdge
            val passesNative = meetsNativeQualityTier(bestMinCoverage, maxSourceEdgePx, display)
            if (passesNative) return true
            if (!allowBlurredFrameFill) return false
            val bestFittedCoverage = maxSourceEdgePx.toDouble() / displayShortEdge
            return bestMinCoverage >= BLURRED_FILL_MIN_COVERAGE ||
                (bestFittedCoverage >= BLURRED_FILL_MIN_COVERAGE && maxSourceEdgePx >= HIGH_DPI_MIN_LONG_EDGE_PX)
        }
    }
}
