package com.sunloom.app.domain.safety

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.SafetyVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article VII §4, layer L3: an untrusted picture is accepted only inside the stated bounds, and the bounds are
 * inclusive: "4 KB..40 MB", "<= 16,384 px per side and <= 100 MP total". The limits protect the phone's memory from
 * a picture built to be too big to decode, so each is tested exactly at the limit and one past it; a check that is
 * off by one in either direction is a different rule from the one written down.
 */
@Requirement("VII.4")
class SafetyGateBoundsTest {

    private val gate = SafetyGate()

    private fun candidate(isUserLocal: Boolean = false) = CandidateAsset(
        id = "bounds-1",
        title = "Mountain lake at dawn",
        description = "A landscape photograph of a mountain lake.",
        category = SafeCategory.LANDSCAPES_NATURE,
        rawCategoriesAndTags = listOf("landscape", "mountains", "lake"),
        downloadUrl = "https://upload.wikimedia.org/wikipedia/commons/sample_lake.jpg",
        isUserLocal = isUserLocal,
        provenance = ProvenanceRecord(
            sourceId = "wikimedia_featured",
            sourceName = "Wikimedia Commons Featured Pictures",
            assetId = "bounds-1",
            originalPageUrl = "https://commons.wikimedia.org/wiki/File:Lake.jpg",
            creatorName = "A. Photographer",
            creatorPageUrl = "https://commons.wikimedia.org/wiki/User:Photographer",
            licenseType = LicenseType.CC_BY_SA,
            attributionText = "A. Photographer, CC BY-SA 4.0",
            retrievedAtEpochMs = 1762280000000L,
            originalWidth = 3840,
            originalHeight = 2400,
            renditionWidth = 3840,
            renditionHeight = 2400,
            safetyClassification = SafetyClassification.SAFE,
            safetyClassificationSource = "Wikimedia Featured Nature Curated Tree",
            sha256Checksum = ""
        )
    )

    /** A JPEG-looking payload of exactly [size] bytes: the signature at each end, zeros between. */
    private fun jpeg(size: Int): ByteArray = ByteArray(size).also {
        it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte(); it[3] = 0xE0.toByte()
        it[size - 2] = 0xFF.toByte(); it[size - 1] = 0xD9.toByte()
    }

    private fun reasonOf(verdict: SafetyVerdict): String? = (verdict as? SafetyVerdict.Rejected)?.reasonCode

    private val sizeReason = "PAYLOAD_SIZE_OUT_OF_BOUNDS"

    /** Ordinary picture dimensions, so the size tests do not depend on how a header happens to be parsed. */
    private val ordinary = ImageHeaderBounds(width = 3_840, height = 2_160, outMimeType = "image/jpeg")

    // --- Size of the download ---

    @Test
    fun `a download of exactly 4 KB is let through and one byte less is refused`() {
        assertTrue(SafetyGate.MIN_PAYLOAD_BYTES == 4 * 1024)
        assertEquals(sizeReason, reasonOf(gate.evaluatePayload(candidate(), jpeg(SafetyGate.MIN_PAYLOAD_BYTES - 1), headerBounds = ordinary)))
        val atLimit = gate.evaluatePayload(candidate(), jpeg(SafetyGate.MIN_PAYLOAD_BYTES), headerBounds = ordinary)
        assertTrue("4 KB is inside the bounds: $atLimit", atLimit is SafetyVerdict.Approved)
    }

    @Test
    fun `a download of exactly 40 MB is let through and one byte more is refused`() {
        assertTrue(SafetyGate.MAX_PAYLOAD_BYTES == 40 * 1024 * 1024)
        assertEquals(sizeReason, reasonOf(gate.evaluatePayload(candidate(), jpeg(SafetyGate.MAX_PAYLOAD_BYTES + 1), headerBounds = ordinary)))
        val atLimit = gate.evaluatePayload(candidate(), jpeg(SafetyGate.MAX_PAYLOAD_BYTES), headerBounds = ordinary)
        assertTrue("40 MB is inside the bounds: $atLimit", atLimit is SafetyVerdict.Approved)
    }

