package com.sunloom.app.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.data.testWallpaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article II / III §1 (v0.59): disliking or removing a picture refuses it for good, and a refused picture cannot be a
 * chosen one. Its slot goes back to Automatic; the other slot is not touched.
 */
@Requirement("III.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ChosenClearTest {
    private lateinit var repo: LocalLibraryRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        repo = LocalLibraryRepository(context = context, sourceAdapters = emptyList(), store = InMemoryLibraryStore())
        listOf("a", "b", "c").forEach { repo.upsertWallpaper(testWallpaper(it)) }
        repo.savePreferences(repo.loadPreferences().copy(daytimePickId = "a", eveningPickId = "b"))
    }

    @Test
    fun `disliking the daytime picture sets the daytime back to Automatic and leaves the evening`() {
        repo.rejectWallpaperForever("a")

        assertEquals(null, repo.loadPreferences().daytimePickId)
        assertEquals("b", repo.loadPreferences().eveningPickId)
    }

    @Test
    fun `removing the evening picture sets the evening back to Automatic and leaves the daytime`() {
        repo.deleteWallpaperFromReserve("b")

        assertEquals("a", repo.loadPreferences().daytimePickId)
        assertEquals(null, repo.loadPreferences().eveningPickId)
    }

    @Test
    fun `one picture chosen for both sides clears both`() {
        repo.savePreferences(repo.loadPreferences().copy(daytimePickId = "a", eveningPickId = "a"))

        repo.rejectWallpaperForever("a")

        assertEquals(null, repo.loadPreferences().daytimePickId)
        assertEquals(null, repo.loadPreferences().eveningPickId)
    }

    @Test
    fun `refusing a picture that is not chosen changes no slot`() {
        repo.rejectWallpaperForever("c")

        assertEquals("a", repo.loadPreferences().daytimePickId)
        assertEquals("b", repo.loadPreferences().eveningPickId)
    }

    @Test
    fun `the picture is still refused when its slot is cleared`() {
        repo.rejectWallpaperForever("a")

        assertTrue(repo.getWallpaperById("a")!!.isRejected)
    }
}
