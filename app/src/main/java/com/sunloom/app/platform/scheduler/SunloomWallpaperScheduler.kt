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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sunloom.app.appContainer
import com.sunloom.app.data.repository.ChangeCountdown
import com.sunloom.app.data.repository.LibraryStoreUnavailableException
import com.sunloom.app.data.repository.LocalLibraryRepository
import com.sunloom.app.domain.matcher.BrightnessFit
import com.sunloom.app.domain.matcher.ChosenWallpaper
import com.sunloom.app.domain.matcher.SubjectFit
import com.sunloom.app.domain.matcher.TasteProfile
import com.sunloom.app.domain.matcher.WallpaperMatcher
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperStatus
import com.sunloom.app.domain.solar.SolarCalculator
import com.sunloom.app.platform.DefaultSystemWallpaperGateway
import com.sunloom.app.platform.GoogleBookPlatformAdapter
import com.sunloom.app.platform.SystemWallpaperGateway
import kotlinx.coroutines.CancellationException
import java.time.ZoneId
import java.io.File
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Articles IX §3, X §2 & XIII:
 * Dedicated background scheduler module managing split WorkManager scheduling:
 * - [ChangeWorker]: offline transition worker (never blocked on network or battery-not-low).
 * - [RefreshWorker]: periodic (every 3 hours) online catalog replenishment worker honoring Wi-Fi / Battery Saver rules.
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
            // A download into a nearly full disk only makes things worse; wait until there is room.
            .setRequiresStorageNotLow(true)
            .build()
    }

    /**
     * Cancels everything, then waits (up to [timeoutMs]) for an attempt that is already running to stop: cancelling the
     * queue does not stop it, and it holds its own repository and could still write. Reset needs this before it erases.
     * Returns false if something was still running at the timeout.
     */
    fun cancelAllScheduledWorkAndWait(timeoutMs: Long = 5_000L): Boolean {
        cancelAllScheduledWork()
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return true
        val names = listOf(WORK_NAME, WORK_NAME_CHANGE, WORK_NAME_REFRESH, WORK_NAME_STORE_WATCH)
        return WorkIdle.awaitIdle(
            isBusy = { names.any { name -> workManager.getWorkInfosForUniqueWork(name).get().any { it.state == WorkInfo.State.RUNNING } } },
            timeoutMs = timeoutMs
        )
    }

    fun cancelAllScheduledWork() {
        cancelAutomationWork()
        runCatching { WorkManager.getInstance(context) }.getOrNull()?.cancelUniqueWork(WORK_NAME_STORE_WATCH)
    }

    /** Cancels the change, refresh and alarm work, leaving any store watch as it is. */
    private fun cancelAutomationWork() {
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        val schedulePrefs = context.applicationContext.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE)
        workManager.cancelUniqueWork(WORK_NAME)
        workManager.cancelUniqueWork(WORK_NAME_CHANGE)
        workManager.cancelUniqueWork(WORK_NAME_REFRESH)
        ChangeAlarm.cancel(context)
        schedulePrefs.edit { remove(PREF_REFRESH_CONSTRAINTS_SIG) }
    }

    /**
     * Automation is suspended because the library store will not open (Article VII §6). Rather than staying off until
     * the app is next opened, a light job checks every few hours and resumes automation by itself once the store
     * opens. It touches no data while the store is down.
     */
    fun suspendForUnavailableStore() {
        cancelAutomationWork() // not the watch: cancelling and re-enqueueing it would restart its interval every time
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME_STORE_WATCH,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<StoreWatchWorker>(RefreshSchedulePolicy.STORE_WATCH_INTERVAL_HOURS, TimeUnit.HOURS).build()
        )
    }

    /**
     * Re-arms automation when nothing is pending, and leaves a pending request alone. Called when the app opens, so a
     * schedule lost to a store error, an Auto Backup restore (WorkManager's own database is not restored) or a
     * force-stop comes back without the person having to edit a setting.
     */
    fun ensureScheduled(preferences: UserPreferences) {
        ensureAutomationScheduled(preferences)
        // The preferences were read from an open store and automation is re-armed, so the watch has done its job.
        // Last, so a watch running this very call is not cancelled halfway through the re-arm.
        stopStoreWatch()
    }

    private fun stopStoreWatch() {
        runCatching { WorkManager.getInstance(context) }.getOrNull()?.cancelUniqueWork(WORK_NAME_STORE_WATCH)
    }

    private fun ensureAutomationScheduled(preferences: UserPreferences) {
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        val plan = RefreshSchedulePolicy.plan(
            preferences.autoChangeEnabled,
            preferences.isPaused,
            preferences.scheduleMode == ScheduleMode.MANUAL_ONLY
        )
        // If WorkManager cannot be queried, assume something is pending: replacing a valid request is worse than waiting.
        val pending = runCatching {
            workManager.getWorkInfosForUniqueWork(WORK_NAME_CHANGE).get().any { !it.state.isFinished }
        }.getOrDefault(true)
        val alarmInPlace = !RefreshSchedulePolicy.usesBackstopAlarm(preferences.scheduleMode) || ChangeAlarm.isScheduled(context)
        if (plan.scheduleChanges && pending && alarmInPlace) {
            // The change is armed; the periodic refresh uses KEEP, so re-syncing it is harmless and restores it too.
            syncRefreshOnly(preferences)
            return
        }
        syncPeriodicSchedule(
            autoEnabled = preferences.autoChangeEnabled,
            isPaused = preferences.isPaused,
            wifiOnly = preferences.wifiOnlyDownloads,
            deferBatterySaver = preferences.deferOnBatterySaver,
            preferences = preferences
        )
    }

    private fun syncRefreshOnly(preferences: UserPreferences) {
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        val schedulePrefs = context.applicationContext.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE)
        scheduleRefreshWork(workManager, schedulePrefs, preferences.wifiOnlyDownloads, preferences.deferOnBatterySaver, true)
    }

    /** Runs the change now in place of the delayed job that the system is holding (see [ChangeAlarm]). */
    fun runChangeNow() {
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        val running = runCatching {
            workManager.getWorkInfosForUniqueWork(WORK_NAME_CHANGE).get().any { it.state == WorkInfo.State.RUNNING }
        }.getOrDefault(false)
        if (!RefreshSchedulePolicy.shouldReplaceChangeOnAlarm(running)) return
        workManager.enqueueUniqueWork(
            WORK_NAME_CHANGE,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ChangeWorker>().setConstraints(buildChangeConstraints()).build()
        )
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
            context.appContainer().newRepository().loadPreferences()
        } catch (_: LibraryStoreUnavailableException) {
            suspendForUnavailableStore()
            return
        } catch (_: Exception) {
            null
        }
        // Only a store that was read (or preferences the caller just read) shows it is open; an unknown state keeps the watch.
        if (effectivePrefs != null) workManager.cancelUniqueWork(WORK_NAME_STORE_WATCH)
        val isManualMode = effectivePrefs?.scheduleMode == ScheduleMode.MANUAL_ONLY

        // Changing the wallpaper follows the schedule; fetching new images does not (Article X §1, v0.14).
        val plan = RefreshSchedulePolicy.plan(autoEnabled, isPaused, isManualMode)
        if (!plan.scheduleRefresh) {
            cancelAllScheduledWork()
            return
        }
        if (!plan.scheduleChanges) {
            workManager.cancelUniqueWork(WORK_NAME)
            workManager.cancelUniqueWork(WORK_NAME_CHANGE)
            ChangeAlarm.cancel(context)
            scheduleRefreshWork(workManager, schedulePrefs, wifiOnly, deferBatterySaver, rescheduleRefreshWork)
            return
        }

        val zoneId = if (effectivePrefs != null) {
            solarCalculator.resolveZoneId(effectivePrefs)
        } else {
            ZoneId.systemDefault()
        }
        val currentZoned = nowZoned ?: ZonedDateTime.now(zoneId)
        // An interval restored from a backup has no countdown on this device yet: start one rather than wait forever.
        if (effectivePrefs?.changeEveryActive == true) {
            ChangeCountdown.ensureStarted(context, currentZoned.toInstant().toEpochMilli())
        }
        val delayMs = armedDelayMs(solarCalculator, effectivePrefs, currentZoned, ChangeCountdown.read(context))

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
        if (RefreshSchedulePolicy.usesBackstopAlarm(effectivePrefs?.scheduleMode)) {
            ChangeAlarm.schedule(context, delayMs = delayMs)
        } else {
            ChangeAlarm.cancel(context) // Follow-OS looks every few minutes; an alarm would only add wake-ups
        }

        scheduleRefreshWork(workManager, schedulePrefs, wifiOnly, deferBatterySaver, rescheduleRefreshWork)
    }

    /** RefreshWorker: periodic online reserve replenishment, independent of wallpaper-change scheduling. */
    private fun scheduleRefreshWork(
        workManager: WorkManager,
        schedulePrefs: android.content.SharedPreferences,
        wifiOnly: Boolean,
        deferBatterySaver: Boolean,
        rescheduleRefreshWork: Boolean
    ) {
        if (!rescheduleRefreshWork) {
            return
        }
        val constraintsSig = refreshConstraintsSignature(wifiOnly, deferBatterySaver)
        val prevSig = schedulePrefs.getString(PREF_REFRESH_CONSTRAINTS_SIG, null)
        // A changed constraint set or cadence (the signature includes the interval) replaces the enqueued request.
        val periodicPolicy = if (prevSig != null && prevSig != constraintsSig) {
            ExistingPeriodicWorkPolicy.UPDATE
        } else {
            ExistingPeriodicWorkPolicy.KEEP
        }
        schedulePrefs.edit { putString(PREF_REFRESH_CONSTRAINTS_SIG, constraintsSig) }

        val refreshRequest = PeriodicWorkRequestBuilder<RefreshWorker>(RefreshSchedulePolicy.REFRESH_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(buildRefreshConstraints(wifiOnly, deferBatterySaver))
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME_REFRESH,
            periodicPolicy,
            refreshRequest
        )
    }

    companion object {
        /**
         * How long to wait before the next change: the schedule's own wait (its next transition), brought forward to meet
         * the "Change every" interval when that is sooner (Article X §1, v0.58).
         */
        internal fun armedDelayMs(
            solar: SolarCalculator,
            preferences: UserPreferences?,
            nowZoned: ZonedDateTime,
            countdownStartMs: Long?
        ): Long {
            val mode = preferences?.scheduleMode
            return RefreshSchedulePolicy.changeDelayMs(
                mode = mode,
                transitionDelayMs = preferences?.let { solar.computeNextTransitionDelayMs(it, nowZoned) },
                changeEveryHours = preferences?.changeEveryHours ?: 0,
                scheduleChanges = preferences?.changesBySchedule ?: false,
                countdownStartMs = countdownStartMs,
                nowMs = nowZoned.toInstant().toEpochMilli()
            )
        }

        const val WORK_NAME = "sunloom_periodic_schedule_work"
        const val WORK_NAME_CHANGE = "sunloom_change_work"
        const val WORK_NAME_REFRESH = "sunloom_refresh_work"
        const val WORK_NAME_STORE_WATCH = "sunloom_store_watch_work"
        private const val PREF_REFRESH_CONSTRAINTS_SIG = "scheduled_refresh_constraints_sig"

        /**
         * Identifies the refresh job's constraints and cadence. A different signature replaces the queued request, so
         * adding the storage need (":storage") reaches installs that queued the job without it.
         */
        internal fun refreshConstraintsSignature(wifiOnly: Boolean, deferBatterySaver: Boolean): String =
            "$wifiOnly:$deferBatterySaver:storage:${RefreshSchedulePolicy.REFRESH_INTERVAL_HOURS}h"

        /**
         * Executes the local wallpaper transition logic for [ChangeWorker], walking up to 3
         * ranked fallback candidates when an apply fails, and honestly updating active state
         * only when `WallpaperManager` succeeds.
         */
        suspend fun executeScheduledWallpaperChange(
            appContext: Context,
            repo: LocalLibraryRepository = appContext.appContainer().newRepository(),
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

            // Follow-OS mode polls every few minutes. When the wallpaper on screen already suits the theme there is
            // nothing to do, and the library pass below (healing, revalidation, reconcile) would only cost battery.
            val nowMs = nowZoned.toInstant().toEpochMilli()
            // A due "Change every" interval makes a Follow-OS poll look further than the theme alone would.
            if (prefs.changeEveryActive) ChangeCountdown.ensureStarted(appContext, nowMs) // repairs a start the clock outran
            val intervalDue = RefreshSchedulePolicy.isIntervalDue(
                prefs.changeEveryHours, prefs.changesBySchedule, repo.getChangeCountdownStartMs(), nowMs
            )
            // A chosen Daytime or Evening picture is applied when its period starts and left alone inside it.
            val choice = ChosenWallpaper.choose(
                prefs, targetBrightness, repo.getLastTargetSeen(), repo::getWallpaperById
            ) { path -> File(path).exists() }
            if (prefs.scheduleMode == ScheduleMode.FOLLOW_OS_THEME) {
                val currentId = repo.getCurrentWallpaperId()
                // The theme and the wallpaper are exactly what the last poll verified (or the person chose the system
                // default for this theme): nothing to read, nothing to do.
                val verified = repo.getFollowOsVerifiedKey()
                val chosenDue = choice is ChosenWallpaper.Choice.Chosen
                if (!chosenDue &&
                    RefreshSchedulePolicy.canSkipFollowOsPoll(prefs.scheduleMode, verified, targetBrightness, currentId, intervalDue)) {
                    repo.setLastTargetSeen(targetBrightness) // the period passed without a change; the next flip is a new one
                    rearmChange(appContext, prefs, nowZoned)
                    return null
                }
                val current = currentId?.let { repo.getWallpaperById(it) }
                val suits = current?.takeIf { it.localFilePath.isNotBlank() && File(it.localFilePath).exists() }
                    ?.let { BrightnessFit.accepts(it.evaluation, targetBrightness) }
                if (RefreshSchedulePolicy.isRedundantFollowOsChange(
                        prefs.scheduleMode, suits, intervalDue, chosenDue = chosenDue
                    )) {
                    repo.setFollowOsVerified(targetBrightness, currentId)
                    repo.setLastTargetSeen(targetBrightness)
                    rearmChange(appContext, prefs, nowZoned)
                    return null
                }
            }

            // This run goes on to change the wallpaper, so the "Change every" wait starts over now, whatever the outcome:
            // a change that keeps failing must not be retried every minute because its interval is still overdue.
            if (prefs.changeEveryActive) ChangeCountdown.start(appContext, nowMs)

            // Inside the period of a usable chosen picture nothing replaces it, and the interval does not rotate it.
            if (choice == ChosenWallpaper.Choice.LeaveAlone) {
                rearmChange(appContext, prefs, nowZoned)
                return null
            }

            val connectedDisplays = platform.getConnectedDisplays()
            val display = platform.getPrimaryOrSharpestDisplayOrNull(connectedDisplays)
                ?: return WallpaperStatus.FAILED_TEMPORARILY

            val activeBefore = repo.getCurrentWallpaperId()
            repo.healMissingFiles()
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
                maxResults = 3,
                taste = TasteProfile.from(allWallpapers),
                subjectFit = SubjectFit.forDisplays(connectedDisplays, prefs)
            )

            val currentGeometryKey = platform.computeDisplayGeometryKey(connectedDisplays, prefs.displayPolicy)
            // The chosen picture goes first; if it cannot be shown, the usual candidates follow for this one change.
            val chosenFirst = (choice as? ChosenWallpaper.Choice.Chosen)?.record
            val finalStatus = applyFirstThatWorks(
                attempts = listOfNotNull(chosenFirst) + rankedCandidates.filter { it.id != chosenFirst?.id },
                chosenId = chosenFirst?.id, repo = repo, platform = platform, prefs = prefs,
                connectedDisplays = connectedDisplays, display = display
            )
            if (finalStatus == WallpaperStatus.APPLIED_SUCCESSFULLY) repo.setLastAppliedDisplayGeometryKey(currentGeometryKey)

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
                        displayPolicy = prefs.displayPolicy,
                    onApplied = { repo.recordWallpaperApplied(baseline.id) }
                    )
                    if (baseStatus == WallpaperStatus.APPLIED_SUCCESSFULLY) {
                        repo.setLastAppliedDisplayGeometryKey(currentGeometryKey)
                    } else {
                        platform.restoreSystemDefaultWallpaper(prefs.dryRunPreviewOnly)
                    }
                } else {
                    platform.restoreSystemDefaultWallpaper(prefs.dryRunPreviewOnly)
                }
            }

            if (ChosenWallpaper.shouldRememberTarget(choice, finalStatus)) repo.setLastTargetSeen(targetBrightness)

            if (RefreshSchedulePolicy.shouldRearmAfterChange(finalStatus)) {
                rearmChange(appContext, prefs, nowZoned)
            }
            return finalStatus
        }

        /** Tries [attempts] in order until one is on screen; a system that refuses wallpapers ends the walk at once. */
        private fun applyFirstThatWorks(
            attempts: List<WallpaperRecord>,
            chosenId: String?,
            repo: LocalLibraryRepository,
            platform: GoogleBookPlatformAdapter,
            prefs: UserPreferences,
            connectedDisplays: List<DisplayProfile>,
            display: DisplayProfile
        ): WallpaperStatus? {
            var status: WallpaperStatus? = null
            for (candidate in attempts) {
                status = platform.applyWallpaperAtomically(
                    record = candidate,
                    policy = prefs.lockWorkspacePolicy,
                    targetDisplay = display,
                    dryRunPreviewOnly = prefs.dryRunPreviewOnly,
                    connectedDisplays = connectedDisplays,
                    displayPolicy = prefs.displayPolicy,
                    chosen = candidate.id == chosenId,
                    onApplied = { repo.recordWallpaperApplied(candidate.id) }
                )
                if (status == WallpaperStatus.APPLIED_SUCCESSFULLY) return status
                if (status == WallpaperStatus.UNSUPPORTED_BY_SYSTEM) break
            }
            return status
        }

        private fun rearmChange(appContext: Context, prefs: UserPreferences, nowZoned: ZonedDateTime) {
            SunloomWallpaperScheduler(appContext).syncPeriodicSchedule(
                autoEnabled = prefs.autoChangeEnabled,
                isPaused = prefs.isPaused,
                wifiOnly = prefs.wifiOnlyDownloads,
                deferBatterySaver = prefs.deferOnBatterySaver,
                preferences = prefs,
                nowZoned = nowZoned,
                rescheduleRefreshWork = false
            )
        }

        /** Re-enqueues the next transition after a change attempt exhausted its retries. */
        fun rearmAfterGivingUp(appContext: Context) {
            val prefs = try {
                appContext.appContainer().newRepository().loadPreferences()
            } catch (_: Exception) {
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
            repo: LocalLibraryRepository = appContext.appContainer().newRepository(),
            platform: GoogleBookPlatformAdapter = GoogleBookPlatformAdapter(appContext),
            gateway: SystemWallpaperGateway = DefaultSystemWallpaperGateway(appContext)
        ): Int {
            val prefs = repo.loadPreferences()
            // Fetching is independent of automatic wallpaper changes: manual-mode users want fresh images too.
            if (prefs.isPaused) return 0
            // The cheap checks first: a run that cannot download anyway touches nothing (no probing, no file scans).
            val budget = BackgroundDataBudget(appContext.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE))
            fun blocked() = BackgroundDownloadPolicy.blockedBy(
                unmeteredOnly = prefs.wifiOnlyDownloads,
                deferOnBatterySaver = prefs.deferOnBatterySaver,
                unmeteredNetwork = gateway.isUnmeteredNetwork(),
                dataSaverRestricting = gateway.isDataSaverRestricting(),
                batterySaverActive = gateway.isBatterySaverActive(),
                bytesUsedToday = budget.usedToday(System.currentTimeMillis())
            )
            if (blocked() != DownloadBlock.NONE) return 0
            val connectedDisplays = platform.getConnectedDisplays()
            val display = platform.getPrimaryOrSharpestDisplayOrNull(connectedDisplays) ?: return 0
            repo.healMissingFiles()
            repo.runStorageHygieneIfDue()
            repo.ensureSeedReserveLoadedForDisplays(connectedDisplays, prefs.displayPolicy)
            // A reserve that is already full needs no request at all; checked before the first round, not after it.
            if (!repo.needsTopUp(prefs)) return 0

            // One round polls at most three sources within their request budgets, so keep going while images
            // arrive and the buffer is still short (a few rounds at most).
            var total = 0
            return try {
                repeat(RefreshSchedulePolicy.MAX_BACKGROUND_ROUNDS) {
                    val added = try {
                        repo.refreshOnlineCatalog(display, connectedDisplays = connectedDisplays)
                    } finally {
                        // What was downloaded counts toward today's budget even if the round was cut short.
                        budget.record(repo.lastSyncBytesDownloaded, System.currentTimeMillis())
                    }
                    total += added
                    // Another round only while it is still wanted and the day's budget has room.
                    if (added <= 0 || !repo.needsTopUp(prefs) || blocked() != DownloadBlock.NONE) return total
                }
                total
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                total
            }
        }
    }
}

