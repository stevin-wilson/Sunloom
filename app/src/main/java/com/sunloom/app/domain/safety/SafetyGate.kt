package com.sunloom.app.domain.safety

import com.sunloom.app.domain.model.SourceIds
import com.sunloom.app.data.sources.SourceRegistry
import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.ImageFormat
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.SafetyVerdict
import java.security.MessageDigest
import java.util.Locale

data class ImageHeaderBounds(
    val width: Int,
    val height: Int,
    val outMimeType: String? = null
)

/**
 * Structured builder for per-layer safety gate outcomes (Article VI §2 & VII §5),
 * replacing scattered string literals across repository and evaluator call sites.
 */
object GateTrace {
    fun metadataPass(isUserLocal: Boolean = false): String =
        if (isUserLocal) "L1:USER_EXEMPT;L2:USER_EXEMPT" else "L1:PASS;L2:PASS"

    fun payloadPass(isUserLocal: Boolean = false): String =
        "${metadataPass(isUserLocal)};L3:PASS"

    fun fullPass(isUserLocal: Boolean = false): String =
        "${payloadPass(isUserLocal)};L4:PASS"

    fun userLocalPass(): String = fullPass(isUserLocal = true)

    fun failedAt(layer: String, reasonCode: String, isUserLocal: Boolean = false): String {
        val cleanReason = reasonCode.trim().ifEmpty { "REJECTED" }
        val l1Token = if (isUserLocal) "L1:USER_EXEMPT" else "L1:PASS"
        val l2Token = if (isUserLocal) "L2:USER_EXEMPT" else "L2:PASS"
        return when (layer.uppercase(Locale.ROOT)) {
            "L1" -> "L1:FAIL($cleanReason)"
            "L2" -> "$l1Token;L2:FAIL($cleanReason)"
            "L3" -> "$l1Token;$l2Token;L3:FAIL($cleanReason)"
            "L4" -> "$l1Token;$l2Token;L3:PASS;L4:FAIL($cleanReason)"
            else -> "$layer:FAIL($cleanReason)"
        }
    }
}

/**
 * Article VI, VII & VIII (Constitution v0.4): Mandatory Layered Fail-Closed Safety Gate.
 *
 * Enforces strict multi-layer content safety and input validation:
 * - L1 (Eligibility & License): Verifies admitted & enabled source in SourceRegistry,
 *   allowlisted license (Article VI §5), and required creator attribution for CC BY / CC BY-SA
 *   works (Article VI §8, rejecting missing or "Unknown author").
 * - L2 (Metadata & Blocklist Screen, incl. L2c single-scene guard rejecting montages, collages and
 *   figure plates): Verifies explicit SAFE classification, HTTPS transport
 *   from an admitted Schedule A host, versioned Unicode NFKD / stem-normalized denylist
 *   (SafetyDenylist Tier 1 & Tier 2), safe visual domain membership without injected tags,
 *   and asset ID / SHA-256 / perceptual dHash blocklists.
 * - L3 (Payload & Structural Verification): Validates magic-byte headers (JPEG, PNG, WebP only),
 *   enforces file size bounds (4 KB .. 40 MB), checks header decode bounds (<= 16,384 px per side,
 *   <= 100 MP total) and MIME-type match, and verifies SHA-256 against the blocklist.
 * - L4/L5 (Perceptual Hash & Near-Duplicate Blocklisting): Computes 64-bit difference hash (dHash)
 *   to block rejected images across restarts and across all sources.
 */
class SafetyGate {

    fun evaluateMetadata(
        candidate: CandidateAsset,
        blockedAssetIds: Set<String> = emptySet(),
        blockedPerceptualHashes: Set<String> = emptySet(),
        blockedSha256Checksums: Set<String> = emptySet(),
        enabledSources: Set<String>? = null
    ): SafetyVerdict {
        val auditNotes = ArrayList<String>()

        // The checks run in this order and the first to object decides; the order is part of the gate's behaviour.
        checkBlocklists(candidate, blockedAssetIds, blockedPerceptualHashes, blockedSha256Checksums)?.let { return it }

        // Article VI §10 & VII §4: User-selected local imports are exempt from L1/L2 source & lexical gates
        if (candidate.isUserLocal) {
            auditNotes.add("User-selected local file: L1 & L2 exempt per Article VI §10")
            return approved(candidate, auditNotes)
        }

        val srcId = candidate.provenance.sourceId
        checkSource(srcId, enabledSources)?.let { return it }
        checkClassification(candidate, auditNotes)?.let { return it }
        checkLicense(candidate, auditNotes)?.let { return it }
        checkTransport(candidate, srcId)?.let { return it }
        checkLexical(candidate, auditNotes)?.let { return it }
        checkNasaRecord(candidate, srcId)?.let { return it }
        checkSafeDomain(candidate, auditNotes)?.let { return it }

        return approved(candidate, auditNotes)
    }

