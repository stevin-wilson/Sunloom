package com.sunloom.app.data.sources

import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.safety.SafetyDenylist
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records must be filed under a category the user enabled, or WallpaperMatcher drops them. */
class NewCategoryFilingTest {

    private class FakeHttpClient(private val canned: Map<String, String>) : HttpClient {
        val requestedUrls = mutableListOf<String>()
        override suspend fun getText(url: String): String {
            requestedUrls += url
            return canned.entries.firstOrNull { url.contains(it.key) }?.value
                ?: throw IllegalStateException("Unexpected URL: $url")
        }

        override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = ByteArray(16_384)
    }

    @Test
    fun `Met files botanical results under BOTANICAL_FLORA when only that category is enabled`() = runTest {
        val client = FakeHttpClient(
            mapOf(
                "v1/search" to """{"total": 1, "objectIDs": [11]}""",
                "objects/11" to """
                    {"objectID": 11, "isPublicDomain": true, "title": "Irises",
                     "artistDisplayName": "Vincent van Gogh",
                     "primaryImage": "https://images.metmuseum.org/irises.jpg",
                     "department": "European Paintings", "classification": "Paintings",
                     "tags": [{"term": "Flowers"}]}
                """.trimIndent()
            )
        )
        val candidates = MetMuseumCc0SourceAdapter(client)
            .fetchCandidates(setOf(SafeCategory.BOTANICAL_FLORA), limit = 2)
        assertEquals(1, candidates.size)
        assertEquals(SafeCategory.BOTANICAL_FLORA, candidates.single().category)
        assertTrue(client.requestedUrls.first().contains("botanical"))
    }

    @Test
    fun `Met files landscape results under LANDSCAPES_NATURE when scenic art is not enabled`() = runTest {
        val client = FakeHttpClient(
            mapOf(
                "v1/search" to """{"total": 1, "objectIDs": [12]}""",
                "objects/12" to """
                    {"objectID": 12, "isPublicDomain": true, "title": "Valley",
                     "primaryImage": "https://images.metmuseum.org/valley.jpg",
                     "department": "European Paintings", "classification": "Paintings"}
                """.trimIndent()
            )
        )
        val candidates = MetMuseumCc0SourceAdapter(client)
            .fetchCandidates(setOf(SafeCategory.LANDSCAPES_NATURE), limit = 2)
        assertEquals(SafeCategory.LANDSCAPES_NATURE, candidates.single().category)
    }

    @Test
    fun `ArtIC queries botanical art and files it under BOTANICAL_FLORA when only that is enabled`() = runTest {
        val client = FakeHttpClient(
            mapOf(
                "api.artic.edu" to """
                    {"data": [{"id": 5, "title": "Water Lilies", "artist_title": "Claude Monet",
                      "image_id": "lilies-5", "is_public_domain": true,
                      "classification_title": "painting", "subject_titles": ["flowers"], "term_titles": []}]}
                """.trimIndent()
            )
        )
        val candidates = ArtInstituteCc0SourceAdapter(client)
            .fetchCandidates(setOf(SafeCategory.BOTANICAL_FLORA), limit = 2)
        assertEquals(SafeCategory.BOTANICAL_FLORA, candidates.single().category)
        assertTrue(client.requestedUrls.single().contains("botanical"))
        assertFalse(client.requestedUrls.single().contains("mountains"))
    }

    @Test
    fun `ArtIC still files landscape results under SCENIC_FINE_ART`() = runTest {
        val client = FakeHttpClient(
            mapOf(
                "api.artic.edu" to """
                    {"data": [{"id": 6, "title": "Mountain", "artist_title": "A", "image_id": "m-6",
                      "is_public_domain": true, "classification_title": "painting",
                      "subject_titles": ["landscapes"], "term_titles": []}]}
                """.trimIndent()
            )
        )
        val candidates = ArtInstituteCc0SourceAdapter(client)
            .fetchCandidates(setOf(SafeCategory.SCENIC_FINE_ART), limit = 2)
        assertEquals(SafeCategory.SCENIC_FINE_ART, candidates.single().category)
    }

    @Test
    fun `seed reserve respects the enabled categories instead of returning every seed`() = runTest {
        val adapter = BundledSeedSourceAdapter()
        val newOnly = adapter.fetchCandidates(
            setOf(SafeCategory.BOTANICAL_FLORA, SafeCategory.AERIAL_EARTH), limit = 50
        )
        assertTrue(newOnly.isNotEmpty())
        assertTrue(newOnly.all { it.category == SafeCategory.BOTANICAL_FLORA || it.category == SafeCategory.AERIAL_EARTH })
        val landscapes = adapter.fetchCandidates(setOf(SafeCategory.LANDSCAPES_NATURE), limit = 50)
        assertTrue(landscapes.isNotEmpty())
        assertTrue(landscapes.all { it.category == SafeCategory.LANDSCAPES_NATURE })
    }

    @Test
    fun `generic industrial and brand words do not satisfy the safe domain check`() {
        assertFalse(SafetyDenylist.matchesSafeDomain("Delta Airlines poster", emptyList()))
        assertFalse(SafetyDenylist.matchesSafeDomain("Coal power plant", listOf("industry")))
        assertTrue(SafetyDenylist.matchesSafeDomain("Yukon River Delta", emptyList()))
        assertTrue(SafetyDenylist.matchesSafeDomain("Ripening Pears", listOf("orchard", "fruit")))
        assertTrue(SafetyDenylist.matchesSafeDomain("Untitled", listOf("flower", "leaf")))
    }
}