/**
 * Folds the database's write-ahead log into the main file at the end of a background run. Auto Backup copies
 * `sunloom_library.db` but not the `-wal` file, and a run can finish while the app is closed, so without this a
 * backup taken afterwards would miss the run's last writes (blocklist and rejection rows especially).
 */
internal fun checkpointAfterRun() {
    // No repository is built for this: a poll that wrote nothing must stay cheap, and a store that is down stays untouched.
    com.sunloom.app.data.repository.SqliteLibraryStore.checkpointSharedIfWritten()
}

class ChangeWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = mapChangeWorkResult(
        runAttemptCount = runAttemptCount,
        execute = { SunloomWallpaperScheduler.executeScheduledWallpaperChange(applicationContext) },
        onGiveUp = { SunloomWallpaperScheduler.rearmAfterGivingUp(applicationContext) },
        afterRun = { checkpointAfterRun() },
        onStoreUnavailable = { SunloomWallpaperScheduler(applicationContext).suspendForUnavailableStore() }
    )
}

/** Checks whether the library store opens again after automation was suspended, and resumes automation if so. */
class StoreWatchWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = mapStoreWatchResult(
        opens = { applicationContext.appContainer().newRepository().loadPreferences() },
        onRecovered = { prefs -> SunloomWallpaperScheduler(applicationContext).ensureScheduled(prefs) }
    )
}

class RefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = mapRefreshWorkResult(
        runAttemptCount = runAttemptCount,
        execute = { SunloomWallpaperScheduler.executeScheduledCatalogRefresh(applicationContext) },
        afterRun = { checkpointAfterRun() },
        onStoreUnavailable = { SunloomWallpaperScheduler(applicationContext).suspendForUnavailableStore() }
    )
}

/**
 * Maps a change execution outcome to a WorkManager [ListenableWorker.Result]. [afterRun] runs once the attempt ends,
 * whatever its outcome (see [checkpointAfterRun]); it cannot change the result.
 */
internal suspend fun mapChangeWorkResult(
    runAttemptCount: Int,
    execute: suspend () -> WallpaperStatus?,
    onGiveUp: () -> Unit = {},
    afterRun: () -> Unit = {},
    onStoreUnavailable: () -> Unit
): ListenableWorker.Result = try {
    mapChangeOutcome(runAttemptCount, execute, onGiveUp, onStoreUnavailable)
} finally {
    runCatching(afterRun)
}

private suspend fun mapChangeOutcome(
    runAttemptCount: Int,
    execute: suspend () -> WallpaperStatus?,
    onGiveUp: () -> Unit,
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
        retryThenSuspend(runAttemptCount, onStoreUnavailable)
    } catch (_: Exception) {
        retryOrGiveUp(runAttemptCount, onGiveUp)
    } catch (_: VirtualMachineError) {
        // Out of memory while decoding or rendering is a failed attempt like any other; it must reach the
        // retry and re-arm logic or the schedule would silently stop.
        retryOrGiveUp(runAttemptCount, onGiveUp)
    }
}

