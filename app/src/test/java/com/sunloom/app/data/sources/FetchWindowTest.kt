package com.sunloom.app.data.sources

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.SafeCategory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fetch offset used to wrap at 48, so every install walked the same handful of result pages.
 * With a monotonic offset each adapter must keep moving through its catalog (Article X §4).
 */
@Requirement("X.4")
class FetchWindowTest {

    private class RecordingClient : HttpClient {
        val urls = mutableListOf<String>()
        override suspend fun getText(url: String): String {
            urls += url
            return "{}"
        }
        override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = ByteArray(0)
    }

    private val allCategories = SafeCategory.entries.toSet() - SafeCategory.USER_LOCAL
    private val offsets = (0 until 600 step 6).toList()

    private suspend fun urlsFor(build: (HttpClient) -> WallpaperSourceAdapter): List<String> {
        val client = RecordingClient()
        val adapter = build(client)
        for (offset in offsets) {
            adapter.fetchCandidates(allCategories, limit = 8, offset = offset, targetWidthPx = 2560)
        }
        return client.urls
    }

    private fun distinctParam(urls: List<String>, name: String): Int =
        urls.mapNotNull { Regex("[?&]$name=([^&]+)").find(it)?.groupValues?.get(1) }.toSet().size

    @Test
    fun `Art Institute reaches both of its themed queries`() = runTest {
        val urls = urlsFor { ArtInstituteCc0SourceAdapter(it) }
        assertTrue("landscape query never used", urls.any { it.contains("landscape") })
        assertTrue("botanical query never used", urls.any { it.contains("botanical") })
    }

    @Test
    fun `Art Institute pages are not limited to the first six`() = runTest {
        val pages = distinctParam(urlsFor { ArtInstituteCc0SourceAdapter(it) }, "page")
        assertTrue("only $pages distinct pages", pages >= 8)
    }

    @Test
    fun `NASA walks more than five result pages`() = runTest {
        val pages = distinctParam(urlsFor { NasaLibrarySourceAdapter(it) }, "page")
        assertTrue("only $pages distinct pages", pages > 5)
    }

    @Test
    fun `Cleveland skip covers far more than 24 results`() = runTest {
        val urls = urlsFor { ClevelandMuseumCc0SourceAdapter(it) }
        val maxSkip = urls.mapNotNull { Regex("[?&]skip=(\\d+)").find(it)?.groupValues?.get(1)?.toInt() }.maxOrNull() ?: 0
        assertTrue("max skip $maxSkip", maxSkip > 24)
    }

    @Test
    fun `SMK offset covers far more than 32 results`() = runTest {
        val urls = urlsFor { SmkOpenSourceAdapter(it) }
        val maxOffset = urls.mapNotNull { Regex("[?&]offset=(\\d+)").find(it)?.groupValues?.get(1)?.toInt() }.maxOrNull() ?: 0
        assertTrue("max offset $maxOffset", maxOffset > 32)
    }

    @Test
    fun `Wikimedia search offset is not capped below 48`() = runTest {
        val urls = urlsFor { WikimediaFeaturedSourceAdapter(it) }
        val maxOffset = urls.mapNotNull { Regex("gsroffset=(\\d+)").find(it)?.groupValues?.get(1)?.toInt() }.maxOrNull() ?: 0
        assertTrue("max gsroffset $maxOffset", maxOffset > 48)
        assertTrue("gsroffset must stay in a sane range", maxOffset < 1000)
    }

    @Test
    fun `the budgeted client counts successful and failed requests separately`() = runTest {
        var calls = 0
        val flaky = object : HttpClient {
            override suspend fun getText(url: String): String {
                calls++
                if (calls % 2 == 0) throw java.io.IOException("HTTP 503")
                return "{}"
            }
            override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = ByteArray(0)
        }
        val client = BudgetedSourceHttpClient(flaky, "artic_cc0", maxRequests = 10, minSpacingMs = 0L)
        repeat(4) { client.fetchTextOrNull("https://api.artic.edu/x$it") }
        assertTrue(client.httpErrorCount == 2 && client.successfulRequestCount == 2)
    }

