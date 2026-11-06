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
        allowBlurredFrameFill: Boolean = false
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

        if (!passesNativeFloor) {
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

                importance[y * gridWidth + x] = edgeEnergy * 1.6 + entropy * 0.4
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
            }
        }

        val centerU = (x0 + x1) * 0.5
        val centerV = (y0 + y1) * 0.5
        val focalDistancePenalty = (abs(centerU - focalX) + abs(centerV - focalY)) * 2.0

        return capturedSubject - clockZoneClutter - focalDistancePenalty
    }

    companion object {
        /** Upper bound on pixels in any bitmap handed to WallpaperManager (~64 MB as ARGB_8888). */
        const val MAX_OUTPUT_PIXELS = 16_000_000

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
                DisplayQualityTier.STANDARD_100 -> minCoverage >= 1.0 - 1e-4
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