    private fun approved(candidate: CandidateAsset, auditNotes: List<String>) = SafetyVerdict.Approved(
        sha256Checksum = candidate.provenance.sha256Checksum,
        detectedFormat = ImageFormat.JPEG,
        auditNotes = auditNotes,
        gateLayerOutcomes = GateTrace.metadataPass(candidate.isUserLocal)
    )

    private fun rejected(reasonCode: String, explanation: String, failedLayer: String) =
        SafetyVerdict.Rejected(reasonCode = reasonCode, explanation = explanation, failedLayer = failedLayer)

    /** L1a. Check asset, SHA-256, & perceptual hash blocklists (Article VII §4 L2 & L5) */
    private fun checkBlocklists(
        candidate: CandidateAsset,
        blockedAssetIds: Set<String>,
        blockedPerceptualHashes: Set<String>,
        blockedSha256Checksums: Set<String>
    ): SafetyVerdict.Rejected? {
        if (candidate.id in blockedAssetIds || candidate.provenance.assetId in blockedAssetIds) {
            return rejected("BLOCKLISTED_ASSET", "Asset '${candidate.id}' is in the permanent rejection blocklist.", "L2")
        }
        if (candidate.provenance.sha256Checksum.isNotBlank() &&
            candidate.provenance.sha256Checksum.lowercase(Locale.ROOT) in blockedSha256Checksums
        ) {
            return rejected("BLOCKLISTED_SHA256", "Asset SHA-256 checksum matches a permanently blocklisted file.", "L2")
        }
        if (candidate.provenance.perceptualHash.isNotBlank() &&
            blockedPerceptualHashes.any { isNearDuplicateDHash(candidate.provenance.perceptualHash, it) }
        ) {
            return rejected("BLOCKLISTED_PERCEPTUAL_HASH", "Asset perceptual hash matches a previously rejected wallpaper.", "L2")
        }
        return null
    }

    /** L1b. Admitted & enabled source check (Article VI §4 & VII §4 L1) */
    private fun checkSource(srcId: String, enabledSources: Set<String>?): SafetyVerdict.Rejected? {
        if (!SourceRegistry.isSourceAdmitted(srcId)) {
            return rejected("UNADMITTED_SOURCE", "Source '$srcId' is not an admitted Schedule A source.", "L1")
        }
        if (enabledSources != null && srcId !in enabledSources && srcId != SourceIds.SEED_RESERVE) {
            return rejected("DISABLED_SOURCE", "Source '$srcId' is currently disabled.", "L1")
        }
        return null
    }

    /** L1c. Fail-closed safety classification & Schedule A signal provenance check (Article VII §1 & §4 L2) */
    private fun checkClassification(candidate: CandidateAsset, auditNotes: MutableList<String>): SafetyVerdict.Rejected? {
        val classificationSignal = candidate.provenance.safetyClassificationSource.trim()
        if (candidate.provenance.safetyClassification != SafetyClassification.SAFE || classificationSignal.isEmpty()) {
            return rejected(
                "UNVERIFIED_OR_UNSAFE_CLASSIFICATION",
                if (classificationSignal.isEmpty()) {
                    "Safety classification signal source is missing (Article VII §4 L2)."
                } else {
                    "Safety classification is ${candidate.provenance.safetyClassification} (must be explicitly SAFE)."
                },
                "L2"
            )
        }
        auditNotes.add("Safety classification verified as SAFE via $classificationSignal")
        return null
    }

