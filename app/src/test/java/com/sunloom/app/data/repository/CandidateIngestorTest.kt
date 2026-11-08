package com.sunloom.app.data.repository

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.data.sources.BudgetedSourceHttpClient
import com.sunloom.app.data.sources.HttpClient
import com.sunloom.app.data.sources.RateLimitException
import com.sunloom.app.data.sources.SourceBudgetExceededException
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.RejectionRecord
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.renderer.EnvelopePlanner
import com.sunloom.app.domain.safety.SafetyGate
import com.sunloom.app.domain.evaluator.ImageEvaluator
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    // --- the ingest contract: what a source's loop relies on ---

    private class RecordingHost : IngestHost {
        val rejections = mutableListOf<RejectionRecord>()
        val sourceFailures = mutableListOf<Throwable>()
        val stored = mutableListOf<WallpaperRecord>()
        override fun recordRejection(rejection: RejectionRecord) { rejections += rejection }
        override fun blocklist(
            assetId: String?, sha256: String?, perceptualHash: String?, sourceId: String, reasonCode: String,
            timestampEpochMs: Long
        ) = Unit
        override fun store(record: WallpaperRecord) { stored += record }
        override fun recordSourceFailure(sourceId: String, error: Throwable) { sourceFailures += error }
    }

    private val tmp = File(System.getProperty("java.io.tmpdir"), "ingestor_test").apply { mkdirs() }
    private val display = DisplayProfile(0, "D", 2560, 1600)

    private fun passing() = candidate(0, 0).copy(
        title = "Mountain Valley Clouds", rawCategoriesAndTags = listOf("landscape", "mountains"),
        downloadUrl = "https://www.artic.edu/iiif/2/c/full/1686,/0/default.jpg"
    )

    private fun state(existing: Set<String> = emptySet()) = SyncState(
        display = display, activeDisplaySet = listOf(display), currentSetSignature = "sig",
        preferences = UserPreferences(), existingIds = existing.toMutableSet(),
        permanentlyBlockedIds = mutableSetOf(), displayScopedRejectedIds = mutableSetOf(),
        blockedSha256s = mutableSetOf(), blockedPhashes = mutableSetOf(),
        diversityGuard = LibraryDiversityGuard(emptyList()), pausedRows = emptyMap(), redownloadRows = emptyMap()
    )

    private fun sourceRun(bytes: suspend (String) -> ByteArray): SourceRun {
        val client = object : HttpClient {
            override suspend fun getText(url: String): String = "{}"
            override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = bytes(url)
        }
        return SourceRun("artic_cc0", BudgetedSourceHttpClient(client, "artic_cc0", maxRequests = 10, minSpacingMs = 0L))
    }

    private fun ingestor(host: IngestHost) =
        CandidateIngestor(SafetyGate(), ImageEvaluator(), EnvelopePlanner(), tmp, tmp, host)

    @Test
    fun `a rate limit stops the source and is recorded as its failure`() = runTest {
        val host = RecordingHost()
        val run = sourceRun { throw RateLimitException(60, "429") }
        assertFalse(ingestor(host).ingest(passing(), state(), run))
        assertTrue(run.failureRecorded)
        assertEquals(1, host.sourceFailures.size)
    }

    @Test
    fun `running out of request budget stops the source without blaming it`() = runTest {
        val host = RecordingHost()
        val run = sourceRun { throw SourceBudgetExceededException("artic_cc0", 5) }
        assertFalse(ingestor(host).ingest(passing(), state(), run))
        assertTrue(run.budgetStopped)
        assertFalse(run.failureRecorded)
        assertTrue(host.sourceFailures.isEmpty())
    }

    @Test
    fun `a download that fails is counted as tried, not ok, and the source keeps going`() = runTest {
        val host = RecordingHost()
        val run = sourceRun { throw IOException("HTTP 503") }
        assertTrue(ingestor(host).ingest(passing(), state(), run))
        assertEquals(1, run.downloadsTried)
        assertEquals(0, run.downloadsOk)
        assertTrue(host.stored.isEmpty())
    }

    @Test
    fun `a picture the library already holds is skipped before any download`() = runTest {
        var downloads = 0
        val run = sourceRun { downloads++; ByteArray(0) }
        assertTrue(ingestor(RecordingHost()).ingest(passing(), state(existing = setOf("c")), run))
        assertEquals(0, downloads)
        assertEquals(1, run.alreadyHad)
        assertEquals(0, run.downloadsTried)
    }
}
