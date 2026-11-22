package com.sunloom.app.data.sources

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.LicenseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The scheduled live check applies [SourceContractRules] to what the real catalogs return. These make sure the rules, and the
 * decision about what counts as drift, are right without a network: a source that changed is caught, a bad day on the network is not.
 */
class SourceContractRulesTest {

    private val wikimedia = SourceRegistry.get("wikimedia_featured")!!
    private val registration = SourceRegistry.get("artic_cc0")!!

    private fun candidate(
        id: String = "artic-1",
        page: String = "https://www.artic.edu/artworks/1",
        image: String = "https://www.artic.edu/iiif/2/abc/full/843,/0/default.jpg",
        license: LicenseType = LicenseType.CC0,
        creator: String = "Claude Monet",
        width: Int = 3000,
        height: Int = 2000
    ) = com.sunloom.app.domain.safety.SafetyFixtures.candidate(sourceId = registration.sourceId).let { base ->
        base.copy(
            id = id,
            downloadUrl = image,
            provenance = base.provenance.copy(
                sourceId = registration.sourceId, assetId = id, originalPageUrl = page, licenseType = license,
                creatorName = creator, originalWidth = width, originalHeight = height
            )
        )
    }

    @Requirement("VI.4.e", "VI.4.f")
    @Test
    fun `a candidate with an id, a public page and an https picture on the source's host meets the contract`() {
        assertEquals(emptyList<String>(), SourceContractRules.violations(candidate(), registration))
    }

    @Requirement("VI.4.e")
    @Test
    fun `a missing id or a page that is not a public https address is a violation`() {
        assertTrue(SourceContractRules.violations(candidate(id = ""), registration).any { it.contains("no id") })
        listOf("", "http://www.artic.edu/artworks/1", "https://", "https://www.artic.edu", "not a url").forEach { page ->
            assertTrue("`$page`", SourceContractRules.violations(candidate(page = page), registration).any { it.contains("public page") })
        }
    }

    @Requirement("VI.4.f")
    @Test
    fun `a picture that is not https, not a raster, or on another host is a violation`() {
        listOf(
            "http://www.artic.edu/iiif/a.jpg", "https://www.artic.edu/iiif/a.tif", "https://evil.example.org/a.jpg", ""
        ).forEach { image ->
            assertTrue("`$image`", SourceContractRules.violations(candidate(image = image), registration).any { it.contains("image URL") })
        }
    }

    @Requirement("VI.4.c", "VI.4.d")
    @Test
    fun `a licence the app does not accept, or an attributed licence with no creator, is a violation`() {
        assertTrue(SourceContractRules.violations(candidate(license = LicenseType.UNKNOWN_OR_RESTRICTED), registration).any { it.startsWith("L1") })
        val noCreator = SourceContractRules.violations(
            candidate(license = LicenseType.CC_BY_SA, creator = "").let { it.copy(provenance = it.provenance.copy(sourceId = wikimedia.sourceId)) },
            wikimedia
        )
        assertTrue("$noCreator", noCreator.any { it.startsWith("L1") })
    }

    @Test
    fun `a negative size is a violation and an unknown size is not`() {
        assertTrue(SourceContractRules.violations(candidate(width = -1), registration).any { it.contains("negative") })
        assertEquals(emptyList<String>(), SourceContractRules.violations(candidate(width = 0, height = 0), registration))
    }

    @Test
    fun `a source missing from the register is a violation`() {
        assertEquals(listOf("the source is not in the register"), SourceContractRules.violations(candidate(), null))
    }

    // --- A downloaded picture ---

    private fun png(w: Int, h: Int): ByteArray {
        fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        val head = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + be32(13) + "IHDR".toByteArray() + be32(w) + be32(h)
        return head + ByteArray(8_000)
    }

    @Test
    fun `a picture the app can size and that is inside the limits has no problems`() {
        assertEquals(emptyList<String>(), SourceContractRules.imageProblems(png(1600, 900)))
    }