    /** L1d. License & rights check (Article VI §5) */
    private fun checkLicense(candidate: CandidateAsset, auditNotes: MutableList<String>): SafetyVerdict.Rejected? {
        if (!candidate.provenance.licenseType.permitsWallpaperUse) {
            return rejected(
                "INCOMPATIBLE_LICENSE",
                "License ${candidate.provenance.licenseType.label} does not explicitly permit wallpaper use.",
                "L1"
            )
        }
        // Article VI §8: Attribution-required licenses (CC BY, CC BY-SA) must have a valid human/institutional creator
        if (candidate.provenance.licenseType in ATTRIBUTION_REQUIRED_LICENSES) {
            val normalizedCreator = SafetyDenylist.normalizeRaw(candidate.provenance.creatorName)
            if (normalizedCreator.isEmpty() || normalizedCreator in INVALID_CREATOR_PLACEHOLDERS) {
                return rejected(
                    "MISSING_REQUIRED_CREATOR",
                    "License ${candidate.provenance.licenseType.label} requires creator attribution, " +
                        "but creatorName was missing or unknown.",
                    "L1"
                )
            }
        }
        auditNotes.add("License verified: ${candidate.provenance.licenseType.label}")
        return null
    }

    /** L1e. Transport security check for internet assets (Article VI §4f & VIII) */
    private fun checkTransport(candidate: CandidateAsset, srcId: String): SafetyVerdict.Rejected? {
        if (!candidate.isUserLocal && srcId != SourceIds.SEED_RESERVE && !candidate.downloadUrl.startsWith("https://")) {
            return rejected("INSECURE_TRANSPORT", "Internet download URL must use encrypted HTTPS transport.", "L1")
        }
        return null
    }

    /**
     * L2a. Mandatory tiered lexical denylist check across metadata (Article VII §1, §2 & §4), then
     * L2c. the single-scene guard: reject montages, collages, N x M arrays and labelled figure plates
     * (Article VII §2 & §4, Constitution v0.13)
     */
    private fun checkLexical(candidate: CandidateAsset, auditNotes: MutableList<String>): SafetyVerdict.Rejected? {
        val matchedProhibited = SafetyDenylist.findProhibitedMatch(
            title = candidate.title,
            description = candidate.description,
            rawCategoriesAndTags = candidate.rawCategoriesAndTags,
            attributionText = candidate.provenance.attributionText
        )
        if (matchedProhibited != null) {
            return rejected(
                "PROHIBITED_CONTENT_TERM",
                "Asset metadata matched prohibited safety or public-display term '$matchedProhibited'.",
                "L2"
            )
        }
        auditNotes.add("Passed L2 safety & public-display denylist gate ($COMPOSITE_GATE_VERSION)")

        val compositeMatch = SafetyDenylist.findCompositeLayoutMatch(
            title = candidate.title,
            description = candidate.description,
            rawCategoriesAndTags = candidate.rawCategoriesAndTags
        )
        if (compositeMatch != null) {
            return rejected(
                "COMPOSITE_OR_MULTI_PANEL_LAYOUT",
                "Asset is a montage, collage or multi-panel figure rather than a single scene (matched '$compositeMatch').",
                "L2"
            )
        }
        return null
    }

    /**
     * L2d. Not a scene: data maps, charts, diagrams and artist renderings (Constitution v0.25)
     * Only NASA's library mixes photographs with maps, products and staff shots; museum prints and user files
     * are not judged here. The record family is read from the stored asset id (`nasa-<id>`).
     */
    private fun checkNasaRecord(candidate: CandidateAsset, srcId: String): SafetyVerdict.Rejected? {
        if (srcId != SourceIds.NASA_LIBRARY) return null
        val notAdmitted = NasaRecordPolicy.rejection(
            nasaId = candidate.provenance.assetId.removePrefix("nasa-"),
            title = candidate.title,
            description = candidate.description,
            keywords = candidate.rawCategoriesAndTags
        )
        if (notAdmitted != null) {
            return rejected(
                "NASA_RECORD_NOT_ADMITTED",
                "NASA record is not a wallpaper-grade photograph: $notAdmitted.",
                "L2"
            )
        }
        val nonPhotographicMatch = SafetyDenylist.findNonPhotographicMatch(
            title = candidate.title,
            description = candidate.description,
            rawCategoriesAndTags = candidate.rawCategoriesAndTags
        )
        if (nonPhotographicMatch != null) {
            return rejected(
                "NON_PHOTOGRAPHIC_CONTENT",
                "Asset is a map, chart, diagram or rendering rather than a photograph (matched '$nonPhotographicMatch').",
                "L2"
            )
        }
        return null
    }