/** Maps a catalog refresh execution outcome to a WorkManager [ListenableWorker.Result]; see [mapChangeWorkResult]. */
internal suspend fun mapRefreshWorkResult(
    runAttemptCount: Int,
    execute: suspend () -> Int,
    afterRun: () -> Unit = {},
    onStoreUnavailable: () -> Unit
): ListenableWorker.Result = try {
    mapRefreshOutcome(runAttemptCount, execute, onStoreUnavailable)
} finally {
    runCatching(afterRun)
}

private suspend fun mapRefreshOutcome(
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
        retryThenSuspend(runAttemptCount, onStoreUnavailable)
    } catch (_: Exception) {
        retryOrSettle(runAttemptCount)
    } catch (_: VirtualMachineError) {
        retryOrSettle(runAttemptCount)
    }
}

private fun retryOrGiveUp(runAttemptCount: Int, onGiveUp: () -> Unit): ListenableWorker.Result =
    if (runAttemptCount < 3) {
        ListenableWorker.Result.retry()
    } else {
        runCatching(onGiveUp)
        ListenableWorker.Result.failure()
    }

private fun retryOrSettle(runAttemptCount: Int): ListenableWorker.Result =
    if (runAttemptCount < 2) ListenableWorker.Result.retry() else ListenableWorker.Result.success()

