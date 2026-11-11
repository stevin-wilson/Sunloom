package com.sunloom.app.data.sources

import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.renderer.CropRenderer
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * OPT-IN live probe (Article X §4). It calls the real catalogs, so it is skipped unless asked for:
 *
 *     SUNLOOM_LIVE_PROBE=1 ./gradlew :app:testGithubDebugUnitTest --tests '*LiveSourceCoverageProbe*' --rerun
 *
 * For each source it makes one small query per category it serves (about 8 candidates, the app's own adapters,
 * User-Agent and per-sync request budgets, a pause between queries), then runs the advertised sizes through the
 * app's pre-download checks for each common display. The yield table is written to
 * `app/build/reports/live-source-coverage.md`.
 *
 * Politeness: what it fetches is kept in `app/build/reports/live-source-candidates.tsv`. Re-running within 24 hours
 * replays that file and makes NO requests, which is all that re-scoring a different display or quality threshold
 * needs (the candidates do not depend on the display). Set SUNLOOM_LIVE_PROBE_REFRESH=1 to fetch again.
 *
 * It does not fail on a bad day: network errors skip a query, and it only fails if no source at all could supply a
 * usable candidate for some display. Read the report, not the pass mark. Sources that advertise no size (Met, NASA)
 * show as "unknown": they are judged after downloading, which this probe does not do.
 */
class LiveSourceCoverageProbe {

    private val adapters: List<WallpaperSourceAdapter> = listOf(
        WikimediaFeaturedSourceAdapter(),
        NasaLibrarySourceAdapter(),
        ArtInstituteCc0SourceAdapter(),
        MetMuseumCc0SourceAdapter(),
        ClevelandMuseumCc0SourceAdapter(),
        SmkOpenSourceAdapter()
    )

    @Test
    fun `live catalogs yield usable candidates for common displays`() {
        assumeTrue("Set SUNLOOM_LIVE_PROBE=1 to run the live source probe", System.getenv("SUNLOOM_LIVE_PROBE") == "1")

        val cacheFile = File("build/reports/live-source-candidates.tsv")
        val cached = if (cacheFile.isFile) ProbeCache.decode(cacheFile.readText()) else emptyList()
        val replay = ProbeCache.isUsable(
            ageMs = System.currentTimeMillis() - cacheFile.lastModified(),
            refreshRequested = System.getenv("SUNLOOM_LIVE_PROBE_REFRESH") == "1",
            hasEntries = cached.isNotEmpty()
        )

        val notes = ArrayList<String>()
        val entries: List<ProbeCache.Entry>
        if (replay) {
            entries = cached
            notes += "Replayed from ${cacheFile.path}: no requests were made. Set SUNLOOM_LIVE_PROBE_REFRESH=1 to fetch again."
        } else {
            val fetched = ArrayList<ProbeCache.Entry>()
            for (adapter in adapters) {
                val served = SourceRegistry.get(adapter.sourceId)?.servedCategories.orEmpty()
                for (category in SafeCategory.entries.filter { it in served }) {
                    try {
                        val candidates = runBlocking {
                            adapter.fetchCandidates(setOf(category), limit = 8, offset = 0, targetWidthPx = 3840)
                        }
                        for (c in candidates) {
                            fetched += ProbeCache.Entry(
                                adapter.sourceId, category.name, c.id, c.provenance.originalWidth, c.provenance.originalHeight
                            )
                        }
                        notes += "${adapter.sourceId} / ${category.name}: ${candidates.size} candidates"
                    } catch (e: Exception) {
                        notes += "${adapter.sourceId} / ${category.name}: skipped (${e.javaClass.simpleName}: ${e.message})"
                    }
                    Thread.sleep(1_000)
                }
            }
            entries = fetched
            if (entries.isNotEmpty()) {
                cacheFile.parentFile.mkdirs()
                cacheFile.writeText(ProbeCache.encode(entries))
            }
        }

        // sizes[source] = advertised (width, height) of each distinct candidate, at the largest rendition requested.
        val sizes = linkedMapOf<String, MutableMap<String, Pair<Int, Int>>>()
        for (e in entries) sizes.getOrPut(e.sourceId) { linkedMapOf() }[e.candidateId] = e.width to e.height
        assumeTrue("No live source answered; check the network", sizes.values.any { it.isNotEmpty() })

        val tallies = sizes.mapValues { (_, byId) ->
            CoverageDisplays.all.associate { it.name to YieldTally.of(byId.values.toList(), it) }
        }
        // The app never polls a source whose renditions cannot satisfy the display (selectAdaptersForSync).
        val notPolled = sizes.keys.associateWith { id ->
            val maxEdge = SourceRegistry.get(id)?.maxRenditionLongEdgePx ?: Int.MAX_VALUE
            CoverageDisplays.all.filter { !CropRenderer.canSourceSatisfyDisplay(maxEdge, it, false) }.map { it.name }.toSet()
        }
        val report = YieldTally.report(tallies, CoverageDisplays.all.map { it.name }, notPolled) +
            "\n## Queries\n\n" + notes.joinToString("\n") { "- $it" } + "\n"
        val out = File("build/reports/live-source-coverage.md")
        out.parentFile.mkdirs()
        out.writeText(report)
        println("Live source coverage report: ${out.absolutePath}")

        for (display in CoverageDisplays.all) {
            val anyUsable = tallies.any { (id, byDisplay) ->
                display.name !in notPolled[id].orEmpty() && (byDisplay[display.name]?.usable ?: 0) > 0
            }
            assertTrue("No live source offered a usable candidate for ${display.name}; see ${out.path}", anyUsable)
        }
    }
}
