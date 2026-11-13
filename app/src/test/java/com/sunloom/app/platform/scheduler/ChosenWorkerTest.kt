package com.sunloom.app.platform.scheduler

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
import com.sunloom.app.domain.model.WallpaperStatus
import com.sunloom.app.platform.GoogleBookPlatformAdapter
import com.sunloom.app.platform.SystemWallpaperGateway
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article III §1 (v0.59): the change run applies a chosen Daytime or Evening picture when its period starts, leaves it
 * alone inside the period, and falls back to the usual choice when it cannot be shown.
 */
@Requirement("III.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ChosenWorkerTest {
    private lateinit var context: Context
    private lateinit var repo: LocalLibraryRepository
    private val display = DisplayProfile(0, "GoogleBook", 2560, 1600)
    private val zone = ZoneId.of("America/Los_Angeles")
    private val noon = ZonedDateTime.of(LocalDate.of(2026, 11, 4), LocalTime.of(12, 0), zone)
    private val night = ZonedDateTime.of(LocalDate.of(2026, 11, 4), LocalTime.of(22, 0), zone)

    private lateinit var seeds: List<WallpaperRecord>
    private var applies = 0
    private var failApplies = 0 // how many of the next applies the gateway refuses

    private val gateway = object : SystemWallpaperGateway {
        override fun isWallpaperSupported() = true
        override fun isSetWallpaperAllowed() = true
        override fun getConnectedDisplays() = listOf(display)
        override fun applyBitmapToDestination(bitmap: Bitmap, flags: Int): Boolean {
            if (failApplies > 0) { failApplies--; return false }
            applies++
            return true
        }
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
            repo.loadPreferences().copy(isSetupCompleted = true, autoChangeEnabled = true, scheduleMode = ScheduleMode.SUNRISE_SUNSET)
        )
        assertTrue("needs seeds of both kinds", seeds.any { it.evaluation.brightnessClass == BrightnessClass.DARK })
        assertTrue("needs seeds of both kinds", seeds.any { it.evaluation.brightnessClass == BrightnessClass.LIGHT })
    }

    private fun dark() = seeds.first { it.evaluation.brightnessClass == BrightnessClass.DARK }
    private fun light() = seeds.first { it.evaluation.brightnessClass == BrightnessClass.LIGHT }

    private fun choose(day: String? = null, evening: String? = null) =
        repo.savePreferences(repo.loadPreferences().copy(daytimePickId = day, eveningPickId = evening))

    private fun run(at: ZonedDateTime): WallpaperStatus? = runBlocking {
        SunloomWallpaperScheduler.executeScheduledWallpaperChange(
            context, repo, GoogleBookPlatformAdapter(context, gateway), nowZonedOverride = at
        )
    }

    @Test
    fun `at the start of the daytime the chosen picture is applied, whatever its own brightness`() {
        val pick = dark() // a picture the matcher would never offer in the daytime
        choose(day = pick.id)
        repo.setLastTargetSeen(BrightnessClass.DARK)

        assertEquals(WallpaperStatus.APPLIED_SUCCESSFULLY, run(noon))

        assertEquals(pick.id, repo.getCurrentWallpaperId())
        assertEquals(BrightnessClass.LIGHT, repo.getLastTargetSeen())
    }

    @Test
    fun `at the start of the evening the evening picture is applied`() {
        val pick = light()
        choose(evening = pick.id)
        repo.setLastTargetSeen(BrightnessClass.LIGHT)

        run(night)

        assertEquals(pick.id, repo.getCurrentWallpaperId())
        assertEquals(BrightnessClass.DARK, repo.getLastTargetSeen())
    }

    @Test
    fun `a first run with no side remembered applies the chosen picture`() {
        val pick = dark() // not what the matcher would offer at noon
        choose(day = pick.id)

        run(noon)

        assertEquals(pick.id, repo.getCurrentWallpaperId())
    }

    @Test
    fun `inside the period a change run leaves the chosen side alone`() {
        val pick = light()
        val other = seeds.first { it.id != pick.id }
        choose(day = pick.id)
        repo.setLastTargetSeen(BrightnessClass.LIGHT)
        repo.recordWallpaperApplied(other.id) // the person pressed Next, or the system changed it

        val status = run(noon)

        assertNull(status)
        assertEquals("nothing is applied inside the period", 0, applies)
        assertEquals(other.id, repo.getCurrentWallpaperId())
    }

    @Test
    fun `the side without a chosen picture still changes inside its period`() {
        choose(day = light().id) // only the daytime is chosen
        repo.setLastTargetSeen(BrightnessClass.DARK)
        run(noon) // the chosen daytime picture is applied at the flip
        val before = applies

        repo.setLastTargetSeen(BrightnessClass.DARK)
        val status = run(night) // a different side, which is automatic

        assertEquals(WallpaperStatus.APPLIED_SUCCESSFULLY, status)
        assertEquals(before + 1, applies)
        assertNotEquals(light().id, repo.getCurrentWallpaperId())
    }

    @Test
    fun `a chosen picture whose file is gone gives way to the usual choice`() {
        // A fetched picture (a bundled seed is re-rendered on every run, so its file never stays missing).
        val template = light()
        val copy = File(template.localFilePath).copyTo(File(context.cacheDir, "fetched-pick.png"), overwrite = true)
        val pick = template.copy(
            id = "fetched-pick", localFilePath = copy.absolutePath, croppedFilePath = copy.absolutePath,
            provenance = template.provenance.copy(sourceId = "wikimedia_featured", assetId = "fetched-pick")
        )
        repo.upsertWallpaper(pick)
        choose(day = pick.id)
        repo.setLastTargetSeen(BrightnessClass.DARK)
        copy.delete()

        val status = run(noon)

        assertEquals(WallpaperStatus.APPLIED_SUCCESSFULLY, status)
        assertNotEquals(pick.id, repo.getCurrentWallpaperId())
        assertEquals(BrightnessClass.LIGHT, repo.getLastTargetSeen())
    }

    @Test
    fun `a rejected chosen picture is not applied`() {
        val pick = light()
        choose(day = pick.id)
        repo.setLastTargetSeen(BrightnessClass.DARK)
        repo.upsertWallpaper(pick.copy(isRejected = true))

        run(noon)

        assertNotEquals(pick.id, repo.getCurrentWallpaperId())
    }

    @Test
    fun `when the chosen picture cannot be put on screen another is used for this period`() {
        val pick = dark()
        choose(day = pick.id)
        repo.setLastTargetSeen(BrightnessClass.DARK)
        failApplies = 1 // the chosen picture's apply is refused

        val status = run(noon)

        assertEquals(WallpaperStatus.APPLIED_SUCCESSFULLY, status)
        assertNotEquals(pick.id, repo.getCurrentWallpaperId())
        assertEquals("the period is handled, so a failing picture is not retried at every look", BrightnessClass.LIGHT, repo.getLastTargetSeen())
    }

    @Test
    fun `nothing reaching the screen keeps the period open`() {
        choose(day = dark().id)
        repo.setLastTargetSeen(BrightnessClass.DARK)
        failApplies = 10 // every apply is refused

        run(noon)

        assertEquals(BrightnessClass.DARK, repo.getLastTargetSeen())
    }

    @Test
    fun `a Follow-OS poll that finds nothing to do still remembers the side`() {
        repo.savePreferences(repo.loadPreferences().copy(scheduleMode = ScheduleMode.FOLLOW_OS_THEME))
        val suiting = light()
        repo.recordWallpaperApplied(suiting.id)
        repo.setFollowOsVerified(BrightnessClass.LIGHT, suiting.id)
        repo.setLastTargetSeen(BrightnessClass.DARK)

        assertNull(run(noon)) // the light wallpaper already suits the light theme

        assertEquals(BrightnessClass.LIGHT, repo.getLastTargetSeen())
    }

    @Test
    fun `a Follow-OS poll does not skip a chosen picture that is due`() {
        repo.savePreferences(repo.loadPreferences().copy(scheduleMode = ScheduleMode.FOLLOW_OS_THEME))
        val suiting = light()
        val pick = seeds.first { it.id != suiting.id && it.evaluation.brightnessClass == BrightnessClass.LIGHT }
        repo.recordWallpaperApplied(suiting.id)
        repo.setFollowOsVerified(BrightnessClass.LIGHT, suiting.id)
        repo.setLastTargetSeen(BrightnessClass.DARK)
        choose(day = pick.id)

        run(noon)

        assertEquals(pick.id, repo.getCurrentWallpaperId())
    }

    @Test
    fun `without any chosen picture the run behaves as before and remembers the side`() {
        val status = run(noon)

        assertEquals(WallpaperStatus.APPLIED_SUCCESSFULLY, status)
        assertEquals(BrightnessClass.LIGHT, repo.getLastTargetSeen())
    }
}
