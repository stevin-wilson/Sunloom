package com.sunloom.app.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.data.repository.InMemoryLibraryStore
import com.sunloom.app.data.repository.LocalLibraryRepository
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.platform.GoogleBookPlatformAdapter
import com.sunloom.app.platform.SystemWallpaperGateway
import com.sunloom.app.platform.scheduler.SunloomWallpaperScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article III §1 and X §1: when the system theme flips while Sunloom follows it, the wallpaper changes once, to the
 * chosen picture when there is one, and the background change that follows the same flip finds nothing left to do.
 * The test device is in light mode, so the flip under test is dark to light.
 */
@Requirement("III.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ThemeFlipGatewayTest {
    private lateinit var context: Context
    private lateinit var repo: LocalLibraryRepository
    private lateinit var gateway: AndroidSunloomGateway
    private lateinit var adapter: GoogleBookPlatformAdapter
    private val display = DisplayProfile(0, "GoogleBook", 2560, 1600)
    private var applies = 0
    private lateinit var seeds: List<WallpaperRecord>

    private val system = object : SystemWallpaperGateway {
        override fun isWallpaperSupported() = true
        override fun isSetWallpaperAllowed() = true
        override fun getConnectedDisplays() = listOf(display)
        override fun applyBitmapToDestination(bitmap: Bitmap, flags: Int): Boolean { applies++; return true }
        override fun isUnmeteredNetwork() = true
        override fun isBatterySaverActive() = false
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("sunloom_state", Context.MODE_PRIVATE).edit().clear().commit()
        repo = LocalLibraryRepository(context = context, sourceAdapters = emptyList(), store = InMemoryLibraryStore())
        seeds = repo.ensureSeedReserveLoaded(display)
        repo.savePreferences(
            repo.loadPreferences().copy(isSetupCompleted = true, autoChangeEnabled = true, scheduleMode = ScheduleMode.FOLLOW_OS_THEME)
        )
        adapter = GoogleBookPlatformAdapter(context, system)
        gateway = AndroidSunloomGateway(context = context, repository = repo, platformAdapter = adapter)
        // The evening was on screen; the device has just turned light.
        repo.recordWallpaperApplied(seeds.first { it.evaluation.brightnessClass == BrightnessClass.DARK }.id)
        repo.setLastTargetSeen(BrightnessClass.DARK)
    }

    private fun light() = seeds.first { it.evaluation.brightnessClass == BrightnessClass.LIGHT }

    private suspend fun backgroundChange() = SunloomWallpaperScheduler.executeScheduledWallpaperChange(context, repo, adapter)

    @Test
    fun `a flip with a chosen daytime picture shows that picture once and nothing else`() = runTest {
        val pin = seeds.last { it.evaluation.brightnessClass == BrightnessClass.DARK }
        repo.savePreferences(repo.loadPreferences().copy(daytimePickId = pin.id))

        gateway.applyThemeChange()
        backgroundChange()

        assertEquals("one apply in all, never another picture first", 1, applies)
        assertEquals(pin.id, repo.getCurrentWallpaperId())
        assertEquals(BrightnessClass.LIGHT, repo.getLastTargetSeen())
    }

    @Test
    fun `a flip with nothing chosen changes the wallpaper once to one that suits the new theme`() = runTest {
        val before = repo.getCurrentWallpaperId()

        val state = gateway.applyThemeChange()
        backgroundChange()

        assertEquals(1, applies)
        assertNotEquals(before, repo.getCurrentWallpaperId())
        assertEquals(BrightnessClass.LIGHT, state.currentWallpaper?.evaluation?.brightnessClass)
    }
}
