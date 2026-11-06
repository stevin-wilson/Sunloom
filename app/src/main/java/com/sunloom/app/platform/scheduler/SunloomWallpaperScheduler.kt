package com.sunloom.app.platform.scheduler

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sunloom.app.data.repository.LibraryStoreUnavailableException
import com.sunloom.app.data.repository.LocalLibraryRepository
import com.sunloom.app.domain.matcher.WallpaperMatcher
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperStatus
import com.sunloom.app.domain.solar.SolarCalculator
import com.sunloom.app.platform.DefaultSystemWallpaperGateway
import com.sunloom.app.platform.GoogleBookPlatformAdapter
import com.sunloom.app.platform.SystemWallpaperGateway
import kotlinx.coroutines.CancellationException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Articles IX §3, X §2 & XIII:
 * Dedicated background scheduler module managing split WorkManager scheduling:
 * - [ChangeWorker]: offline transition worker (never blocked on network or battery-not-low).
 * - [RefreshWorker]: periodic 12-hour online catalog replenishment worker honoring Wi-Fi / Battery Saver rules.
 */
class SunloomWallpaperScheduler(
    private val context: Context,
    private val solarCalculator: SolarCalculator = SolarCalculator()
) {

    fun buildChangeConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()
    }

    fun buildRefreshConstraints(wifiOnly: Boolean, deferBatterySaver: Boolean): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(deferBatterySaver)
            .build()
    }

    fun cancelAllScheduledWork() {
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        val schedulePrefs = context.applicationContext.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE)
        workManager.cancelUniqueWork(WORK_NAME)
        workManager.cancelUniqueWork(WORK_NAME_CHANGE)
        workManager.cancelUniqueWork(WORK_NAME_REFRESH)
        schedulePrefs.edit { remove(PREF_REFRESH_CONSTRAINTS_SIG) }
    }

    fun syncPeriodicSchedule(
        autoEnabled: Boolean,
        isPaused: Boolean,
        wifiOnly: Boolean,
        deferBatterySaver: Boolean,
        preferences: UserPreferences? = null,
        nowZoned: ZonedDateTime? = null,
        rescheduleRefreshWork: Boolean = true
    ) {
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        val schedulePrefs = context.applicationContext.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE)
        val effectivePrefs = preferences ?: try {
            LocalLibraryRepository(context).loadPreferences()
        } catch (_: LibraryStoreUnavailableException) {
            cancelAllScheduledWork()
            return
        } catch (_: Throwable) {
            null
        }
        val isManualMode = effectivePrefs?.scheduleMode == ScheduleMode.MANUAL_ONLY

        if (!autoEnabled || isPaused || isManualMode) {
            cancelAllScheduledWork()
            return
        }

        val zoneId = if (effectivePrefs != null) {
            solarCalculator.resolveZoneId(effectivePrefs)
        } else {
            ZoneId.systemDefault()
        }
        val currentZoned = nowZoned ?: ZonedDateTime.now(zoneId)
        val delayMs = if (effectivePrefs != null) {
            solarCalculator.computeNextTransitionDelayMs(effectivePrefs, currentZoned) ?: TimeUnit.HOURS.toMillis(6)
        } else {
            TimeUnit.HOURS.toMillis(6)
        }

        // 1. ChangeWorker: OneTimeWorkRequest targeted to exact transition delay (offline, no battery-not-low gate)
        val changeRequest = OneTimeWorkRequestBuilder<ChangeWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(buildChangeConstraints())
            .build()

        workManager.enqueueUniqueWork(
            WORK_NAME_CHANGE,
            ExistingWorkPolicy.REPLACE,
            changeRequest
        )

        if (!rescheduleRefreshWork) {
            return
        }

        // 2. RefreshWorker: 12-hour PeriodicWorkRequest for online reserve replenishment
        val constraintsSig = "$wifiOnly:$deferBatterySaver"
        val prevSig = schedulePrefs.getString(PREF_REFRESH_CONSTRAINTS_SIG, null)
        val periodicPolicy = if (prevSig != null && prevSig != constraintsSig) {
            ExistingPeriodicWorkPolicy.UPDATE
        } else {
            ExistingPeriodicWorkPolicy.KEEP
        }
        schedulePrefs.edit { putString(PREF_REFRESH_CONSTRAINTS_SIG, constraintsSig) }

        val refreshRequest = PeriodicWorkRequestBuilder<RefreshWorker>(12, TimeUnit.HOURS)
            .setConstraints(buildRefreshConstraints(wifiOnly, deferBatterySaver))
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME_REFRESH,
            periodicPolicy,
            refreshRequest
        )
    }

    companion object {
        const val WORK_NAME = "sunloom_periodic_schedule_work"
        const val WORK_NAME_CHANGE = "sunloom_change_work"
        const val WORK_NAME_REFRESH = "sunloom_refresh_work"
        private const val PREF_REFRESH_CONSTRAINTS_SIG = "scheduled_refresh_constraints_sig"

        /**
         * Executes the local wallpaper transition logic for [ChangeWorker], walking up to 3
         * ranked fallback candidates when an apply fails, and honestly updating active state
         * only when `WallpaperManager` succeeds.
         */
        suspend fun executeScheduledWallpaperChange(
            appContext: Context,
            repo: LocalLibraryRepository = LocalLibraryRepository(appContext),
            platform: GoogleBookPlatformAdapter = GoogleBookPlatformAdapter(appContext),
            nowZonedOverride: ZonedDateTime? = null
        ): WallpaperStatus? {
            val prefs = repo.loadPreferences()
            if (!prefs.autoChangeEnabled || prefs.isPaused || prefs.scheduleMode == ScheduleMode.MANUAL_ONLY) {
                return null
            }

            val solar = SolarCalculator()
            val isDarkUi = (appContext.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val solarSnapshot = solar.resolveSolarSnapshot(
                preferences = prefs,
                isSystemInDarkTheme = isDarkUi,
                nowZonedOverride = nowZonedOverride
            )
            val nowZoned = solarSnapshot.nowZoned
            val targetBrightness = solarSnapshot.targetBrightness

            val connectedDisplays = platform.getConnectedDisplays()
            val display = platform.getPrimaryOrSharpestDisplayOrNull(connectedDisplays)
                ?: return WallpaperStatus.FAILED_TEMPORARILY

            val activeBefore = repo.getCurrentWallpaperId()
            repo.ensureSeedReserveLoadedForDisplays(connectedDisplays, prefs.displayPolicy)
            repo.revalidateGateVersionIfNeeded(display)
            repo.reconcileReserveForDisplaySet(connectedDisplays, prefs)

            val matcher = WallpaperMatcher()
            val allWallpapers = repo.getAllWallpapers()
            val currentId = repo.getCurrentWallpaperId()
            val rankedCandidates = matcher.rankCandidatesForSelection(
                candidates = allWallpapers,
                targetBrightness = targetBrightness,
                preferences = prefs,
                currentWallpaperId = currentId,
                recentlyUsedIds = setOfNotNull(currentId, repo.getPreviousWallpaperId()),
                maxResults = 3
            )

            val currentGeometryKey = platform.computeDisplayGeometryKey(connectedDisplays, prefs.displayPolicy)
            var finalStatus: WallpaperStatus? = null
            for (candidate in rankedCandidates) {
                val status = platform.applyWallpaperAtomically(
                    record = candidate,
                    policy = prefs.lockWorkspacePolicy,
                    targetDisplay = display,
                    dryRunPreviewOnly = prefs.dryRunPreviewOnly,
                    connectedDisplays = connectedDisplays,
                    displayPolicy = prefs.displayPolicy
                )
                finalStatus = status
                if (status == WallpaperStatus.APPLIED_SUCCESSFULLY) {
                    repo.recordWallpaperApplied(candidate.id)
                    repo.setLastAppliedDisplayGeometryKey(currentGeometryKey)
                    break
                }
                if (status == WallpaperStatus.UNSUPPORTED_BY_SYSTEM) {
                    break
                }
            }

            // If active wallpaper was invalidated during gate re-validation and no replacement applied, restore baseline
            if (finalStatus != WallpaperStatus.APPLIED_SUCCESSFULLY &&
                activeBefore != null &&
                repo.getCurrentWallpaperId() == null
            ) {
                val baseline = allWallpapers.firstOrNull { it.isBaselineRestore && !it.isRejected }
                if (baseline != null) {
                    val baseStatus = platform.applyWallpaperAtomically(
                        record = baseline,
                        policy = prefs.lockWorkspacePolicy,
                        targetDisplay = display,
                        dryRunPreviewOnly = prefs.dryRunPreviewOnly,
                        connectedDisplays = connectedDisplays,
                        displayPolicy = prefs.displayPolicy
                    )
                    if (baseStatus == WallpaperStatus.APPLIED_SUCCESSFULLY) {
                        repo.recordWallpaperApplied(baseline.id)
                        repo.setLastAppliedDisplayGeometryKey(currentGeometryKey)
                    } else {
                        platform.restoreSystemDefaultWallpaper(prefs.dryRunPreviewOnly)
                    }
                } else {
                    platform.restoreSystemDefaultWallpaper(prefs.dryRunPreviewOnly)
                }
            }

            SunloomWallpaperScheduler(appContext).syncPeriodicSchedule(
                autoEnabled = prefs.autoChangeEnabled,
                isPaused = prefs.isPaused,
                wifiOnly = prefs.wifiOnlyDownloads,
                deferBatterySaver = prefs.deferOnBatterySaver,
                preferences = prefs,
                nowZoned = nowZoned,
                rescheduleRefreshWork = false
            )
            return finalStatus
        }

        /** Re-enqueues the next transition after a change attempt exhausted its retries. */
        fun rearmAfterGivingUp(appContext: Context) {
            val prefs = try {
                LocalLibraryRepository(appContext).loadPreferences()
            } catch (_: Throwable) {
                return
            }
            SunloomWallpaperScheduler(appContext).syncPeriodicSchedule(
                autoEnabled = prefs.autoChangeEnabled,
                isPaused = prefs.isPaused,
                wifiOnly = prefs.wifiOnlyDownloads,
                deferBatterySaver = prefs.deferOnBatterySaver,
                preferences = prefs,
                rescheduleRefreshWork = false
            )
        }

        /**
         * Executes background online catalog replenishment when permitted by network/power rules.
         */
        suspend fun executeScheduledCatalogRefresh(
            appContext: Context,
            repo: LocalLibraryRepository = LocalLibraryRepository(appContext),
            platform: GoogleBookPlatformAdapter = GoogleBookPlatformAdapter(appContext),
            gateway: SystemWallpaperGateway = DefaultSystemWallpaperGateway(appContext)
        ): Int {
            val prefs = repo.loadPreferences()
            if (!prefs.autoChangeEnabled || prefs.isPaused) return 0
            val connectedDisplays = platform.getConnectedDisplays()
            val display = platform.getPrimaryOrSharpestDisplayOrNull(connectedDisplays) ?: return 0
            repo.ensureSeedReserveLoadedForDisplays(connectedDisplays, prefs.displayPolicy)

            val canDownload = (!prefs.wifiOnlyDownloads || gateway.isWifiConnected()) &&
                (!prefs.deferOnBatterySaver || !gateway.isBatterySaverActive())
            if (!canDownload) return 0

            return try {
                repo.refreshOnlineCatalog(display, connectedDisplays = connectedDisplays)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                0
            }
        }
    }
}

class ChangeWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = mapChangeWorkResult(
        runAttemptCount = runAttemptCount,
        execute = { SunloomWallpaperScheduler.executeScheduledWallpaperChange(applicationContext) },
        onGiveUp = { SunloomWallpaperScheduler.rearmAfterGivingUp(applicationContext) },
        onStoreUnavailable = { SunloomWallpaperScheduler(applicationContext).cancelAllScheduledWork() }
    )
}

class RefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = mapRefreshWorkResult(
        runAttemptCount = runAttemptCount,
        execute = { SunloomWallpaperScheduler.executeScheduledCatalogRefresh(applicationContext) },
        onStoreUnavailable = { SunloomWallpaperScheduler(applicationContext).cancelAllScheduledWork() }
    )
}

/** Maps a change execution outcome to a WorkManager [ListenableWorker.Result]. */
internal suspend fun mapChangeWorkResult(
    runAttemptCount: Int,
    execute: suspend () -> WallpaperStatus?,
    onGiveUp: () -> Unit = {},
    onStoreUnavailable: () -> Unit
): ListenableWorker.Result {
    return try {
        val status = execute()
        if (status == WallpaperStatus.FAILED_TEMPORARILY) {
            if (runAttemptCount < 3) {
                ListenableWorker.Result.retry()
            } else {
                // Out of retries: re-arm the next transition so automation doesn't silently stop.
                runCatching(onGiveUp)
                ListenableWorker.Result.success()
            }
        } else {
            ListenableWorker.Result.success()
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: LibraryStoreUnavailableException) {
        onStoreUnavailable()
        ListenableWorker.Result.failure()
    } catch (_: Throwable) {
        if (runAttemptCount < 3) {
            ListenableWorker.Result.retry()
        } else {
            runCatching(onGiveUp)
            ListenableWorker.Result.failure()
        }
    }
}

/** Maps a catalog refresh execution outcome to a WorkManager [ListenableWorker.Result]. */
internal suspend fun mapRefreshWorkResult(
    runAttemptCount: Int,
    execute: suspend () -> Int,
    onStoreUnavailable: () -> Unit
): ListenableWorker.Result {
    return try {
        execute()
        ListenableWorker.Result.success()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: LibraryStoreUnavailableException) {
        onStoreUnavailable()
        ListenableWorker.Result.failure()
    } catch (_: Throwable) {
        if (runAttemptCount < 2) ListenableWorker.Result.retry() else ListenableWorker.Result.success()
    }
}
