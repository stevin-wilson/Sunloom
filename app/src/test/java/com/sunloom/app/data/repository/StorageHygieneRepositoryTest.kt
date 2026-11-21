package com.sunloom.app.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.DisplayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Storage hygiene runs at most once a day, however often the app opens or a worker runs (Article X §1). */
@Requirement("X.3")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class StorageHygieneRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE).edit().remove("last_storage_hygiene_ms").commit()
    }

    @Requirement("X.3.e", "X.3.f")
    @Test
    fun `hygiene runs once, then waits a day`() {
        val repo = LocalLibraryRepository(context)
        val day = 24L * 60 * 60 * 1000
        val t0 = 1_800_000_000_000L

        assertNotNull("the first call runs", repo.runStorageHygieneIfDue(t0))
        assertNull("a second call the same hour does nothing", repo.runStorageHygieneIfDue(t0 + 60 * 60 * 1000))
        assertNull("still within the day", repo.runStorageHygieneIfDue(t0 + day - 1))
        assertEquals(StorageHygieneReport(orphanFilesRemoved = 0, staleRowsRemoved = 0), repo.runStorageHygieneIfDue(t0 + day))
    }

    @Requirement("X.3.f")
    @Test
    fun `the blocklist and the rejection log survive a clean-up, however long ago the picture was refused`() {
        val repo = LocalLibraryRepository(context)
        val display = DisplayProfile(0, "Display", 2560, 1600)
        val picture = repo.ensureSeedReserveLoaded(display).first()
        repo.rejectWallpaperForever(picture.id)
        val blockedBefore = repo.getPermanentBlockedAssetIds()
        val rejectionsBefore = repo.getAllRejections().map { it.assetId }
        assertTrue("the picture is refused for good", picture.id in blockedBefore)

        val longAfter = 1_800_000_000_000L + 400L * 24 * 60 * 60 * 1000 // 400 days on
        assertNotNull("the clean-up is due, so it really runs", repo.runStorageHygieneIfDue(longAfter))

        assertEquals(blockedBefore, repo.getPermanentBlockedAssetIds())
        assertEquals(rejectionsBefore, repo.getAllRejections().map { it.assetId })
    }
}
