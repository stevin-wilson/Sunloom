package com.sunloom.app.domain.renderer

import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.DisplayProfile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class EnvelopePlan(
    val envelopeCrop: CropRect,
    val outputWidthPx: Int,
    val outputHeightPx: Int,
    val satisfiedDisplays: List<DisplayProfile>,
    val excludedDisplays: List<DisplayProfile>,
    val perDisplayCrops: Map<Int, CropRect>,
    val disclosureNote: String
)

/**
 * Constitution v0.6 Article IV §1–2 & XVIII §1:
 * Plans a single shared envelope bitmap across connected displays so each satisfied display's
 * system center-crop meets its physical-PPI quality tier without stretching or upscaling,
 * while excluding conflicting portrait/aspect or >16MP outlier displays cleanly with honest disclosure.
 */
class EnvelopePlanner(
    private val cropRenderer: CropRenderer = CropRenderer()
) {

    fun planEnvelope(
        sourceWidth: Int,
        sourceHeight: Int,
        focalX: Double,
        focalY: Double,
        connectedDisplays: List<DisplayProfile>,
        policy: DisplayPolicy = DisplayPolicy.SAME_ON_ALL_DISPLAYS,
        allowBlurredFrameFill: Boolean = false
    ): EnvelopePlan? {
        val validDisplays = connectedDisplays.filter { it.widthPx > 0 && it.heightPx > 0 }
        if (sourceWidth <= 0 || sourceHeight <= 0 || validDisplays.isEmpty()) {
            return null
        }

        if (policy == DisplayPolicy.OPTIMIZE_PRIMARY_SHARPEST || validDisplays.size == 1) {
            val target = validDisplays.maxByOrNull { it.widthPx.toLong() * it.heightPx.toLong() }
                ?: return null
            val singleCrop = cropRenderer.computeCropFromFocalPoint(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                focalX = focalX,
                focalY = focalY,
                display = target,
                allowBlurredFrameFill = allowBlurredFrameFill
            ) ?: return null

            val (outW, outH) = if (singleCrop.usesBlurredFrameFill) {
                CropRenderer.budgetedSize(target.widthPx, target.heightPx)
            } else {
                val (budgetW, budgetH) = CropRenderer.budgetedSize(target.widthPx, target.heightPx)
                if (singleCrop.width > budgetW && singleCrop.height > budgetH) {
                    budgetW to budgetH
                } else {
                    CropRenderer.budgetedSize(singleCrop.width, singleCrop.height)
                }
            }

            val note = if (validDisplays.size > 1) {
                "Optimized crop for sharpest display: ${target.name} (${target.resolutionLabel})."
            } else {
                "Native crop for ${target.name} (${target.resolutionLabel})."
            }

            return EnvelopePlan(
                envelopeCrop = singleCrop,
                outputWidthPx = outW,
                outputHeightPx = outH,
                satisfiedDisplays = listOf(target),
                excludedDisplays = emptyList(),
                perDisplayCrops = mapOf(target.displayId to singleCrop),
                disclosureNote = note
            )
        }

        // 1. First attempt to satisfy ALL connected displays together natively
        planNativeSubset(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            focalX = focalX,
            focalY = focalY,
            subset = validDisplays,
            excluded = emptyList()
        )?.let { return it }

        // 2. Identify whether any non-anchor displays are genuine geometry/cap outliers
        // (conflicting portrait/landscape orientation, >2.2x aspect spread, or >16MP cap impossible).
        // Standard compatible multi-monitor sets (e.g. 16:10 laptop + 21:9 UWQHD or 16:9 4K) are NOT
        // outliers, so low-resolution images are properly rejected rather than shown blurry on externals.
        val anchor = validDisplays.firstOrNull { it.isPrimary }
            ?: validDisplays.maxByOrNull { it.widthPx.toLong() * it.heightPx.toLong() }!!
        val excludableIds = validDisplays
            .filter { d -> d.displayId != anchor.displayId && isIncompatibleOutlierDisplay(d, anchor) }
            .map { it.displayId }
            .toSet()

        if (excludableIds.isNotEmpty()) {
            val candidateSubsets = generateAnchorSubsets(validDisplays, anchor, excludableIds)
            for (subset in candidateSubsets) {
                val subsetIds = subset.map { it.displayId }.toSet()
                val excluded = validDisplays.filter { it.displayId !in subsetIds }
                planNativeSubset(
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    focalX = focalX,
                    focalY = focalY,
                    subset = subset,
                    excluded = excluded
                )?.let { return it }
            }
        }

        // 3. Optional blurred frame fill fallback when explicitly enabled by the user
        if (allowBlurredFrameFill) {
            val coreDisplays = validDisplays.filter { it.displayId !in excludableIds }.ifEmpty { listOf(anchor) }
            val excluded = validDisplays.filter { d -> coreDisplays.none { it.displayId == d.displayId } }
            val allPassBlurred = coreDisplays.all { d ->
                cropRenderer.computeCropFromFocalPoint(
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    focalX = focalX,
                    focalY = focalY,
                    display = d,
                    allowBlurredFrameFill = true
                ) != null
            }
            if (allPassBlurred) {
                val sharpest = coreDisplays.maxByOrNull { it.widthPx.toLong() * it.heightPx.toLong() }!!
                val (outW, outH) = CropRenderer.budgetedSize(sharpest.widthPx, sharpest.heightPx)
                val perDisplay = coreDisplays.associate { d ->
                    val cov = min(
                        sourceWidth.toDouble() / d.widthPx.toDouble(),
                        sourceHeight.toDouble() / d.heightPx.toDouble()
                    )
                    d.displayId to CropRect(
                        left = 0,
                        top = 0,
                        width = sourceWidth,
                        height = sourceHeight,
                        focalX = focalX.coerceIn(0.0, 1.0),
                        focalY = focalY.coerceIn(0.0, 1.0),
                        usesBlurredFrameFill = true,
                        pixelCoverageRatio = cov
                    )
                }
                val minCov = perDisplay.values.minOf { it.pixelCoverageRatio }
                val fillCrop = CropRect(
                    left = 0,
                    top = 0,
                    width = sourceWidth,
                    height = sourceHeight,
                    focalX = focalX.coerceIn(0.0, 1.0),
                    focalY = focalY.coerceIn(0.0, 1.0),
                    usesBlurredFrameFill = true,
                    pixelCoverageRatio = minCov
                )
                return EnvelopePlan(
                    envelopeCrop = fillCrop,
                    outputWidthPx = outW,
                    outputHeightPx = outH,
                    satisfiedDisplays = coreDisplays,
                    excludedDisplays = excluded,
                    perDisplayCrops = perDisplay,
                    disclosureNote = buildDisclosureNote(coreDisplays, excluded, usesBlurredFill = true)
                )
            }
        }

        return null
    }

    private fun isIncompatibleOutlierDisplay(display: DisplayProfile, anchor: DisplayProfile): Boolean {
        val anchorPortrait = anchor.widthPx < anchor.heightPx
        val displayPortrait = display.widthPx < display.heightPx
        if (anchorPortrait != displayPortrait && anchor.widthPx != anchor.heightPx && display.widthPx != display.heightPx) {
            return true
        }
        val aspectSpread = max(
            display.aspectRatio / anchor.aspectRatio.coerceAtLeast(1e-4),
            anchor.aspectRatio / display.aspectRatio.coerceAtLeast(1e-4)
        )
        if (aspectSpread > MAX_COMPATIBLE_ASPECT_SPREAD) {
            return true
        }
        // Check if even an infinite-resolution image budgeted to MAX_OUTPUT_PIXELS cannot satisfy display
        val (maxBudgetW, maxBudgetH) = CropRenderer.budgetedSize(display.widthPx * 2, display.heightPx * 2)
        val maxPossibleCoverage = min(
            maxBudgetW.toDouble() / display.widthPx.toDouble(),
            maxBudgetH.toDouble() / display.heightPx.toDouble()
        )
        if (!CropRenderer.meetsNativeQualityTier(maxPossibleCoverage, max(maxBudgetW, maxBudgetH), display)) {
            return true
        }
        return false
    }

    private fun generateAnchorSubsets(
        allDisplays: List<DisplayProfile>,
        anchor: DisplayProfile,
        excludableIds: Set<Int>
    ): List<List<DisplayProfile>> {
        val required = allDisplays.filter { it.displayId == anchor.displayId || it.displayId !in excludableIds }
        val optional = allDisplays.filter { it.displayId in excludableIds && it.displayId != anchor.displayId }.take(5)
        val results = ArrayList<List<DisplayProfile>>()
        val totalMasks = 1 shl optional.size
        // Exclude mask == totalMasks - 1 because the full set was already tested
        for (mask in 0 until (totalMasks - 1)) {
            val subset = ArrayList<DisplayProfile>(required.size + optional.size)
            subset.addAll(required)
            for (bit in optional.indices) {
                if ((mask and (1 shl bit)) != 0) {
                    subset.add(optional[bit])
                }
            }
            results.add(subset)
        }
        return results.sortedWith(
            compareByDescending<List<DisplayProfile>> { it.size }
                .thenByDescending { list -> list.sumOf { it.widthPx.toLong() * it.heightPx.toLong() } }
        )
    }

    private fun planNativeSubset(
        sourceWidth: Int,
        sourceHeight: Int,
        focalX: Double,
        focalY: Double,
        subset: List<DisplayProfile>,
        excluded: List<DisplayProfile>
    ): EnvelopePlan? {
        if (subset.isEmpty()) return null
        val rSrc = sourceWidth.toDouble() / sourceHeight.toDouble()
        val rMin = subset.minOf { it.aspectRatio }
        val rMax = subset.maxOf { it.aspectRatio }

        val candidateAspects = LinkedHashSet<Double>()
        candidateAspects.add(rSrc.coerceIn(rMin, rMax))
        if (rMax - rMin > 1e-3) {
            candidateAspects.add(sqrt(rMin * rMax))
            val steps = 6
            for (i in 0..steps) {
                val ratio = rMin * (rMax / rMin).pow(i.toDouble() / steps.toDouble())
                candidateAspects.add(ratio)
            }
        }

        var bestWithFocal: EnvelopePlan? = null
        var bestFallback: EnvelopePlan? = null

        for (envAspect in candidateAspects) {
            val envBounds = cropRenderer.computeAspectCropBounds(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                focalX = focalX,
                focalY = focalY,
                targetAspect = envAspect
            )
            val (outW, outH) = CropRenderer.budgetedSize(envBounds.width, envBounds.height)
            val actualEnvAspect = outW.toDouble() / outH.toDouble()

            val fxPx = focalX.coerceIn(0.0, 1.0) * sourceWidth.toDouble()
            val fyPx = focalY.coerceIn(0.0, 1.0) * sourceHeight.toDouble()
            val uFocal = ((fxPx - envBounds.left) / envBounds.width.coerceAtLeast(1).toDouble()).coerceIn(0.0, 1.0)
            val vFocal = ((fyPx - envBounds.top) / envBounds.height.coerceAtLeast(1).toDouble()).coerceIn(0.0, 1.0)

            val perDisplay = LinkedHashMap<Int, CropRect>(subset.size)
            var allPassFloor = true
            var allContainFocal = true
            var minCoverageAcrossSet = Double.POSITIVE_INFINITY

            for (d in subset) {
                val rD = d.aspectRatio
                val sliceW: Int
                val sliceH: Int
                if (actualEnvAspect >= rD) {
                    sliceH = outH
                    sliceW = (outH * rD).roundToInt().coerceIn(1, outW)
                } else {
                    sliceW = outW
                    sliceH = (outW / rD).roundToInt().coerceIn(1, outH)
                }
                val sliceLeft = (outW - sliceW) / 2
                val sliceTop = (outH - sliceH) / 2

                val cov = min(
                    sliceW.toDouble() / d.widthPx.toDouble(),
                    sliceH.toDouble() / d.heightPx.toDouble()
                )
                val sliceLongEdge = max(sliceW, sliceH)
                if (!CropRenderer.meetsNativeQualityTier(cov, sliceLongEdge, d)) {
                    allPassFloor = false
                    break
                }

                val u0 = sliceLeft.toDouble() / outW.toDouble() - 0.02
                val u1 = (sliceLeft + sliceW).toDouble() / outW.toDouble() + 0.02
                val v0 = sliceTop.toDouble() / outH.toDouble() - 0.02
                val v1 = (sliceTop + sliceH).toDouble() / outH.toDouble() + 0.02
                if (uFocal !in u0..u1 || vFocal !in v0..v1) {
                    allContainFocal = false
                }

                minCoverageAcrossSet = min(minCoverageAcrossSet, cov)
                perDisplay[d.displayId] = CropRect(
                    left = sliceLeft,
                    top = sliceTop,
                    width = sliceW,
                    height = sliceH,
                    focalX = focalX.coerceIn(0.0, 1.0),
                    focalY = focalY.coerceIn(0.0, 1.0),
                    usesBlurredFrameFill = false,
                    pixelCoverageRatio = cov
                )
            }

            if (!allPassFloor) continue

            val candidatePlan = EnvelopePlan(
                envelopeCrop = envBounds.copy(pixelCoverageRatio = minCoverageAcrossSet),
                outputWidthPx = outW,
                outputHeightPx = outH,
                satisfiedDisplays = subset,
                excludedDisplays = excluded,
                perDisplayCrops = perDisplay,
                disclosureNote = buildDisclosureNote(subset, excluded, usesBlurredFill = false)
            )

            // Prefer the first candidate (rSrc.coerceIn(rMin, rMax)) when it passes both floor and focal containment
            if (allContainFocal) {
                if (bestWithFocal == null) {
                    bestWithFocal = candidatePlan
                    break
                }
            } else if (bestFallback == null ||
                candidatePlan.envelopeCrop.pixelCoverageRatio > bestFallback.envelopeCrop.pixelCoverageRatio
            ) {
                bestFallback = candidatePlan
            }
        }

        return bestWithFocal ?: bestFallback
    }

    private fun buildDisclosureNote(
        satisfied: List<DisplayProfile>,
        excluded: List<DisplayProfile>,
        usesBlurredFill: Boolean
    ): String {
        val modeDesc = if (usesBlurredFill) "blurred frame fill" else "shared native envelope"
        return buildString {
            append("Prepared $modeDesc for ${satisfied.joinToString { "${it.name} (${it.resolutionLabel})" }}.")
            if (excluded.isNotEmpty()) {
                append(" Excluded incompatible display(s) from envelope target: ")
                append(excluded.joinToString { "${it.name} (${it.resolutionLabel})" })
                append(" — system center-crop applies on excluded panels.")
            }
        }
    }

    companion object {
        private const val MAX_COMPATIBLE_ASPECT_SPREAD = 2.2
    }
}
