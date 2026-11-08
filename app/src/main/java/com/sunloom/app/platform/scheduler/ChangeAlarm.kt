package com.sunloom.app.platform.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.sunloom.app.platform.SunloomScheduleReceiver

/**
 * Backstop for the delayed change job. WorkManager treats the delay as a minimum, and Doze or a Rare/Restricted
 * app-standby bucket can hold the job for hours, so a sunset switch could be shown at midnight. An inexact alarm
 * (no permission needed) set just after the transition wakes the app; its receiver runs the change right away,
 * replacing the held job. When WorkManager is on time it re-arms first and replaces this alarm.
 */
internal object ChangeAlarm {
    const val ACTION = "com.sunloom.app.action.CHANGE_ALARM"

    /** Long enough that WorkManager, when it is on time, runs and re-arms before the alarm fires. */
    const val SLACK_MS = 2L * 60L * 1000L

    fun triggerAtMillis(nowMs: Long, delayMs: Long): Long = nowMs + delayMs + SLACK_MS

    fun schedule(context: Context, nowMs: Long = System.currentTimeMillis(), delayMs: Long) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis(nowMs, delayMs), pendingIntent(context))
        }
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        // AlarmManager.cancel removes the alarm but the PendingIntent lives on, which isScheduled would mistake for it.
        runCatching { pendingIntent(context).also { manager.cancel(it) }.cancel() }
    }

    /** Whether the alarm is set (a reboot or force-stop clears it while WorkManager's own job survives). */
    fun isScheduled(context: Context): Boolean = runCatching {
        PendingIntent.getBroadcast(
            context.applicationContext, 0, Intent(context, SunloomScheduleReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) != null
    }.getOrDefault(false)

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, SunloomScheduleReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context.applicationContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
