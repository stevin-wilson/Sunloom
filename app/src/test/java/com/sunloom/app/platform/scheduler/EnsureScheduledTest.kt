package com.sunloom.app.platform.scheduler

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.sunloom.app.NativeSqliteAssumption
import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article IX §4 / X §1: automation must come back by itself. Opening the app re-arms the next transition when
 * nothing is pending (after a store error, an Auto Backup restore or a lost WorkManager database), and leaves a
 * pending one alone.
 */
@Requirement("IX.4")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class EnsureScheduledTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    private val prefs = UserPreferences().copy(
        isSetupCompleted = true,
        autoChangeEnabled = true,
        isPaused = false,
        scheduleMode = ScheduleMode.FIXED_CLOCK
    )

    @Before
    fun setUp() {
        NativeSqliteAssumption.assumeAvailable()
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        workManager = WorkManager.getInstance(context)
    }

    private fun changeWork() = workManager.getWorkInfosForUniqueWork(SunloomWallpaperScheduler.WORK_NAME_CHANGE).get()

    @Test
    fun `enqueues the next change when none is pending`() {
        assertTrue(changeWork().isEmpty())
        SunloomWallpaperScheduler(context).ensureScheduled(prefs)
        assertEquals(WorkInfo.State.ENQUEUED, changeWork().single().state)
    }

    @Test
    fun `leaves a pending change alone`() {
        val scheduler = SunloomWallpaperScheduler(context)
        scheduler.ensureScheduled(prefs)
        val first = changeWork().single().id
        scheduler.ensureScheduled(prefs)
        assertEquals("the pending request is kept, not replaced", first, changeWork().single().id)
    }

    @Test
    fun `does nothing when automation is paused`() {
        SunloomWallpaperScheduler(context).ensureScheduled(prefs.copy(isPaused = true))
        assertTrue(changeWork().isEmpty())
    }

    private fun watchWork() = workManager.getWorkInfosForUniqueWork(SunloomWallpaperScheduler.WORK_NAME_STORE_WATCH).get()

    @Test
    fun `suspending for an unavailable store cancels automation and starts the store watch`() {
        val scheduler = SunloomWallpaperScheduler(context)
        scheduler.ensureScheduled(prefs)
        assertEquals(WorkInfo.State.ENQUEUED, changeWork().single().state)

        scheduler.suspendForUnavailableStore()

        assertTrue("automation is suspended", changeWork().all { it.state == WorkInfo.State.CANCELLED })
        assertEquals(WorkInfo.State.ENQUEUED, watchWork().single().state)
    }

    @Test
    fun `re-arming automation stops the store watch`() {
        val scheduler = SunloomWallpaperScheduler(context)
        scheduler.suspendForUnavailableStore()
        scheduler.ensureScheduled(prefs)
        assertTrue(watchWork().all { it.state == WorkInfo.State.CANCELLED })
        assertEquals(WorkInfo.State.ENQUEUED, changeWork().last().state)
    }

    @Test
    fun `suspending again does not restart the store watch's interval`() {
        val scheduler = SunloomWallpaperScheduler(context)
        scheduler.suspendForUnavailableStore()
        val first = watchWork().single().id
        scheduler.suspendForUnavailableStore()
        assertEquals("the pending watch is kept, so repeated calls cannot postpone it forever", first, watchWork().single().id)
    }
}
