package com.sunloom.app.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.data.testWallpaper
import com.sunloom.app.domain.model.WallpaperRecord
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article III §1 / IX §2 (v0.59): a picture chosen for the daytime or evening is treated like a Kept one. The cache
 * limit does not evict it and the daily clean-up does not drop its row, so a slot does not quietly empty itself.
 */
@Requirement("III.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ChosenProtectionTest {
    private lateinit var context: Context
    private lateinit var repo: LocalLibraryRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        repo = LocalLibraryRepository(context = context, sourceAdapters = emptyList(), store = InMemoryLibraryStore())
    }

    private fun fetched(id: String, retrievedAt: Long): WallpaperRecord {
        val file = File(repo.wallpaperDir, "$id.jpg").apply { writeBytes(ByteArray(10_000)) }
        return testWallpaper(
            id, localFilePath = file.absolutePath, croppedFilePath = file.absolutePath, fileSizeBytes = file.length(),
            retrievedAt = retrievedAt
        ).also { repo.upsertWallpaper(it) }
    }

    private fun staleRow(id: String): WallpaperRecord =
        testWallpaper(id, retrievedAt = 1_000L).also { repo.upsertWallpaper(it) }

    private fun choose(day: String? = null, evening: String? = null, maxCacheBytes: Long = 1L) =
        repo.savePreferences(repo.loadPreferences().copy(daytimePickId = day, eveningPickId = evening, maxCacheBytes = maxCacheBytes))

    private fun kept(id: String) = (repo.getWallpaperById(id)?.fileSizeBytes ?: 0L) > 0L

    // --- the cache limit ---

    @Test
    fun `the cache limit evicts the oldest pictures but not the daytime or evening ones`() {
        // Eight light pictures, oldest first; the three newest are the offline minimum, the rest can go.
        (1..8).forEach { fetched("p$it", retrievedAt = it * 1_000L) }
        choose(day = "p1", evening = "p2")

        repo.enforceCacheLimit()

        assertTrue("the daytime pick stays", kept("p1"))
        assertTrue("the evening pick stays", kept("p2"))
        assertEquals("an unchosen old picture is evicted", false, kept("p3"))
        assertEquals("another unchosen old picture is evicted", false, kept("p4"))
    }

    @Test
    fun `without a chosen picture the same old picture is evicted`() {
        (1..8).forEach { fetched("p$it", retrievedAt = it * 1_000L) }
        choose()

        repo.enforceCacheLimit()

        assertEquals(false, kept("p1"))
    }

    @Test
    fun `a chosen id that no longer exists does no harm`() {
        (1..4).forEach { fetched("p$it", retrievedAt = it * 1_000L) }
        choose(day = "gone")

        repo.enforceCacheLimit()

        assertNull(repo.getWallpaperById("gone"))
    }

    // --- the daily clean-up ---

    @Test
    fun `the daily clean-up keeps the row of a chosen picture that has no file, and drops other stale rows`() {
        staleRow("chosen-day")
        staleRow("chosen-evening")
        staleRow("other")
        choose(day = "chosen-day", evening = "chosen-evening", maxCacheBytes = 500L * 1024 * 1024)

        val report = repo.runStorageHygieneIfDue(nowEpochMs = 1_800_000_000_000L)

        assertNotNull(report)
        assertNotNull("the daytime row stays so the card can say it is not available", repo.getWallpaperById("chosen-day"))
        assertNotNull(repo.getWallpaperById("chosen-evening"))
        assertNull("an unchosen stale row is dropped", repo.getWallpaperById("other"))
    }
}
