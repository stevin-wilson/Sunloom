package com.sunloom.app.data.sources

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.SafetyVerdict
import com.sunloom.app.domain.safety.SafetyGate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceAdaptersTest {

    private class FakeHttpClient(private val cannedResponses: Map<String, String>) : HttpClient {
        override suspend fun getText(url: String): String {
            return cannedResponses.entries.firstOrNull { url.contains(it.key) }?.value
                ?: throw IllegalStateException("Unexpected URL: $url")
        }

        override suspend fun getBytes(url: String, maxBytes: Int): ByteArray {
            return ByteArray(16_384)
        }
    }

    @Requirement("VII.4")
    @Test
    fun `Wikimedia Nectarine Fruit Development montage parses but the safety gate rejects it while a single-scene botanical passes`() = runTest {
        val json = """
            {
              "query": {
                "pages": {
                  "3766525": {
                    "pageid": 3766525,
                    "title": "File:Nectarine_Fruit_Development.jpg",
                    "imageinfo": [
                      {
                        "width": 2440,
                        "height": 2480,
                        "url": "https://upload.wikimedia.org/wikipedia/commons/5/5e/Nectarine_Fruit_Development.jpg",
                        "descriptionurl": "https://commons.wikimedia.org/wiki/File:Nectarine_Fruit_Development.jpg",
                        "extmetadata": {
                          "Artist": { "value": "<a href=\"https://commons.wikimedia.org/wiki/User:Jjron\">jjron</a>" },
                          "LicenseShortName": { "value": "CC BY-SA 3.0" },
                          "UsageTerms": { "value": "Creative Commons Attribution-Share Alike 3.0" },
                          "ImageDescription": { "value": "Nectarine (<i>Prunus persica</i>) fruit development over a 7&#189; month period." },
                          "Categories": { "value": "Prunus persica|GFDL|License migration redundant|Self-published work|Featured pictures on Wikipedia, English|Featured pictures on Wikipedia, Turkish|Featured pictures on Wikipedia, Vietnamese|Montages of plants|3x2 arrays|Featured pictures of Rosales|Montages of food" }
                        }
                      }
                    ]
                  },
                  "4100200": {
                    "pageid": 4100200,
                    "title": "File:Peach_Blossom_in_Orchard.jpg",
                    "imageinfo": [
                      {
                        "width": 4000,
                        "height": 2600,
                        "url": "https://upload.wikimedia.org/wikipedia/commons/1/1a/Peach_Blossom_in_Orchard.jpg",
                        "descriptionurl": "https://commons.wikimedia.org/wiki/File:Peach_Blossom_in_Orchard.jpg",
                        "extmetadata": {
                          "Artist": { "value": "Flora Photographer" },
                          "LicenseShortName": { "value": "CC BY-SA 4.0" },
                          "ImageDescription": { "value": "Peach blossom in an orchard at sunrise." },
                          "Categories": { "value": "Prunus persica|Featured pictures of Rosales|Orchards" }
                        }
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val candidates = WikimediaFeaturedSourceAdapter(FakeHttpClient(emptyMap()))
            .parseResponse(json, SafeCategory.BOTANICAL_FLORA)
        val montage = candidates.single { it.id == "wiki-3766525" }
        val blossom = candidates.single { it.id == "wiki-4100200" }
        assertTrue(montage.rawCategoriesAndTags.containsAll(listOf("Montages of plants", "3x2 arrays", "Montages of food")))

        val gate = SafetyGate()
        val montageVerdict = gate.evaluateMetadata(montage)
        assertTrue("Expected montage Rejected, got $montageVerdict", montageVerdict is SafetyVerdict.Rejected)
        assertEquals("COMPOSITE_OR_MULTI_PANEL_LAYOUT", (montageVerdict as SafetyVerdict.Rejected).reasonCode)
        val blossomVerdict = gate.evaluateMetadata(blossom)
        assertTrue("Expected single-scene botanical Approved, got $blossomVerdict", blossomVerdict is SafetyVerdict.Approved)
    }

    @Test
    fun `WikimediaFeaturedSourceAdapter parses Featured Picture JSON and preserves creator and license provenance`() = runTest {
        val sampleWikiJson = """
            {
              "query": {
                "pages": {
                  "99881": {
                    "pageid": 99881,
                    "title": "File:Dolomites_Autumn_Sunrise.jpg",
                    "imageinfo": [
                      {
                        "width": 4200,
                        "height": 2800,
                        "url": "https://upload.wikimedia.org/wikipedia/commons/a/ab/Dolomites_Autumn_Sunrise.jpg",
                        "descriptionurl": "https://commons.wikimedia.org/wiki/File:Dolomites_Autumn_Sunrise.jpg",
                        "extmetadata": {
                          "Artist": { "value": "<a href=\"https://commons.wikimedia.org/wiki/User:Marco\">Marco Alpinist</a>" },
                          "LicenseShortName": { "value": "CC BY-SA 4.0" },
                          "ImageDescription": { "value": "Sunrise over the Dolomites mountain range in northern Italy." },
                          "Categories": { "value": "Featured pictures of landscapes|Dolomites|Mountains of Italy" }
                        }
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val adapter = WikimediaFeaturedSourceAdapter(FakeHttpClient(mapOf("commons.wikimedia.org" to sampleWikiJson)))
        val candidates = adapter.fetchCandidates(setOf(SafeCategory.LANDSCAPES_NATURE), limit = 4)

        assertEquals(1, candidates.size)
        val asset = candidates.first()
        assertEquals("Dolomites Autumn Sunrise", asset.title)
        assertEquals("Marco Alpinist", asset.provenance.creatorName)
        assertEquals("https://commons.wikimedia.org/wiki/User:Marco", asset.provenance.creatorPageUrl)
        assertEquals(LicenseType.CC_BY_SA, asset.provenance.licenseType)
        assertEquals("4.0", asset.provenance.licenseVersion)
        assertEquals(SafetyClassification.SAFE, asset.provenance.safetyClassification)
        assertEquals(4200, asset.provenance.originalWidth)
    }

    @Test
    fun `NasaLibrarySourceAdapter parses NASA public domain space imagery`() = runTest {
        val sampleNasaJson = """
            {
              "collection": {
                "items": [
                  {
                    "data": [
                      {
                        "nasa_id": "PIA25432",
                        "title": "Carina Nebula Cosmic Cliffs",
                        "description": "James Webb Space Telescope infrared view of star-forming region.",
                        "center": "GSFC",
                        "photographer": "NASA / ESA / CSA / STScI",
                        "keywords": ["Nebula", "Galaxy", "Webb"]
                      }
                    ],
                    "links": [
                      {
                        "href": "https://images-assets.nasa.gov/image/PIA25432/PIA25432~thumb.jpg",
                        "rel": "preview"
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val adapter = NasaLibrarySourceAdapter(FakeHttpClient(mapOf("images-api.nasa.gov" to sampleNasaJson)))
        val candidates = adapter.fetchCandidates(setOf(SafeCategory.SPACE_ASTRONOMY), limit = 4)

        assertEquals(1, candidates.size)
        val asset = candidates.first()
        assertEquals("Carina Nebula Cosmic Cliffs", asset.title)
        assertEquals(LicenseType.PUBLIC_DOMAIN, asset.provenance.licenseType)
        assertTrue(asset.downloadUrl.endsWith("~orig.jpg"))
        assertEquals(0, asset.provenance.originalWidth)
        assertEquals(0, asset.provenance.originalHeight)
    }

    @Test
    fun `ArtInstituteCc0SourceAdapter imports CC0 landscapes and filters out figurative sculpture items`() = runTest {
        val sampleArticJson = """
            {
              "data": [
                {
                  "id": 111,
                  "title": "Mount Sainte-Victoire",
                  "artist_title": "Paul Cézanne",
                  "image_id": "iiif-cezanne-111",
                  "is_public_domain": true,
                  "classification_title": "oil on canvas",
                  "subject_titles": ["landscapes", "mountains"],
                  "term_titles": ["Impressionism"]
                },
                {
                  "id": 222,
                  "title": "Classical Marble Sculpture",
                  "artist_title": "Ancient Sculptor",
                  "image_id": "iiif-sculpture-222",
                  "is_public_domain": true,
                  "classification_title": "sculpture",
                  "subject_titles": ["figures"],
                  "term_titles": ["Antiquity"]
                }
              ]
            }
        """.trimIndent()

        val adapter = ArtInstituteCc0SourceAdapter(FakeHttpClient(mapOf("api.artic.edu" to sampleArticJson)))
        val candidates = adapter.fetchCandidates(setOf(SafeCategory.SCENIC_FINE_ART), limit = 4)

        assertEquals(1, candidates.size)
        assertEquals("Mount Sainte-Victoire", candidates.first().title)
        assertEquals(LicenseType.CC0, candidates.first().provenance.licenseType)
    }

    @Test
    fun `MetMuseumCc0SourceAdapter parses public domain CC0 landscapes and rejects non-public-domain objects`() = runTest {
        val searchJson = """{"total": 2, "objectIDs": [436535, 999999]}"""
        val publicDomainObjJson = """
            {
              "objectID": 436535,
              "isPublicDomain": true,
              "title": "Wheat Field with Cypresses",
              "artistDisplayName": "Vincent van Gogh",
              "primaryImage": "https://images.metmuseum.org/CRDImages/ep/original/DT1567.jpg",
              "primaryImageSmall": "https://images.metmuseum.org/CRDImages/ep/web-large/DT1567.jpg",
              "department": "European Paintings",
              "classification": "Paintings",
              "medium": "Oil on canvas",
              "objectURL": "https://www.metmuseum.org/art/collection/search/436535",
              "tags": [{"term": "Landscapes"}, {"term": "Trees"}]
            }
        """.trimIndent()
        val restrictedObjJson = """
            {
              "objectID": 999999,
              "isPublicDomain": false,
              "title": "Restricted Modern Painting",
              "primaryImage": "https://images.metmuseum.org/CRDImages/modern.jpg"
            }
        """.trimIndent()

        val client = FakeHttpClient(
            mapOf(
                "collectionapi.metmuseum.org/public/collection/v1.1/search" to searchJson,
                "objects/436535" to publicDomainObjJson,
                "objects/999999" to restrictedObjJson
            )
        )
        val adapter = MetMuseumCc0SourceAdapter(client)
        val candidates = adapter.fetchCandidates(setOf(SafeCategory.SCENIC_FINE_ART), limit = 4)

        assertEquals(1, candidates.size)
        assertEquals("Wheat Field with Cypresses", candidates.first().title)
        assertEquals(LicenseType.CC0, candidates.first().provenance.licenseType)
        assertEquals("https://images.metmuseum.org/CRDImages/ep/original/DT1567.jpg", candidates.first().downloadUrl)
    }

    @Test
    fun `BundledSeedSourceAdapter provides offline CC0 wallpapers with honest provenance`() = runTest {
        val adapter = BundledSeedSourceAdapter()
        val allSeeds = adapter.fetchCandidates(SafeCategory.entries.toSet(), limit = 50, targetWidthPx = 2560)
        assertEquals(BundledSeedSourceAdapter.SEED_SPECS.size, allSeeds.size)
        assertTrue(allSeeds.size >= 6)
        assertTrue(allSeeds.all { it.provenance.licenseType == LicenseType.CC0 })
        assertTrue(allSeeds.all { it.provenance.originalPageUrl.isEmpty() })
        assertTrue(allSeeds.all { it.provenance.creatorName == "Generated on device by Sunloom" })
        assertTrue(allSeeds.all { it.provenance.originalWidth == 2560 && it.provenance.originalHeight == 1600 })
    }

    @Test
    fun `testZeroInjectedTagsOnWikimediaNasaArticAndMet`() {
        val wiki = WikimediaFeaturedSourceAdapter()
        val wikiItems = wiki.parseResponse(
            """
            {
              "query": {
                "pages": {
                  "1": {
                    "title": "File:Untagged_Test.jpg",
                    "imageinfo": [{
                      "width": 3840, "height": 2160,
                      "url": "https://upload.wikimedia.org/wikipedia/commons/1/11/Untagged_Test.jpg",
                      "descriptionurl": "https://commons.wikimedia.org/wiki/File:Untagged_Test.jpg",
                      "extmetadata": {
                        "Artist": { "value": "Jane Doe" },
                        "LicenseShortName": { "value": "CC0" },
                        "Categories": { "value": "" }
                      }
                    }]
                  }
                }
              }
            }
            """.trimIndent(),
            SafeCategory.LANDSCAPES_NATURE
        )
        assertEquals(1, wikiItems.size)
        assertTrue(wikiItems.first().rawCategoriesAndTags.isEmpty())

        val nasa = NasaLibrarySourceAdapter()
        val nasaItems = nasa.parseResponse(
            """
            {
              "collection": {
                "items": [{
                  "data": [{ "nasa_id": "N1", "title": "Untagged Space", "center": "JPL" }],
                  "links": [{ "href": "https://images-assets.nasa.gov/image/N1/N1~thumb.jpg" }]
                }]
              }
            }
            """.trimIndent()
        )
        assertEquals(1, nasaItems.size)
        assertTrue(nasaItems.first().rawCategoriesAndTags.isEmpty())

        val artic = ArtInstituteCc0SourceAdapter()
        val articItems = artic.parseResponse(
            """
            {
              "data": [{
                "id": 500,
                "title": "Untagged Canvas",
                "image_id": "iiif-500",
                "is_public_domain": true,
                "classification_title": "",
                "subject_titles": [],
                "term_titles": []
              }]
            }
            """.trimIndent()
        )
        assertEquals(1, articItems.size)
        assertTrue(articItems.first().rawCategoriesAndTags.isEmpty())
    }

    @Test
    fun `testWikimediaMachineLicenseAllowlistRejectsCc10AndRestrictionsAndUnknownAuthor`() {
        val wiki = WikimediaFeaturedSourceAdapter()

        // CC BY 1.0 must be rejected
        assertEquals(
            LicenseType.UNKNOWN_OR_RESTRICTED to "",
            wiki.mapLicenseAndVersion("cc-by-1.0", "CC BY 1.0", "Creative Commons Attribution 1.0")
        )
        // NonCommercial must be rejected
        assertEquals(
            LicenseType.UNKNOWN_OR_RESTRICTED to "",
            wiki.mapLicenseAndVersion("cc-by-nc-4.0", "CC BY-NC 4.0", "")
        )
        // CC BY 4.0 must be approved with version 4.0
        assertEquals(
            LicenseType.CC_BY to "4.0",
            wiki.mapLicenseAndVersion("cc-by-4.0", "CC BY 4.0", "")
        )

        // Restrictions field non-empty -> rejected by parseResponse
        val restrictedJson = """
            {
              "query": {
                "pages": {
                  "2": {
                    "title": "File:Trademarked_Building.jpg",
                    "imageinfo": [{
                      "width": 3840, "height": 2160,
                      "url": "https://upload.wikimedia.org/wikipedia/commons/2/22/Trademarked_Building.jpg",
                      "descriptionurl": "https://commons.wikimedia.org/wiki/File:Trademarked_Building.jpg",
                      "extmetadata": {
                        "Artist": { "value": "Jane Doe" },
                        "LicenseShortName": { "value": "CC BY-SA 4.0" },
                        "Restrictions": { "value": "trademarked" },
                        "Categories": { "value": "Architecture" }
                      }
                    }]
                  }
                }
              }
            }
        """.trimIndent()
        assertTrue(wiki.parseResponse(restrictedJson, SafeCategory.ARCHITECTURE_CITYSCAPES).isEmpty())

        // Unknown author on CC BY-SA 4.0 produces empty creatorName so L1 rejects it
        val unknownAuthorJson = """
            {
              "query": {
                "pages": {
                  "3": {
                    "title": "File:Anon_Mountain.jpg",
                    "imageinfo": [{
                      "width": 3840, "height": 2160,
                      "url": "https://upload.wikimedia.org/wikipedia/commons/3/33/Anon_Mountain.jpg",
                      "descriptionurl": "https://commons.wikimedia.org/wiki/File:Anon_Mountain.jpg",
                      "extmetadata": {
                        "Artist": { "value": "Unknown author" },
                        "LicenseShortName": { "value": "CC BY-SA 4.0" },
                        "Categories": { "value": "Mountains" }
                      }
                    }]
                  }
                }
              }
            }
        """.trimIndent()
        val parsedUnknown = wiki.parseResponse(unknownAuthorJson, SafeCategory.LANDSCAPES_NATURE)
        assertEquals(1, parsedUnknown.size)
        assertEquals("", parsedUnknown.first().provenance.creatorName)
    }

    @Test
    fun `testArticFailsClosedWhenIsPublicDomainMissingOrFalseAndRequests1686Rendition`() {
        val artic = ArtInstituteCc0SourceAdapter()
        val json = """
            {
              "data": [
                {
                  "id": 10,
                  "title": "Missing Public Domain Flag",
                  "image_id": "iiif-10",
                  "classification_title": "painting",
                  "subject_titles": ["landscapes"]
                },
                {
                  "id": 11,
                  "title": "False Public Domain Flag",
                  "image_id": "iiif-11",
                  "is_public_domain": false,
                  "classification_title": "painting",
                  "subject_titles": ["landscapes"]
                },
                {
                  "id": 12,
                  "title": "Verified Public Domain Landscape",
                  "image_id": "iiif-12",
                  "is_public_domain": true,
                  "classification_title": "painting",
                  "subject_titles": ["landscapes"]
                }
              ]
            }
        """.trimIndent()
        val results = artic.parseResponse(json)
        assertEquals(1, results.size)
        assertEquals("artic-12", results.first().id)
        assertEquals("https://www.artic.edu/iiif/2/iiif-12/full/1686,/0/default.jpg", results.first().downloadUrl)
        assertEquals(0, results.first().provenance.originalWidth)
    }

    @Test
    fun `testMetMuseumPrefersPrimaryImageAndEnforcesDepartmentAllowlist`() {
        val met = MetMuseumCc0SourceAdapter()
        val armsAndArmorJson = """
            {
              "objectID": 777,
              "isPublicDomain": true,
              "title": "Ceremonial Sword",
              "department": "Arms and Armor",
              "classification": "Swords",
              "primaryImage": "https://images.metmuseum.org/CRDImages/aa/original/sword.jpg"
            }
        """.trimIndent()
        assertNull(met.parseObjectResponse(armsAndArmorJson))

        val validLandscapeJson = """
            {
              "objectID": 888,
              "isPublicDomain": true,
              "title": "Autumn River Valley",
              "department": "Asian Art",
              "classification": "Paintings",
              "primaryImage": "https://images.metmuseum.org/CRDImages/as/original/river_full.jpg",
              "primaryImageSmall": "https://images.metmuseum.org/CRDImages/as/web-large/river_small.jpg",
              "tags": [{"term": "Rivers"}, {"term": "Mountains"}]
            }
        """.trimIndent()
        val parsed = met.parseObjectResponse(validLandscapeJson)
        assertNotNull(parsed)
        assertEquals("https://images.metmuseum.org/CRDImages/as/original/river_full.jpg", parsed!!.downloadUrl)
    }

    @Test
    fun `testNasaRejectsHqCenterAndThirdPartyCreditsAndSelectsOrigFromManifest`() {
        val nasa = NasaLibrarySourceAdapter()
        val json = """
            {
              "collection": {
                "items": [
                  {
                    "data": [{
                      "nasa_id": "HQ-2025-01",
                      "title": "Press Briefing at Headquarters",
                      "center": "HQ",
                      "keywords": ["space"]
                    }],
                    "links": [{ "href": "https://images-assets.nasa.gov/image/HQ1/HQ1~thumb.jpg" }]
                  },
                  {
                    "data": [{
                      "nasa_id": "THIRD-PARTY-02",
                      "title": "Night Sky Over Observatory",
                      "center": "GSFC",
                      "description": "Image credit: Copyright John Smith / Getty Images",
                      "keywords": ["astronomy"]
                    }],
                    "links": [{ "href": "https://images-assets.nasa.gov/image/TP2/TP2~thumb.jpg" }]
                  },
                  {
                    "data": [{
                      "nasa_id": "VALID-JPL-03",
                      "title": "Europa Horizon from Juno",
                      "center": "JPL",
                      "description": "Courtesy of NASA/JPL-Caltech",
                      "keywords": ["Jupiter", "Europa", "Space"]
                    }],
                    "links": [{ "href": "https://images-assets.nasa.gov/image/VALID-JPL-03/VALID-JPL-03~thumb.jpg" }]
                  }
                ]
              }
            }
        """.trimIndent()

        val items = nasa.parseResponse(json)
        assertEquals(1, items.size)
        assertEquals("nasa-VALID-JPL-03", items.first().id)

        val manifestJson = """
            [
              "https://images-assets.nasa.gov/image/PIA25432/PIA25432~orig.tif",
              "https://images-assets.nasa.gov/image/PIA25432/PIA25432~orig.jpg",
              "https://images-assets.nasa.gov/image/PIA25432/PIA25432~large.jpg",
              "https://images-assets.nasa.gov/image/PIA25432/PIA25432~thumb.jpg"
            ]
        """.trimIndent()
        assertEquals(
            "https://images-assets.nasa.gov/image/PIA25432/PIA25432~orig.jpg",
            nasa.selectBestRenditionFromManifest(manifestJson)
        )
    }

    @Test
    fun `testRedirectValidatorRejectsCrossDomainRedirect`() {
        // Admitted hosts succeed
        DefaultHttpsClient.validateHttpsAndHost("https://commons.wikimedia.org/w/api.php", "wikimedia_featured")
        DefaultHttpsClient.validateHttpsAndHost("https://upload.wikimedia.org/wikipedia/commons/a/ab/test.jpg", "wikimedia_featured")

        // Unadmitted host or cleartext HTTP fails closed
        var rejectedHost = false
        try {
            DefaultHttpsClient.validateHttpsAndHost("https://evil.example.com/payload.jpg", "wikimedia_featured")
        } catch (_: IllegalArgumentException) {
            rejectedHost = true
        }
        assertTrue(rejectedHost)

        var rejectedHttp = false
        try {
            DefaultHttpsClient.validateHttpsAndHost("http://upload.wikimedia.org/wikipedia/commons/a/ab/test.jpg", "wikimedia_featured")
        } catch (_: IllegalArgumentException) {
            rejectedHttp = true
        }
        assertTrue(rejectedHttp)
    }

    @Test
    fun `ClevelandMuseumCc0SourceAdapter parses CC0 print JPEG, sets web fallback, skips tif, and blocks non-CC0 or sculpture`() = runTest {
        assertTrue("cma_cc0 must be admitted in SourceRegistry", SourceRegistry.isSourceAdmitted("cma_cc0"))
        val sampleCmaJson = """
            {
              "info": { "total": 4 },
              "data": [
                {
                  "id": 93014,
                  "accession_number": "1335.1917",
                  "share_license_status": "CC0",
                  "title": "View of Schroon Mountain, Essex County, New York, After a Storm",
                  "creators": [{ "description": "Thomas Cole (American, 1801-1848)" }],
                  "culture": ["America, 19th century"],
                  "technique": "oil on canvas",
                  "department": "American Painting and Sculpture",
                  "collection": "American - Painting",
                  "type": "Painting",
                  "url": "https://www.clevelandart.org/art/1335.1917",
                  "images": {
                    "web": { "url": "https://openaccess-cdn.clevelandart.org/1335.1917/1335.1917_web.jpg", "width": "800", "height": "517" },
                    "print": { "url": "https://openaccess-cdn.clevelandart.org/1335.1917/1335.1917_print.jpg", "width": "3000", "height": "1938" },
                    "full": { "url": "https://openaccess-cdn.clevelandart.org/1335.1917/1335.1917_full.tif", "width": "7524", "height": "4862" }
                  }
                },
                {
                  "id": 93015,
                  "share_license_status": "Copyrighted",
                  "title": "Restricted Landscape",
                  "type": "Painting",
                  "images": { "print": { "url": "https://openaccess-cdn.clevelandart.org/2/2_print.jpg" } }
                },
                {
                  "id": 93016,
                  "share_license_status": "CC0",
                  "title": "Bronze Bust in Garden",
                  "type": "Sculpture",
                  "images": { "print": { "url": "https://openaccess-cdn.clevelandart.org/3/3_print.jpg" } }
                }
              ]
            }
        """.trimIndent()

        val adapter = ClevelandMuseumCc0SourceAdapter(
            FakeHttpClient(mapOf("openaccess-api.clevelandart.org" to sampleCmaJson))
        )
        val candidates = adapter.fetchCandidates(setOf(SafeCategory.SCENIC_FINE_ART), limit = 4)
        assertEquals(1, candidates.size)
        val cole = candidates.first()
        assertEquals("cma-93014", cole.id)
        assertEquals(LicenseType.CC0, cole.provenance.licenseType)
        assertEquals("https://openaccess-cdn.clevelandart.org/1335.1917/1335.1917_print.jpg", cole.downloadUrl)
        assertEquals(listOf("https://openaccess-cdn.clevelandart.org/1335.1917/1335.1917_web.jpg"), cole.fallbackDownloadUrls)
        assertFalse("Must not leak department word 'Sculpture' into painting tags", cole.rawCategoriesAndTags.any { it.contains("sculpture", ignoreCase = true) })
        // Verify Thomas Cole passes full L1+L2 SafetyGate evaluation without false rejection
        val gateVerdict = com.sunloom.app.domain.safety.SafetyGate().evaluateMetadata(cole)
        assertTrue("Expected Thomas Cole CMA landscape to pass SafetyGate, got $gateVerdict", gateVerdict is com.sunloom.app.domain.model.SafetyVerdict.Approved)
    }

    @Test
    fun `SmkOpenSourceAdapter parses English titles, PDM 1_0 and CC0 rights, builds bounded IIIF URLs, and rejects non-PD`() = runTest {
        assertTrue("smk_open must be admitted in SourceRegistry", SourceRegistry.isSourceAdmitted("smk_open"))
        val sampleSmkJson = """
            {
              "offset": 0,
              "rows": 3,
              "items": [
                {
                  "id": "123456",
                  "object_number": "KMS1234",
                  "public_domain": true,
                  "rights": "https://creativecommons.org/publicdomain/mark/1.0/",
                  "titles": [
                    { "title": "Et kystlandskab", "language": "da" },
                    { "title": "A Coastal Landscape at Sunset", "language": "en" }
                  ],
                  "production": [{ "creator": "Christen Købke" }],
                  "object_names": [{ "name": "painting", "language": "en" }],
                  "motif": ["Landscape", "Coast", "Sunset"],
                  "image_iiif_id": "https://iip.smk.dk/iiif/jp2/kms1234.tif.jp2",
                  "frontend_url": "https://open.smk.dk/en/artwork/image/kms1234"
                },
                {
                  "id": "999999",
                  "object_number": "KMS9999",
                  "public_domain": false,
                  "rights": "https://www.smk.dk/en/use-of-images/",
                  "titles": [{ "title": "Copyrighted Sea", "language": "en" }],
                  "object_names": [{ "name": "painting", "language": "en" }],
                  "image_iiif_id": "https://iip.smk.dk/iiif/jp2/kms9999.tif.jp2"
                }
              ]
            }
        """.trimIndent()

        val adapter = SmkOpenSourceAdapter(FakeHttpClient(mapOf("api.smk.dk" to sampleSmkJson)))
        val candidates = adapter.fetchCandidates(setOf(SafeCategory.SCENIC_FINE_ART), limit = 4)
        assertEquals(1, candidates.size)
        val kobke = candidates.first()
        assertEquals("A Coastal Landscape at Sunset", kobke.title)
        assertEquals(LicenseType.PUBLIC_DOMAIN, kobke.provenance.licenseType)
        assertEquals("https://iip.smk.dk/iiif/jp2/kms1234.tif.jp2/full/!2560,2560/0/default.jpg", kobke.downloadUrl)
        assertTrue(kobke.fallbackDownloadUrls.contains("https://iip.smk.dk/iiif/jp2/kms1234.tif.jp2/full/!1686,1686/0/default.jpg"))
        val gateVerdict = com.sunloom.app.domain.safety.SafetyGate().evaluateMetadata(kobke)
        assertTrue("Expected SMK coastal landscape to pass SafetyGate, got $gateVerdict", gateVerdict is com.sunloom.app.domain.model.SafetyVerdict.Approved)
    }

    @Test
    fun `ArtInstituteCc0SourceAdapter declares 843px IIIF fallback URL and BudgetedSourceHttpClient enforces cap and spacing`() = runTest {
        val artic = ArtInstituteCc0SourceAdapter()
        val parsed = artic.parseResponse(
            """
            {
              "data": [{
                "id": 77,
                "title": "Alpine Lake Valley",
                "image_id": "iiif-77",
                "is_public_domain": true,
                "classification_title": "painting",
                "subject_titles": ["landscapes"]
              }]
            }
            """.trimIndent()
        )
        assertEquals(1, parsed.size)
        assertEquals(
            listOf("https://www.artic.edu/iiif/2/iiif-77/full/843,/0/default.jpg"),
            parsed.first().fallbackDownloadUrls
        )

        var fakeNow = 1_000L
        val sleeps = ArrayList<Long>()
        val budgeted = BudgetedSourceHttpClient(
            delegate = FakeHttpClient(mapOf("api.artic.edu" to "{}")),
            sourceId = "artic_cc0",
            maxRequests = 2,
            minSpacingMs = 200L,
            clockMs = { fakeNow },
            sleeper = { ms ->
                sleeps.add(ms)
                fakeNow += ms
            }
        )
        budgeted.getText("https://api.artic.edu/api/v1/artworks/search")
        fakeNow += 50L
        budgeted.getBytes("https://www.artic.edu/iiif/2/iiif-77/full/1686,/0/default.jpg")
        assertEquals(2, budgeted.requestCount)
        assertEquals(listOf(150L), sleeps)

        var budgetExceeded = false
        try {
            budgeted.getBytes("https://www.artic.edu/iiif/2/iiif-77/full/843,/0/default.jpg")
        } catch (_: SourceBudgetExceededException) {
            budgetExceeded = true
        }
        assertTrue("Expected 3rd request to throw SourceBudgetExceededException", budgetExceeded)
    }

    @Test
    fun `CmaAndSmkAdaptersEnforceStrictLicenseEdgeCasesZeroSyntheticTagsAndConditionalEtagCaching`() = runTest {
        val cma = ClevelandMuseumCc0SourceAdapter()
        // Conflicting copyright notice must fail closed even if share_license_status is CC0
        val conflictingCma = cma.parseResponse(
            """
            {
              "data": [{
                "id": 501,
                "share_license_status": "CC0",
                "copyright": "© 2024 Artist Estate, All Rights Reserved",
                "title": "Mountain Ridge",
                "type": "Painting",
                "images": { "print": { "url": "https://openaccess-cdn.clevelandart.org/501/501_print.jpg" } }
              }]
            }
            """.trimIndent()
        )
        assertTrue(conflictingCma.isEmpty())

        // SMK license parser edge cases & bare IIIF identifier support
        val smk = SmkOpenSourceAdapter()
        assertEquals(LicenseType.CC0 to "1.0", smk.mapRightsToLicense(true, "https://creativecommons.org/publicdomain/zero/1.0/"))
        assertEquals(LicenseType.PUBLIC_DOMAIN to "1.0", smk.mapRightsToLicense(true, "https://www.smk.dk/en/article/smk-open/"))
        assertEquals(LicenseType.UNKNOWN_OR_RESTRICTED to "", smk.mapRightsToLicense(true, "https://creativecommons.org/licenses/by-nc/4.0/"))
        assertEquals(LicenseType.UNKNOWN_OR_RESTRICTED to "", smk.mapRightsToLicense(false, "https://creativecommons.org/publicdomain/mark/1.0/"))

        val bareIiifParsed = smk.parseResponse(
            """
            {
              "items": [{
                "id": "777",
                "public_domain": true,
                "rights": "",
                "titles": [{ "title": "Nordkysten i solnedgang", "language": "da" }],
                "object_names": ["maleri"],
                "image_iiif_id": "kms777.tif.jp2"
              }]
            }
            """.trimIndent()
        )
        assertEquals(1, bareIiifParsed.size)
        assertEquals("https://iip.smk.dk/iiif/jp2/kms777.tif.jp2/full/!2560,2560/0/default.jpg", bareIiifParsed.first().downloadUrl)
        assertEquals(listOf("maleri"), bareIiifParsed.first().rawCategoriesAndTags)

        // DefaultHttpsClient ETag -> If-None-Match -> 304 Not Modified conditional caching
        var callIndex = 0
        var seenIfNoneMatch: String? = null
        val httpsClient = DefaultHttpsClient { url ->
            callIndex++
            object : java.net.HttpURLConnection(url) {
                private val reqProps = HashMap<String, String>()
                override fun setRequestProperty(key: String, value: String) {
                    reqProps[key] = value
                }
                override fun getResponseCode(): Int {
                    if (callIndex == 2) {
                        seenIfNoneMatch = reqProps["If-None-Match"]
                        return HTTP_NOT_MODIFIED
                    }
                    return HTTP_OK
                }
                override fun getHeaderField(name: String): String? {
                    return if (callIndex == 1 && name.equals("ETag", ignoreCase = true)) "\"etag-v1\"" else null
                }
                override fun getContentLengthLong(): Long = 16L
                override fun getInputStream(): java.io.InputStream =
                    "{\"cached\":true}".byteInputStream(Charsets.UTF_8)
                override fun disconnect() {}
                override fun usingProxy(): Boolean = false
                override fun connect() {}
            }
        }
        val firstBody = httpsClient.getText("https://api.smk.dk/api/v1/art/search/?keys=landscape")
        val secondBody = httpsClient.getText("https://api.smk.dk/api/v1/art/search/?keys=landscape")
        assertEquals("{\"cached\":true}", firstBody)
        assertEquals("{\"cached\":true}", secondBody)
        assertEquals("\"etag-v1\"", seenIfNoneMatch)
    }

    @Test
    fun `All online SourceAdapters rethrow CancellationException and leave HTTP budget headroom for downloads`() = runTest {
        val cancellingClient = object : HttpClient {
            override suspend fun getText(url: String): String {
                throw kotlinx.coroutines.CancellationException("Job was cancelled")
            }
            override suspend fun getBytes(url: String, maxBytes: Int): ByteArray {
                throw kotlinx.coroutines.CancellationException("Job was cancelled")
            }
        }

        val adapters: List<WallpaperSourceAdapter> = listOf(
            WikimediaFeaturedSourceAdapter(cancellingClient),
            NasaLibrarySourceAdapter(cancellingClient),
            ArtInstituteCc0SourceAdapter(cancellingClient),
            MetMuseumCc0SourceAdapter(cancellingClient),
            ClevelandMuseumCc0SourceAdapter(cancellingClient),
            SmkOpenSourceAdapter(cancellingClient)
        )
        for (adapter in adapters) {
            var rethrown = false
            try {
                adapter.fetchCandidates(SafeCategory.entries.toSet(), limit = 4)
            } catch (_: kotlinx.coroutines.CancellationException) {
                rethrown = true
            }
            assertTrue("${adapter.sourceId} must rethrow CancellationException instead of swallowing it", rethrown)
        }

        // Verify WikimediaFeaturedSourceAdapter with all 5 categories enabled leaves >= 4 requests of download headroom out of 6
        val wikiBudgeted = BudgetedSourceHttpClient(
            delegate = FakeHttpClient(mapOf("commons.wikimedia.org" to """{"query":{"pages":{}}}""")),
            sourceId = "wikimedia_featured",
            maxRequests = SourceRegistry.get("wikimedia_featured")!!.maxRequestsPerSync,
            minSpacingMs = 0L
        )
        WikimediaFeaturedSourceAdapter(wikiBudgeted).fetchCandidates(SafeCategory.entries.toSet(), limit = 8, offset = 0)
        assertTrue(
            "Wikimedia metadata queries should leave >= 4 requests for image downloads, used ${wikiBudgeted.requestCount}",
            wikiBudgeted.requestCount <= 2
        )

        // Verify MetMuseumCc0SourceAdapter leaves >= 3 requests of download headroom out of 7
        val metBudgeted = BudgetedSourceHttpClient(
            delegate = FakeHttpClient(
                mapOf(
                    "collection/v1.1/search" to """{"total":10,"objectIDs":[1,2,3,4,5,6,7,8,9,10]}""",
                    "collection/v1/objects/" to """{"objectID":1,"isPublicDomain":false}"""
                )
            ),
            sourceId = "met_museum_cc0",
            maxRequests = SourceRegistry.get("met_museum_cc0")!!.maxRequestsPerSync,
            minSpacingMs = 0L
        )
        MetMuseumCc0SourceAdapter(metBudgeted).fetchCandidates(SafeCategory.entries.toSet(), limit = 8, offset = 0)
        assertTrue(
            "Met metadata lookups should leave >= 3 requests for image downloads, used ${metBudgeted.requestCount}",
            metBudgeted.requestCount <= 4
        )
    }

    @Test
    fun `SourceAdapterUtils fetchTextOrNull and raster URL helpers handle budget, rate-limit, cancellation, and host rules`() = runTest {
        var budgetFlag = false
        val budgetClient = object : HttpClient {
            override suspend fun getText(url: String): String =
                throw SourceBudgetExceededException("cma_cc0", 3)
            override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = ByteArray(0)
        }
        val budgetRes = budgetClient.fetchTextOrNull("https://openaccess-api.clevelandart.org/api/artworks/") {
            budgetFlag = true
        }
        assertNull(budgetRes)
        assertTrue(budgetFlag)

        val rateLimitClient = object : HttpClient {
            override suspend fun getText(url: String): String =
                throw RateLimitException(120L, "HTTP 429")
            override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = ByteArray(0)
        }
        var caughtRateLimit = false
        try {
            rateLimitClient.fetchTextOrNull("https://openaccess-api.clevelandart.org/api/artworks/")
        } catch (_: RateLimitException) {
            caughtRateLimit = true
        }
        assertTrue(caughtRateLimit)

        assertEquals(
            "openaccess-cdn.clevelandart.org",
            SourceAdapterUtils.extractHost("https://openaccess-cdn.clevelandart.org/1335.1917/1335.1917_print.jpg")
        )
        assertTrue(
            SourceAdapterUtils.isSupportedHttpsRasterUrl(
                "https://openaccess-cdn.clevelandart.org/1335.1917/1335.1917_print.jpg?ver=1",
                "cma_cc0"
            )
        )
        assertFalse(
            SourceAdapterUtils.isSupportedHttpsRasterUrl(
                "https://openaccess-cdn.clevelandart.org/1335.1917/1335.1917_full.tif",
                "cma_cc0"
            )
        )
        assertFalse(
            SourceAdapterUtils.isSupportedHttpsRasterUrl(
                "https://evil.example.com/1335.1917_print.jpg",
                "cma_cc0"
            )
        )
    }

    @Test
    fun `SourceAdapters scale rendition URLs and Seeds to UWQHD 4K 32_9 and Portrait displays without distortion`() = runTest {
        // 1. SMK scales IIIF bound up to targetWidthPx (e.g. 3440 / 3840) while keeping !2560,2560 and !1686,1686 fallbacks
        val smk = SmkOpenSourceAdapter()
        val smkJson = """
            {
              "items": [{
                "id": "123",
                "object_number": "KMS123",
                "public_domain": true,
                "rights": "https://creativecommons.org/publicdomain/mark/1.0/",
                "titles": [{ "title": "Fjord Morning", "language": "en" }],
                "object_names": [{ "name": "painting", "language": "en" }],
                "image_iiif_id": "https://iip.smk.dk/iiif/jp2/kms123.tif.jp2"
              }]
            }
        """.trimIndent()
        val uwqhdSmk = smk.parseResponse(smkJson, SafeCategory.SCENIC_FINE_ART, targetWidthPx = 3440).first()
        assertEquals("https://iip.smk.dk/iiif/jp2/kms123.tif.jp2/full/!3440,3440/0/default.jpg", uwqhdSmk.downloadUrl)
        assertTrue(uwqhdSmk.fallbackDownloadUrls.contains("https://iip.smk.dk/iiif/jp2/kms123.tif.jp2/full/!2560,2560/0/default.jpg"))

        // 2. CMA prefers bounded full JPEG when targetWidthPx > 3000 (4K / UWQHD) while keeping print and web fallbacks
        val cma = ClevelandMuseumCc0SourceAdapter()
        val cmaJson = """
            {
              "data": [{
                "id": 9901,
                "share_license_status": "CC0",
                "title": "Panoramic Harbor",
                "type": "Painting",
                "images": {
                  "web": { "url": "https://openaccess-cdn.clevelandart.org/9901/9901_web.jpg", "width": "800" },
                  "print": { "url": "https://openaccess-cdn.clevelandart.org/9901/9901_print.jpg", "width": "3000" },
                  "full": { "url": "https://openaccess-cdn.clevelandart.org/9901/9901_full.jpg", "width": "4800" }
                }
              }]
            }
        """.trimIndent()
        val uhdCma = cma.parseResponse(cmaJson, SafeCategory.SCENIC_FINE_ART, targetWidthPx = 3840).first()
        assertEquals("https://openaccess-cdn.clevelandart.org/9901/9901_full.jpg", uhdCma.downloadUrl)
        assertEquals(
            listOf(
                "https://openaccess-cdn.clevelandart.org/9901/9901_print.jpg",
                "https://openaccess-cdn.clevelandart.org/9901/9901_web.jpg"
            ),
            uhdCma.fallbackDownloadUrls
        )

        // 3. Wikimedia promotes bounded fullUrl when thumbwidth is below targetWidthPx and includes thumbUrl fallback
        val wiki = WikimediaFeaturedSourceAdapter()
        val wikiJson = """
            {
              "query": {
                "pages": {
                  "42": {
                    "title": "File:Glacier_Panorama.jpg",
                    "imageinfo": [{
                      "width": 5400,
                      "height": 2400,
                      "thumbwidth": 2560,
                      "thumbheight": 1138,
                      "thumburl": "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Glacier_Panorama.jpg/2560px-Glacier_Panorama.jpg",
                      "url": "https://upload.wikimedia.org/wikipedia/commons/a/ab/Glacier_Panorama.jpg",
                      "descriptionurl": "https://commons.wikimedia.org/wiki/File:Glacier_Panorama.jpg",
                      "extmetadata": {
                        "Artist": { "value": "Jane Doe" },
                        "License": { "value": "cc-by-sa-4.0" },
                        "LicenseShortName": { "value": "CC BY-SA 4.0" },
                        "Categories": { "value": "Glaciers|Featured pictures" }
                      }
                    }]
                  }
                }
              }
            }
        """.trimIndent()
        val uwqhdWiki = wiki.parseResponse(wikiJson, SafeCategory.LANDSCAPES_NATURE, targetWidthPx = 3840).first()
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/a/ab/Glacier_Panorama.jpg", uwqhdWiki.downloadUrl)
        assertTrue(uwqhdWiki.fallbackDownloadUrls.isNotEmpty())
    }

    @Test
    fun `DefaultHttpsClient parseRetryAfterSeconds supports both integer delta-seconds and RFC-1123 HTTP-date`() {
        val nowMs = 1_762_280_000_000L
        assertEquals(120L, DefaultHttpsClient.parseRetryAfterSeconds("120", nowEpochMs = nowMs))
        val futureDate = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
            java.time.Instant.ofEpochMilli(nowMs + 300_000L).atZone(java.time.ZoneOffset.UTC)
        )
        assertEquals(300L, DefaultHttpsClient.parseRetryAfterSeconds(futureDate, nowEpochMs = nowMs))
        assertEquals(60L, DefaultHttpsClient.parseRetryAfterSeconds("invalid-header", nowEpochMs = nowMs))
    }

    @Test
    fun `Wikimedia and NASA adapters support newly adopted BOTANICAL_FLORA and AERIAL_EARTH categories`() = runTest {
        val sampleWikiFloraJson = """
            {
              "query": {
                "pages": {
                  "8811": {
                    "pageid": 8811,
                    "title": "File:Apple Orchard in Spring Blossom.jpg",
                    "imageinfo": [{
                      "url": "https://upload.wikimedia.org/wikipedia/commons/apple_orchard.jpg",
                      "thumburl": "https://upload.wikimedia.org/wikipedia/commons/thumb/apple_orchard_2560.jpg",
                      "thumbwidth": 2560,
                      "thumbheight": 1600,
                      "width": 5000,
                      "height": 3125,
                      "extmetadata": {
                        "Artist": { "value": "Linnaeus Botanist" },
                        "License": { "value": "cc-by-sa-4.0" },
                        "LicenseShortName": { "value": "CC BY-SA 4.0" },
                        "Categories": { "value": "Apple orchards|Flora|Featured pictures" }
                      }
                    }]
                  }
                }
              }
            }
        """.trimIndent()

        val wiki = WikimediaFeaturedSourceAdapter(FakeHttpClient(mapOf("commons.wikimedia.org" to sampleWikiFloraJson)))
        val floraCandidates = wiki.fetchCandidates(setOf(SafeCategory.BOTANICAL_FLORA), limit = 4)
        assertEquals(1, floraCandidates.size)
        assertEquals("Apple Orchard in Spring Blossom", floraCandidates.first().title)
        assertEquals(SafeCategory.BOTANICAL_FLORA, floraCandidates.first().category)

        val sampleNasaEarthJson = """
            {
              "collection": {
                "items": [{
                  "href": "https://images-assets.nasa.gov/image/iss068e012345/collection.json",
                  "data": [{
                    "nasa_id": "iss068e012345",
                    "title": "Nile River Delta from Orbit",
                    "description": "Astronaut photograph of the Nile River delta and Mediterranean coastline taken from the ISS.",
                    "center": "JSC",
                    "keywords": ["Earth", "Delta", "Coastline", "Orbital"]
                  }],
                  "links": [{ "href": "https://images-assets.nasa.gov/image/iss068e012345/iss068e012345~thumb.jpg" }]
                }]
              }
            }
        """.trimIndent()

        val nasa = NasaLibrarySourceAdapter(FakeHttpClient(mapOf("images-api.nasa.gov" to sampleNasaEarthJson)))
        val earthCandidates = nasa.fetchCandidates(setOf(SafeCategory.AERIAL_EARTH), limit = 4)
        assertEquals(1, earthCandidates.size)
        assertEquals("Nile River Delta from Orbit", earthCandidates.first().title)
        assertEquals(SafeCategory.AERIAL_EARTH, earthCandidates.first().category)
    }
}
