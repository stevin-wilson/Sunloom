package com.sunloom.app.data.sources

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.SafeCategory
import java.net.URLDecoder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article X §4: NASA's image search requires every term of a query to match. The original four- and five-word
 * queries returned no results at all (checked against the live API on 2026-11-07: 0, 0 and 6 hits, against hundreds
 * for one or two words), which left the source silent. Queries must stay short, and rotate for variety.
 */
@Requirement("X.4")
class NasaQueryTest {

    private class RecordingHttp : HttpClient {
        val urls = ArrayList<String>()
        override suspend fun getText(url: String): String {
            urls += url
            return """{ "collection": { "items": [] } }"""
        }
        override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = ByteArray(16)
    }

    private fun term(url: String): String =
        URLDecoder.decode(url.substringAfter("?q=").substringBefore("&"), "UTF-8")

    private suspend fun queriesFor(category: SafeCategory, offset: Int): List<String> {
        val http = RecordingHttp()
        NasaLibrarySourceAdapter(http).fetchCandidates(setOf(category), limit = 8, offset = offset)
        return http.urls.filter { it.contains("images-api.nasa.gov/search") }.map(::term)
    }

    @Test
    fun `every query is at most two words so the search can match`() = runTest {
        for (category in listOf(SafeCategory.SPACE_ASTRONOMY, SafeCategory.AERIAL_EARTH, SafeCategory.LANDSCAPES_NATURE)) {
            for (offset in listOf(0, 8 * 20, 8 * 40, 8 * 60)) {
                val queries = queriesFor(category, offset)
                assertTrue("no query issued for $category at $offset", queries.isNotEmpty())
                queries.forEach { q ->
                    assertTrue("'$q' for $category has more than two words", q.trim().split(Regex("\\s+")).size <= 2)
                }
            }
        }
    }

    @Test
    fun `a category rotates through different queries as the fetch window advances`() = runTest {
        val seen = (0 until 6).flatMap { queriesFor(SafeCategory.SPACE_ASTRONOMY, it * 8 * 20) }.toSet()
        assertTrue("expected variety, got $seen", seen.size >= 2)
    }

    @Test
    fun `the same window always asks the same question`() = runTest {
        assertEquals(queriesFor(SafeCategory.AERIAL_EARTH, 0), queriesFor(SafeCategory.AERIAL_EARTH, 0))
    }
}
