package com.sunloom.app.data.repository

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.ImageEvaluationResult
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.WallpaperRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Requirement("X.3")
class LibraryDiversityGuardTest {

    private fun row(id: String, creator: String, hash: String, applied: Int = 0, source: String = "met_museum_cc0") =
        WallpaperRecord(
            id = id, title = id, category = SafeCategory.SCENIC_FINE_ART,
            localFilePath = "/tmp/$id.jpg", croppedFilePath = "", fileSizeBytes = 10L, appliedCount = applied,
            provenance = ProvenanceRecord(
                sourceId = source, sourceName = source, assetId = id, originalPageUrl = "https://e.org",
                creatorName = creator, creatorPageUrl = "", licenseType = LicenseType.CC0, attributionText = "CC0",
                retrievedAtEpochMs = 1L, originalWidth = 4000, originalHeight = 2500, renditionWidth = 2560,
                renditionHeight = 1600, safetyClassification = SafetyClassification.SAFE,
                safetyClassificationSource = "t", sha256Checksum = "x", perceptualHash = hash
            ),
            evaluation = ImageEvaluationResult(
                true, null, 0.5, 0.5, 0.5, 0.0, 0.1, BrightnessClass.LIGHT,
                CropRect(0, 0, 2560, 1600, 0.5, 0.5, false, 1.0), "t"
            )
        )

    private val informative = "5a3c96e1b2d47f08"

    @Test
    fun `identical informative hashes are duplicates and small differences too`() {
        val guard = LibraryDiversityGuard(listOf(row("a", "x", informative)))
        assertTrue(guard.isNearDuplicate(informative))
        assertTrue(guard.isNearDuplicate("5a3c96e1b2d47f0b")) // 2 bits apart
        assertFalse(guard.isNearDuplicate("a5c3691e4d2b80f7")) // inverted
    }

    @Test
    fun `featureless hashes from flat images never count as duplicates`() {
        val guard = LibraryDiversityGuard(listOf(row("flat-1", "x", "0000000000000000"), row("flat-2", "y", "ffffffffffffffff")))
        assertFalse(guard.isNearDuplicate("0000000000000000"))
        assertFalse(guard.isNearDuplicate("ffffffffffffffff"))
        assertFalse(guard.isNearDuplicate("0000000000000007")) // almost empty hash
    }

    @Test
    fun `creator cap counts only unseen images and normalises the name`() {
        val rows = listOf(
            row("m1", "Claude Monet", "1111111111111111"),
            row("m2", " claude  MONET ", "2222222222222222"),
            row("m3", "Claude Monet", "3333333333333333", applied = 4)
        )
        val guard = LibraryDiversityGuard(rows)
        assertFalse("two unseen of three", guard.creatorCapReached("met_museum_cc0", "Claude Monet"))
        guard.noteAdded("met_museum_cc0", "Claude Monet", "4444444444444444")
        assertTrue(guard.creatorCapReached("met_museum_cc0", "claude monet"))
    }

    @Test
    fun `institutional or unknown creators are never capped`() {
        val guard = LibraryDiversityGuard(emptyList())
        repeat(10) { guard.noteAdded("nasa_library", "NASA", "5a3c96e1b2d47f0$it") }
        repeat(10) { guard.noteAdded("met_museum_cc0", "Unknown", "5a3c96e1b2d4${it}f08") }
        repeat(10) { guard.noteAdded("met_museum_cc0", "", "5a3c96e1b2${it}d7f08") }
        assertFalse(guard.creatorCapReached("nasa_library", "NASA"))
        assertFalse(guard.creatorCapReached("met_museum_cc0", "Unknown"))
        assertFalse(guard.creatorCapReached("met_museum_cc0", ""))
    }

    @Test
    fun `the synthetic Wikimedia public domain creator is not a real creator`() {
        val guard = LibraryDiversityGuard(emptyList())
        repeat(10) { guard.noteAdded("wikimedia_featured", "Archival / Public Domain (Wikimedia Commons)", "5a3c96e1b2d47f0$it") }
        assertFalse(guard.creatorCapReached("wikimedia_featured", "Archival / Public Domain (Wikimedia Commons)"))
    }
}
