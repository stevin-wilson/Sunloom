package com.sunloom.app.platform.scheduler

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The real WorkManager accepts the app's schedule: enabling automation queues the next change (Article X §1). */
@RunWith(AndroidJUnit4::class)
class WorkManagerSmokeTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workManager = WorkManager.getInstance(context)
        SunloomWallpaperScheduler(context).cancelAllScheduledWork()
    }

    @After
    fun tearDown() {
        SunloomWallpaperScheduler(context).cancelAllScheduledWork()
    }

    @Test
    fun ensureScheduledQueuesTheNextChange() {
        val prefs = UserPreferences().copy(
            isSetupCompleted = true,
            autoChangeEnabled = true,
            isPaused = false,
            scheduleMode = ScheduleMode.FIXED_CLOCK
        )
        assertTrue(changeWork().none { !it.state.isFinished })

        SunloomWallpaperScheduler(context).ensureScheduled(prefs)

        assertEquals(WorkInfo.State.ENQUEUED, changeWork().single { !it.state.isFinished }.state)
    }

    private fun changeWork() = workManager.getWorkInfosForUniqueWork(SunloomWallpaperScheduler.WORK_NAME_CHANGE).get()
}
