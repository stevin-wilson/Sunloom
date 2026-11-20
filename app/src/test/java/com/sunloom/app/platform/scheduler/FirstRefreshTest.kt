package com.sunloom.app.platform.scheduler

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.sunloom.app.NativeSqliteAssumption
import com.sunloom.app.Requirement
import com.sunloom.app.data.repository.InMemoryLibraryStore
import com.sunloom.app.data.repository.LocalLibraryRepository
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.platform.GoogleBookPlatformAdapter
import com.sunloom.app.platform.SystemWallpaperGateway
import com.sunloom.app.ui.AndroidSunloomGateway
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article X §1 and §2: finishing setup queues one catalog refresh that runs as soon as the network rules allow, so a
 * person who set up offline gets their pictures when they go online, without waiting for the next three-hour run.
 */
@Requirement("X.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class FirstRefreshTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private val display = DisplayProfile(0, "GoogleBook", 2560, 1600)

    private val system = object : SystemWallpaperGateway {
        override fun isWallpaperSupported() = true
        override fun isSetWallpaperAllowed() = true
        override fun getConnectedDisplays() = listOf(display)
        override fun applyBitmapToDestination(bitmap: Bitmap, flags: Int) = true
        override fun isUnmeteredNetwork() = false
        override fun isBatterySaverActive() = false
    }

    @Before
    fun setUp() {
        NativeSqliteAssumption.assumeAvailable()
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("sunloom_state", Context.MODE_PRIVATE).edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        workManager = WorkManager.getInstance(context)
    }

    private fun firstRefresh() = workManager.getWorkInfosForUniqueWork(SunloomWallpaperScheduler.WORK_NAME_FIRST_REFRESH).get()

    @Test
    fun `a first refresh waits for an unmetered network when downloads are Wi-Fi only`() {
        SunloomWallpaperScheduler(context).requestFirstRefresh(wifiOnly = true, deferBatterySaver = true)

        val constraints = firstRefresh().single().constraints
        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresStorageNotLow())
        assertTrue(constraints.requiresBatteryNotLow())
    }

    @Test
    fun `a first refresh needs any network when metered downloads are allowed`() {
        SunloomWallpaperScheduler(context).requestFirstRefresh(wifiOnly = false, deferBatterySaver = false)

        assertEquals(NetworkType.CONNECTED, firstRefresh().single().constraints.requiredNetworkType)
    }

    @Test
    fun `asking twice keeps the first request`() {
        val scheduler = SunloomWallpaperScheduler(context)
        scheduler.requestFirstRefresh(wifiOnly = true, deferBatterySaver = true)
        val first = firstRefresh().single().id

        scheduler.requestFirstRefresh(wifiOnly = true, deferBatterySaver = true)

        assertEquals(first, firstRefresh().single().id)
    }

    @Test
    fun `finishing setup queues the first refresh, and saving again does not`() = runTest {
        val repo = LocalLibraryRepository(context = context, sourceAdapters = emptyList(), store = InMemoryLibraryStore())
        val gateway = AndroidSunloomGateway(context = context, repository = repo, platformAdapter = GoogleBookPlatformAdapter(context, system))
        val setup = UserPreferences(isSetupCompleted = true)

        gateway.savePreferences(setup.copy(isSetupCompleted = false))
        assertTrue("nothing before setup is finished", firstRefresh().isEmpty())

        gateway.savePreferences(setup)
        val queued = firstRefresh().single().id

        gateway.savePreferences(setup.copy(wifiOnlyDownloads = false))
        assertEquals("only the moment setup finishes asks for it", queued, firstRefresh().single().id)
    }
}
