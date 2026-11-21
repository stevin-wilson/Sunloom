package com.sunloom.app.domain.safety

import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification

/** A candidate that passes every layer, and payloads of the right and wrong kind, for tests that change one thing at a time. */
internal object SafetyFixtures {

    fun candidate(
        isUserLocal: Boolean = false,
        sourceId: String = "wikimedia_featured",
        tags: List<String> = listOf("landscape", "mountains", "lake")
    ) = CandidateAsset(
        id = "fixture-1",
        title = "Mountain lake at dawn",
        description = "A landscape photograph of a mountain lake.",
        category = SafeCategory.LANDSCAPES_NATURE,
        rawCategoriesAndTags = tags,
        downloadUrl = "https://upload.wikimedia.org/wikipedia/commons/sample_lake.jpg",
        isUserLocal = isUserLocal,
        provenance = ProvenanceRecord(
            sourceId = sourceId,
            sourceName = "Wikimedia Commons Featured Pictures",
            assetId = "fixture-1",
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
    fun jpeg(size: Int = 65_536): ByteArray = ByteArray(size).also {
        it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte(); it[3] = 0xE0.toByte()
        it[size - 2] = 0xFF.toByte(); it[size - 1] = 0xD9.toByte()
    }

    /** Bytes of an executable, not a picture: they begin with the ELF signature. */
    fun notAPicture(size: Int = 65_536): ByteArray = ByteArray(size).also {
        it[0] = 0x7F; it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte(); it[3] = 'F'.code.toByte()
    }

    /** Ordinary picture dimensions, so a test does not depend on how a header happens to be parsed. */
    val ordinaryBounds = ImageHeaderBounds(width = 3_840, height = 2_160, outMimeType = "image/jpeg")
}