    /** The Met retired /v1/search on 2026-10-01 (HTTP 410); /v1.1/search is paginated by offset and limit. */
    @Test
    fun `Met uses the paginated v1_1 search and moves its window with the cursor`() = runTest {
        val urls = mutableListOf<String>()
        val client = object : HttpClient {
            override suspend fun getText(url: String): String {
                urls += url
                return if ("/search" in url) """{"total":5000,"objectIDs":[11,22,33,44,55,66]}""" else "{}"
            }
            override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = ByteArray(0)
        }
        val adapter = MetMuseumCc0SourceAdapter(client)
        for (offset in listOf(0, 60, 120)) {
            adapter.fetchCandidates(setOf(SafeCategory.SCENIC_FINE_ART), limit = 8, offset = offset, targetWidthPx = 2560)
        }
        val searches = urls.filter { "/search" in it }
        assertTrue(searches.toString(), searches.all { "/v1.1/search" in it && "limit=" in it && "offset=" in it })
        assertTrue(searches.none { "/v1/search" in it })
        val starts = searches.map { Regex("offset=(\\d+)").find(it)!!.groupValues[1].toInt() }
        assertTrue("window should move: $starts", starts.toSet().size == 3)
    }

    @Test
    fun `Met falls back to the first window when a deep window is empty`() = runTest {
        val urls = mutableListOf<String>()
        val client = object : HttpClient {
            override suspend fun getText(url: String): String {
                urls += url
                return when {
                    "/search" in url && "offset=0" !in url -> """{"total":3,"objectIDs":null}"""
                    "/search" in url -> """{"total":3,"objectIDs":[7]}"""
                    else -> "{}"
                }
            }
            override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = ByteArray(0)
        }
        MetMuseumCc0SourceAdapter(client).fetchCandidates(setOf(SafeCategory.SCENIC_FINE_ART), limit = 8, offset = 90, targetWidthPx = 2560)
        assertTrue(urls.toString(), urls.any { "/search" in it && "offset=0" in it })
        assertTrue(urls.toString(), urls.any { it.endsWith("/objects/7") })
    }

    /** Many NASA records (ISS photos) carry no keywords; the safety gate rejects those, so asking for more and keeping tagged ones wastes nothing. */
    @Test
    fun `NASA searches a wider page and keeps only records that carry keywords`() = runTest {
        val urls = mutableListOf<String>()
        val client = object : HttpClient {
            override suspend fun getText(url: String): String {
                urls += url
                fun item(id: String, keywords: String) = """{"data":[{"nasa_id":"$id","title":"Title $id","center":"JSC","description":"Earth from orbit","keywords":$keywords}],"links":[{"href":"https://images-assets.nasa.gov/image/$id/$id~thumb.jpg"}]}"""
                return """{"collection":{"items":[${item("a", "[]")},${item("b", """["EARTH (PLANET)","HORIZON"]""")},${item("c", "[]")}]}}"""
            }
            override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = ByteArray(0)
        }
        val found = NasaLibrarySourceAdapter(client).fetchCandidates(allCategories, limit = 8, offset = 0, targetWidthPx = 2560)
        assertTrue(urls.toString(), urls.all { "page_size=20" in it })
        assertTrue(found.map { it.id }.toString(), found.isNotEmpty() && found.all { it.rawCategoriesAndTags.isNotEmpty() })
    }

    @Test
    fun `Met object lookups rotate through the whole search window across syncs`() = runTest {
        val looked = mutableListOf<Int>()
        val client = object : HttpClient {
            override suspend fun getText(url: String): String {
                if ("/search" in url) return """{"total":5000,"objectIDs":[1,2,3,4,5,6,7,8,9,10,11,12]}"""
                Regex("/objects/(\\d+)").find(url)?.let { looked += it.groupValues[1].toInt() }
                return "{}"
            }
            override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = ByteArray(0)
        }
        val adapter = MetMuseumCc0SourceAdapter(client)
        // The cursor moves in steps of 6, all within the same 12-id window.
        for (offset in listOf(0, 6, 12, 18)) {
            adapter.fetchCandidates(setOf(SafeCategory.SCENIC_FINE_ART), limit = 8, offset = offset, targetWidthPx = 2560)
        }
        assertTrue("only ${looked.toSet().size} distinct objects examined: $looked", looked.toSet().size >= 9)
    }
}
