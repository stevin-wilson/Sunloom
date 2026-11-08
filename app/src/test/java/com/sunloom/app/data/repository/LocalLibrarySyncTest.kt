package com.sunloom.app.data.repository

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.data.sources.HttpClient
import com.sunloom.app.data.sources.WallpaperSourceAdapter
import com.sunloom.app.data.sources.fetchTextOrNull
import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.RejectionRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.UserPreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import com.sunloom.app.data.sources.SourceBudgetExceededException
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Regression tests for the "no fresh images ever arrive" symptom: restored or evicted library rows
 * whose image file is gone must not block re-download, and a failing network must not look like a
 * successful "up to date" sync (Article X §3–4).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LocalLibrarySyncTest {

    private lateinit var context: Context
    private val display = DisplayProfile(0, "GoogleBook Retina", 2560, 1600, densityDpi = 220)
    private val rawPrefs get() = context.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE)

    /** A distinct, deterministic picture per seed so dHash-based duplicate checks only fire deliberately. */
    private fun jpegFor(seed: String): ByteArray {
        val rng = java.util.Random(seed.hashCode().toLong())
        val width = 1700
        val height = 1100
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // A 9x8 mosaic of random luminances: the dHash compares neighbouring cells of exactly such a grid,
        // so two seeds differ in roughly half of the hash bits while a copy stays identical. Pixels are
        // written directly because Robolectric does not rasterise Canvas drawing.
        val cells = IntArray(9 * 8) { 20 + rng.nextInt(215) }
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val v = cells[(y * 8 / height) * 9 + (x * 9 / width)]
            pixels[y * width + x] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray().also { bmp.recycle() }
    }

    /** Which picture a download URL stands for: the candidate id is the path segment after `/2/` or `/image/`. */
    private fun imageKey(url: String): String =
        Regex("(?:/2/|/image/)([^/]+)").find(url)?.groupValues?.get(1) ?: url

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun candidate(
        id: String,
        sourceId: String = "artic_cc0",
        creator: String = "Artist $id",
        imageKey: String = id,
        width: Int = 0,
        height: Int = 0,
        title: String = "Mountain Valley Clouds $id",
        tags: List<String> = listOf("landscape", "mountains")
    ) = CandidateAsset(
        id = id,
        title = title,
        description = "Landscape",
        category = SafeCategory.SCENIC_FINE_ART,
        rawCategoriesAndTags = tags,
        downloadUrl = when (sourceId) {
            "nasa_library" -> "https://images-assets.nasa.gov/image/$imageKey/$imageKey~orig.jpg"
            "wikimedia_featured" -> "https://upload.wikimedia.org/wikipedia/commons/image/$imageKey/$imageKey.jpg"
            "met_museum_cc0" -> "https://images.metmuseum.org/CRDImages/image/$imageKey/$imageKey.jpg"
            else -> "https://www.artic.edu/iiif/2/$imageKey/full/1686,/0/default.jpg"
        },
        provenance = ProvenanceRecord(
            sourceId = sourceId,
            sourceName = sourceId,
            assetId = id,
            originalPageUrl = "https://www.artic.edu/artworks/$id",
            creatorName = creator,
            creatorPageUrl = "",
            licenseType = LicenseType.CC0,
            attributionText = "CC0",
            retrievedAtEpochMs = 1762280000000L,
            originalWidth = width,
            originalHeight = height,
            renditionWidth = 0,
            renditionHeight = 0,
            safetyClassification = SafetyClassification.SAFE,
            safetyClassificationSource = sourceId,
            sha256Checksum = ""
        )
    )

    private var downloads = 0

    private val okClient = object : HttpClient {
        override suspend fun getText(url: String): String = "{}"
        override suspend fun getBytes(url: String, maxBytes: Int): ByteArray {
            downloads++
            return jpegFor(imageKey(url))
        }
    }

    private class FixedAdapter(
        override val sourceId: String,
        private val candidates: List<CandidateAsset>,
        private val seenOffsets: MutableList<Int> = mutableListOf(),
        private val onFetch: suspend (HttpClient) -> Unit = {}
    ) : WallpaperSourceAdapter {
        override val sourceName: String = sourceId
        override val licenseSummary: String = "CC0"
        var client: HttpClient? = null
        override fun withHttpClient(client: HttpClient): WallpaperSourceAdapter =
            FixedAdapter(sourceId, candidates, seenOffsets, onFetch).also { it.client = client }

        override suspend fun fetchCandidates(
            enabledCategories: Set<SafeCategory>,
            limit: Int,
            offset: Int,
            targetWidthPx: Int
        ): List<CandidateAsset> {
            seenOffsets += offset
            client?.let { onFetch(it) }
            return candidates
        }
    }

    private fun repo(
        vararg adapters: WallpaperSourceAdapter,
        client: HttpClient = okClient,
        random: kotlin.random.Random = kotlin.random.Random(1)
    ) = LocalLibraryRepository(context = context, httpClient = client, sourceAdapters = adapters.toList(), random = random)

    @Test
    fun `a restored row whose image file is gone is downloaded again and keeps its history`() = kotlinx.coroutines.test.runTest {
        val repo = repo(FixedAdapter("artic_cc0", listOf(candidate("ghost-1"))))
        assertEquals(1, repo.refreshOnlineCatalog(display))

        repo.recordWallpaperApplied("ghost-1")
        repo.setFavorite("ghost-1", true)
        val file = File(repo.getWallpaperById("ghost-1")!!.localFilePath)
        assertTrue(file.delete()) // what a reinstall with restored database but no image directory looks like

        assertEquals("ghost row must not block re-download", 1, repo.refreshOnlineCatalog(display))

        val healed = repo.getWallpaperById("ghost-1")!!
        assertTrue(File(healed.localFilePath).length() > 0L)
        assertTrue(healed.fileSizeBytes > 0L)
        assertEquals(1, healed.appliedCount)
        assertTrue(healed.isFavorite)
    }

    @Test
    fun `an evicted row with a blank path is downloaded again`() = kotlinx.coroutines.test.runTest {
        val repo = repo(FixedAdapter("artic_cc0", listOf(candidate("evicted-1"))))
        repo.refreshOnlineCatalog(display)
        val row = repo.getWallpaperById("evicted-1")!!
        repo.upsertWallpaper(row.copy(localFilePath = "", croppedFilePath = "", fileSizeBytes = 0L, appliedCount = 3))

        assertEquals(1, repo.refreshOnlineCatalog(display))
        assertEquals(3, repo.getWallpaperById("evicted-1")!!.appliedCount)
        assertTrue(repo.getWallpaperById("evicted-1")!!.fileSizeBytes > 0L)
    }

    @Test
    fun `a rejected row is never downloaded again`() = kotlinx.coroutines.test.runTest {
        val repo = repo(FixedAdapter("artic_cc0", listOf(candidate("nope-1"))))
        repo.refreshOnlineCatalog(display)
        repo.rejectWallpaperForever("nope-1")
        assertEquals(0, repo.refreshOnlineCatalog(display))
        assertTrue(repo.getWallpaperById("nope-1")!!.isRejected)
    }

    @Test
    fun `healMissingFiles evicts online rows whose file is missing and leaves healthy rows and seeds alone`() = kotlinx.coroutines.test.runTest {
        val repo = repo(FixedAdapter("artic_cc0", listOf(candidate("ok-1"), candidate("lost-1"))))
        repo.refreshOnlineCatalog(display)
        repo.ensureSeedReserveLoaded(display)
        File(repo.getWallpaperById("lost-1")!!.localFilePath).delete()

        assertEquals(1, repo.healMissingFiles())

        val lost = repo.getWallpaperById("lost-1")!!
        assertEquals("", lost.localFilePath)
        assertEquals(0L, lost.fileSizeBytes)
        assertTrue(repo.getWallpaperById("ok-1")!!.fileSizeBytes > 0L)
        assertTrue(repo.getAllWallpapers().filter { it.provenance.sourceId == "seed_reserve" }.all { it.fileSizeBytes > 0L })
        assertEquals("a second pass has nothing left to heal", 0, repo.healMissingFiles())
    }

    @Test
    fun `default reserve cap is one gibibyte`() {
        assertEquals(1024L * 1024L * 1024L, UserPreferences().maxCacheBytes)
        assertEquals(1024L * 1024L * 1024L, LocalLibraryRepository(context).loadPreferences().maxCacheBytes)
    }

    @Test
    fun `the legacy 250 MB default is raised to 1 GB exactly once`() {
        rawPrefs.edit().putLong("max_cache_bytes", 250L * 1024L * 1024L).remove("cache_default_migrated_v1").commit()
        val repo = LocalLibraryRepository(context)

        assertEquals(1024L * 1024L * 1024L, repo.loadPreferences().maxCacheBytes)

        // A later deliberate choice of 250 MB is respected.
        repo.savePreferences(repo.loadPreferences().copy(maxCacheBytes = 250L * 1024L * 1024L))
        assertEquals(250L * 1024L * 1024L, repo.loadPreferences().maxCacheBytes)
    }

    @Test
    fun `an explicit non-default cap survives the migration`() {
        rawPrefs.edit().putLong("max_cache_bytes", 500L * 1024L * 1024L).remove("cache_default_migrated_v1").commit()
        assertEquals(500L * 1024L * 1024L, LocalLibraryRepository(context).loadPreferences().maxCacheBytes)
    }

    @Test
    fun `a source whose requests all fail is reported as failed and its fetch cursor does not advance`() = kotlinx.coroutines.test.runTest {
        rawPrefs.edit().putInt("fetch_cursor_artic_cc0", 12).commit()
        val failingClient = object : HttpClient {
            override suspend fun getText(url: String): String = throw IOException("HTTP 503")
            override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = throw IOException("HTTP 503")
        }
        val adapter = FixedAdapter("artic_cc0", emptyList()) { client ->
            client.fetchTextOrNull("https://api.artic.edu/api/v1/artworks/search?q=x")
        }
        val repo = repo(adapter, client = failingClient)

        assertEquals(0, repo.refreshOnlineCatalog(display))

        assertEquals(1, repo.lastSyncAttemptedSources)
        assertEquals("swallowed HTTP errors must count as a failed source", 1, repo.lastSyncFailedSources)
        assertEquals(12, rawPrefs.getInt("fetch_cursor_artic_cc0", -1))
        val diagnostics = repo.getSourceDiagnostics().single { it.sourceId == "artic_cc0" }
        assertEquals(1, diagnostics.consecutiveFailures)
        assertTrue(diagnostics.lastError.isNotBlank())
    }

    @Test
    fun `a source that dies with a JVM error is recorded as failed and the other sources still sync`() = kotlinx.coroutines.test.runTest {
        val broken = FixedAdapter("cma_cc0", emptyList()) { throw StackOverflowError("deeply nested JSON") }
        val healthy = FixedAdapter("artic_cc0", listOf(candidate("after-crash")))
        val repo = repo(broken, healthy)

        assertEquals(1, repo.refreshOnlineCatalog(display))

        assertEquals(1, repo.lastSyncFailedSources)
        assertEquals(1, repo.getSourceDiagnostics().single { it.sourceId == "cma_cc0" }.consecutiveFailures)
        assertNotNull(repo.getWallpaperById("after-crash"))
    }

    @Test
    fun `each source advances its own cursor and keeps going past 48`() = kotlinx.coroutines.test.runTest {
        rawPrefs.edit().putInt("fetch_cursor_artic_cc0", 48).putInt("fetch_cursor_cma_cc0", 100).commit()
        val articOffsets = mutableListOf<Int>()
        val cmaOffsets = mutableListOf<Int>()
        val repo = repo(
            FixedAdapter("artic_cc0", listOf(candidate("fresh-1")), articOffsets),
            FixedAdapter("cma_cc0", emptyList(), cmaOffsets)
        )

        assertEquals(1, repo.refreshOnlineCatalog(display))

        assertEquals(listOf(48), articOffsets)
        assertEquals(listOf(100), cmaOffsets)
        assertEquals(54, rawPrefs.getInt("fetch_cursor_artic_cc0", -1))
        assertEquals(106, rawPrefs.getInt("fetch_cursor_cma_cc0", -1))
        assertEquals(0, repo.lastSyncFailedSources)
    }

    @Test
    fun `a source starts at a random point of its catalog and then walks forward`() = kotlinx.coroutines.test.runTest {
        fun firstOffsetsWith(seed: Int): List<Int> {
            rawPrefs.edit().clear().commit()
            val seen = mutableListOf<Int>()
            kotlinx.coroutines.runBlocking {
                val repo = repo(FixedAdapter("artic_cc0", emptyList(), seen), random = kotlin.random.Random(seed))
                repo.refreshOnlineCatalog(display)
                repo.refreshOnlineCatalog(display)
            }
            return seen
        }
        val starts = (1..6).map { firstOffsetsWith(it) }
        assertTrue("random starts expected, got $starts", starts.map { it[0] }.toSet().size > 1)
        starts.forEach { assertEquals("second sync continues from the first", it[0] + 6, it[1]) }
    }

    @Test
    fun `changing the blurred frame fill setting retries display-scoped rejections but keeps permanent ones`() {
        val repo = LocalLibraryRepository(context)
        val base = repo.loadPreferences()
        repo.savePreferences(base.copy(allowBlurredFrameFill = false))
        repo.recordRejection(
            RejectionRecord("soft-1", "artic_cc0", "INSUFFICIENT_POST_CROP_RESOLUTION", "too small", 1L, false, "sig")
        )
        repo.recordRejection(
            RejectionRecord("hard-1", "artic_cc0", "PROHIBITED_CONTENT_TERM", "blocked", 2L, true)
        )

        repo.savePreferences(base.copy(allowBlurredFrameFill = true))

        val remaining = repo.getAllRejections().map { it.assetId }
        assertFalse("soft-1" in remaining)
        assertNotNull(remaining.firstOrNull { it == "hard-1" })
    }

    @Test
    fun `diagnostics show what the last sync fetched, added and rejected per source`() = kotlinx.coroutines.test.runTest {
        val repo = repo(FixedAdapter("artic_cc0", listOf(candidate("sum-1"), candidate("sum-2"))))
        repo.refreshOnlineCatalog(display)

        val first = repo.getSourceDiagnostics().single { it.sourceId == "artic_cc0" }.lastSyncSummary
        assertTrue(first, first.contains("fetched 2") && first.contains("added 2"))

        // Second pass: both are already in the library.
        repo.refreshOnlineCatalog(display)
        val second = repo.getSourceDiagnostics().single { it.sourceId == "artic_cc0" }.lastSyncSummary
        assertTrue(second, second.contains("fetched 2") && second.contains("added 0") && second.contains("already had 2"))
    }

    @Test
    fun `the sync summary reports HTTP errors for a failing source`() = kotlinx.coroutines.test.runTest {
        val failingClient = object : HttpClient {
            override suspend fun getText(url: String): String = throw IOException("HTTP 503")
            override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = throw IOException("HTTP 503")
        }
        val adapter = FixedAdapter("artic_cc0", emptyList()) { it.fetchTextOrNull("https://api.artic.edu/x") }
        val repo = repo(adapter, client = failingClient)
        repo.refreshOnlineCatalog(display)

        val summary = repo.getSourceDiagnostics().single { it.sourceId == "artic_cc0" }.lastSyncSummary
        assertTrue(summary, summary.contains("HTTP errors 1"))
    }

    @Test
    fun `a second id carrying the same picture is rejected as a near duplicate of the library`() = kotlinx.coroutines.test.runTest {
        val repo = repo(
            FixedAdapter("artic_cc0", listOf(candidate("orig-1"), candidate("copy-1", imageKey = "orig-1")))
        )
        assertEquals(1, repo.refreshOnlineCatalog(display))

        assertNotNull(repo.getWallpaperById("orig-1"))
        assertEquals(null, repo.getWallpaperById("copy-1"))
        assertTrue(repo.getAllRejections().any { it.assetId == "copy-1" && it.reasonCode == "NEAR_DUPLICATE_OF_LIBRARY" && !it.isPermanent })
    }

    @Test
    fun `an accepted image is moved out of quarantine with its bytes intact and nothing is left behind`() = kotlinx.coroutines.test.runTest {
        var downloaded: ByteArray? = null
        val capturing = object : HttpClient {
            override suspend fun getText(url: String): String = "{}"
            override suspend fun getBytes(url: String, maxBytes: Int): ByteArray =
                jpegFor(imageKey(url)).also { downloaded = it }
        }
        val repo = repo(FixedAdapter("artic_cc0", listOf(candidate("moved-1"))), client = capturing)
        assertEquals(1, repo.refreshOnlineCatalog(display))

        val stored = repo.getWallpaperById("moved-1")!!
        val file = java.io.File(stored.localFilePath)
        assertTrue(file.exists())
        assertEquals(stored.fileSizeBytes, file.length())
        assertTrue("stored bytes are exactly the downloaded bytes", file.readBytes().contentEquals(downloaded!!))
        assertEquals("quarantine is empty afterwards", 0, repo.quarantineDir.listFiles().orEmpty().size)
    }

    @Test
    fun `distinct pictures are all accepted`() = kotlinx.coroutines.test.runTest {
        val repo = repo(FixedAdapter("artic_cc0", (1..4).map { candidate("pic-$it") }))
        assertEquals(4, repo.refreshOnlineCatalog(display))
    }

    @Test
    fun `one picture that cannot be stored does not abort the rest of the sync`() = kotlinx.coroutines.test.runTest {
        // A disk-full write or a store error on one candidate used to leave refreshOnlineCatalog, skipping the other
        // candidates, the sync summary and the cache cleanup.
        val inner = InMemoryLibraryStore(java.io.File(context.filesDir, "sync_failing_store"))
        val flaky = object : LibraryStore by inner {
            override fun upsertWallpaper(record: com.sunloom.app.domain.model.WallpaperRecord) {
                if (record.id == "pic-2") throw java.io.IOException("No space left on device")
                inner.upsertWallpaper(record)
            }
        }
        val repo = LocalLibraryRepository(
            context = context,
            store = flaky,
            httpClient = okClient,
            sourceAdapters = listOf(FixedAdapter("artic_cc0", (1..4).map { candidate("pic-$it") })),
            random = kotlin.random.Random(1)
        )
        assertEquals(3, repo.refreshOnlineCatalog(display))
        assertTrue(repo.getAllWallpapers().none { it.id == "pic-2" })
    }

    @Test
    fun `two syncs never run at once, so the app and the background worker cannot double a source's request rate`() = kotlinx.coroutines.test.runTest {
        var running = 0
        var peak = 0
        val slowFetch: suspend (HttpClient) -> Unit = {
            running++
            peak = maxOf(peak, running)
            kotlinx.coroutines.delay(100)
            running--
        }
        val a = repo(FixedAdapter("artic_cc0", listOf(candidate("a-1")), onFetch = slowFetch))
        val b = repo(FixedAdapter("artic_cc0", listOf(candidate("b-1")), onFetch = slowFetch))
        val first = async { a.refreshOnlineCatalog(display) }
        val second = async { b.refreshOnlineCatalog(display) }
        first.await()
        second.await()
        assertEquals("syncs overlapped", 1, peak)
    }

    @Test
    fun `a rendition that is too large to decode falls back to the next one instead of banning the picture`() = kotlinx.coroutines.test.runTest {
        // A PNG header that claims 20000 x 20000 pixels, padded past the 4 KB minimum.
        val oversized = ByteArray(5000).also {
            byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
                0, 0, 0x4E, 0x20, 0, 0, 0x4E, 0x20).copyInto(it)
        }
        val client = object : HttpClient {
            override suspend fun getText(url: String): String = "{}"
            override suspend fun getBytes(url: String, maxBytes: Int): ByteArray =
                if (url.contains("1686,")) oversized else jpegFor(imageKey(url))
        }
        val big = candidate("big-1").copy(
            fallbackDownloadUrls = listOf("https://www.artic.edu/iiif/2/big-1/full/843,/0/default.jpg")
        )
        val repo = repo(FixedAdapter("artic_cc0", listOf(big)), client = client)
        assertEquals(1, repo.refreshOnlineCatalog(display))
        assertTrue("not permanently banned", repo.getAllWallpapers().any { it.id == "big-1" && !it.isRejected })
    }

    @Test
    fun `a source whose images cannot be downloaded does not stay healthy`() = kotlinx.coroutines.test.runTest {
        // Metadata answers, every image download fails (the image host is down): that is a failing source.
        val client = object : HttpClient {
            override suspend fun getText(url: String): String = "{}"
            override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = throw IOException("HTTP 503")
        }
        val repo = repo(FixedAdapter("artic_cc0", (1..3).map { candidate("dl-$it") }), client = client)
        assertEquals(0, repo.refreshOnlineCatalog(display))
        assertEquals(1, repo.lastSyncFailedSources)
        assertEquals(1, repo.getSourceDiagnostics().single { it.sourceId == "artic_cc0" }.consecutiveFailures)
        // Failures accumulate across syncs, so the backoff can start.
        repo.refreshOnlineCatalog(display)
        repo.refreshOnlineCatalog(display)
        assertEquals(3, repo.getSourceDiagnostics().single { it.sourceId == "artic_cc0" }.consecutiveFailures)
    }

    @Test
    fun `one failed download among successful ones keeps the source healthy`() = kotlinx.coroutines.test.runTest {
        var n = 0
        val client = object : HttpClient {
            override suspend fun getText(url: String): String = "{}"
            override suspend fun getBytes(url: String, maxBytes: Int): ByteArray {
                if (n++ == 0) throw IOException("HTTP 500")
                return jpegFor(imageKey(url))
            }
        }
        val repo = repo(FixedAdapter("artic_cc0", (1..3).map { candidate("ok-$it") }), client = client)
        assertEquals(2, repo.refreshOnlineCatalog(display))
        assertEquals(0, repo.lastSyncFailedSources)
    }

    @Test
    fun `a picture skipped by a storage error is listed in Diagnostics but never blocked`() = kotlinx.coroutines.test.runTest {
        val inner = InMemoryLibraryStore(java.io.File(context.filesDir, "skip_visible_store"))
        var failing = true
        val flaky = object : LibraryStore by inner {
            override fun upsertWallpaper(record: com.sunloom.app.domain.model.WallpaperRecord) {
                if (failing && record.id == "pic-2") throw IOException("No space left on device")
                inner.upsertWallpaper(record)
            }
        }
        val repo = LocalLibraryRepository(
            context = context, store = flaky, httpClient = okClient,
            sourceAdapters = listOf(FixedAdapter("artic_cc0", (1..3).map { candidate("pic-$it") })),
            random = kotlin.random.Random(1)
        )
        assertEquals(2, repo.refreshOnlineCatalog(display))
        val skipped = repo.getAllRejections().single { it.assetId == "pic-2" }
        assertEquals("STORAGE_ERROR", skipped.reasonCode)
        assertEquals("not permanent", false, skipped.isPermanent)

        failing = false
        assertEquals("the same picture is fetched again once storage works", 1, repo.refreshOnlineCatalog(display))
    }

    @Test
    fun `a store that becomes unavailable mid-sync is not swallowed as a skipped picture`() = kotlinx.coroutines.test.runTest {
        val inner = InMemoryLibraryStore(java.io.File(context.filesDir, "sync_unavailable_store"))
        val dead = object : LibraryStore by inner {
            override fun upsertWallpaper(record: com.sunloom.app.domain.model.WallpaperRecord) {
                throw LibraryStoreUnavailableException("down")
            }
        }
        val repo = LocalLibraryRepository(
            context = context, store = dead, httpClient = okClient,
            sourceAdapters = listOf(FixedAdapter("artic_cc0", listOf(candidate("u-1")))), random = kotlin.random.Random(1)
        )
        try {
            repo.refreshOnlineCatalog(display)
            org.junit.Assert.fail("the worker must see the store failure so it can retry and suspend")
        } catch (_: LibraryStoreUnavailableException) {
        }
    }

    @Test
    fun `running out of request budget before any download is not a failing source`() = kotlinx.coroutines.test.runTest {
        val client = object : HttpClient {
            override suspend fun getText(url: String): String = "{}"
            override suspend fun getBytes(url: String, maxBytes: Int): ByteArray =
                throw SourceBudgetExceededException("artic_cc0", 5)
        }
        val repo = repo(FixedAdapter("artic_cc0", listOf(candidate("b-1"))), client = client)
        repo.refreshOnlineCatalog(display)
        assertEquals(0, repo.lastSyncFailedSources)
        assertEquals(0, repo.getSourceDiagnostics().single { it.sourceId == "artic_cc0" }.consecutiveFailures)
    }

    @Test
    fun `rejecting a picture records the rejection before it deletes the file`() = kotlinx.coroutines.test.runTest {
        // If the process dies between the two steps, the picture must still be refused rather than fetched again.
        val inner = InMemoryLibraryStore(java.io.File(context.filesDir, "reject_order_store"))
        var failRejectedWrites = false
        val flaky = object : LibraryStore by inner {
            override fun upsertWallpaper(record: com.sunloom.app.domain.model.WallpaperRecord) {
                if (failRejectedWrites && record.isRejected) throw IOException("crash")
                inner.upsertWallpaper(record)
            }
        }
        val repo = LocalLibraryRepository(
            context = context, store = flaky, httpClient = okClient,
            sourceAdapters = listOf(FixedAdapter("artic_cc0", listOf(candidate("r-1")))), random = kotlin.random.Random(1)
        )
        assertEquals(1, repo.refreshOnlineCatalog(display))
        val file = java.io.File(repo.getAllWallpapers().single { it.id == "r-1" }.localFilePath)
        assertTrue(file.exists())

        failRejectedWrites = true
        try {
            repo.rejectWallpaperForever("r-1")
        } catch (_: IOException) {
        }
        assertTrue("file is deleted only after the rejection is stored", file.exists())
        assertTrue("the permanent blocklist already refuses it", repo.getPermanentBlockedAssetIds().contains("r-1"))
    }

    @Test
    fun `no more than three unseen images from one creator are kept`() = kotlinx.coroutines.test.runTest {
        val monet = (1..5).map { candidate("monet-$it", creator = "Claude Monet") }
        val repo = repo(FixedAdapter("artic_cc0", monet))
        assertEquals(3, repo.refreshOnlineCatalog(display))

        // Showing one frees a slot for the next sync.
        repo.recordWallpaperApplied(repo.getAllWallpapers().first { it.id.startsWith("monet") }.id)
        assertEquals(1, repo.refreshOnlineCatalog(display))
    }

    @Test
    fun `the creator cap does not apply to institutional NASA imagery`() = kotlinx.coroutines.test.runTest {
        val nasa = (1..5).map {
            candidate("nasa-PIA0000$it", sourceId = "nasa_library", creator = "NASA", tags = listOf("landscape", "Galileo"))
        }
        val repo = repo(FixedAdapter("nasa_library", nasa))
        val added = repo.refreshOnlineCatalog(display)
        assertEquals("rejections=${repo.getAllRejections()}", 5, added)
    }

    @Test
    fun `a candidate whose advertised size cannot fill the display is skipped before downloading`() = kotlinx.coroutines.test.runTest {
        downloads = 0
        val repo = repo(
            FixedAdapter(
                "artic_cc0",
                listOf(candidate("tiny-1", width = 800, height = 500), candidate("big-1", width = 4000, height = 2500))
            )
        )
        assertEquals(1, repo.refreshOnlineCatalog(display))
        assertEquals("only the large candidate is downloaded", 1, downloads)
        assertEquals(null, repo.getWallpaperById("tiny-1"))
    }

    @Test
    fun `missing safe-domain keywords are a soft, retryable outcome and not a permanent ban`() = kotlinx.coroutines.test.runTest {
        val repo = repo(
            FixedAdapter(
                "wikimedia_featured",
                listOf(candidate("clock-1", sourceId = "wikimedia_featured", title = "Antique brass clockwork", tags = listOf("clocks")))
            )
        )
        repo.refreshOnlineCatalog(display)

        val rejection = repo.getAllRejections().single { it.assetId == "clock-1" }
        assertEquals("UNRECOGNIZED_SAFE_DOMAIN", rejection.reasonCode)
        assertFalse(rejection.isPermanent)
        assertFalse("clock-1" in repo.getPermanentBlockedAssetIds())
    }

    @Test
    fun `soft bans from an older denylist are lifted once while hard bans stay`() = kotlinx.coroutines.test.runTest {
        val repo = repo(
            FixedAdapter(
                "met_museum_cc0",
                listOf(
                    candidate("legacy-soft", sourceId = "met_museum_cc0", title = "Untitled Study No. 4", tags = listOf("Paintings")),
                    candidate("legacy-hard", sourceId = "met_museum_cc0", title = "Untitled Study No. 5", tags = listOf("Paintings"))
                )
            )
        )
        val now = 1L
        repo.store.addBlocklistEntry(com.sunloom.app.domain.model.BlocklistEntry("legacy-soft", "", "", "met_museum_cc0", "UNRECOGNIZED_SAFE_DOMAIN", now))
        repo.store.addBlocklistEntry(com.sunloom.app.domain.model.BlocklistEntry("legacy-hard", "", "", "met_museum_cc0", "PROHIBITED_CONTENT_TERM", now))
        repo.recordRejection(RejectionRecord("legacy-soft", "met_museum_cc0", "MISSING_SAFETY_TAGS", "old", now, true))
        rawPrefs.edit().putString("soft_rejections_denylist_version", "2026.11.06-v4").commit()

        assertEquals(1, repo.refreshOnlineCatalog(display))

        assertNotNull(repo.getWallpaperById("legacy-soft"))
        assertEquals(null, repo.getWallpaperById("legacy-hard"))
        assertTrue("legacy-hard" in repo.getPermanentBlockedAssetIds())
        assertFalse("legacy-soft" in repo.getPermanentBlockedAssetIds())

        // The sweep runs once per denylist version.
        assertEquals(com.sunloom.app.domain.safety.SafetyDenylist.DENYLIST_VERSION, rawPrefs.getString("soft_rejections_denylist_version", null))
    }

    @Test
    fun `one failed request among successful ones does not fail the source or freeze its cursor`() = kotlinx.coroutines.test.runTest {
        var calls = 0
        val flaky = object : HttpClient {
            override suspend fun getText(url: String): String {
                calls++
                if (calls == 2) throw IOException("HTTP 503")
                return "{}"
            }
            override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = jpegFor("x")
        }
        rawPrefs.edit().putInt("fetch_cursor_artic_cc0", 12).commit()
        val adapter = FixedAdapter("artic_cc0", emptyList()) { client ->
            repeat(3) { client.fetchTextOrNull("https://api.artic.edu/api/v1/artworks/search?q=$it") }
        }
        val repo = repo(adapter, client = flaky)
        repo.refreshOnlineCatalog(display)
        assertEquals(0, repo.lastSyncFailedSources)
        assertEquals(18, rawPrefs.getInt("fetch_cursor_artic_cc0", -1))
    }

    /** A source whose catalog is endless: every cursor position yields five new pictures. */
    private class EndlessAdapter(
        override val sourceId: String,
        private val build: (String) -> CandidateAsset,
        private val rounds: MutableList<Int>
    ) : WallpaperSourceAdapter {
        override val sourceName: String = sourceId
        override val licenseSummary: String = "CC0"
        override fun withHttpClient(client: HttpClient): WallpaperSourceAdapter = this
        override suspend fun fetchCandidates(
            enabledCategories: Set<SafeCategory>, limit: Int, offset: Int, targetWidthPx: Int
        ): List<CandidateAsset> {
            rounds += offset
            return (1..5).map { build("endless-$offset-$it") }
        }
    }

    private fun platformFor(vararg displays: DisplayProfile) = com.sunloom.app.platform.GoogleBookPlatformAdapter(
        context,
        object : com.sunloom.app.platform.SystemWallpaperGateway {
            override fun isWallpaperSupported() = true
            override fun isSetWallpaperAllowed() = true
            override fun getConnectedDisplays() = displays.toList()
            override fun applyBitmapToDestination(bitmap: Bitmap, flags: Int) = true
            override fun isWifiConnected() = true
            override fun isBatterySaverActive() = false
        }
    )

    @Test
    fun `the background refresh still runs in manual mode and fetches in several rounds`() = kotlinx.coroutines.test.runTest {
        val rounds = mutableListOf<Int>()
        val repo = repo(EndlessAdapter("artic_cc0", { candidate(it, creator = "Artist $it") }, rounds))
        repo.savePreferences(repo.loadPreferences().copy(autoChangeEnabled = false))

        val added = com.sunloom.app.platform.scheduler.SunloomWallpaperScheduler.executeScheduledCatalogRefresh(
            appContext = context, repo = repo, platform = platformFor(display)
        )

        assertTrue("manual mode must still fetch ($added added)", added > 0)
        assertTrue("several rounds expected, got $rounds", rounds.size >= 2)
        assertTrue(rounds.size <= com.sunloom.app.platform.scheduler.RefreshSchedulePolicy.MAX_BACKGROUND_ROUNDS)
    }

    @Test
    fun `the background refresh does nothing while automation is paused`() = kotlinx.coroutines.test.runTest {
        val rounds = mutableListOf<Int>()
        val repo = repo(EndlessAdapter("artic_cc0", { candidate(it, creator = "Artist $it") }, rounds))
        repo.savePreferences(repo.loadPreferences().copy(isPaused = true))
        val added = com.sunloom.app.platform.scheduler.SunloomWallpaperScheduler.executeScheduledCatalogRefresh(
            appContext = context, repo = repo, platform = platformFor(display)
        )
        assertEquals(0, added)
        assertTrue(rounds.isEmpty())
    }

    @Test
    fun `needsTopUp reflects the buffer of unseen fetched images`() = kotlinx.coroutines.test.runTest {
        val repo = repo(FixedAdapter("artic_cc0", (1..3).map { candidate("few-$it") }))
        assertTrue(repo.needsTopUp())
        repo.refreshOnlineCatalog(display)
        assertTrue("3 images are far below the target", repo.needsTopUp())
    }

    @Test
    fun `a search that succeeds is not an outage even when every object lookup fails`() = kotlinx.coroutines.test.runTest {
        var texts = 0
        val client = object : HttpClient {
            override suspend fun getText(url: String): String {
                texts++
                if (texts == 1) return "{}"
                throw IOException("HTTP 404")
            }
            override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = jpegFor("x")
        }
        rawPrefs.edit().putInt("fetch_cursor_artic_cc0", 30).commit()
        val adapter = FixedAdapter("artic_cc0", emptyList()) { c ->
            repeat(4) { c.fetchTextOrNull("https://api.artic.edu/api/v1/artworks/$it") }
        }
        val repo = repo(adapter, client = client)
        repo.refreshOnlineCatalog(display)
        assertEquals(0, repo.lastSyncFailedSources)
        assertEquals(36, rawPrefs.getInt("fetch_cursor_artic_cc0", -1))
    }

    @Test
    fun `a page that keeps failing does not pin the fetch cursor forever`() = kotlinx.coroutines.test.runTest {
        val dead = object : HttpClient {
            override suspend fun getText(url: String): String = throw IOException("HTTP 500")
            override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = throw IOException("HTTP 500")
        }
        rawPrefs.edit().putInt("fetch_cursor_artic_cc0", 60).putInt("source_fail_count_artic_cc0", 2).commit()
        val adapter = FixedAdapter("artic_cc0", emptyList()) { it.fetchTextOrNull("https://api.artic.edu/x") }
        repo(adapter, client = dead).refreshOnlineCatalog(display)
        assertEquals("third consecutive failure moves on to the next page", 66, rawPrefs.getInt("fetch_cursor_artic_cc0", -1))
    }

    @Test
    fun `a picture a display crop would mostly discard is skipped before downloading`() = kotlinx.coroutines.test.runTest {
        downloads = 0
        val portrait = candidate("tall-1", width = 3000, height = 4000)
        val repo = repo(FixedAdapter("artic_cc0", listOf(portrait)))
        assertEquals(0, repo.refreshOnlineCatalog(display))
        assertEquals(0, downloads)
        assertTrue(repo.getAllRejections().any { it.assetId == "tall-1" && it.reasonCode == "COMPOSITION_CROPS_TOO_MUCH" && !it.isPermanent })
    }

    @Test
    fun `healMissingFiles leaves the users own photos alone`() = kotlinx.coroutines.test.runTest {
        val repo = repo(FixedAdapter("artic_cc0", listOf(candidate("mine-1"))))
        repo.refreshOnlineCatalog(display)
        val row = repo.getWallpaperById("mine-1")!!
        File(row.localFilePath).delete()
        repo.upsertWallpaper(row.copy(provenance = row.provenance.copy(sourceId = "user_local")))
        assertEquals(0, repo.healMissingFiles())
        assertEquals(row.localFilePath, repo.getWallpaperById("mine-1")!!.localFilePath)
    }

    @Test
    fun `healing does not overwrite a record that was just re-downloaded`() = kotlinx.coroutines.test.runTest {
        val base = InMemoryLibraryStore()
        val repo = LocalLibraryRepository(context = context, httpClient = okClient, sourceAdapters = listOf(FixedAdapter("artic_cc0", listOf(candidate("race-1")))), store = base)
        repo.refreshOnlineCatalog(display)
        val fresh = repo.getWallpaperById("race-1")!!
        // A stale view: the same row as it was before the file was written back.
        val stale = fresh.copy(localFilePath = "/definitely/missing.jpg")
        val racingStore = object : LibraryStore by base {
            override fun getAllWallpapers() = base.getAllWallpapers().map { if (it.id == "race-1") stale else it }
        }
        val racing = LocalLibraryRepository(context = context, httpClient = okClient, sourceAdapters = emptyList(), store = racingStore)

        assertEquals(0, racing.healMissingFiles())
        assertEquals(fresh.localFilePath, base.getWallpaperById("race-1")!!.localFilePath)
    }
}