    @Test
    fun `a photo the person imported may be as small as 64 bytes, a downloaded one may not`() {
        assertTrue(SafetyGate.MIN_USER_LOCAL_PAYLOAD_BYTES == 64)
        assertEquals(sizeReason, reasonOf(gate.evaluatePayload(candidate(isUserLocal = true), jpeg(63), headerBounds = ordinary)))
        val atLimit = gate.evaluatePayload(candidate(isUserLocal = true), jpeg(64), headerBounds = ordinary)
        assertTrue("64 bytes is inside a user photo's bounds: $atLimit", atLimit is SafetyVerdict.Approved)
        // The same 64 bytes from a download is far below its 4 KB minimum.
        assertEquals(sizeReason, reasonOf(gate.evaluatePayload(candidate(isUserLocal = false), jpeg(64), headerBounds = ordinary)))
    }

    // --- Size of the picture ---

    private fun bounds(width: Int, height: Int) = ImageHeaderBounds(width, height, "image/jpeg")

    private fun verdictFor(width: Int, height: Int) =
        gate.evaluatePayload(candidate(), jpeg(SafetyGate.MIN_PAYLOAD_BYTES), headerBounds = bounds(width, height))

    @Test
    fun `a picture 16384 pixels along a side is let through and 16385 is refused`() {
        val side = SafetyGate.MAX_DIMENSION_PX
        assertEquals(16_384, side)
        assertFalse(SafetyGate.exceedsDecodeBounds(bounds(side, 1)))
        assertFalse(SafetyGate.exceedsDecodeBounds(bounds(1, side)))
        assertTrue(SafetyGate.exceedsDecodeBounds(bounds(side + 1, 1)))
        assertTrue(SafetyGate.exceedsDecodeBounds(bounds(1, side + 1)))

        assertTrue("16384 x 1 is inside the bounds", verdictFor(side, 1) is SafetyVerdict.Approved)
        assertTrue("1 x 16384 is inside the bounds", verdictFor(1, side) is SafetyVerdict.Approved)
        assertEquals("IMAGE_DIMENSIONS_OUT_OF_BOUNDS", reasonOf(verdictFor(side + 1, 1)))
        assertEquals("IMAGE_DIMENSIONS_OUT_OF_BOUNDS", reasonOf(verdictFor(1, side + 1)))
    }

    @Test
    fun `a picture of exactly 100 megapixels is let through and one row more is refused`() {
        assertEquals(100_000_000L, SafetyGate.MAX_TOTAL_PIXELS)
        // 10,000 x 10,000 is exactly 100 MP; 10,000 x 10,001 is 10,000 pixels over.
        assertFalse(SafetyGate.exceedsDecodeBounds(bounds(10_000, 10_000)))
        assertTrue(SafetyGate.exceedsDecodeBounds(bounds(10_000, 10_001)))
        assertTrue(verdictFor(10_000, 10_000) is SafetyVerdict.Approved)
        assertEquals("IMAGE_DIMENSIONS_OUT_OF_BOUNDS", reasonOf(verdictFor(10_000, 10_001)))
    }

    @Test
    fun `each side may be within its limit and the picture still too big overall`() {
        // 16,384 x 16,384 is 268 MP: neither side is over, the area is.
        val side = SafetyGate.MAX_DIMENSION_PX
        assertTrue(SafetyGate.exceedsDecodeBounds(bounds(side, side)))
        assertEquals("IMAGE_DIMENSIONS_OUT_OF_BOUNDS", reasonOf(verdictFor(side, side)))
        // Small sides stay far from the area limit, so the area is really multiplied (not divided or added).
        assertFalse(SafetyGate.exceedsDecodeBounds(bounds(3_840, 2_160)))
    }

    @Test
    fun `a header with a zero or negative side is corrupt, a one-pixel picture is not`() {
        listOf(0 to 100, 100 to 0, 0 to 0, -1 to 100, 100 to -1).forEach { (w, h) ->
            assertEquals("$w x $h", "CORRUPT_BITMAP_HEADER", reasonOf(verdictFor(w, h)))
        }
        assertTrue("1 x 1 is a (tiny) valid picture", verdictFor(1, 1) is SafetyVerdict.Approved)
    }
}
