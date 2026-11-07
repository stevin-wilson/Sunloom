package com.sunloom.app.domain.matcher

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.ImageEvaluationResult
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.RejectionRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.model.WallpaperStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article X §4 (v0.14): Diagnostics shows, per source, what the reserve holds and why candidates were skipped,
 * so a source that never contributes is visible instead of silently absent.
 */
@Requirement("X.4")
class ReserveBreakdownTest {

    private var n = 0
    private fun row(
        brightness: BrightnessClass,
        source: String,
        sourceName: String = source,
        applied: Int = 0,
        size: Long = 1_000_000L,
        category: SafeCategory = SafeCategory.LANDSCAPES_NATURE,
        rejected: Boolean = false,
        eligible: Boolean = true,
        status: WallpaperStatus = WallpaperStatus.PREPARED
    ) = WallpaperRecord(
        id = "r${n++}", title = "t", category = category,
        localFilePath = if (size > 0) "/tmp/x.jpg" else "", croppedFilePath = "", fileSizeBytes = size,
        appliedCount = applied, isRejected = rejected, status = status,
        provenance = ProvenanceRecord(
            sourceId = source, sourceName = sourceName, assetId = "a", originalPageUrl = "https://e.org",
            creatorName = "c", creatorPageUrl = "", licenseType = LicenseType.CC0, attributionText = "CC0",
            retrievedAtEpochMs = 1L, originalWidth = 4000, originalHeight = 2500, renditionWidth = 2560,
            renditionHeight = 1600, safetyClassification = SafetyClassification.SAFE,
            safetyClassificationSource = "t", sha256Checksum = "x"
        ),
        evaluation = ImageEvaluationResult(
            eligible, null, 0.5, 0.5, 0.5, 0.0, 0.1, brightness,
            CropRect(0, 0, 2560, 1600, 0.5, 0.5, false, 1.0), "t"
        )
    )

    private fun rejection(source: String, reason: String) =
        RejectionRecord("id${n++}", source, reason, "why", 1L)

    private val all = SafeCategory.entries.toSet()

    @Test
    fun `counts split by brightness per source and unseen is tracked separately`() {
        val rows = ReserveBreakdown.bySource(
            listOf(
                row(BrightnessClass.LIGHT, "wikimedia_featured", sourceName = "Wikimedia"),
                row(BrightnessClass.LIGHT, "wikimedia_featured", sourceName = "Wikimedia", applied = 2),
                row(BrightnessClass.DARK, "wikimedia_featured", sourceName = "Wikimedia"),
                row(BrightnessClass.NEUTRAL, "met_museum_cc0", sourceName = "The Met")
            ),
            all
        )
        val wiki = rows.single { it.sourceId == "wikimedia_featured" }
        assertEquals(2, wiki.light)
        assertEquals(1, wiki.dark)
        assertEquals(0, wiki.balanced)
        assertEquals(3, wiki.total)
        assertEquals(2, wiki.unseen)
        assertEquals("Wikimedia", wiki.sourceName)
        val met = rows.single { it.sourceId == "met_museum_cc0" }
        assertEquals(1, met.balanced)
        assertEquals(1, met.unseen)
    }

    @Test
    fun `unusable records are not counted`() {
        val rows = ReserveBreakdown.bySource(
            listOf(
                row(BrightnessClass.LIGHT, "wikimedia_featured"),
                row(BrightnessClass.LIGHT, "wikimedia_featured", size = 0L),
                row(BrightnessClass.LIGHT, "wikimedia_featured", rejected = true),
                row(BrightnessClass.LIGHT, "wikimedia_featured", eligible = false),
                row(BrightnessClass.LIGHT, "wikimedia_featured", status = WallpaperStatus.DISABLED_BY_USER),
                row(BrightnessClass.LIGHT, "wikimedia_featured", category = SafeCategory.ABSTRACT_PATTERNS)
            ),
            enabledCategories = setOf(SafeCategory.LANDSCAPES_NATURE)
        )
        assertEquals(1, rows.single().total)
    }

    @Test
    fun `seeds and imports are fallbacks, listed after online sources`() {
        val rows = ReserveBreakdown.bySource(
            listOf(
                row(BrightnessClass.DARK, "seed_reserve", sourceName = "Offline seeds"),
                row(BrightnessClass.DARK, "seed_reserve", sourceName = "Offline seeds"),
                row(BrightnessClass.LIGHT, "user_local", sourceName = "Your images"),
                row(BrightnessClass.LIGHT, "met_museum_cc0", sourceName = "The Met")
            ),
            all
        )
        assertEquals(listOf("met_museum_cc0", "seed_reserve", "user_local"), rows.map { it.sourceId })
        assertFalse(rows[0].isFallback)
        assertTrue(rows[1].isFallback)
        assertTrue(rows[2].isFallback)
    }

    @Test
    fun `a known source with nothing in the reserve still gets a row of zeros`() {
        val rows = ReserveBreakdown.bySource(
            listOf(row(BrightnessClass.LIGHT, "wikimedia_featured", sourceName = "Wikimedia")),
            all,
            knownSources = listOf("wikimedia_featured" to "Wikimedia", "cma_cc0" to "Cleveland Museum of Art")
        )
        assertEquals(listOf("wikimedia_featured", "cma_cc0"), rows.map { it.sourceId })
        val cma = rows.last()
        assertEquals("Cleveland Museum of Art", cma.sourceName)
        assertEquals(0, cma.total)
        assertFalse(cma.isFallback)
    }

    @Test
    fun `rejections are grouped by source and reason with the most frequent first`() {
        val groups = ReserveBreakdown.rejectionsBySourceAndReason(
            listOf(
                rejection("cma_cc0", "COMPOSITION_CROPS_TOO_MUCH"),
                rejection("smk_open", "INSUFFICIENT_POST_CROP_RESOLUTION"),
                rejection("cma_cc0", "COMPOSITION_CROPS_TOO_MUCH"),
                rejection("cma_cc0", "COMPOSITION_CROPS_TOO_MUCH"),
                rejection("cma_cc0", "NEAR_DUPLICATE_OF_LIBRARY")
            )
        )
        assertEquals(
            listOf(
                Triple("cma_cc0", "COMPOSITION_CROPS_TOO_MUCH", 3),
                Triple("cma_cc0", "NEAR_DUPLICATE_OF_LIBRARY", 1),
                Triple("smk_open", "INSUFFICIENT_POST_CROP_RESOLUTION", 1)
            ),
            groups.map { Triple(it.sourceId, it.reasonCode, it.count) }
        )
    }
}
