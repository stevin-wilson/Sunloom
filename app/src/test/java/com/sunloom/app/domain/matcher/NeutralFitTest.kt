package com.sunloom.app.domain.matcher

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.ImageEvaluationResult
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article XI §1-2 and Article X §4: a "Balanced" image stands in for a mode only on its own side of the
 * middle of the Balanced band, so a mid-tone daytime photograph is not served as a dark-mode wallpaper
 * while a genuinely dark image is merely repeated, and the reserve does not count it as stock for that mode.
 */
@Requirement("XI.2", "X.4")
class NeutralFitTest {

    private val matcher = WallpaperMatcher(fileExistsVerifier = { it.fileSizeBytes > 0L })
    private val prefs = UserPreferences()
    private val all = SafeCategory.entries.toSet()

    private fun eval(brightness: BrightnessClass, lightness: Double) = ImageEvaluationResult(
        isEligible = true, rejectionReasonCode = null, globalLuminance = 0.3, desktopZoneLuminance = 0.3,
        lockClockZoneLuminance = 0.3, glareFractionInSafeZones = 0.0, contrastStdDev = 0.1,
        brightnessClass = brightness, cropRect = CropRect(0, 0, 2560, 1600, 0.5, 0.5, false, 1.0),
        explanation = "t", lightnessP50 = lightness
    )

    private fun record(
        id: String,
        brightness: BrightnessClass,
        lightness: Double = -1.0,
        applied: Int = 0,
        lastApplied: Long = 0L
    ) = WallpaperRecord(
        id = id, title = id, category = SafeCategory.LANDSCAPES_NATURE,
        localFilePath = "/tmp/$id.jpg", croppedFilePath = "", fileSizeBytes = 2048L,
        appliedCount = applied, lastAppliedEpochMs = lastApplied,
        provenance = ProvenanceRecord(
            sourceId = "wikimedia_featured", sourceName = "Wikimedia", assetId = id, originalPageUrl = "https://e.org/$id",
            creatorName = "c", creatorPageUrl = "", licenseType = LicenseType.CC0, attributionText = "CC0",
            retrievedAtEpochMs = 1L, originalWidth = 4000, originalHeight = 2500, renditionWidth = 2560,
            renditionHeight = 1600, safetyClassification = SafetyClassification.SAFE,
            safetyClassificationSource = "t", sha256Checksum = "x"
        ),
        evaluation = eval(brightness, lightness)
    )

    private fun pick(target: BrightnessClass, vararg pool: WallpaperRecord) =
        matcher.selectNextWallpaper(pool.toList(), target, prefs, deterministic = true)

    // ---- BrightnessFit ----

    @Test
    fun `a Balanced image fits dark mode only at or below the split and light mode only at or above it`() {
        val split = BrightnessFit.NEUTRAL_SPLIT_LIGHTNESS
        assertTrue(BrightnessFit.accepts(eval(BrightnessClass.NEUTRAL, 41.0), BrightnessClass.DARK))
        assertTrue(BrightnessFit.accepts(eval(BrightnessClass.NEUTRAL, split), BrightnessClass.DARK))
        assertFalse(BrightnessFit.accepts(eval(BrightnessClass.NEUTRAL, split + 0.5), BrightnessClass.DARK))
        assertFalse(BrightnessFit.accepts(eval(BrightnessClass.NEUTRAL, 55.0), BrightnessClass.DARK))

        assertTrue(BrightnessFit.accepts(eval(BrightnessClass.NEUTRAL, 60.0), BrightnessClass.LIGHT))
        assertTrue(BrightnessFit.accepts(eval(BrightnessClass.NEUTRAL, split), BrightnessClass.LIGHT))
        assertFalse(BrightnessFit.accepts(eval(BrightnessClass.NEUTRAL, split - 0.5), BrightnessClass.LIGHT))
    }

    @Test
    fun `exact class always fits, the opposite class never does and unknown lightness keeps the old behaviour`() {
        assertTrue(BrightnessFit.accepts(eval(BrightnessClass.DARK, 20.0), BrightnessClass.DARK))
        assertTrue(BrightnessFit.accepts(eval(BrightnessClass.LIGHT, 80.0), BrightnessClass.LIGHT))
        assertFalse(BrightnessFit.accepts(eval(BrightnessClass.DARK, 20.0), BrightnessClass.LIGHT))
        assertFalse(BrightnessFit.accepts(eval(BrightnessClass.LIGHT, 80.0), BrightnessClass.DARK))
        // Records from before lightness was stored have -1 until they are re-evaluated; they fit both modes.
        assertTrue(BrightnessFit.accepts(eval(BrightnessClass.NEUTRAL, -1.0), BrightnessClass.DARK))
        assertTrue(BrightnessFit.accepts(eval(BrightnessClass.NEUTRAL, -1.0), BrightnessClass.LIGHT))
    }

    // ---- Matcher ladder ----

    @Test
    fun `dark mode repeats a recent Dark image rather than serving an unseen bright Balanced one`() {
        val recentDark = record("dark-recent", BrightnessClass.DARK, 25.0, applied = 3, lastApplied = 1000L)
        val brightNeutral = record("skyline", BrightnessClass.NEUTRAL, 49.0)
        assertEquals("dark-recent", pick(BrightnessClass.DARK, recentDark, brightNeutral)?.id)
    }

    @Test
    fun `dark mode still prefers an unseen near-dark Balanced image to repeating a recent one`() {
        val recentDark = record("dark-recent", BrightnessClass.DARK, 25.0, applied = 3, lastApplied = 1000L)
        val nearDarkNeutral = record("dusk", BrightnessClass.NEUTRAL, 44.0)
        assertEquals("dusk", pick(BrightnessClass.DARK, recentDark, nearDarkNeutral)?.id)
    }

    @Test
    fun `light mode repeats a recent Light image rather than serving an unseen dim Balanced one`() {
        val recentLight = record("light-recent", BrightnessClass.LIGHT, 75.0, applied = 3, lastApplied = 1000L)
        val dimNeutral = record("overcast", BrightnessClass.NEUTRAL, 43.0)
        assertEquals("light-recent", pick(BrightnessClass.LIGHT, recentLight, dimNeutral)?.id)
    }

    @Test
    fun `an ill-fitting Balanced image still beats the opposite class as a last resort`() {
        val light = record("light", BrightnessClass.LIGHT, 80.0)
        val brightNeutral = record("skyline", BrightnessClass.NEUTRAL, 55.0)
        assertEquals("skyline", pick(BrightnessClass.DARK, light, brightNeutral)?.id)
    }

    @Test
    fun `with nothing else the ill-fitting image is still served rather than nothing`() {
        val brightNeutral = record("skyline", BrightnessClass.NEUTRAL, 55.0)
        assertNotNull(pick(BrightnessClass.DARK, brightNeutral))
    }

    // ---- Reserve counting ----

    @Test
    fun `unseen counts include a Balanced image only for the mode it fits`() {
        val pool = listOf(
            record("dark", BrightnessClass.DARK, 20.0),
            record("dusk", BrightnessClass.NEUTRAL, 44.0),
            record("skyline", BrightnessClass.NEUTRAL, 50.0)
        )
        assertEquals(2, matcher.countUnseenForBrightness(pool, BrightnessClass.DARK, prefs))
        assertEquals(1, matcher.countUnseenForBrightness(pool, BrightnessClass.LIGHT, prefs))
        assertEquals(2, ReserveBuffer.unseenFor(pool, BrightnessClass.DARK, all))
        assertEquals(1, ReserveBuffer.unseenFor(pool, BrightnessClass.LIGHT, all))
    }
}
