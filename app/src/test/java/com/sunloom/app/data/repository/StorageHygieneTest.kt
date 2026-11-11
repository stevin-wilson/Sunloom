package com.sunloom.app.data.repository

import com.sunloom.app.Requirement
import com.sunloom.app.data.testWallpaper
import com.sunloom.app.domain.model.SourceIds
import com.sunloom.app.domain.model.WallpaperRecord
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Article X §3: the cache must not leak. Files no row points to are removed (after a grace period, so a download that
 * has just landed is safe), and rows that only record an evicted, long-unseen picture are dropped. The permanent
 * blocklist and rejection log are never touched by either.
 */
@Requirement("X.3")
class StorageHygieneTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val now = 1_800_000_000_000L

    private lateinit var dir: File
    private var rows = mutableListOf<WallpaperRecord>()
    private val deleted = mutableListOf<String>()

    @Before
    fun setUp() {
        dir = folder.newFolder("wallpapers")
    }

    private fun hygiene() = StorageHygiene(dir, records = { rows.toList() }, deleteRow = { deleted += it; rows.removeAll { r -> r.id == it } }, writeLock = Any())

    private fun file(name: String, ageMs: Long, bytes: Int = 100): File =
        File(dir, name).apply { writeBytes(ByteArray(bytes)); setLastModified(now - ageMs) }

    // --- orphan files ---

    @Test
    fun `an old file no row points to is removed and a referenced one is kept`() {
        val kept = file("kept.jpg", 5 * hour)
        val orphan = file("orphan.jpg", 5 * hour)
        rows += testWallpaper("a", localFilePath = kept.absolutePath, fileSizeBytes = 100)

        assertEquals(1, hygiene().sweepOrphanedFiles(now))

        assertTrue(kept.exists())
        assertFalse(orphan.exists())
    }

    @Test
    fun `a file is kept when it is the cropped file of a row`() {
        val cropped = file("crop.jpg", 5 * hour)
        rows += testWallpaper("a", localFilePath = File(dir, "gone.jpg").absolutePath, croppedFilePath = cropped.absolutePath, fileSizeBytes = 100)

        assertEquals(0, hygiene().sweepOrphanedFiles(now))
        assertTrue(cropped.exists())
    }

    @Test
    fun `a recent unreferenced file is kept because its row may be about to be written`() {
        val fresh = file("fresh.jpg", 10 * 60 * 1000)
        rows += testWallpaper("a", localFilePath = file("kept.jpg", 5 * hour).absolutePath, fileSizeBytes = 100)

        assertEquals(0, hygiene().sweepOrphanedFiles(now))
        assertTrue(fresh.exists())
    }

    @Test
    fun `nothing is swept while the library has no rows at all`() {
        // An empty library next to a full folder looks like a lost database, not a folder of orphans.
        val stray = file("stray.jpg", 5 * hour)

        assertEquals(0, hygiene().sweepOrphanedFiles(now))
        assertTrue(stray.exists())
    }

    @Test
    fun `subfolders are left alone`() {
        rows += testWallpaper("a", localFilePath = file("kept.jpg", 5 * hour).absolutePath, fileSizeBytes = 100)
        val sub = File(dir, "sub").apply { mkdirs() }
        File(sub, "inner.jpg").apply { writeBytes(ByteArray(10)); setLastModified(now - 9 * hour) }

        assertEquals(0, hygiene().sweepOrphanedFiles(now))
        assertTrue(File(sub, "inner.jpg").exists())
    }

    // --- stale rows ---

    private fun gone(id: String, ageDays: Long, sourceId: String = "wikimedia_featured", favorite: Boolean = false, baseline: Boolean = false, rejected: Boolean = false, lastAppliedDaysAgo: Long? = null) =
        testWallpaper(
            id, sourceId = sourceId, isFavorite = favorite, isBaselineRestore = baseline, isRejected = rejected,
            retrievedAt = now - ageDays * day,
            lastApplied = lastAppliedDaysAgo?.let { now - it * day } ?: 0L
        )

    @Test
    fun `an evicted picture nobody kept and nobody has seen for ninety days is dropped`() {
        rows += gone("old", ageDays = 120)
        rows += gone("recent", ageDays = 30)

        assertEquals(1, hygiene().compactStaleRows(now, activeIds = emptySet()))

        assertEquals(listOf("old"), deleted)
        assertEquals(listOf("recent"), rows.map { it.id })
    }

    @Test
    fun `a picture shown recently is not dropped even if it was fetched long ago`() {
        rows += gone("seen", ageDays = 400, lastAppliedDaysAgo = 10)

        assertEquals(0, hygiene().compactStaleRows(now, activeIds = emptySet()))
    }

    @Test
    fun `kept, baseline, personal, seed, rejected and active rows are never dropped`() {
        rows += gone("kept", 400, favorite = true)
        rows += gone("base", 400, baseline = true)
        rows += gone("mine", 400, sourceId = SourceIds.USER_LOCAL)
        rows += gone("seed", 400, sourceId = SourceIds.SEED_RESERVE)
        rows += gone("rej", 400, rejected = true)
        rows += gone("live", 400)

        assertEquals(0, hygiene().compactStaleRows(now, activeIds = setOf("live")))
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `a row that still has its file is never dropped`() {
        val present = file("present.jpg", 5 * hour)
        rows += testWallpaper("has-file", localFilePath = present.absolutePath, fileSizeBytes = 100, retrievedAt = now - 400 * day)

        assertEquals(0, hygiene().compactStaleRows(now, activeIds = emptySet()))
    }

    // --- real disk usage ---

    @Test
    fun `disk bytes counts the files that exist, each once`() {
        val shared = file("same.jpg", hour, bytes = 300)
        val other = file("other.jpg", hour, bytes = 50)
        val h = hygiene()

        assertEquals("local and cropped may be the same file", 300L, h.diskBytes(testWallpaper("a", localFilePath = shared.absolutePath, croppedFilePath = shared.absolutePath, fileSizeBytes = 1)))
        assertEquals(350L, h.diskBytes(testWallpaper("b", localFilePath = shared.absolutePath, croppedFilePath = other.absolutePath, fileSizeBytes = 1)))
        assertEquals("a file that is gone counts for nothing", 0L, h.diskBytes(testWallpaper("c", localFilePath = File(dir, "nope.jpg").absolutePath, fileSizeBytes = 999)))
    }
}