    @Test
    fun `a picture of another kind, one too small or too big, or one with no readable size, is a problem`() {
        assertTrue(SourceContractRules.imageProblems(ByteArray(6_000) { 0x41 }).any { it.contains("not a JPEG") })
        assertTrue(SourceContractRules.imageProblems(png(20_000, 100)).any { it.contains("decode limits") })
        assertTrue(SourceContractRules.imageProblems(png(1600, 900).copyOf(100)).any { it.contains("only") })
        val noSize = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(6_000)
        assertTrue(SourceContractRules.imageProblems(noSize).any { it.contains("no size") })
    }

    // --- What counts as drift ---

    private fun check(
        candidates: Int = 4,
        violations: List<String> = emptyList(),
        failure: Throwable? = null,
        httpError: Throwable? = null,
        imageProblems: List<String> = emptyList(),
        imageFetchError: Throwable? = null,
        imageRefusal: String? = null
    ) = SourceCheck("artic_cc0", "SCENIC_FINE_ART", candidates, violations, failure, httpError, imageProblems, imageFetchError, imageRefusal)

    @Test
    fun `a healthy source is fine, and a changed one is drift`() {
        assertEquals(Verdict.OK, classify(check()))
        assertEquals(Verdict.DRIFT, classify(check(failure = SourceResponseException("not json"))))
        assertEquals(Verdict.DRIFT, classify(check(violations = listOf("no id"))))
        assertEquals(Verdict.DRIFT, classify(check(imageProblems = listOf("not a JPEG, PNG or WebP picture"))))
        assertEquals("a picture the client refuses (host, URL or size) is drift", Verdict.DRIFT, classify(check(imageRefusal = "host not allowed")))
    }

    @Test
    fun `a rate limit or a bad day on the network is never drift`() {
        assertEquals(Verdict.RATE_LIMITED, classify(check(candidates = 0, failure = RateLimitException(60, "slow down"))))
        assertEquals(Verdict.RATE_LIMITED, classify(check(imageFetchError = RateLimitException(60, "slow down"))))
        assertEquals(Verdict.TRANSIENT, classify(check(candidates = 0, failure = IOException("timeout"))))
        assertEquals(Verdict.TRANSIENT, classify(check(candidates = 0, httpError = IOException("reset"))))
    }

    @Test
    fun `an empty answer with no error is a warning, and a picture that could not be fetched is a warning`() {
        assertEquals(Verdict.WARN, classify(check(candidates = 0)))
        assertEquals(Verdict.WARN, classify(check(imageFetchError = IOException("too large"))))
    }

    @Test
    fun `only a run in which every source could be judged is a pass that may close the issue`() {
        assertEquals("PASSED", overallResult(listOf(Verdict.OK, Verdict.WARN, Verdict.OK)))
        assertEquals("FAILED", overallResult(listOf(Verdict.OK, Verdict.DRIFT, Verdict.TRANSIENT)))
        assertEquals("PARTIAL", overallResult(listOf(Verdict.OK, Verdict.TRANSIENT)))
        assertEquals("PARTIAL", overallResult(listOf(Verdict.OK, Verdict.RATE_LIMITED)))
        assertEquals("INCONCLUSIVE", overallResult(listOf(Verdict.TRANSIENT, Verdict.TRANSIENT)))
        assertEquals("PARTIAL", overallResult(emptyList()))
    }

    @Test
    fun `the run fails on drift alone, and is inconclusive only when every source had a network problem`() {
        assertTrue(runFails(listOf(Verdict.OK, Verdict.DRIFT)))
        assertFalse(runFails(listOf(Verdict.OK, Verdict.WARN, Verdict.RATE_LIMITED, Verdict.TRANSIENT)))
        assertTrue(runInconclusive(listOf(Verdict.TRANSIENT, Verdict.TRANSIENT)))
        assertFalse(runInconclusive(listOf(Verdict.TRANSIENT, Verdict.OK)))
        assertFalse(runInconclusive(emptyList()))
    }
}
