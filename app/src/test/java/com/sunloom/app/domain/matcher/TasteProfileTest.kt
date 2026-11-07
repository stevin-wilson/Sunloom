package com.sunloom.app.domain.matcher

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.ImageEvaluationResult
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.WallpaperRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/** Article III §3: local-first personalisation. Only counts of Kept and disliked images on the device are used. */
@Requirement("VII.3")
class TasteProfileTest {

    private fun row(id: String, category: SafeCategory, source: String, kept: Boolean = false, rejected: Boolean = false) =
        WallpaperRecord(
            id = id, title = id, category = category, localFilePath = "", croppedFilePath = "",
            isFavorite = kept, isRejected = rejected,
            provenance = ProvenanceRecord(
                sourceId = source, sourceName = source, assetId = id, originalPageUrl = "https://e.org",
                creatorName = "c", creatorPageUrl = "", licenseType = LicenseType.CC0, attributionText = "CC0",
                retrievedAtEpochMs = 1L, originalWidth = 1, originalHeight = 1, renditionWidth = 1, renditionHeight = 1,
                safetyClassification = SafetyClassification.SAFE, safetyClassificationSource = "t", sha256Checksum = "x"
            ),
            evaluation = ImageEvaluationResult(
                true, null, 0.5, 0.5, 0.5, 0.0, 0.1, BrightnessClass.LIGHT,
                CropRect(0, 0, 1, 1, 0.5, 0.5, false, 1.0), "t"
            )
        )

    @Test
    fun `no signals means a neutral profile`() {
        val profile = TasteProfile.from(listOf(row("a", SafeCategory.SCENIC_FINE_ART, "met_museum_cc0")))
        assertEquals(1.0, profile.multiplier(row("x", SafeCategory.SCENIC_FINE_ART, "met_museum_cc0")), 1e-9)
    }

    @Test
    fun `kept images raise and disliked images lower their category and source`() {
        val rows = listOf(
            row("k1", SafeCategory.SCENIC_FINE_ART, "met_museum_cc0", kept = true),
            row("k2", SafeCategory.SCENIC_FINE_ART, "met_museum_cc0", kept = true),
            row("d1", SafeCategory.ABSTRACT_PATTERNS, "wikimedia_featured", rejected = true),
            row("d2", SafeCategory.ABSTRACT_PATTERNS, "wikimedia_featured", rejected = true)
        )
        val profile = TasteProfile.from(rows)
        val loved = profile.multiplier(row("x", SafeCategory.SCENIC_FINE_ART, "met_museum_cc0"))
        val disliked = profile.multiplier(row("y", SafeCategory.ABSTRACT_PATTERNS, "wikimedia_featured"))
        assert(loved > 1.0) { "loved=$loved" }
        assert(disliked < 1.0) { "disliked=$disliked" }
    }

    @Test
    fun `multipliers stay inside the gentle bounds`() {
        val many = (1..200).map { row("k$it", SafeCategory.SCENIC_FINE_ART, "met_museum_cc0", kept = true) } +
            (1..200).map { row("d$it", SafeCategory.ABSTRACT_PATTERNS, "wikimedia_featured", rejected = true) }
        val profile = TasteProfile.from(many)
        val high = profile.multiplier(row("x", SafeCategory.SCENIC_FINE_ART, "met_museum_cc0"))
        val low = profile.multiplier(row("y", SafeCategory.ABSTRACT_PATTERNS, "wikimedia_featured"))
        assert(high <= 1.6 + 1e-9 && high > 1.4) { "high=$high" }
        assert(low >= 0.6 - 1e-9 && low < 0.8) { "low=$low" }
    }
}
