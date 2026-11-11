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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article X §1: Follow-OS looks every 15 minutes, so it has no backstop alarm; the other modes keep theirs. Needs the
 * real WorkManager, so it runs where Robolectric's native SQLite exists (CI).
 */
@Requirement("X.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class FollowOsAlarmTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    private fun prefs(mode: ScheduleMode) = UserPreferences().copy(
        isSetupCompleted = true, autoChangeEnabled = true, isPaused = false, scheduleMode = mode
    )

    @Before
    fun setUp() {
        NativeSqliteAssumption.assumeAvailable()
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        workManager = WorkManager.getInstance(context)
        ChangeAlarm.cancel(context)
    }

    private fun changeWork() = workManager.getWorkInfosForUniqueWork(SunloomWallpaperScheduler.WORK_NAME_CHANGE).get()

    @Test
    fun `follow-OS queues the next look but sets no alarm`() {
        SunloomWallpaperScheduler(context).ensureScheduled(prefs(ScheduleMode.FOLLOW_OS_THEME))

        assertEquals(WorkInfo.State.ENQUEUED, changeWork().single().state)
        assertFalse(ChangeAlarm.isScheduled(context))
    }

    @Test
    fun `a fixed clock keeps its alarm`() {
        SunloomWallpaperScheduler(context).ensureScheduled(prefs(ScheduleMode.FIXED_CLOCK))

        assertTrue(ChangeAlarm.isScheduled(context))
    }

    @Test
    fun `opening the app again does not replace a follow-OS job that is already waiting`() {
        val scheduler = SunloomWallpaperScheduler(context)
        scheduler.ensureScheduled(prefs(ScheduleMode.FOLLOW_OS_THEME))
        val first = changeWork().single().id

        scheduler.ensureScheduled(prefs(ScheduleMode.FOLLOW_OS_THEME))

        assertEquals("with no alarm to check, the pending job is still left alone", first, changeWork().single().id)
    }
}
