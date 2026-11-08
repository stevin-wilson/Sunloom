package com.sunloom.app.data.repository

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Article X §3 / XI §3: a picture paused as too small is fetched again only when its source offers a larger one. */
@Requirement("X.3")
class CandidateIngestorTest {
    private fun candidate(w: Int, h: Int) = CandidateAsset(
        id = "c", title = "t", description = "", category = SafeCategory.SCENIC_FINE_ART,
        rawCategoriesAndTags = emptyList(), downloadUrl = "https://example.org/c.jpg",
        provenance = provenance(w, h)
    )

    private fun provenance(w: Int, h: Int) = ProvenanceRecord(
        sourceId = "artic_cc0", sourceName = "s", assetId = "c", originalPageUrl = "", creatorName = "x",
        creatorPageUrl = "", licenseType = LicenseType.CC0, attributionText = "", retrievedAtEpochMs = 0L,
        originalWidth = w, originalHeight = h, renditionWidth = 0, renditionHeight = 0,
        safetyClassification = SafetyClassification.SAFE, safetyClassificationSource = "s", sha256Checksum = ""
    )

    @Test
    fun `a larger advertised size is worth fetching again`() {
        assertTrue(CandidateIngestor.offersLargerRendition(candidate(3000, 2000), provenance(1500, 1000)))
    }

    @Test
    fun `the same or a smaller size is not`() {
        assertFalse(CandidateIngestor.offersLargerRendition(candidate(1500, 1000), provenance(1500, 1000)))
        assertFalse(CandidateIngestor.offersLargerRendition(candidate(800, 600), provenance(1500, 1000)))
    }

    @Test
    fun `a source that advertises no size offers nothing larger`() {
        assertFalse(CandidateIngestor.offersLargerRendition(candidate(0, 0), provenance(1500, 1000)))
    }
}