    /** L2b. Internet candidates must have non-empty source tags and match a recognized safe visual domain */
    private fun checkSafeDomain(candidate: CandidateAsset, auditNotes: MutableList<String>): SafetyVerdict.Rejected? {
        if (candidate.isUserLocal) return null
        if (candidate.rawCategoriesAndTags.isEmpty()) {
            return rejected(
                "MISSING_SAFETY_TAGS",
                "Internet-sourced candidate has no category or subject tags from the source.",
                "L2"
            )
        }
        val declaredByMuseumQuery = candidate.provenance.sourceId in MUSEUM_SOURCES_WITH_CLASSIFICATION_FILTER &&
            candidate.category in MUSEUM_SAFE_CATEGORIES
        val matchesSafeDomain = SafetyDenylist.matchesSafeDomain(candidate.title, candidate.rawCategoriesAndTags)
        if (!declaredByMuseumQuery && !matchesSafeDomain) {
            return rejected(
                "UNRECOGNIZED_SAFE_DOMAIN",
                "Candidate does not match any verified safe visual domain (landscape, nature, space, architecture, abstract, scenic).",
                "L2"
            )
        }
        auditNotes.add(
            if (declaredByMuseumQuery && !matchesSafeDomain) {
                "Safe visual domain taken from the filtered museum query (${candidate.category.displayName}); L2a passed"
            } else {
                "Verified safe visual domain membership (${candidate.category.displayName})"
            }
        )
        return null
    }

    fun evaluatePayload(
        candidate: CandidateAsset,
        bytes: ByteArray,
        blockedAssetIds: Set<String> = emptySet(),
        blockedPerceptualHashes: Set<String> = emptySet(),
        blockedSha256Checksums: Set<String> = emptySet(),
        enabledSources: Set<String>? = null,
        headerBounds: ImageHeaderBounds? = null
    ): SafetyVerdict {
        val metaVerdict = evaluateMetadata(
            candidate = candidate,
            blockedAssetIds = blockedAssetIds,
            blockedPerceptualHashes = blockedPerceptualHashes,
            blockedSha256Checksums = blockedSha256Checksums,
            enabledSources = enabledSources
        )
        if (metaVerdict is SafetyVerdict.Rejected) {
            return metaVerdict
        }

        val minBytes = if (candidate.isUserLocal) MIN_USER_LOCAL_PAYLOAD_BYTES else MIN_PAYLOAD_BYTES
        if (bytes.size < minBytes || bytes.size > MAX_PAYLOAD_BYTES) {
            return SafetyVerdict.Rejected(
                reasonCode = "PAYLOAD_SIZE_OUT_OF_BOUNDS",
                explanation = "Payload size (${bytes.size} bytes) is outside permitted bounds ($minBytes..$MAX_PAYLOAD_BYTES bytes).",
                failedLayer = "L3"
            )
        }

        val format = detectImageFormat(bytes)
        if (format == ImageFormat.UNSUPPORTED) {
            return SafetyVerdict.Rejected(
                reasonCode = "INVALID_IMAGE_HEADER",
                explanation = "Binary payload magic bytes do not match a valid JPEG, PNG, or WebP header.",
                failedLayer = "L3"
            )
        }

        val effectiveBounds = headerBounds ?: inspectImageHeaderBounds(bytes)
        if (effectiveBounds != null) {
            if (effectiveBounds.width <= 0 || effectiveBounds.height <= 0) {
                return SafetyVerdict.Rejected(
                    reasonCode = "CORRUPT_BITMAP_HEADER",
                    explanation = "Image header reported non-positive dimensions (${effectiveBounds.width}×${effectiveBounds.height}).",
                    failedLayer = "L3"
                )
            }
            val totalPixels = effectiveBounds.width.toLong() * effectiveBounds.height.toLong()
            if (exceedsDecodeBounds(effectiveBounds)) {
                return SafetyVerdict.Rejected(
                    reasonCode = "IMAGE_DIMENSIONS_OUT_OF_BOUNDS",
                    explanation = "Image dimensions (${effectiveBounds.width}×${effectiveBounds.height}, $totalPixels px) exceed safe decode bounds (${MAX_DIMENSION_PX}px / ${MAX_TOTAL_PIXELS}px).",
                    failedLayer = "L3"
                )
            }
            val mime = effectiveBounds.outMimeType?.lowercase(Locale.ROOT).orEmpty()
            if (mime.isNotBlank() && mime != format.mimeType) {
                return SafetyVerdict.Rejected(
                    reasonCode = "MIME_MAGIC_MISMATCH",
                    explanation = "Decoder MIME type '$mime' does not match magic-byte format '${format.mimeType}'.",
                    failedLayer = "L3"
                )
            }
        }

        val sha256 = sha256Hex(bytes)
        if (sha256 in blockedSha256Checksums) {
            return SafetyVerdict.Rejected(
                reasonCode = "BLOCKLISTED_SHA256",
                explanation = "Payload SHA-256 ($sha256) matches a permanently blocklisted image.",
                failedLayer = "L3"
            )
        }

        val notes = (metaVerdict as SafetyVerdict.Approved).auditNotes + listOf(
            "Binary header verified as $format (${bytes.size} bytes)",
            "SHA-256 integrity checksum computed: ${sha256.take(16)}..."
        )

        return SafetyVerdict.Approved(
            sha256Checksum = sha256,
            detectedFormat = format,
            auditNotes = notes,
            gateLayerOutcomes = GateTrace.payloadPass(candidate.isUserLocal)
        )
    }

