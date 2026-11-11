package com.sunloom.app.platform.scheduler

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.data.repository.InMemoryLibraryStore
import com.sunloom.app.data.repository.LibraryStore
import com.sunloom.app.data.repository.LocalLibraryRepository
import com.sunloom.app.data.testWallpaper
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.model.WallpaperStatus
import com.sunloom.app.platform.GoogleBookPlatformAdapter
import com.sunloom.app.platform.SystemWallpaperGateway
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
 * Article X §1: Follow-OS mode polls every 15 minutes, so a poll that finds nothing to do must cost next to nothing -
 * no library reads, no display probing - and must not undo a choice the person just made (the system default).
 */
@Requirement("X.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class FollowOsPollTest {
    private lateinit var context: Context
    private lateinit var store: CountingStore
    private lateinit var repo: LocalLibraryRepository
    private var displaysAsked = 0

    /** Counts the reads a poll makes of the library. */
    private class CountingStore(private val base: LibraryStore) : LibraryStore by base {
        var reads = 0
        override fun getAllWallpapers(): List<WallpaperRecord> { reads++; return base.getAllWallpapers() }
        override fun getWallpaperById(id: String): WallpaperRecord? { reads++; return base.getWallpaperById(id) }
    }

    private val gateway = object : SystemWallpaperGateway {
        override fun isWallpaperSupported() = true
        override fun isSetWallpaperAllowed() = true
        override fun getConnectedDisplays(): List<com.sunloom.app.domain.model.DisplayProfile> { displaysAsked++; return emptyList() }
        override fun applyBitmapToDestination(bitmap: android.graphics.Bitmap, flags: Int) = true
        override fun isUnmeteredNetwork() = true
        override fun isBatterySaverActive() = false
    }

    private val light = BrightnessClass.LIGHT

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        store = CountingStore(InMemoryLibraryStore())
        repo = LocalLibraryRepository(context = context, sourceAdapters = emptyList(), store = store)
        repo.savePreferences(repo.loadPreferences().copy(isSetupCompleted = true, autoChangeEnabled = true, scheduleMode = ScheduleMode.FOLLOW_OS_THEME))
    }

    private fun setCurrent(id: String?) {
        context.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE).edit().apply {
            if (id == null) remove("current_wallpaper_id") else putString("current_wallpaper_id", id)
        }.commit()
    }

    private fun poll(): WallpaperStatus? = runBlocking {
        SunloomWallpaperScheduler.executeScheduledWallpaperChange(context, repo, GoogleBookPlatformAdapter(context, gateway))
    }

    private fun pollReached(): Boolean { // true when the poll got past the early exit and went looking for displays
        displaysAsked = 0
        val result = poll()
        return displaysAsked > 0 || result == WallpaperStatus.FAILED_TEMPORARILY
    }

    // --- the early exit ---

    @Test
    fun `a poll that finds the theme and the wallpaper unchanged reads nothing`() {
        setCurrent("w1")
        repo.setFollowOsVerified(light, "w1")
        store.reads = 0

        assertNull(poll())

        assertEquals("no library reads", 0, store.reads)
        assertEquals("no display probing", 0, displaysAsked)
    }

    @Test
    fun `a wallpaper picked by hand is checked again even though the theme did not change`() {
        setCurrent("w2")
        repo.setFollowOsVerified(light, "w1") // verified for the previous wallpaper

        assertTrue("the poll goes on to look at the new pick", pollReached())
    }

    @Test
    fun `a theme change is acted on`() {
        setCurrent("w1")
        repo.setFollowOsVerified(BrightnessClass.DARK, "w1") // verified while the theme was dark; it is light now

        assertTrue(pollReached())
    }

    @Test
    fun `a fresh install with nothing verified yet is not skipped`() {
        setCurrent(null)

        assertTrue(pollReached())
    }

    @Test
    fun `a wallpaper that suits the theme is remembered, so the next poll costs nothing`() {
        val file = File(context.cacheDir, "suits.jpg").apply { writeBytes(ByteArray(10)) }
        store.upsertWallpaper(testWallpaper("w1", localFilePath = file.absolutePath, fileSizeBytes = 10, brightness = light))
        setCurrent("w1")

        assertNull("it suits the light theme", poll())
        store.reads = 0
        displaysAsked = 0

        assertNull(poll())
        assertEquals("the second poll read nothing", 0, store.reads)
    }

    // --- a deliberate system default ---

    @Test
    fun `a system default the person chose is not covered up by the next poll`() {
        setCurrent(null)
        repo.markSystemDefaultChosen(light)
        store.reads = 0

        assertNull(poll())

        assertEquals(0, store.reads)
        assertEquals(0, displaysAsked)
    }

    @Test
    fun `but a theme change after that starts Sunloom's wallpapers again`() {
        setCurrent(null)
        repo.markSystemDefaultChosen(BrightnessClass.DARK) // chosen while dark; it is light now

        assertTrue(pollReached())
    }

    @Test
    fun `applying a wallpaper replaces the system default marker`() {
        repo.markSystemDefaultChosen(light)
        store.upsertWallpaper(testWallpaper("w9", localFilePath = "", fileSizeBytes = 0))

        repo.recordWallpaperApplied("w9")

        assertFalse(repo.getFollowOsVerifiedKey()?.endsWith(":@system") == true)
    }
}
