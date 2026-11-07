package com.sunloom.app.data.sources

import com.sunloom.app.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Article VI §8: a response that is not the expected JSON (an HTML error page served with 200, a changed API) is a
 * failed request, not an empty catalog; otherwise the source stays HEALTHY and its cursor advances while nothing arrives.
 */
@Requirement("VI.8")
class UnparseableResponseTest {
    private val html = "<html><body>Service temporarily unavailable</body></html>"

    private fun assertFailsAsSourceResponse(name: String, parse: () -> Any) {
        try {
            parse()
            fail("$name: an unparseable body must not look like an empty catalog")
        } catch (e: SourceResponseException) {
            assertTrue(name, e.message!!.isNotBlank())
        }
    }

    @Test
    fun `every JSON adapter fails on an unparseable body`() {
        assertFailsAsSourceResponse("wikimedia") { WikimediaFeaturedSourceAdapter().parseResponse(html, com.sunloom.app.domain.model.SafeCategory.LANDSCAPES_NATURE) }
        assertFailsAsSourceResponse("artic") { ArtInstituteCc0SourceAdapter().parseResponse(html) }
        assertFailsAsSourceResponse("cleveland") { ClevelandMuseumCc0SourceAdapter().parseResponse(html) }
        assertFailsAsSourceResponse("smk") { SmkOpenSourceAdapter().parseResponse(html) }
        assertFailsAsSourceResponse("nasa") { NasaLibrarySourceAdapter().parseResponse(html) }
    }

    @Test
    fun `a well-formed response with no results is still just empty`() {
        assertEquals(0, ArtInstituteCc0SourceAdapter().parseResponse("""{"data": []}""").size)
        assertEquals(0, NasaLibrarySourceAdapter().parseResponse("""{"collection": {"items": []}}""").size)
    }
}
