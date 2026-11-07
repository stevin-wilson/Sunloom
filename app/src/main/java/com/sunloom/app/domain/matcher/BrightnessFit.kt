package com.sunloom.app.domain.matcher

import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.ImageEvaluationResult

/**
 * Whether an evaluated image suits a Light or Dark target (Article XI §1-2).
 *
 * "Balanced" ([BrightnessClass.NEUTRAL]) covers everything between the Dark and Light cuts, and anything
 * high-contrast or failing a legibility gate. It may stand in for a mode, but only on its own side of the
 * middle of that band: a mid-tone daytime photograph must not be served as a dark-mode wallpaper because
 * the unseen Dark pool happens to be short, and a dim one is equally out of place in light mode.
 */
object BrightnessFit {

    /**
     * Median CIE L* splitting the Balanced band: at or below it a Balanced image fits Dark, at or above it Light.
     * Midway between the Dark cut (L* 40) and the Light cut (L* 52).
     */
    const val NEUTRAL_SPLIT_LIGHTNESS = 46.0

    /** True when [evaluation] is exactly [target], or a Balanced image on [target]'s side of the split. */
    fun accepts(evaluation: ImageEvaluationResult, target: BrightnessClass): Boolean = when (evaluation.brightnessClass) {
        target -> true
        BrightnessClass.NEUTRAL -> neutralFits(evaluation.lightnessP50, target)
        else -> false
    }

    /** True for a Balanced image that fits [target] (the stand-in rung of the selection ladder). */
    fun isFittingNeutral(evaluation: ImageEvaluationResult, target: BrightnessClass): Boolean =
        evaluation.brightnessClass == BrightnessClass.NEUTRAL && neutralFits(evaluation.lightnessP50, target)

    // A negative lightness means "not measured" (a record from before it was stored, until it is re-evaluated):
    // keep the old behaviour of fitting both modes rather than hiding such images.
    private fun neutralFits(lightness: Double, target: BrightnessClass): Boolean = when {
        lightness < 0.0 -> true
        target == BrightnessClass.DARK -> lightness <= NEUTRAL_SPLIT_LIGHTNESS
        target == BrightnessClass.LIGHT -> lightness >= NEUTRAL_SPLIT_LIGHTNESS
        else -> true
    }
}
