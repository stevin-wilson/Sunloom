package com.sunloom.app.domain.renderer

/**
 * What a picture is about to be cropped and scaled to: the crop's aspect ratio and focal point, and the size of the
 * bitmap that will be handed to the system.
 */
data class DecodeTarget(
    val aspect: Double,
    val focalX: Double,
    val focalY: Double,
    val outputWidth: Int,
    val outputHeight: Int
)

/**
 * How far to shrink a picture while decoding it (BitmapFactory only allows powers of two).
 *
 * Decoding the whole picture is the biggest allocation when a wallpaper is applied, so it should be no bigger than the
 * result needs. The part that will be shown (the crop) is what has to stay at least as large as the output, otherwise the
 * display is fed an upscaled, softer image. The older rule (the whole picture no larger than twice the display) ignored
 * the crop: for a tall portrait it could decode a 16:10 crop narrower than the screen.
 */
object DecodeSampling {
    /** The older rule, still used where the picture is not cropped (blurred fill, bundled seeds). */
    fun legacySampleSize(sourceWidth: Int, sourceHeight: Int, displayWidth: Int, displayHeight: Int): Int {
        val maxW = (displayWidth * 2).coerceAtLeast(2560)
        val maxH = (displayHeight * 2).coerceAtLeast(1600)
        var sample = 1
        while (sourceWidth / sample > maxW || sourceHeight / sample > maxH) sample *= 2
        return sample
    }

    /**
     * The largest power of two that keeps the crop at least as large as [target]'s output in both directions (1 when the
     * picture is already no larger than that). Without a [target] the picture is not cropped, so the legacy rule applies
     * with [legacyMaxW] x [legacyMaxH] as the display.
     */
    fun sampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        target: DecodeTarget?,
        cropRenderer: CropRenderer,
        legacyMaxW: Int,
        legacyMaxH: Int
    ): Int {
        if (target == null) return legacySampleSize(sourceWidth, sourceHeight, legacyMaxW, legacyMaxH)
        val crop = cropRenderer.computeAspectCropBounds(sourceWidth, sourceHeight, target.focalX, target.focalY, target.aspect)
        val outW = target.outputWidth.coerceAtLeast(1)
        val outH = target.outputHeight.coerceAtLeast(1)
        var sample = 1
        while (crop.width / (sample * 2) >= outW && crop.height / (sample * 2) >= outH) sample *= 2
        return sample
    }
}
