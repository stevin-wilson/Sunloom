package com.sunloom.app.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.data.testWallpaper
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
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
    fun `starting records the moment`() {
        ChangeCountdown.start(context, 1_000L)
        assertEquals(1_000L, ChangeCountdown.read(context))
    }

    @Test
    fun `ensuring a countdown starts a missing one but never moves a running one`() {
        ChangeCountdown.ensureStarted(context, 9_999L)
        assertEquals("started", 9_999L, ChangeCountdown.read(context))

        ChangeCountdown.ensureStarted(context, 20_000L)
        assertEquals("kept", 9_999L, ChangeCountdown.read(context))
    }

    @Test
    fun `a countdown that starts in the future, after the clock was stepped back, starts over now`() {
        ChangeCountdown.start(context, 50_000L)

        ChangeCountdown.ensureStarted(context, 9_999L)

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

    private fun withActiveInterval(): UserPreferences {
        repo.savePreferences(repo.loadPreferences().copy(changeEveryHours = 3))
        ChangeCountdown.start(context, 5L) // pretend time passed
        return repo.loadPreferences()
    }

    @Test
    fun `turning automatic switching back on starts a fresh wait instead of an overdue one`() {
        val active = withActiveInterval()
        repo.savePreferences(active.copy(autoChangeEnabled = false))
        assertEquals("switching off leaves it alone", 5L, repo.getChangeCountdownStartMs())

        repo.savePreferences(active.copy(autoChangeEnabled = true))

        assertTrue((repo.getChangeCountdownStartMs() ?: 0L) > 5L)
    }

    @Test
    fun `leaving the manual schedule starts a fresh wait`() {
        val active = withActiveInterval()
        repo.savePreferences(active.copy(scheduleMode = ScheduleMode.MANUAL_ONLY))
        assertEquals(5L, repo.getChangeCountdownStartMs())

        repo.savePreferences(active.copy(scheduleMode = ScheduleMode.FIXED_CLOCK))

        assertTrue((repo.getChangeCountdownStartMs() ?: 0L) > 5L)
    }

    @Test
    fun `resuming from a pause starts a fresh wait`() {
        val active = withActiveInterval()
        repo.savePreferences(active.copy(isPaused = true))
        assertEquals(5L, repo.getChangeCountdownStartMs())

        repo.savePreferences(active.copy(isPaused = false))

        assertTrue((repo.getChangeCountdownStartMs() ?: 0L) > 5L)
    }

    @Test
    fun `saving with the interval off never starts a countdown`() {
        repo.savePreferences(repo.loadPreferences().copy(autoChangeEnabled = false))
        repo.savePreferences(repo.loadPreferences().copy(autoChangeEnabled = true))

        assertNull(repo.getChangeCountdownStartMs())
    }
}
