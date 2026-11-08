package com.sunloom.app.platform.scheduler

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Article X §1: a wallpaper change is a WorkManager job with a delay, and the system may hold such a job for hours
 * (Doze, app-standby buckets). The inexact alarm set just after the transition wakes the app so the change still
 * happens about on time. It needs no permission (Article XVIII §2).
 */
@Requirement("X.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ChangeAlarmTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val alarms get() = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @Test
    fun `the alarm goes off a little after the work is due, so WorkManager normally wins`() {
        assertEquals(1_000L + 5_000L + ChangeAlarm.SLACK_MS, ChangeAlarm.triggerAtMillis(nowMs = 1_000L, delayMs = 5_000L))
    }

    @Test
    fun `scheduling sets one alarm and re-arming replaces it`() {
        ChangeAlarm.schedule(context, nowMs = 0L, delayMs = 60_000L)
        ChangeAlarm.schedule(context, nowMs = 0L, delayMs = 120_000L)
        assertEquals("re-arming replaces, not adds", 1, alarms.scheduledAlarms.size)
        val next = alarms.nextScheduledAlarm // note: this removes the alarm it returns
        assertNotNull(next)
        assertEquals(120_000L + ChangeAlarm.SLACK_MS, next!!.triggerAtMs)
    }

    @Test
    fun `cancelling removes the alarm`() {
        ChangeAlarm.schedule(context, nowMs = 0L, delayMs = 60_000L)
        ChangeAlarm.cancel(context)
        assertNull(alarms.nextScheduledAlarm)
    }
}
