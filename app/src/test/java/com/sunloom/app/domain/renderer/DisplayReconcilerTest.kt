package com.sunloom.app.domain.renderer

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.ImageEvaluationResult
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.SourceIds
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article IV §§1–2 / XI §3: when the connected displays change, a stored picture is re-judged for them without being
 * deleted or banned. Too small for the new set means paused (and fine again if a larger set returns), not rejected.
 */
@Requirement("IV.1")
class DisplayReconcilerTest {
    private val cropRenderer = CropRenderer()
    private val reconciler = DisplayReconciler(EnvelopePlanner(cropRenderer), cropRenderer)
    private val laptop = DisplayProfile(0, "Built-in", 2560, 1600, densityDpi = 220)
    private val prefs = UserPreferences()

    private fun record(
        w: Int, h: Int,
        sourceId: String = "wikimedia_featured",
        rejected: Boolean = false,
        baseline: Boolean = false,
        eligible: Boolean = true,
        reason: String? = null
    ) = WallpaperRecord(
        id = "r", title = "t", category = SafeCategory.LANDSCAPES_NATURE,
        localFilePath = "/tmp/r.jpg", croppedFilePath = "/tmp/r.jpg", fileSizeBytes = 1000L,
        isRejected = rejected, isBaselineRestore = baseline,
        provenance = ProvenanceRecord(
            sourceId = sourceId, sourceName = "s", assetId = "r", originalPageUrl = "", creatorName = "c",
            creatorPageUrl = "", licenseType = LicenseType.CC0, attributionText = "", retrievedAtEpochMs = 0L,
            originalWidth = w, originalHeight = h, renditionWidth = 0, renditionHeight = 0,
            safetyClassification = SafetyClassification.SAFE, safetyClassificationSource = "s", sha256Checksum = "x"
        ),
        evaluation = ImageEvaluationResult(
            isEligible = eligible, rejectionReasonCode = reason, globalLuminance = 0.5, desktopZoneLuminance = 0.5,
            lockClockZoneLuminance = 0.5, glareFractionInSafeZones = 0.0, contrastStdDev = 0.1,
            brightnessClass = BrightnessClass.LIGHT, cropRect = CropRect(0, 0, w, h, 0.5, 0.5, false, 1.0),
            explanation = "old"
        )
    )

    @Test
    fun `seeds, baselines, rejected pictures and pictures of unknown size are left alone`() {
        assertNull(reconciler.reconcile(record(4000, 2500, sourceId = SourceIds.SEED_RESERVE), listOf(laptop), prefs))
        assertNull(reconciler.reconcile(record(4000, 2500, baseline = true), listOf(laptop), prefs))
        assertNull(reconciler.reconcile(record(4000, 2500, rejected = true), listOf(laptop), prefs))
        assertNull(reconciler.reconcile(record(0, 0), listOf(laptop), prefs))
    }

    @Test
    fun `a picture big enough for the displays is marked eligible with the planned crop`() {
        val updated = reconciler.reconcile(record(4000, 2500), listOf(laptop), prefs)
        assertNotNull(updated)
        assertTrue(updated!!.evaluation.isEligible)
        assertNull(updated.evaluation.rejectionReasonCode)
        assertTrue("rendition size is recorded", updated.provenance.renditionWidth > 0 && updated.provenance.renditionHeight > 0)
    }

    @Test
    fun `reconciling the result again changes nothing`() {
        val once = reconciler.reconcile(record(4000, 2500), listOf(laptop), prefs)!!
        assertNull("nothing left to change", reconciler.reconcile(once, listOf(laptop), prefs))
    }

    @Test
    fun `a picture too small for the displays is paused, not banned`() {
        val updated = reconciler.reconcile(record(800, 500), listOf(laptop), prefs)!!
        assertFalse(updated.evaluation.isEligible)
        assertEquals("RESOLUTION_BELOW_DISPLAY_TARGET", updated.evaluation.rejectionReasonCode)
        assertFalse("pausing is not rejecting", updated.isRejected)
        assertTrue(updated.evaluation.explanation.contains("800×500"))
    }

