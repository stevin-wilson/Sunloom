package com.sunloom.app.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.data.testWallpaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article X §1 (v0.58): the "Change every" countdown starts at the last change of any kind and when the interval is
 * chosen, so choosing 3 hours at 6 pm does not fire a minute later and a flip or a Next press restarts the wait.
 * It is derived state: kept out of the preferences file that Auto Backup restores.
 */
@Requirement("X.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ChangeCountdownTest {
    private lateinit var context: Context
    private lateinit var repo: LocalLibraryRepository
    private lateinit var store: InMemoryLibraryStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("sunloom_state", Context.MODE_PRIVATE).edit().clear().commit()
        store = InMemoryLibraryStore()
        repo = LocalLibraryRepository(context = context, sourceAdapters = emptyList(), store = store)
    }

    @Test
    fun `there is no countdown until something starts it`() {
        assertNull(ChangeCountdown.read(context))
        assertNull(repo.getChangeCountdownStartMs())
    }

    @Test
    fun `starting records the moment, and starting if missing never overwrites it`() {
        ChangeCountdown.start(context, 1_000L)
        assertEquals(1_000L, ChangeCountdown.read(context))

        ChangeCountdown.startIfMissing(context, 9_999L)
        assertEquals("kept", 1_000L, ChangeCountdown.read(context))

        context.getSharedPreferences("sunloom_state", Context.MODE_PRIVATE).edit().clear().commit()
        ChangeCountdown.startIfMissing(context, 9_999L)
        assertEquals(9_999L, ChangeCountdown.read(context))
    }

    @Test
    fun `applying any wallpaper restarts the countdown`() {
        store.upsertWallpaper(testWallpaper("w1"))
        val before = System.currentTimeMillis()

        repo.recordWallpaperApplied("w1")

        assertTrue((repo.getChangeCountdownStartMs() ?: 0L) >= before)
    }

    @Test
    fun `choosing an interval starts the countdown, saving the same choice again does not`() {
        repo.savePreferences(repo.loadPreferences().copy(changeEveryHours = 3))
        val first = repo.getChangeCountdownStartMs()
        assertTrue("started when chosen", first != null)

        ChangeCountdown.start(context, 5L) // pretend time passed
        repo.savePreferences(repo.loadPreferences().copy(changeEveryHours = 3, selectedCityName = "Elsewhere"))
        assertEquals("an unrelated save leaves it alone", 5L, repo.getChangeCountdownStartMs())

        repo.savePreferences(repo.loadPreferences().copy(changeEveryHours = 6))
        assertTrue("a new choice starts it again", (repo.getChangeCountdownStartMs() ?: 0L) > 5L)
    }

    @Test
    fun `the countdown is kept out of the backed-up preferences`() {
        ChangeCountdown.start(context, 1_000L)

        val backedUp = context.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE)
        assertFalse(backedUp.all.keys.any { it.contains("countdown") })
    }
}
