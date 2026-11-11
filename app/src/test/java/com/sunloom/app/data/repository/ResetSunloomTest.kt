package com.sunloom.app.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.data.testWallpaper
import com.sunloom.app.domain.model.BlocklistEntry
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "Reset Sunloom" (Article II §2, VIII §1): the person can take everything the app keeps off the device. After the
 * wipe nothing of theirs is left - library, blocklist, settings, images, staging files - and the app starts again as
 * if freshly installed.
 */
@Requirement("VIII.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ResetSunloomTest {
    private lateinit var context: Context
    private lateinit var store: InMemoryLibraryStore
    private lateinit var repo: LocalLibraryRepository

    private fun prefsFile(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = InMemoryLibraryStore()
        repo = LocalLibraryRepository(context = context, sourceAdapters = emptyList(), store = store)
    }

    private fun populate(): List<File> {
        val image = File(repo.wallpaperDir, "kept.jpg").apply { writeBytes(ByteArray(64)) }
        val staged = File(repo.quarantineDir, "stage_1.png").apply { writeBytes(ByteArray(8)) }
        val exported = File(context.cacheDir, "export-1.jpg").apply { writeBytes(ByteArray(8)) }
        store.upsertWallpaper(testWallpaper("w1", localFilePath = image.absolutePath, fileSizeBytes = 64, isFavorite = true))
        store.addBlocklistEntry(BlocklistEntry("bad-1", "sha", "dhash", "test", "SAFETY", 1L))
        repo.savePreferences(UserPreferences(isSetupCompleted = true, autoChangeEnabled = false, scheduleMode = ScheduleMode.FIXED_CLOCK))
        prefsFile("sunloom_preferences").edit().putString("current_wallpaper_id", "w1").putInt("lost_imports_notice", 3).commit()
        prefsFile("sunloom_display_history").edit().putString("remembered_displays_v1", "[{}]").commit()
        prefsFile("sunloom_ui").edit().putBoolean("reserve_grid_view", true).commit()
        val seedRender = File(context.cacheDir, "seed_renders/1000x625_1000x625/seed-a.png").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(8)) }
        return listOf(image, staged, exported, seedRender)
    }

    @Test
    fun `a reset leaves no library, blocklist, settings or files behind`() = runBlocking {
        val files = populate()
        assertTrue(files.all { it.exists() })

        repo.wipeAllData()

        assertTrue("library rows are gone", store.getAllWallpapers().isEmpty())
        assertTrue("the blocklist is gone", store.getAllBlocklistEntries().isEmpty())
        assertTrue("the rejection log is gone", store.getAllRejections().isEmpty())
        assertTrue("image, staging and export files are gone", files.none { it.exists() })
        for (name in listOf("sunloom_preferences", "sunloom_display_history", "sunloom_ui")) {
            assertTrue("$name is empty", prefsFile(name).all.isEmpty())
        }
        assertNull(repo.getCurrentWallpaperId())
    }

    @Test
    fun `after a reset the app reads as freshly installed`() = runBlocking {
        populate()

        repo.wipeAllData()

        val prefs = repo.loadPreferences()
        assertFalse("first-run setup is shown again", prefs.isSetupCompleted)
        assertEquals(UserPreferences().scheduleMode, prefs.scheduleMode)
        assertEquals(UserPreferences().autoChangeEnabled, prefs.autoChangeEnabled)
        assertEquals(0, repo.takeLostImportsNotice())
    }

    @Test
    fun `the folders stay usable after a reset`() = runBlocking {
        populate()

        repo.wipeAllData()

        assertTrue(repo.wallpaperDir.isDirectory)
        assertTrue(repo.quarantineDir.isDirectory)
        File(repo.wallpaperDir, "new.jpg").writeBytes(ByteArray(4)) // a later download can still be written
    }
}
