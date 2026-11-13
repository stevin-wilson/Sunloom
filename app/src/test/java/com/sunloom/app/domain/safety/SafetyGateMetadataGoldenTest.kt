package com.sunloom.app.domain.safety

import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.SourceIds
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Pins which check refuses a candidate first, and what it says, for every single fault and every pair of faults:
 * the order of the checks in [SafetyGate.evaluateMetadata] is part of its behaviour. The expected text is recorded
 * in `src/test/resources/golden/safety_gate_metadata.txt`; delete that file and run once to record it again after
 * an intended change.
 */
class SafetyGateMetadataGoldenTest {

    private class Case(
        val candidate: CandidateAsset,
        val blockedIds: Set<String> = emptySet(),
        val blockedHashes: Set<String> = emptySet(),
        val blockedSha: Set<String> = emptySet(),
        val enabled: Set<String>? = null
    )

    private fun base(): Case = Case(
        CandidateAsset(
            id = "wiki-101",
            title = "Yosemite Valley Sunrise over Half Dome",
            description = "High-resolution landscape photograph of granite cliffs and pine forest at dawn.",
            category = SafeCategory.LANDSCAPES_NATURE,
            rawCategoriesAndTags = listOf("landscape", "mountains", "yosemite", "nature"),
            downloadUrl = "https://upload.wikimedia.org/wikipedia/commons/sample_yosemite.jpg",
            provenance = ProvenanceRecord(
                sourceId = "wikimedia_featured",
                sourceName = "Wikimedia Commons Featured Pictures",
                assetId = "wiki-101",
                originalPageUrl = "https://commons.wikimedia.org/wiki/File:Yosemite.jpg",
                creatorName = "Ansel Explorer",
                creatorPageUrl = "",
                licenseType = LicenseType.CC_BY_SA,
                attributionText = "Ansel Explorer, CC BY-SA 4.0",
                retrievedAtEpochMs = 1762280000000L,
                originalWidth = 3840,
                originalHeight = 2400,
                renditionWidth = 3840,
                renditionHeight = 2400,
                safetyClassification = SafetyClassification.SAFE,
                safetyClassificationSource = "Wikimedia Featured Nature Curated Tree",
                sha256Checksum = "ABCDEF0123",
                perceptualHash = "0123456789abcdef"
            )
        )
    )

    private fun Case.with(change: (CandidateAsset) -> CandidateAsset) = Case(change(candidate), blockedIds, blockedHashes, blockedSha, enabled)
    private fun Case.prov(change: (ProvenanceRecord) -> ProvenanceRecord) = with { it.copy(provenance = change(it.provenance)) }

    private val faults: List<Pair<String, (Case) -> Case>> = listOf(
        "blocked-id" to { c -> Case(c.candidate, setOf("wiki-101"), c.blockedHashes, c.blockedSha, c.enabled) },
        "blocked-sha" to { c -> Case(c.candidate, c.blockedIds, c.blockedHashes, setOf("abcdef0123"), c.enabled) },
        "blocked-phash" to { c -> Case(c.candidate, c.blockedIds, setOf("0123456789abcdef"), c.blockedSha, c.enabled) },
        "user-local" to { c -> c.with { it.copy(isUserLocal = true) } },
        "unadmitted-source" to { c -> c.prov { it.copy(sourceId = "bogus_source") } },
        "disabled-source" to { c -> Case(c.candidate, c.blockedIds, c.blockedHashes, c.blockedSha, setOf("met_museum_cc0")) },
        "uncertain-classification" to { c -> c.prov { it.copy(safetyClassification = SafetyClassification.UNCERTAIN) } },
        "missing-signal" to { c -> c.prov { it.copy(safetyClassificationSource = "  ") } },
        "bad-license" to { c -> c.prov { it.copy(licenseType = LicenseType.UNKNOWN_OR_RESTRICTED) } },
        "unknown-creator" to { c -> c.prov { it.copy(creatorName = "Unknown author") } },
        "http-url" to { c -> c.with { it.copy(downloadUrl = "http://upload.wikimedia.org/x.jpg") } },
        "prohibited-title" to { c -> c.with { it.copy(title = "Document Scan of Historical Manuscript Page") } },
        "collage" to { c -> c.with { it.copy(title = "Collage of Alpine Views") } },
        "nasa-map" to { c ->
            c.prov { it.copy(sourceId = SourceIds.NASA_LIBRARY, assetId = "nasa-PIA00001") }
                .with { it.copy(title = "Global map of surface temperature", rawCategoriesAndTags = listOf("map", "chart")) }
        },
        "no-tags" to { c -> c.with { it.copy(rawCategoriesAndTags = emptyList()) } },
        "unrecognized-domain" to { c -> c.with { it.copy(title = "Quarterly sales", rawCategoriesAndTags = listOf("widget")) } },
        "museum-declared" to { c ->
            c.prov { it.copy(sourceId = "artic_cc0") }
                .with { it.copy(title = "Untitled study", category = SafeCategory.SCENIC_FINE_ART, rawCategoriesAndTags = listOf("oil on canvas")) }
        }
    )

    private fun render(): String = buildString {
        val gate = SafetyGate()
        fun line(name: String, c: Case) {
            val v = gate.evaluateMetadata(c.candidate, c.blockedIds, c.blockedHashes, c.blockedSha, c.enabled)
            append("$name -> $v\n")
        }
        line("none", base())
        for ((n, f) in faults) line(n, f(base()))
        for (i in faults.indices) for (j in i + 1 until faults.size) {
            line("${faults[i].first}+${faults[j].first}", faults[j].second(faults[i].second(base())))
        }
    }

    @Test
    fun `evaluateMetadata verdicts are unchanged for every single and paired fault`() {
        val golden = File("src/test/resources/golden/safety_gate_metadata.txt")
        val actual = render()
        if (!golden.exists()) {
            golden.parentFile.mkdirs()
            golden.writeText(actual)
            fail("Recorded ${golden.path}; run the test again.")
        }
        assertEquals(golden.readText().lines(), actual.lines())
    }
}
