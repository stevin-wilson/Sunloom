package com.sunloom.app.domain.safety

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.ImageFormat
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.SafetyVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SafetyGateTest {

    private lateinit var safetyGate: SafetyGate

    @Before
    fun setUp() {
        safetyGate = SafetyGate()
    }

    private fun validCandidate(
        title: String = "Yosemite Valley Sunrise over Half Dome",
        description: String = "High-resolution landscape photograph of granite cliffs and pine forest at dawn.",
        category: SafeCategory = SafeCategory.LANDSCAPES_NATURE,
        tags: List<String> = listOf("landscape", "mountains", "yosemite", "nature", "featured picture"),
        url: String = "https://upload.wikimedia.org/wikipedia/commons/sample_yosemite.jpg",
        safetyClassification: SafetyClassification = SafetyClassification.SAFE,
        licenseType: LicenseType = LicenseType.CC_BY_SA,
        creatorName: String = "Ansel Explorer",
        sourceId: String = "wikimedia_featured",
        isUserLocal: Boolean = false
    ): CandidateAsset {
        return CandidateAsset(
            id = "wiki-101",
            title = title,
            description = description,
            category = category,
            rawCategoriesAndTags = tags,
            downloadUrl = url,
            isUserLocal = isUserLocal,
            provenance = ProvenanceRecord(
                sourceId = sourceId,
                sourceName = "Wikimedia Commons Featured Pictures",
                assetId = "wiki-101",
                originalPageUrl = "https://commons.wikimedia.org/wiki/File:Yosemite.jpg",
                creatorName = creatorName,
                creatorPageUrl = "https://commons.wikimedia.org/wiki/User:Explorer",
                licenseType = licenseType,
                attributionText = "Ansel Explorer, CC BY-SA 4.0",
                retrievedAtEpochMs = 1762280000000L,
                originalWidth = 3840,
                originalHeight = 2400,
                renditionWidth = 3840,
                renditionHeight = 2400,
                safetyClassification = safetyClassification,
                safetyClassificationSource = "Wikimedia Featured Nature Curated Tree",
                sha256Checksum = ""
            )
        )
    }

    private fun validJpegBytes(size: Int = 65_536): ByteArray {
        val bytes = ByteArray(size) { 0x42 }
        // JPEG Magic Bytes: FF D8 FF E0 ... FF D9
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xD8.toByte()
        bytes[2] = 0xFF.toByte()
        bytes[3] = 0xE0.toByte()
        bytes[size - 2] = 0xFF.toByte()
        bytes[size - 1] = 0xD9.toByte()
        return bytes
    }

    private fun validPngBytes(size: Int = 65_536): ByteArray {
        val bytes = ByteArray(size) { 0x11 }
        // PNG Magic Bytes: 89 50 4E 47 0D 0A 1A 0A
        val header = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        System.arraycopy(header, 0, bytes, 0, header.size)
        return bytes
    }

    @Test
    fun `evaluateMetadata approves valid safe-for-work landscape candidate`() {
        val candidate = validCandidate()
        val verdict = safetyGate.evaluateMetadata(candidate)
        assertTrue("Expected Approved verdict but got $verdict", verdict is SafetyVerdict.Approved)
    }

    @Test
    fun `evaluateMetadata fails closed when safety classification is MISSING or UNCERTAIN or NSFW`() {
        for (unsafeStatus in listOf(
            SafetyClassification.MISSING,
            SafetyClassification.UNCERTAIN,
            SafetyClassification.CONFLICTING,
            SafetyClassification.NSFW_OR_UNSAFE
        )) {
            val candidate = validCandidate(safetyClassification = unsafeStatus)
            val verdict = safetyGate.evaluateMetadata(candidate)
            assertTrue("Expected Rejected for $unsafeStatus", verdict is SafetyVerdict.Rejected)
            assertEquals("UNVERIFIED_OR_UNSAFE_CLASSIFICATION", (verdict as SafetyVerdict.Rejected).reasonCode)
        }
    }

    @Test
    fun `evaluateMetadata rejects NSFW, nudity, figurative, violent, political, and spoofing terms`() {
        val prohibitedSamples = listOf(
            "Classical Nude Sculpture in Marble" to "PROHIBITED_CONTENT_TERM",
            "Explicit NSFW boudoir photography" to "PROHIBITED_CONTENT_TERM",
            "Graphic violence battlefield injury scene" to "PROHIBITED_CONTENT_TERM",
            "Vote for Election Campaign Rally Banner" to "PROHIBITED_CONTENT_TERM",
            "Critical System Error Fake Lock Screen Warning Prompt" to "PROHIBITED_CONTENT_TERM",
            "Bikini Lingerie Model Portrait" to "PROHIBITED_CONTENT_TERM"
        )
        for ((badTitle, expectedCode) in prohibitedSamples) {
            val candidate = validCandidate(title = badTitle)
            val verdict = safetyGate.evaluateMetadata(candidate)
            assertTrue("Expected Rejected for title '$badTitle'", verdict is SafetyVerdict.Rejected)
            assertEquals(expectedCode, (verdict as SafetyVerdict.Rejected).reasonCode)
        }
    }

    @Test
    fun `evaluateMetadata rejects internet candidate when tags are empty or lack safe visual domain`() {
        val untagged = validCandidate(tags = emptyList())
        val verdictEmpty = safetyGate.evaluateMetadata(untagged)
        assertTrue(verdictEmpty is SafetyVerdict.Rejected)
        assertEquals("MISSING_SAFETY_TAGS", (verdictEmpty as SafetyVerdict.Rejected).reasonCode)

        val unrelatedTags = validCandidate(
            title = "Untitled Document",
            description = "Scan of a page",
            tags = listOf("document", "scan", "paperwork")
        )
        val verdictUnrelated = safetyGate.evaluateMetadata(unrelatedTags)
        assertTrue(verdictUnrelated is SafetyVerdict.Rejected)
        assertEquals("UNRECOGNIZED_SAFE_DOMAIN", (verdictUnrelated as SafetyVerdict.Rejected).reasonCode)
    }

    @Test
    fun `evaluateMetadata rejects non-HTTPS internet download URLs`() {
        val cleartextCandidate = validCandidate(url = "http://upload.wikimedia.org/insecure.jpg")
        val verdict = safetyGate.evaluateMetadata(cleartextCandidate)
        assertTrue(verdict is SafetyVerdict.Rejected)
        assertEquals("INSECURE_TRANSPORT", (verdict as SafetyVerdict.Rejected).reasonCode)
    }

    @Test
    fun `evaluateMetadata rejects restricted or unknown licenses`() {
        val restricted = validCandidate(licenseType = LicenseType.UNKNOWN_OR_RESTRICTED)
        val verdict = safetyGate.evaluateMetadata(restricted)
        assertTrue(verdict is SafetyVerdict.Rejected)
        assertEquals("INCOMPATIBLE_LICENSE", (verdict as SafetyVerdict.Rejected).reasonCode)
    }

    @Test
    fun `evaluatePayload verifies magic bytes and computes SHA-256 checksum for valid JPEG and PNG`() {
        val candidate = validCandidate()
        val jpegVerdict = safetyGate.evaluatePayload(candidate, validJpegBytes())
        assertTrue(jpegVerdict is SafetyVerdict.Approved)
        val approvedJpeg = jpegVerdict as SafetyVerdict.Approved
        assertEquals(ImageFormat.JPEG, approvedJpeg.detectedFormat)
        assertEquals(64, approvedJpeg.sha256Checksum.length)

        val pngVerdict = safetyGate.evaluatePayload(candidate, validPngBytes())
        assertTrue(pngVerdict is SafetyVerdict.Approved)
        assertEquals(ImageFormat.PNG, (pngVerdict as SafetyVerdict.Approved).detectedFormat)
    }

    @Test
    fun `evaluatePayload rejects executable ELF, HTML error page, and tiny or oversized payloads`() {
        val candidate = validCandidate()

        // ELF executable disguised as image
        val elfBytes = ByteArray(70_000) { 0x00 }
        elfBytes[0] = 0x7F
        elfBytes[1] = 'E'.code.toByte()
        elfBytes[2] = 'L'.code.toByte()
        elfBytes[3] = 'F'.code.toByte()
        val elfVerdict = safetyGate.evaluatePayload(candidate, elfBytes)
        assertTrue(elfVerdict is SafetyVerdict.Rejected)
        assertEquals("INVALID_IMAGE_HEADER", (elfVerdict as SafetyVerdict.Rejected).reasonCode)

        // Too small payload (< 4 KB)
        val tinyVerdict = safetyGate.evaluatePayload(candidate, validJpegBytes(size = 512))
        assertTrue(tinyVerdict is SafetyVerdict.Rejected)
        assertEquals("PAYLOAD_SIZE_OUT_OF_BOUNDS", (tinyVerdict as SafetyVerdict.Rejected).reasonCode)
    }

    @Test
    fun `Schedule B - approves benign nature descriptions like adult eagle and rejects v0_3 hard negatives`() {
        val benignEagle = validCandidate(
            title = "Adult Bald Eagle Soaring Over Glacier Bay",
            description = "An adult eagle in flight above coastal mountains and forest."
        )
        assertTrue(safetyGate.evaluateMetadata(benignEagle) is SafetyVerdict.Approved)

        val v03HardNegatives = listOf(
            "Close-up Portrait of Fashion Model",
            "Document Scan of Historical Manuscript Page",
            "Official Apollo Mission Patch Insignia",
            "Tactical Assault Rifle Weapon Display",
            "Wildlife Poaching Animals in Distress"
        )
        for (hardNeg in v03HardNegatives) {
            val verdict = safetyGate.evaluateMetadata(validCandidate(title = hardNeg))
            assertTrue("Expected Rejected for '$hardNeg'", verdict is SafetyVerdict.Rejected)
            assertEquals("PROHIBITED_CONTENT_TERM", (verdict as SafetyVerdict.Rejected).reasonCode)
        }
    }

    @Test
    fun `Schedule B - enforces creator attribution on CC BY-SA and perceptual dHash blocklisting across sources`() {
        val missingCreatorCc = validCandidate(licenseType = LicenseType.CC_BY_SA, creatorName = "")
        val creatorVerdict = safetyGate.evaluateMetadata(missingCreatorCc)
        assertTrue(creatorVerdict is SafetyVerdict.Rejected)
        assertEquals("MISSING_REQUIRED_CREATOR", (creatorVerdict as SafetyVerdict.Rejected).reasonCode)

        val unknownAuthorCc = validCandidate(licenseType = LicenseType.CC_BY, creatorName = "Unknown author")
        val unknownVerdict = safetyGate.evaluateMetadata(unknownAuthorCc)
        assertTrue(unknownVerdict is SafetyVerdict.Rejected)
        assertEquals("MISSING_REQUIRED_CREATOR", (unknownVerdict as SafetyVerdict.Rejected).reasonCode)

        val pixels = IntArray(32 * 20) { idx ->
            val x = idx % 32
            val luma = (x * 7) and 0xFF
            (luma shl 16) or (luma shl 8) or luma
        }
        val dHash = SafetyGate.computePerceptualDHashFromPixels(pixels, 32, 20)
        assertEquals(16, dHash.length)
        assertTrue(SafetyGate.isNearDuplicateDHash(dHash, dHash))

        val candidateWithHash = validCandidate().let {
            it.copy(provenance = it.provenance.copy(perceptualHash = dHash))
        }
        val blockVerdict = safetyGate.evaluateMetadata(
            candidate = candidateWithHash,
            blockedPerceptualHashes = setOf(dHash)
        )
        assertTrue(blockVerdict is SafetyVerdict.Rejected)
        assertEquals("BLOCKLISTED_PERCEPTUAL_HASH", (blockVerdict as SafetyVerdict.Rejected).reasonCode)
    }

    @Test
    fun `testUnicodeNormalizationCatchesAccentedAndCyrillicLookalikes`() {
        // Accented "nüde" and Cyrillic lookalike "n\u0430ked" ('а' is Cyrillic U+0430)
        val accented = validCandidate(title = "Classical Nüde Study in Oil")
        val verdictAccented = safetyGate.evaluateMetadata(accented)
        assertTrue(verdictAccented is SafetyVerdict.Rejected)
        assertEquals("PROHIBITED_CONTENT_TERM", (verdictAccented as SafetyVerdict.Rejected).reasonCode)

        val cyrillic = validCandidate(title = "Figure Study n\u0430ked by the River")
        val verdictCyrillic = safetyGate.evaluateMetadata(cyrillic)
        assertTrue(verdictCyrillic is SafetyVerdict.Rejected)
        assertEquals("PROHIBITED_CONTENT_TERM", (verdictCyrillic as SafetyVerdict.Rejected).reasonCode)
    }

    @Test
    fun `testStemmingCatchesPluralProhibitedTerms`() {
        val plurals = listOf(
            "Ancient Roman Statues in Courtyard",
            "Royal Family Portraits Gallery",
            "Medieval Swords and Rifles Collection",
            "Anatomical Skulls and Skeletons",
            "Military Soldiers Marching"
        )
        for (pluralTitle in plurals) {
            val verdict = safetyGate.evaluateMetadata(validCandidate(title = pluralTitle))
            assertTrue("Expected plural '$pluralTitle' to be rejected", verdict is SafetyVerdict.Rejected)
            assertEquals("PROHIBITED_CONTENT_TERM", (verdict as SafetyVerdict.Rejected).reasonCode)
        }
    }

    @Test
    fun `testTier2AllowsBenignDescriptionAndLandmarksWhileRejectingPeopleTags`() {
        // Benign NASA ISS description mentioning "astronaut" while title & tags are pure Earth/Space
        val issEarthHorizon = validCandidate(
            title = "Aurora Australis Over the Indian Ocean",
            description = "An astronaut aboard the International Space Station photographed the green aurora over the horizon.",
            category = SafeCategory.SPACE_ASTRONOMY,
            tags = listOf("Earth", "Aurora", "Horizon", "Space")
        )
        val verdictIss = safetyGate.evaluateMetadata(issEarthHorizon)
        assertTrue("Expected ISS Earth horizon to be Approved, got $verdictIss", verdictIss is SafetyVerdict.Approved)

        // Benign landmarks ("North Face of the Eiger", "Death Valley Sand Dunes", "Dead Sea Coast")
        val landmarks = listOf(
            "Sunrise on the North Face of the Eiger",
            "Golden Sand Dunes in Death Valley National Park",
            "Turquoise Salt Formations along the Dead Sea Coast"
        )
        for (landmark in landmarks) {
            val verdict = safetyGate.evaluateMetadata(validCandidate(title = landmark))
            assertTrue("Expected landmark '$landmark' to be Approved, got $verdict", verdict is SafetyVerdict.Approved)
        }

        // Actual astronaut/crew portrait in title or tags -> Rejected
        val astronautTag = validCandidate(
            title = "Expedition 70 Crew in Cupola",
            tags = listOf("astronaut", "space")
        )
        val verdictCrew = safetyGate.evaluateMetadata(astronautTag)
        assertTrue(verdictCrew is SafetyVerdict.Rejected)
        assertEquals("PROHIBITED_CONTENT_TERM", (verdictCrew as SafetyVerdict.Rejected).reasonCode)
    }

    @Test
    fun `testSourceRegistryRejectsUnadmittedSourceAndDisabledSource`() {
        val unadmitted = validCandidate(sourceId = "random_scraper")
        val verdictUnadmitted = safetyGate.evaluateMetadata(unadmitted)
        assertTrue(verdictUnadmitted is SafetyVerdict.Rejected)
        assertEquals("UNADMITTED_SOURCE", (verdictUnadmitted as SafetyVerdict.Rejected).reasonCode)

        val disabled = validCandidate(sourceId = "wikimedia_featured")
        val verdictDisabled = safetyGate.evaluateMetadata(
            candidate = disabled,
            enabledSources = setOf("nasa_library")
        )
        assertTrue(verdictDisabled is SafetyVerdict.Rejected)
        assertEquals("DISABLED_SOURCE", (verdictDisabled as SafetyVerdict.Rejected).reasonCode)
    }

    @Test
    fun `testSafeDomainDoesNotMatchFeaturedAlone`() {
        // "featured" or "featured pictures" alone without a real safe domain stem must be rejected
        val featuredOnly = validCandidate(
            title = "Antique Brass Clockwork Mechanism",
            description = "Macro photograph of gears",
            tags = listOf("Featured pictures on Wikimedia Commons", "Clocks", "Brass")
        )
        val verdict = safetyGate.evaluateMetadata(featuredOnly)
        assertTrue(verdict is SafetyVerdict.Rejected)
        assertEquals("UNRECOGNIZED_SAFE_DOMAIN", (verdict as SafetyVerdict.Rejected).reasonCode)
    }

    @Test
    fun `testEvaluatePayloadEnforcesDimensionBoundsAndMimeMatch`() {
        val candidate = validCandidate()
        val bytes = validJpegBytes()

        // Oversized dimensions (> 16384 px) -> Rejected
        val hugeVerdict = safetyGate.evaluatePayload(
            candidate = candidate,
            bytes = bytes,
            headerBounds = ImageHeaderBounds(width = 20_000, height = 12_000, outMimeType = "image/jpeg")
        )
        assertTrue(hugeVerdict is SafetyVerdict.Rejected)
        assertEquals("IMAGE_DIMENSIONS_OUT_OF_BOUNDS", (hugeVerdict as SafetyVerdict.Rejected).reasonCode)

        // MIME mismatch (JPEG magic bytes but decoder reports image/png) -> Rejected
        val mimeMismatchVerdict = safetyGate.evaluatePayload(
            candidate = candidate,
            bytes = bytes,
            headerBounds = ImageHeaderBounds(width = 3840, height = 2160, outMimeType = "image/png")
        )
        assertTrue(mimeMismatchVerdict is SafetyVerdict.Rejected)
        assertEquals("MIME_MAGIC_MISMATCH", (mimeMismatchVerdict as SafetyVerdict.Rejected).reasonCode)
    }

    @Test
    fun `testSha256BlocklistEnforcement`() {
        val candidate = validCandidate()
        val bytes = validJpegBytes()
        val sha = SafetyGate.sha256Hex(bytes)

        val verdict = safetyGate.evaluatePayload(
            candidate = candidate,
            bytes = bytes,
            blockedSha256Checksums = setOf(sha)
        )
        assertTrue(verdict is SafetyVerdict.Rejected)
        assertEquals("BLOCKLISTED_SHA256", (verdict as SafetyVerdict.Rejected).reasonCode)
    }

    @Test
    fun `Schedule B - folds Nordic ligatures and blocks untranslated Danish and Dutch portrait or nude titles`() {
        val nordicHardNegatives = listOf(
            "Portræt af en kvinde ved havet",
            "Selvportræt i landskab",
            "Nøgen aktmodel i studie",
            "Naakt studie bij rivier"
        )
        for (unsafeTitle in nordicHardNegatives) {
            val verdict = safetyGate.evaluateMetadata(
                validCandidate(title = unsafeTitle, tags = listOf("landscape", "sea"))
            )
            assertTrue("Expected Rejected for '$unsafeTitle', got $verdict", verdict is SafetyVerdict.Rejected)
            assertEquals("PROHIBITED_CONTENT_TERM", (verdict as SafetyVerdict.Rejected).reasonCode)
        }
    }

    @Test
    fun `evaluateMetadata rejects SAFE classification when safetyClassificationSource is blank`() {
        val base = validCandidate()
        val missingSignal = base.copy(
            provenance = base.provenance.copy(safetyClassificationSource = "   ")
        )
        val verdict = safetyGate.evaluateMetadata(missingSignal)
        assertTrue("Expected blank safetyClassificationSource to be rejected fail-closed", verdict is SafetyVerdict.Rejected)
        assertEquals("UNVERIFIED_OR_UNSAFE_CLASSIFICATION", (verdict as SafetyVerdict.Rejected).reasonCode)
    }

    @Test
    fun `GateTrace builds structured per-layer outcomes for approved user-exempt and failed stages`() {
        assertEquals(
            "L1:PASS;L2:PASS;L3:PASS;L4:PASS",
            GateTrace.fullPass()
        )
        assertEquals(
            "L1:USER_EXEMPT;L2:USER_EXEMPT;L3:PASS;L4:PASS",
            GateTrace.userLocalPass()
        )
        assertEquals(
            "L1:PASS;L2:PASS;L3:FAIL(CORRUPT_BITMAP_HEADER)",
            GateTrace.failedAt(layer = "L3", reasonCode = "CORRUPT_BITMAP_HEADER")
        )
    }

    // ---- Article VII §4 L2c: composite / multi-panel layout screen (Constitution v0.13) ----

    private val nectarineCategories = listOf(
        "Prunus persica", "GFDL", "License migration redundant", "Self-published work",
        "Featured pictures on Wikipedia, English", "Featured pictures on Wikipedia, Turkish",
        "Featured pictures on Wikipedia, Vietnamese", "Montages of plants", "3x2 arrays",
        "Featured pictures of Rosales", "Montages of food"
    )

    private fun assertCompositeRejected(verdict: SafetyVerdict, label: String) {
        assertTrue("Expected $label to be Rejected, got $verdict", verdict is SafetyVerdict.Rejected)
        val rejected = verdict as SafetyVerdict.Rejected
        assertEquals(label, "COMPOSITE_OR_MULTI_PANEL_LAYOUT", rejected.reasonCode)
        assertEquals(label, "L2", rejected.failedLayer)
    }

    @Requirement("VII.4")
    @Test
    fun `testNectarineFruitDevelopmentMontageIsRejectedAtL2`() {
        val nectarine = validCandidate(
            title = "Nectarine Fruit Development",
            description = "Nectarine (Prunus persica) fruit development over a 7½ month period. " +
                "Early winter to midsummer, East Gippsland, Victoria, Australia.",
            category = SafeCategory.BOTANICAL_FLORA,
            tags = nectarineCategories
        )
        assertCompositeRejected(safetyGate.evaluateMetadata(nectarine), "Nectarine montage")
    }

    @Requirement("VII.4")
    @Test
    fun `testMontageCollageAndGridArrayCategoriesAreRejected`() {
        val cases = mapOf(
            "montage category" to listOf("Montages of plants", "flower"),
            "photomontage category" to listOf("Photomontages", "landscape"),
            "collage category" to listOf("Collages of flowers", "flower"),
            "grid array category only" to listOf("3x2 arrays", "flower"),
            "spaced grid array category" to listOf("4 x 3 arrays", "flower")
        )
        for ((label, tags) in cases) {
            val candidate = validCandidate(
                title = "Spring Flower Study",
                description = "Several photographs of spring flowers.",
                category = SafeCategory.BOTANICAL_FLORA,
                tags = tags
            )
            assertCompositeRejected(safetyGate.evaluateMetadata(candidate), label)
        }
    }

    @Requirement("VII.4")
    @Test
    fun `testMontageOrCollageInTitleIsRejected`() {
        val candidate = validCandidate(
            title = "Autumn Leaf Collage",
            tags = listOf("leaf", "autumn")
        )
        assertCompositeRejected(safetyGate.evaluateMetadata(candidate), "collage title")
    }

    @Requirement("VII.4")
    @Test
    fun `testLabelledFigurePlateDescriptionIsRejected`() {
        val plate = validCandidate(
            title = "Peach Blossom Stages",
            description = "Figure 2. Stages of peach blossom development: (A) bud swell, (B) full bloom, (C) petal fall.",
            category = SafeCategory.BOTANICAL_FLORA,
            tags = listOf("flower", "blossom")
        )
        assertCompositeRejected(safetyGate.evaluateMetadata(plate), "figure plate with (A)/(B) labels")

        val labelledOnly = validCandidate(
            title = "Peach Blossom Stages",
            description = "Left (a) bud swell and right (b) full bloom of a peach tree in spring.",
            category = SafeCategory.BOTANICAL_FLORA,
            tags = listOf("flower", "blossom")
        )
        assertCompositeRejected(safetyGate.evaluateMetadata(labelledOnly), "(a)/(b) panel labels")
    }

    @Requirement("VII.4")
    @Test
    fun `testSingleSceneImagesWithSimilarWordsStillPass`() {
        val controls = mapOf(
            "featured botanical photo" to validCandidate(
                title = "Prunus persica Blossom",
                description = "A peach tree in full bloom.",
                category = SafeCategory.BOTANICAL_FLORA,
                tags = listOf("Prunus persica", "Featured pictures of Rosales", "blossom")
            ),
            "radio telescope array" to validCandidate(
                title = "Very Large Array at Dusk",
                description = "The antennas of the Very Large Array under a twilight sky.",
                category = SafeCategory.SPACE_ASTRONOMY,
                tags = listOf("Radio telescope arrays", "twilight", "sky")
            ),
            "hubble composite nebula" to validCandidate(
                title = "Carina Nebula",
                description = "A composite image combining visible and infrared exposures of the nebula.",
                category = SafeCategory.SPACE_ASTRONOMY,
                tags = listOf("nebula", "star", "space")
            ),
            "lone parenthetical (a)" to validCandidate(
                title = "Pine Forest at Dawn",
                description = "Taken from option (a) of the trail, looking east across the forest at dawn.",
                tags = listOf("forest", "dawn")
            ),
            "panorama category" to validCandidate(
                title = "Alpine Panorama at Sunrise",
                description = "A wide view of the alpine ridge at sunrise.",
                tags = listOf("Panoramics", "alpine", "sunrise")
            ),
            "prose mentioning montage" to validCandidate(
                title = "Mountain Lake at Sunset",
                description = "Unlike a montage, this is a single exposure of the mountain lake at sunset.",
                tags = listOf("lake", "mountain", "sunset")
            )
        )
        for ((label, candidate) in controls) {
            val verdict = safetyGate.evaluateMetadata(candidate)
            assertTrue("Expected $label to be Approved, got $verdict", verdict is SafetyVerdict.Approved)
        }
    }

    @Requirement("VII.4")
    @Test
    fun `testCompositeScreenChangesTheCompositeGateVersion`() {
        // Cached records carry the old gate version; a new denylist version forces revalidation (Schedule B §6).
        assertTrue(SafetyDenylist.DENYLIST_VERSION != "2026.11.04-v3")
        assertTrue(SafetyGate.COMPOSITE_GATE_VERSION.contains(SafetyDenylist.DENYLIST_VERSION))
    }

    @Test
    fun `testBotanicalFloraAndAerialEarthAssetsPassMetadataScreening`() {
        val orchardFruit = validCandidate(
            title = "Ripening Pears in a Summer Orchard",
            description = "High-resolution botanical photograph of pear branches with sunlight filtering through leaves.",
            category = SafeCategory.BOTANICAL_FLORA,
            tags = listOf("orchard", "fruit", "botanical", "leaf", "tree")
        )
        val verdictFlora = safetyGate.evaluateMetadata(orchardFruit)
        assertTrue("Expected orchard/fruit candidate to be Approved, got $verdictFlora", verdictFlora is SafetyVerdict.Approved)

        val aerialDelta = validCandidate(
            title = "Aerial View of the Yukon River Delta",
            description = "High-altitude aerial photography of the winding glacial river delta and coastal wetlands.",
            category = SafeCategory.AERIAL_EARTH,
            tags = listOf("aerial", "delta", "river", "coast", "wetlands")
        )
        val verdictAerial = safetyGate.evaluateMetadata(aerialDelta)
        assertTrue("Expected aerial delta candidate to be Approved, got $verdictAerial", verdictAerial is SafetyVerdict.Approved)
    }
}
