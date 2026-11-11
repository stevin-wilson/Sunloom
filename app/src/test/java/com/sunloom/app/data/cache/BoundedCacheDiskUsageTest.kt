package com.sunloom.app.data.cache

import com.sunloom.app.Requirement
import com.sunloom.app.data.testWallpaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Article X §3: the cache limit is about bytes on disk, not the sizes a row once recorded. */
@Requirement("X.3")
class BoundedCacheDiskUsageTest {
    private val mb = 1024L * 1024L
    private val manager = BoundedCacheManager(minProtectedPerBrightness = 0)

    private fun rows() = listOf(
        testWallpaper("a", localFilePath = "/x/a.jpg", fileSizeBytes = 1 * mb, retrievedAt = 1),
        testWallpaper("b", localFilePath = "/x/b.jpg", fileSizeBytes = 1 * mb, retrievedAt = 2)
    )

    @Test
    fun `files larger than their recorded size push the cache over its limit`() {
        // Recorded as 1 MB each (2 MB total, under a 3 MB limit) but 2 MB each on disk.
        val report = manager.selectEvictions(rows(), emptySet(), 3 * mb, sizeOf = { 2 * mb })

        assertEquals(4 * mb, report.bytesBefore)
        assertEquals("one file must go to get to 3 MB", 1, report.evictedIds.size)
        assertTrue(report.bytesAfter <= 3 * mb)
    }

    @Test
    fun `files smaller than their recorded size do not cause needless eviction`() {
        // Recorded as 1 MB each (2 MB total, over a 1 MB limit) but only 100 KB on disk.
        val report = manager.selectEvictions(rows(), emptySet(), 1 * mb, sizeOf = { 100 * 1024L })

        assertTrue(report.evictedIds.isEmpty())
    }

    @Test
    fun `without a size function the recorded size is used`() {
        val report = manager.selectEvictions(rows(), emptySet(), 1 * mb)

        assertEquals(2 * mb, report.bytesBefore)
        assertEquals(1, report.evictedIds.size)
    }
}