    companion object {
        /**
         * Museum adapters already restrict results by department/classification and search a category-specific
         * query, so their tags (object types, techniques) rarely contain a landscape word. For them the declared
         * category stands in for the L2b keyword; L2a/L2c and the tag requirement still apply.
         */
        private val MUSEUM_SOURCES_WITH_CLASSIFICATION_FILTER =
            setOf("met_museum_cc0", "cma_cc0", "smk_open", "artic_cc0")
        private val MUSEUM_SAFE_CATEGORIES = setOf(
            SafeCategory.SCENIC_FINE_ART,
            SafeCategory.LANDSCAPES_NATURE,
            SafeCategory.ARCHITECTURE_CITYSCAPES,
            SafeCategory.BOTANICAL_FLORA
        )

        const val GATE_VERSION = "v0.6.0"
        const val EVALUATOR_VERSION = "eval-v6"
        const val BLOCKLIST_SCHEMA_VERSION = "bl-v2"
        val COMPOSITE_GATE_VERSION: String =
            "$GATE_VERSION:${SafetyDenylist.DENYLIST_VERSION}:$EVALUATOR_VERSION:$BLOCKLIST_SCHEMA_VERSION"

        const val MIN_PAYLOAD_BYTES = 4_096
        const val MIN_USER_LOCAL_PAYLOAD_BYTES = 64
        const val MAX_PAYLOAD_BYTES = 40 * 1024 * 1024
        /** True when a picture is larger than the L3 decode bounds (per side or in total pixels). */
        fun exceedsDecodeBounds(bounds: ImageHeaderBounds): Boolean =
            bounds.width > MAX_DIMENSION_PX || bounds.height > MAX_DIMENSION_PX ||
                bounds.width.toLong() * bounds.height.toLong() > MAX_TOTAL_PIXELS

        const val MAX_DIMENSION_PX = 16_384
        const val MAX_TOTAL_PIXELS = 100_000_000L

        private val ATTRIBUTION_REQUIRED_LICENSES = setOf(
            LicenseType.CC_BY,
            LicenseType.CC_BY_SA
        )

        private val INVALID_CREATOR_PLACEHOLDERS = setOf(
            "unknown",
            "unknown author",
            "unknown artist",
            "unknown photographer",
            "unknown creator",
            "anonymous",
            "n a",
            "na",
            "none",
            "not provided",
            "unidentified"
        )

        fun detectMagicFormat(bytes: ByteArray): ImageFormat = detectImageFormat(bytes)

        /** SOF0..SOF15 carry the picture size; DHT (C4), JPG (C8) and DAC (CC) share the range but do not. */
        private fun isJpegFrameMarker(marker: Int): Boolean = marker in 0xC0..0xCF && marker !in NON_FRAME_MARKERS

        private val NON_FRAME_MARKERS = setOf(0xC4, 0xC8, 0xCC)

        /** Lossy `VP8 ` and lossless `VP8L` WebP, the common kinds; extended `VP8X` is read by the caller. */
        private fun webpSimpleBounds(bytes: ByteArray): ImageHeaderBounds? {
            if (bytes.size < 30 || String(bytes, 12, 3, Charsets.ISO_8859_1) != "VP8") return null
            fun u8(i: Int) = bytes[i].toInt() and 0xFF
            when (bytes[15]) {
                ' '.code.toByte() -> {
                    // Frame tag (3 bytes), start code 9D 01 2A, then two 14-bit little-endian sizes.
                    if (u8(23) != 0x9D || u8(24) != 0x01 || u8(25) != 0x2A) return null
                    val w = (u8(26) or (u8(27) shl 8)) and 0x3FFF
                    val h = (u8(28) or (u8(29) shl 8)) and 0x3FFF
                    if (w > 0 && h > 0) return ImageHeaderBounds(w, h, ImageFormat.WEBP.mimeType)
                }
                'L'.code.toByte() -> {
                    // Signature 0x2F, then 14 bits of width-1 and 14 bits of height-1.
                    if (u8(20) != 0x2F) return null
                    val bits = u8(21) or (u8(22) shl 8) or (u8(23) shl 16) or (u8(24) shl 24)
                    val w = (bits and 0x3FFF) + 1
                    val h = ((bits ushr 14) and 0x3FFF) + 1
                    return ImageHeaderBounds(w, h, ImageFormat.WEBP.mimeType)
                }
            }
            return null
        }

        /**
         * Parses PNG IHDR, JPEG SOF0/SOF2, or WebP VP8/VP8L/VP8X header dimensions directly
         * from the byte stream without allocating a Bitmap. Returns null if the byte stream
         * is a synthetic test buffer without a full dimension header.
         */
        fun inspectImageHeaderBounds(bytes: ByteArray): ImageHeaderBounds? {
            val format = detectImageFormat(bytes)
            when (format) {
                ImageFormat.PNG -> {
                    // PNG IHDR chunk starts at byte 8: length(4), 'IHDR'(4), width(4 BE), height(4 BE)
                    if (bytes.size >= 24 &&
                        bytes[12] == 'I'.code.toByte() &&
                        bytes[13] == 'H'.code.toByte() &&
                        bytes[14] == 'D'.code.toByte() &&
                        bytes[15] == 'R'.code.toByte()
                    ) {
                        val w = readInt32Be(bytes, 16)
                        val h = readInt32Be(bytes, 20)
                        if (w > 0 && h > 0) {
                            return ImageHeaderBounds(w, h, ImageFormat.PNG.mimeType)
                        }
                    }
                }
                ImageFormat.JPEG -> {
                    var offset = 2
                    while (offset + 9 < bytes.size) {
                        if (bytes[offset] != 0xFF.toByte()) {
                            offset++
                            continue
                        }
                        var marker = bytes[offset + 1].toInt() and 0xFF
                        while (marker == 0xFF && offset + 2 < bytes.size) {
                            offset++
                            marker = bytes[offset + 1].toInt() and 0xFF
                        }
                        if (marker == 0xD8 || marker == 0xD9 || marker in 0xD0..0xD7 || marker == 0x01) {
                            offset += 2
                            continue
                        }
                        if (offset + 4 >= bytes.size) break
                        val segLen = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                            (bytes[offset + 3].toInt() and 0xFF)
                        if (segLen < 2) break
                        // Every frame header (SOF0..SOF15) carries the size, except DHT (C4), JPG (C8) and DAC (CC).
                        if (isJpegFrameMarker(marker)) {
                            if (offset + 9 < bytes.size) {
                                val h = ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
                                    (bytes[offset + 6].toInt() and 0xFF)
                                val w = ((bytes[offset + 7].toInt() and 0xFF) shl 8) or
                                    (bytes[offset + 8].toInt() and 0xFF)
                                if (w > 0 && h > 0) {
                                    return ImageHeaderBounds(w, h, ImageFormat.JPEG.mimeType)
                                }
                            }
                            break
                        }
                        offset += 2 + segLen
                    }
                }
                ImageFormat.WEBP -> {
                    webpSimpleBounds(bytes)?.let { return it }
                    if (bytes.size >= 30 &&
                        bytes[12] == 'V'.code.toByte() &&
                        bytes[13] == 'P'.code.toByte() &&
                        bytes[14] == '8'.code.toByte() &&
                        bytes[15] == 'X'.code.toByte()
                    ) {
                        val w = 1 + ((bytes[24].toInt() and 0xFF) or
                            ((bytes[25].toInt() and 0xFF) shl 8) or
                            ((bytes[26].toInt() and 0xFF) shl 16))
                        val h = 1 + ((bytes[27].toInt() and 0xFF) or
                            ((bytes[28].toInt() and 0xFF) shl 8) or
                            ((bytes[29].toInt() and 0xFF) shl 16))
                        if (w > 0 && h > 0) {
                            return ImageHeaderBounds(w, h, ImageFormat.WEBP.mimeType)
                        }
                    }
                }
                ImageFormat.UNSUPPORTED -> return null
            }
            return null
        }

        private fun readInt32Be(bytes: ByteArray, offset: Int): Int {
            return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
        }

        fun detectImageFormat(bytes: ByteArray): ImageFormat {
            if (bytes.size < 12) return ImageFormat.UNSUPPORTED

            // JPEG: FF D8 FF
            if (bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte()
            ) {
                return ImageFormat.JPEG
            }

            // PNG: 89 50 4E 47 0D 0A 1A 0A
            if (bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte() &&
                bytes[4] == 0x0D.toByte() &&
                bytes[5] == 0x0A.toByte() &&
                bytes[6] == 0x1A.toByte() &&
                bytes[7] == 0x0A.toByte()
            ) {
                return ImageFormat.PNG
            }

            // WebP: "RIFF" .... "WEBP"
            if (bytes[0] == 'R'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() &&
                bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() &&
                bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() &&
                bytes[11] == 'P'.code.toByte()
            ) {
                return ImageFormat.WEBP
            }

            return ImageFormat.UNSUPPORTED
        }

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hex = StringBuilder(digest.size * 2)
            for (b in digest) {
                hex.append(String.format(Locale.ROOT, "%02x", b))
            }
            return hex.toString()
        }

