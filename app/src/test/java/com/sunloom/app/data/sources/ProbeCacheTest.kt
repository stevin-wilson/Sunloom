package com.sunloom.app.data.sources

import com.sunloom.app.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article X §4 (politeness): the opt-in live probe keeps what it fetched, so re-scoring displays or thresholds
 * replays from disk instead of asking the public catalogs again.
 */
@Requirement("X.4")
class ProbeCacheTest {

    private val entries = listOf(
        ProbeCache.Entry("wikimedia_featured", "LANDSCAPES_NATURE", "wm-1", 4000, 2250),
        ProbeCache.Entry("met_museum_cc0", "SCENIC_FINE_ART", "met-2", 0, 0),
        ProbeCache.Entry("cma_cc0", "SCENIC_FINE_ART", "cma-3", 3400, 2200)
    )

    @Test
    fun `entries survive a round trip`() {
        assertEquals(entries, ProbeCache.decode(ProbeCache.encode(entries)))
    }

    @Test
    fun `malformed or blank lines are skipped, not fatal`() {
        val text = ProbeCache.encode(entries) + "\n\nnot a row\nsrc\tcat\tid\tabc\t12\n"
        assertEquals(entries, ProbeCache.decode(text))
    }

    @Test
    fun `a cache is replayed only while it is fresh and no refresh was asked for`() {
        val hour = 3_600_000L
        assertTrue(ProbeCache.isUsable(ageMs = 2 * hour, refreshRequested = false, hasEntries = true))
        assertFalse("expired", ProbeCache.isUsable(ageMs = 25 * hour, refreshRequested = false, hasEntries = true))
        assertFalse("refresh asked for", ProbeCache.isUsable(ageMs = 1 * hour, refreshRequested = true, hasEntries = true))
        assertFalse("nothing in it", ProbeCache.isUsable(ageMs = 1 * hour, refreshRequested = false, hasEntries = false))
    }
}
