package com.sunloom.app.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sunloom.app.appContainer
import com.sunloom.app.data.repository.LibraryStoreUnavailableException
import com.sunloom.app.platform.scheduler.ChangeAlarm
import com.sunloom.app.platform.scheduler.SunloomWallpaperScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Event-driven BroadcastReceiver for TIME_SET and TIMEZONE_CHANGED
 * (Articles IX §1 & X §3: Recomputes solar transition schedule off the main thread when
 * system clock or timezone changes; WorkManager's RescheduleReceiver handles BOOT_COMPLETED).
 */
class SunloomScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == ChangeAlarm.ACTION) {
            // The delayed change job was held past its time (Doze, standby bucket): run it now. runChangeNow asks
            // WorkManager's database, so keep it off the main thread.
            val appContext = context.applicationContext
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    SunloomWallpaperScheduler(appContext).runChangeNow()
                } catch (_: Exception) {
                    // Nothing to recover here: the next arm of the schedule replaces this attempt.
                } finally {
                    pending?.finish()
                }
            }
            return
        }
        if (action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED &&
            action != "android.intent.action.TIME_SET"
        ) {
            return
        }
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = appContext.appContainer().newRepository()
                val prefs = repo.loadPreferences()
                SunloomWallpaperScheduler(appContext).syncPeriodicSchedule(
                    autoEnabled = prefs.autoChangeEnabled,
                    isPaused = prefs.isPaused,
                    wifiOnly = prefs.wifiOnlyDownloads,
                    deferBatterySaver = prefs.deferOnBatterySaver,
                    preferences = prefs
                )
            } catch (_: LibraryStoreUnavailableException) {
                SunloomWallpaperScheduler(appContext).suspendForUnavailableStore()
            } catch (_: Exception) {
                // Fail-closed: do not crash broadcast process on transient store error
            } catch (_: VirtualMachineError) {
                // Same for running out of memory: this process was only woken to re-arm a schedule.
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
