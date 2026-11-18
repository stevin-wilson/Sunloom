package com.sunloom.app.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.data.repository.InMemoryLibraryStore
import com.sunloom.app.data.repository.LocalLibraryRepository
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.platform.GoogleBookPlatformAdapter
import com.sunloom.app.platform.SystemWallpaperGateway
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article IX §1: the first launch works offline from the bundled pictures. Finishing first-run setup sets a wallpaper
 * at once, whatever the schedule; it does not wait for the first scheduled change, and it never says it did when the
 * system refused.
 */
@Requirement("IX.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SetupAppliesWallpaperTest {
    private lateinit var context: Context
    private lateinit var repo: LocalLibraryRepository
    private val display = DisplayProfile(0, "GoogleBook", 2560, 1600)
    private var applies = 0
    private var displays = listOf(display)
    private var systemAccepts = true

    private val system = object : SystemWallpaperGateway {
        override fun isWallpaperSupported() = true
        override fun isSetWallpaperAllowed() = true
        override fun getConnectedDisplays() = displays
        override fun applyBitmapToDestination(bitmap: Bitmap, flags: Int): Boolean { applies++; return systemAccepts }
        override fun isUnmeteredNetwork() = false // offline
        override fun isBatterySaverActive() = false
    }

    private fun gateway() = AndroidSunloomGateway(
        context = context,
        repository = repo,
        platformAdapter = GoogleBookPlatformAdapter(context, system)
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("sunloom_state", Context.MODE_PRIVATE).edit().clear().commit()
        repo = LocalLibraryRepository(context = context, sourceAdapters = emptyList(), store = InMemoryLibraryStore())
    }

    private fun finishedSetup(mode: ScheduleMode = ScheduleMode.FOLLOW_OS_THEME, auto: Boolean = true) =
        UserPreferences(scheduleMode = mode, autoChangeEnabled = auto, isSetupCompleted = true)

    @Test
    fun `finishing setup offline sets a wallpaper from the bundled pictures`() = runTest {
        val state = gateway().savePreferences(finishedSetup())

        assertEquals(1, applies)
        assertNotNull("the applied picture is recorded", repo.getCurrentWallpaperId())
        assertTrue(state.isCurrentWallpaperAppliedToSystem)
        assertEquals(repo.getCurrentWallpaperId(), state.currentWallpaper?.id)
    }

    @Test
    fun `finishing setup in manual mode also sets a wallpaper`() = runTest {
        gateway().savePreferences(finishedSetup(auto = false))

        assertEquals(1, applies)
        assertNotNull(repo.getCurrentWallpaperId())
    }

    @Test
    fun `saving again after setup does not set another wallpaper`() = runTest {
        val gateway = gateway()
        gateway.savePreferences(finishedSetup())
        val applied = repo.getCurrentWallpaperId()

        gateway.savePreferences(finishedSetup(mode = ScheduleMode.SUNRISE_SUNSET))
        gateway.savePreferences(finishedSetup().copy(isSetupCompleted = true))

        assertEquals(1, applies)
        assertEquals(applied, repo.getCurrentWallpaperId())
    }

    @Test
    fun `when the system refuses the wallpaper setup says so instead of reporting success`() = runTest {
        systemAccepts = false

        val state = gateway().savePreferences(finishedSetup())

        assertNull("nothing is recorded as applied", repo.getCurrentWallpaperId())
        assertFalse(state.isCurrentWallpaperAppliedToSystem)
        assertTrue("setup itself is still saved", repo.loadPreferences().isSetupCompleted)
    }

    @Test
    fun `with no display known nothing is applied and nothing is recorded`() = runTest {
        displays = emptyList()

        val state = gateway().savePreferences(finishedSetup())

        assertEquals(0, applies)
        assertNull(repo.getCurrentWallpaperId())
        assertFalse(state.isCurrentWallpaperAppliedToSystem)
        assertTrue(repo.loadPreferences().isSetupCompleted)
    }

    @Test
    fun `a preferences change before setup is finished sets nothing`() = runTest {
        gateway().savePreferences(finishedSetup().copy(isSetupCompleted = false))

        assertEquals(0, applies)
        assertNull(repo.getCurrentWallpaperId())
    }
}
