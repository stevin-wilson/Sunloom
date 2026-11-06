package com.sunloom.app.domain.evaluator

import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.DisplayQualityTier
import com.sunloom.app.domain.model.ImageEvaluationResult
import com.sunloom.app.domain.renderer.CropRenderer
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
        if (imageWidth <= 0 || imageHeight <= 0 || gridWidth <= 0 || gridHeight <= 0 || rgbGrid.size < gridWidth * gridHeight) {
            return ImageEvaluationResult(
                isEligible = false,
                rejectionReasonCode = "INVALID_DIMENSIONS",
                globalLuminance = 0.0,
                desktopZoneLuminance = 0.0,
                lockClockZoneLuminance = 0.0,
                glareFractionInSafeZones = 0.0,
                contrastStdDev = 0.0,
                brightnessClass = BrightnessClass.NEUTRAL,
                cropRect = null,
                explanation = "Image dimensions ($imageWidth × $imageHeight) or pixel sample grid are invalid."
            )
        }

        val cropRect: CropRect = cropRenderer.computeOptimalCrop(
            sourceWidth = imageWidth,
            sourceHeight = imageHeight,
            rgbGrid = rgbGrid,
            gridWidth = gridWidth,
            gridHeight = gridHeight,
            display = display,
            allowBlurredFrameFill = allowBlurredFrameFill
        ) ?: run {
            val floorDesc = when (display.qualityTier) {
                DisplayQualityTier.HIGH_65 -> "65% high-DPI floor (>= 1600px long edge)"
                DisplayQualityTier.MID_80 -> "80% mid-PPI floor (120–209 PPI)"
                DisplayQualityTier.STANDARD_100 -> "100% native pixel floor"
            }
            return ImageEvaluationResult(
                isEligible = false,
                rejectionReasonCode = "INSUFFICIENT_POST_CROP_RESOLUTION",
                globalLuminance = 0.0,
                desktopZoneLuminance = 0.0,
                lockClockZoneLuminance = 0.0,
                glareFractionInSafeZones = 0.0,
                contrastStdDev = 0.0,
                brightnessClass = BrightnessClass.NEUTRAL,
                cropRect = null,
                explanation = "After cropping to ${display.resolutionLabel} aspect ratio, the source image ($imageWidth × $imageHeight) does not meet the $floorDesc without enlargement."
            )
        }

        // Compute relative luminance Y for the cropped region
        val xStartNorm = (cropRect.left.toDouble() / imageWidth.toDouble()).coerceIn(0.0, 0.99)
        val yStartNorm = (cropRect.top.toDouble() / imageHeight.toDouble()).coerceIn(0.0, 0.99)
        val xEndNorm = ((cropRect.left + cropRect.width).toDouble() / imageWidth.toDouble()).coerceIn(xStartNorm + 0.01, 1.0)
        val yEndNorm = ((cropRect.top + cropRect.height).toDouble() / imageHeight.toDouble()).coerceIn(yStartNorm + 0.01, 1.0)

        var globalCount = 0
        var globalMean = 0.0
        var globalM2 = 0.0
        var desktopSum = 0.0
        var desktopCount = 0
        var clockSum = 0.0
        var clockCount = 0
        var safeZoneTotalPixels = 0
        var safeZoneGlarePixels = 0

        for (gy in 0 until gridHeight) {
            val vGlobal = (gy + 0.5) / gridHeight.toDouble()
            if (vGlobal < yStartNorm || vGlobal > yEndNorm) continue
            val vCrop = ((vGlobal - yStartNorm) / (yEndNorm - yStartNorm)).coerceIn(0.0, 1.0)

            for (gx in 0 until gridWidth) {
                val uGlobal = (gx + 0.5) / gridWidth.toDouble()
                if (uGlobal < xStartNorm || uGlobal > xEndNorm) continue
                val uCrop = ((uGlobal - xStartNorm) / (xEndNorm - xStartNorm)).coerceIn(0.0, 1.0)

                val rgb = rgbGrid[gy * gridWidth + gx]
                val lum = relativeLuminance(rgb)
                globalCount++
                val delta = lum - globalMean
                globalMean += delta / globalCount.toDouble()
                globalM2 += delta * (lum - globalMean)

                // Desktop Legibility Zones: top bar [0..0.10], bottom shelf [0.88..1.0], left icon column [0..0.15]
                val inDesktopZone = vCrop <= 0.10 || vCrop >= 0.88 || uCrop <= 0.15
                // Lock-Screen Clock Zone: upper center x in [0.25..0.75], y in [0.10..0.38]
                val inClockZone = uCrop in 0.25..0.75 && vCrop in 0.10..0.38

                if (inDesktopZone) {
                    desktopSum += lum
                    desktopCount++
                }
                if (inClockZone) {
                    clockSum += lum
                    clockCount++
                }
                if (inDesktopZone || inClockZone) {
                    safeZoneTotalPixels++
                    if (lum > 0.85) {
                        safeZoneGlarePixels++
                    }
                }
            }
        }

        val globalLum = if (globalCount > 0) globalMean else 0.5
        val desktopLum = if (desktopCount > 0) desktopSum / desktopCount.toDouble() else globalLum
        val clockLum = if (clockCount > 0) clockSum / clockCount.toDouble() else globalLum
        val glareFraction = if (safeZoneTotalPixels > 0) {
            safeZoneGlarePixels.toDouble() / safeZoneTotalPixels.toDouble()
        } else {
            0.0
        }

        val variance = if (globalCount > 0) {
            (globalM2 / globalCount.toDouble()).coerceAtLeast(0.0)
        } else {
            0.0
        }
        val stdDev = sqrt(variance)

        // Determine visual Light / Dark classification (Article I.2 & V.3)
        // Calibrated for linearized sRGB relative luminance (where mid-gray 0.5^2.4 = 0.189):
        // - DARK: true night/evening/space scenes (globalLum <= 0.15) with calm clock & desktop zones
        // - LIGHT: daylight skies, landscapes, and architecture (globalLum >= 0.20)
        val brightnessClass = when {
            globalLum <= 0.15 && clockLum <= 0.20 && desktopLum <= 0.22 && glareFraction <= 0.06 -> BrightnessClass.DARK
            globalLum >= 0.20 && stdDev <= 0.38 -> BrightnessClass.LIGHT
            else -> BrightnessClass.NEUTRAL
        }

        val coveragePct = (cropRect.pixelCoverageRatio * 100.0).roundToInt()
        val fillNote = when {
            cropRect.usesBlurredFrameFill ->
                "Prepared with user-approved blurred frame extension (${coveragePct}% native core)."
            coveragePct >= 100 ->
                "100% native pixel coverage (${cropRect.width} × ${cropRect.height} crop for ${display.resolutionLabel})."
            display.qualityTier == DisplayQualityTier.MID_80 ->
                "${coveragePct}% native pixel coverage (${cropRect.width} × ${cropRect.height} crop for ${display.resolutionLabel}; passes >= 80% mid-PPI floor)."
            else ->
                "${coveragePct}% native pixel coverage (${cropRect.width} × ${cropRect.height} crop for ${display.resolutionLabel}; passes >= 65% high-DPI floor)."
        }

        val explanation = buildString {
            append("Classified as ${brightnessClass.displayName}: ")
            append("overall luminance=${"%.2f".format(globalLum)}, ")
            append("clock zone=${"%.2f".format(clockLum)}, ")
            append("desktop bar/icon zone=${"%.2f".format(desktopLum)}, ")
            append("glare=${"%.1f".format(glareFraction * 100)}%. ")
            append(fillNote)
        }

        return ImageEvaluationResult(
            isEligible = true,
            rejectionReasonCode = null,
            globalLuminance = globalLum,
            desktopZoneLuminance = desktopLum,
            lockClockZoneLuminance = clockLum,
            glareFractionInSafeZones = glareFraction,
            contrastStdDev = stdDev,
            brightnessClass = brightnessClass,
            cropRect = cropRect,
            explanation = explanation
        )
    }

    companion object {
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
