package com.sunloom.app.data.sources

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.SafeCategory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Article X §4: candidates that advertise a size too small, or a shape a display crop would waste, are skipped
 * before downloading. That only works if the adapters pass on the size their catalog states for the rendition
 * they will actually download; Cleveland and SMK state it, so they must.
 */
@Requirement("X.4")
class AdvertisedSizeTest {

    private class FakeHttp(private val body: String) : HttpClient {
        override suspend fun getText(url: String): String = body
        override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = ByteArray(16)
    }

    private fun cmaJson(images: String) = """
        { "info": { "total": 1 }, "data": [ {
            "id": 1, "accession_number": "1.1", "share_license_status": "CC0", "title": "A Lake",
            "creators": [{ "description": "Someone (American)" }], "technique": "oil on canvas",
            "department": "Painting", "type": "Painting", "url": "https://www.clevelandart.org/art/1.1",
            "images": $images
        } ] }
    """.trimIndent()

    private suspend fun cmaCandidate(images: String, targetWidthPx: Int) =
        ClevelandMuseumCc0SourceAdapter(FakeHttp(cmaJson(images)))
            .fetchCandidates(setOf(SafeCategory.SCENIC_FINE_ART), limit = 4, targetWidthPx = targetWidthPx)
            .single()

    @Test
    fun `Cleveland advertises the size of the print rendition it will download`() = runTest {
        val c = cmaCandidate(
            """{
              "web": { "url": "https://openaccess-cdn.clevelandart.org/1/1_web.jpg", "width": "800", "height": "517" },
              "print": { "url": "https://openaccess-cdn.clevelandart.org/1/1_print.jpg", "width": "3000", "height": "1938" },
              "full": { "url": "https://openaccess-cdn.clevelandart.org/1/1_full.tif", "width": "7524", "height": "4862" }
            }""",
            targetWidthPx = 2560
        )
        assertEquals("https://openaccess-cdn.clevelandart.org/1/1_print.jpg", c.downloadUrl)
        assertEquals(3000, c.provenance.originalWidth)
        assertEquals(1938, c.provenance.originalHeight)
    }

    @Test
    fun `Cleveland advertises the full rendition when it is a JPEG and the display is large`() = runTest {
        val c = cmaCandidate(
            """{
              "web": { "url": "https://openaccess-cdn.clevelandart.org/2/2_web.jpg", "width": "800", "height": "517" },
              "print": { "url": "https://openaccess-cdn.clevelandart.org/2/2_print.jpg", "width": "3000", "height": "1938" },
              "full": { "url": "https://openaccess-cdn.clevelandart.org/2/2_full.jpg", "width": "4800", "height": "3100" }
            }""",
            targetWidthPx = 3440
        )
        assertEquals("https://openaccess-cdn.clevelandart.org/2/2_full.jpg", c.downloadUrl)
        assertEquals(4800, c.provenance.originalWidth)
        assertEquals(3100, c.provenance.originalHeight)
    }

    @Test
    fun `Cleveland leaves the size unknown when the catalog gives no height`() = runTest {
        val c = cmaCandidate(
            """{ "print": { "url": "https://openaccess-cdn.clevelandart.org/3/3_print.jpg", "width": "3000" } }""",
            targetWidthPx = 2560
        )
        assertEquals(0, c.provenance.originalWidth)
        assertEquals(0, c.provenance.originalHeight)
    }

    @Test
    fun `SMK advertises the size of the original image`() = runTest {
        val json = """
            { "offset": 0, "rows": 1, "items": [ {
                "id": "1", "object_number": "KMS1", "public_domain": true,
                "rights": "https://creativecommons.org/publicdomain/mark/1.0/",
                "titles": [{ "title": "A Coastal Landscape", "language": "en" }],
                "production": [{ "creator": "Someone" }],
                "object_names": [{ "name": "painting", "language": "en" }],
                "image_iiif_id": "https://iip.smk.dk/iiif/jp2/kms1.tif.jp2",
                "image_width": 5400, "image_height": 3200
            } ] }
        """.trimIndent()
        val c = SmkOpenSourceAdapter(FakeHttp(json))
            .fetchCandidates(setOf(SafeCategory.SCENIC_FINE_ART), limit = 4).single()
        assertEquals(5400, c.provenance.originalWidth)
        assertEquals(3200, c.provenance.originalHeight)
    }

    @Test
    fun `SMK leaves the size unknown when the catalog gives none`() = runTest {
        val json = """
            { "offset": 0, "rows": 1, "items": [ {
                "id": "2", "object_number": "KMS2", "public_domain": true,
                "rights": "https://creativecommons.org/publicdomain/mark/1.0/",
                "titles": [{ "title": "A Coastal Landscape", "language": "en" }],
                "production": [{ "creator": "Someone" }],
                "object_names": [{ "name": "painting", "language": "en" }],
                "image_iiif_id": "https://iip.smk.dk/iiif/jp2/kms2.tif.jp2"
            } ] }
        """.trimIndent()
        val c = SmkOpenSourceAdapter(FakeHttp(json))
            .fetchCandidates(setOf(SafeCategory.SCENIC_FINE_ART), limit = 4).single()
        assertEquals(0, c.provenance.originalWidth)
        assertEquals(0, c.provenance.originalHeight)
    }
}