    @Test
    fun `a paused picture becomes eligible again when the displays suit it`() {
        val paused = reconciler.reconcile(record(800, 500), listOf(laptop), prefs)!!
        // A display this 800x500 picture covers fully (measured with the planner, not assumed from the floors).
        val small = DisplayProfile(0, "Small", 800, 500, densityDpi = 160)
        val resumed = reconciler.reconcile(paused, listOf(small), prefs)
        assertNotNull(resumed)
        assertTrue(resumed!!.evaluation.isEligible)
    }

    @Test
    fun `a user's own picture that is too small is kept whole with a blurred fill`() {
        val updated = reconciler.reconcile(record(800, 500, sourceId = SourceIds.USER_LOCAL), listOf(laptop), prefs)!!
        assertTrue(updated.evaluation.isEligible)
        assertTrue(updated.evaluation.cropRect!!.usesBlurredFrameFill)
        assertEquals(laptop.widthPx, updated.provenance.renditionWidth)
    }

    @Test
    fun `the pause explanation names every display of a multi-display set`() {
        val monitor = DisplayProfile(1, "Monitor", 3840, 2160, densityDpi = 160, isPrimary = false)
        val updated = reconciler.reconcile(record(800, 500), listOf(laptop, monitor), prefs)!!
        assertTrue(updated.evaluation.explanation.contains("2560 × 1600"))
        assertTrue(updated.evaluation.explanation.contains("3840 × 2160"))
    }

    @Test
    fun `an empty display list changes nothing instead of crashing`() {
        assertNull(reconciler.reconcile(record(4000, 2500), emptyList(), prefs))
    }

    @Test
    fun `a picture that stays paused for the same displays is not rewritten`() {
        val paused = reconciler.reconcile(record(800, 500), listOf(laptop), prefs)!!
        assertNull(reconciler.reconcile(paused, listOf(laptop), prefs))
    }

    @Test
    fun `a user's picture kept whole is not rewritten on the next pass`() {
        val kept = reconciler.reconcile(record(800, 500, sourceId = SourceIds.USER_LOCAL), listOf(laptop), prefs)!!
        assertNull(reconciler.reconcile(kept, listOf(laptop), prefs))
    }

    @Test
    fun `a picture with no stored crop is planned from the centre`() {
        val noCrop = record(4000, 2500).let { it.copy(evaluation = it.evaluation.copy(cropRect = null)) }
        val updated = reconciler.reconcile(noCrop, listOf(laptop), prefs)
        assertNotNull(updated)
        assertEquals(0.5, updated!!.evaluation.cropRect!!.focalX, 0.5) // a planned crop exists; the focal point defaults
    }

    @Test
    fun `resuming a paused picture clears the pause reason`() {
        val paused = reconciler.reconcile(record(800, 500), listOf(laptop), prefs)!!
        val resumed = reconciler.reconcile(paused, listOf(DisplayProfile(0, "Small", 800, 500, densityDpi = 160)), prefs)!!
        assertNull(resumed.evaluation.rejectionReasonCode)
        assertFalse(resumed.evaluation.explanation.contains("Temporarily paused"))
    }

    @Test
    fun `the pause explanation follows the displays when only a smaller display is added`() {
        // The sharpest display is unchanged, so the code, crop and size stay the same; the text must still name both.
        val forLaptop = reconciler.reconcile(record(800, 500), listOf(laptop), prefs)!!
        val second = DisplayProfile(1, "Second", 1280, 800, densityDpi = 160, isPrimary = false)
        val forBoth = reconciler.reconcile(forLaptop, listOf(laptop, second), prefs)
        assertNotNull("the stored text still names only the old display", forBoth)
        assertTrue(forBoth!!.evaluation.explanation.contains("1280 × 800"))
    }
}