/**
 * A store that will not open is often transient (a full disk, a locked database), so retry before suspending
 * automation (Article VII §6). Opening the app re-arms it once the store works again ([SunloomWallpaperScheduler.ensureScheduled]).
 */
private fun retryThenSuspend(runAttemptCount: Int, onStoreUnavailable: () -> Unit): ListenableWorker.Result =
    if (runAttemptCount < 3) {
        ListenableWorker.Result.retry()
    } else {
        onStoreUnavailable()
        ListenableWorker.Result.failure()
    }

/**
 * A store-watch run always succeeds so the periodic job keeps going: it either finds the store open (and resumes
 * automation, which also removes the watch) or finds it still down and tries again at the next interval.
 */
internal suspend fun <T> mapStoreWatchResult(
    opens: suspend () -> T,
    onRecovered: (T) -> Unit
): ListenableWorker.Result {
    val opened = try {
        opens()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: LibraryStoreUnavailableException) {
        return ListenableWorker.Result.success() // still down; the next interval checks again
    } catch (_: Exception) {
        return ListenableWorker.Result.success()
    } catch (_: VirtualMachineError) {
        return ListenableWorker.Result.success()
    }
    // The store is open. A failure re-arming is a different fault from "still down": log it and try at the next interval.
    try {
        onRecovered(opened)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (err: Exception) {
        android.util.Log.w("StoreWatch", "rearm_failed", err)
    }
    return ListenableWorker.Result.success()
}
