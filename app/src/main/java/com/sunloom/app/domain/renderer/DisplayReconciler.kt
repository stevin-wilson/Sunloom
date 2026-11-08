package com.sunloom.app.domain.renderer

import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.ImageEvaluationResult
import com.sunloom.app.domain.model.SourceIds
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord

/**
 * Article IV §§1–2 & XI §3: re-judges one stored picture for the connected displays without deleting or banning it. A
 * picture too small for the set is paused (and fine again if a larger set returns); a user's own picture that is too
 * small is kept whole with a blurred fill. The decision is pure: the repository decides which records to look at and
 * stores the result.
 */
class DisplayReconciler(
    private val envelopePlanner: EnvelopePlanner,
    private val cropRenderer: CropRenderer
) {

    /**
     * The record as it should now be stored for [validDisplays] (all of them with a known size, at least one), or null
     * when it is not subject to reconciliation or nothing about it changes.
     */
    fun reconcile(
        record: WallpaperRecord,
        validDisplays: List<DisplayProfile>,
        preferences: UserPreferences
    ): WallpaperRecord? {
        if (record.isRejected || record.isBaselineRestore || record.provenance.sourceId == SourceIds.SEED_RESERVE) {
            return null
        }
        val primaryOrSharpest = validDisplays.maxBy { it.widthPx.toLong() * it.heightPx.toLong() }
        val srcW = record.provenance.originalWidth
        val srcH = record.provenance.originalHeight
        if (srcW <= 0 || srcH <= 0) return null

        val focalX = record.evaluation.cropRect?.focalX ?: 0.5
        val focalY = record.evaluation.cropRect?.focalY ?: 0.5
        val isUserLocal = record.provenance.sourceId == SourceIds.USER_LOCAL

        val plan = envelopePlanner.planForRecord(record, validDisplays, preferences)

        val updatedEval: ImageEvaluationResult
        val newRendW: Int
        val newRendH: Int

        if (plan != null) {
            newRendW = plan.outputWidthPx
            newRendH = plan.outputHeightPx
            updatedEval = record.evaluation.copy(
                isEligible = true,
                rejectionReasonCode = null,
                cropRect = plan.envelopeCrop,
                explanation = plan.disclosureNote
            )
        } else if (isUserLocal) {
            val localFallback = CropRect
                .wholePictureBlurred(srcW, srcH, primaryOrSharpest, focalX = focalX, focalY = focalY)
            newRendW = primaryOrSharpest.widthPx
            newRendH = primaryOrSharpest.heightPx
            updatedEval = record.evaluation.copy(
                isEligible = true,
                rejectionReasonCode = null,
                cropRect = localFallback
            )
        } else {
            // Preserve focalX/focalY in a non-null crop bounds object while marking ineligible for this display set
            val preservedBounds = cropRenderer.computeAspectCropBounds(
                sourceWidth = srcW,
                sourceHeight = srcH,
                focalX = focalX,
                focalY = focalY,
                targetAspect = primaryOrSharpest.aspectRatio
            )
            val coverage = minOf(
                preservedBounds.width.toDouble() / primaryOrSharpest.widthPx.coerceAtLeast(1),
                preservedBounds.height.toDouble() / primaryOrSharpest.heightPx.coerceAtLeast(1)
            )
            newRendW = preservedBounds.width
            newRendH = preservedBounds.height
            val targetLabel = if (validDisplays.size > 1) {
                validDisplays.joinToString(" + ") { it.resolutionLabel }
            } else {
                primaryOrSharpest.resolutionLabel
            }
            updatedEval = record.evaluation.copy(
                isEligible = false,
                rejectionReasonCode = "RESOLUTION_BELOW_DISPLAY_TARGET",
                cropRect = preservedBounds.copy(pixelCoverageRatio = coverage),
                explanation = "Temporarily paused for $targetLabel: source (${srcW}×${srcH}) is below native sharpness floor."
            )
        }

        val changed = record.evaluation.isEligible != updatedEval.isEligible ||
            record.evaluation.rejectionReasonCode != updatedEval.rejectionReasonCode ||
            record.evaluation.cropRect != updatedEval.cropRect ||
            record.provenance.renditionWidth != newRendW ||
            record.provenance.renditionHeight != newRendH
        if (!changed) return null
        return record.copy(
            provenance = record.provenance.copy(renditionWidth = newRendW, renditionHeight = newRendH),
            evaluation = updatedEval
        )
    }
}
