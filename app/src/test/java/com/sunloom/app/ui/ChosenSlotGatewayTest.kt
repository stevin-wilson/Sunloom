package com.sunloom.app.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.data.repository.InMemoryLibraryStore
import com.sunloom.app.data.repository.LocalLibraryRepository
import com.sunloom.app.domain.matcher.ChosenSlot
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.platform.GoogleBookPlatformAdapter
import com.sunloom.app.platform.SystemWallpaperGateway
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article III §1 (v0.59): choosing a picture for the daytime or evening saves it, shows it at once when it is the
 * current period's slot, and otherwise keeps it for when that period starts. Clearing a slot changes nothing on screen.
 * The test device is in light mode and follows the system theme, so the daytime is the current period.
 */
@Requirement("III.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ChosenSlotGatewayTest {
    private lateinit var context: Context
    private lateinit var repo: LocalLibraryRepository
    private lateinit var gateway: AndroidSunloomGateway
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
        gateway = AndroidSunloomGateway(context = context, repository = repo, platformAdapter = GoogleBookPlatformAdapter(context, system))
    }

    private fun dark() = seeds.first { it.evaluation.brightnessClass == BrightnessClass.DARK }
    private fun light() = seeds.first { it.evaluation.brightnessClass == BrightnessClass.LIGHT }

    @Test
    fun `choosing the daytime picture in the daytime saves it and shows it at once`() = runTest {
        val pick = dark() // whatever its own brightness, it is the one the person chose

        val state = gateway.setChosenWallpaper(ChosenSlot.DAYTIME, pick.id)

        assertEquals(pick.id, repo.loadPreferences().daytimePickId)
        assertEquals(pick.id, repo.getCurrentWallpaperId())
        assertEquals(pick.id, state.currentWallpaper?.id)
        assertEquals(BrightnessClass.LIGHT, repo.getLastTargetSeen())
        assertEquals(1, applies)
    }

    @Test
    fun `choosing the evening picture in the daytime saves it and changes nothing on screen`() = runTest {
        val before = light()
        repo.recordWallpaperApplied(before.id)

        gateway.setChosenWallpaper(ChosenSlot.EVENING, dark().id)

        assertEquals(dark().id, repo.loadPreferences().eveningPickId)
        assertEquals(before.id, repo.getCurrentWallpaperId())
        assertEquals(0, applies)
    }

    @Test
    fun `choosing the picture that is already on screen applies nothing again but marks the period handled`() = runTest {
        val pick = light()
        repo.recordWallpaperApplied(pick.id)

        gateway.setChosenWallpaper(ChosenSlot.DAYTIME, pick.id)

        assertEquals(0, applies)
        assertEquals(pick.id, repo.loadPreferences().daytimePickId)
        assertEquals(BrightnessClass.LIGHT, repo.getLastTargetSeen())
    }

    @Test
    fun `a picture that cannot be used is not saved as a choice`() = runTest {
        val pick = light()
        repo.upsertWallpaper(pick.copy(isRejected = true))

        gateway.setChosenWallpaper(ChosenSlot.DAYTIME, pick.id)
        gateway.setChosenWallpaper(ChosenSlot.EVENING, "no-such-picture")

        assertEquals(null, repo.loadPreferences().daytimePickId)
        assertEquals(null, repo.loadPreferences().eveningPickId)
        assertEquals(0, applies)
    }

    @Test
    fun `clearing a slot makes it Automatic and leaves the screen as it is`() = runTest {
        gateway.setChosenWallpaper(ChosenSlot.DAYTIME, dark().id)
        val onScreen = repo.getCurrentWallpaperId()
        val appliesBefore = applies

        gateway.setChosenWallpaper(ChosenSlot.DAYTIME, null)

        assertEquals(null, repo.loadPreferences().daytimePickId)
        assertEquals(onScreen, repo.getCurrentWallpaperId())
        assertEquals(appliesBefore, applies)
    }

    @Test
    fun `with automatic changes off the choice is saved and nothing is applied`() = runTest {
        repo.savePreferences(repo.loadPreferences().copy(autoChangeEnabled = false))

        gateway.setChosenWallpaper(ChosenSlot.DAYTIME, dark().id)

        assertEquals(dark().id, repo.loadPreferences().daytimePickId)
        assertEquals(0, applies)
    }

    @Test
    fun `a chosen picture that is too small can still be applied by hand, an unchosen one cannot`() = runTest {
        val file = java.io.File(context.cacheDir, "small-chosen.png")
        Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).also { bmp ->
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        }
        val template = light()
        val small = template.copy(
            id = "small-chosen", localFilePath = file.absolutePath, croppedFilePath = file.absolutePath, fileSizeBytes = file.length(),
            provenance = template.provenance.copy(sourceId = "wikimedia_featured", originalWidth = 400, originalHeight = 300),
            evaluation = template.evaluation.copy(isEligible = false, rejectionReasonCode = "RESOLUTION_BELOW_DISPLAY_TARGET")
        )
        repo.upsertWallpaper(small)

        gateway.applyWallpaper(small.id)
        assertNotEquals("not chosen: refused", small.id, repo.getCurrentWallpaperId())

        repo.savePreferences(repo.loadPreferences().copy(eveningPickId = small.id))
        gateway.applyWallpaper(small.id)
        assertEquals("chosen: shown", small.id, repo.getCurrentWallpaperId())
        assertTrue(applies >= 1)
    }
}
