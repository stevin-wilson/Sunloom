package com.sunloom.app.domain.safety

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.SafetyVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article VII (v0.14): harmless wording must not permanently ban a good image ("naked eye" astronomy,
 * Bikini Atoll, blood moon), while real prohibited content is still rejected exactly as before.
 */
@Requirement("VII.4")
class SafetyNarrowingTest {

    private fun find(title: String, description: String = "", tags: List<String> = emptyList()) =
        SafetyDenylist.findProhibitedMatch(title, description, tags, attributionText = "")

    @Test
    fun `benign phrases containing a prohibited word pass`() {
        assertNull(find("Comet visible to the naked eye over the desert"))
        assertNull(find("Bikini Atoll lagoon from above", tags = listOf("aerial", "atoll")))
        assertNull(find("Blood moon rising over the lake"))
        assertNull(find("Blood orange sunset sky"))
        assertNull(find("Sunset", description = "A supernova remnant seen with the naked eye in dark skies."))
    }

    @Test
    fun `the same words in a prohibited context are still rejected`() {
        assertEquals("naked", find("Naked woman by the sea"))
        assertNotNull(find("Bikini model on the beach"))
        assertNotNull(find("Blood on the battlefield"))
        assertNotNull(find("Comet seen with the naked eye", description = "nude figure study"))
        assertNotNull(find("Bloody massacre painting"))
    }

    private fun candidate(sourceId: String, title: String, tags: List<String>, category: SafeCategory) = CandidateAsset(
        id = "c-1", title = title, description = "", category = category, rawCategoriesAndTags = tags,
        downloadUrl = "https://example.org/x.jpg",
        provenance = ProvenanceRecord(
            sourceId = sourceId, sourceName = sourceId, assetId = "c-1", originalPageUrl = "https://example.org",
            creatorName = "Artist", creatorPageUrl = "", licenseType = LicenseType.CC0, attributionText = "CC0",
            retrievedAtEpochMs = 1L, originalWidth = 4000, originalHeight = 2500, renditionWidth = 2560,
            renditionHeight = 1600, safetyClassification = SafetyClassification.SAFE,
            safetyClassificationSource = "test", sha256Checksum = ""
        )
    )

    @Test
    fun `museum sources may rely on the category their filtered query declared`() {
        val gate = SafetyGate()
        // No safe-domain keyword in the title or tags, but it came from a landscape query of a museum
        // adapter that already filters by department and classification.
        for (source in listOf("met_museum_cc0", "cma_cc0", "smk_open", "artic_cc0")) {
            val verdict = gate.evaluateMetadata(
                candidate(source, "Untitled Study No. 4", listOf("Paintings", "Oil on canvas"), SafeCategory.SCENIC_FINE_ART)
            )
            assertTrue("$source should pass, got $verdict", verdict is SafetyVerdict.Approved)
        }
    }

    @Test
    fun `photo sources still need a recognised safe domain and museums still need tags`() {
        val gate = SafetyGate()
        val wiki = gate.evaluateMetadata(
            candidate("wikimedia_featured", "Untitled Study No. 4", listOf("Paintings"), SafeCategory.SCENIC_FINE_ART)
        )
        assertEquals("UNRECOGNIZED_SAFE_DOMAIN", (wiki as SafetyVerdict.Rejected).reasonCode)

        val untaggedMuseum = gate.evaluateMetadata(
            candidate("met_museum_cc0", "Untitled Study No. 4", emptyList(), SafeCategory.SCENIC_FINE_ART)
        )
        assertEquals("MISSING_SAFETY_TAGS", (untaggedMuseum as SafetyVerdict.Rejected).reasonCode)
    }

    @Test
    fun `the museum fallback never overrides the lexical denylist`() {
        val gate = SafetyGate()
        val verdict = gate.evaluateMetadata(
            candidate("met_museum_cc0", "Reclining nude by the river", listOf("Paintings"), SafeCategory.SCENIC_FINE_ART)
        )
        assertEquals("PROHIBITED_CONTENT_TERM", (verdict as SafetyVerdict.Rejected).reasonCode)
    }

    @Test
    fun `the denylist version records this narrowing`() {
        assertEquals("2026.11.08-v9", SafetyDenylist.DENYLIST_VERSION)
    }
}