        /**
         * Computes a 64-bit horizontal difference perceptual hash (dHash) from an ARGB pixel buffer.
         */
        fun computePerceptualDHashFromPixels(pixels: IntArray, width: Int, height: Int): String {
            if (width <= 0 || height <= 0 || pixels.size < width * height) return ""
            var hashBits = 0L
            var bitIndex = 0
            for (row in 0 until 8) {
                val y = ((row + 0.5) * height / 8.0).toInt().coerceIn(0, height - 1)
                var prevLuma = sampleLuma(pixels, width, height, 0, y)
                for (col in 1..8) {
                    val x = ((col + 0.5) * width / 9.0).toInt().coerceIn(0, width - 1)
                    val currLuma = sampleLuma(pixels, width, height, x, y)
                    if (prevLuma > currLuma) {
                        hashBits = hashBits or (1L shl bitIndex)
                    }
                    prevLuma = currLuma
                    bitIndex++
                }
            }
            return java.lang.Long.toUnsignedString(hashBits, 16).padStart(16, '0')
        }

        private fun sampleLuma(pixels: IntArray, width: Int, height: Int, x: Int, y: Int): Int {
            val safeX = x.coerceIn(0, width - 1)
            val safeY = y.coerceIn(0, height - 1)
            val argb = pixels[safeY * width + safeX]
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return (r * 299 + g * 587 + b * 114) / 1000
        }

        /**
         * A dHash of a flat, very dark or very bright picture is almost all zeros or ones, so unrelated minimal
         * wallpapers would "match". Only hashes carrying real structure should drive duplicate/similarity logic.
         */
        fun isInformativeDHash(hash: String): Boolean {
            if (hash.length != 16) return false
            val bits = hash.toULongOrNull(16)?.countOneBits() ?: return false
            return bits in 10..54
        }

        /**
         * Returns true if two 16-char hex 64-bit perceptual dHashes have a Hamming distance <= maxHammingBits.
         */
        fun isNearDuplicateDHash(hashA: String, hashB: String, maxHammingBits: Int = 6): Boolean {
            if (hashA.length != 16 || hashB.length != 16) return false
            return try {
                val bitsA = java.lang.Long.parseUnsignedLong(hashA, 16)
                val bitsB = java.lang.Long.parseUnsignedLong(hashB, 16)
                java.lang.Long.bitCount(bitsA xor bitsB) <= maxHammingBits
            } catch (_: NumberFormatException) {
                false
            }
        }
    }
}
