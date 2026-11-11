package com.sunloom.app.platform.scheduler

import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.FollowOsVerification
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.WallpaperStatus

/**
 * Article X §1 (v0.14): which background work is scheduled.
 *
 * Changing the wallpaper follows the schedule; fetching new images does not depend on it, so a person who changes
 * the wallpaper by hand still gets a reserve that stays fresh. Pausing automation stops both.
 */
internal object RefreshSchedulePolicy {
    /** Periodic fetch cadence; the background worker also runs several rounds each time the buffer is short. */
    const val REFRESH_INTERVAL_HOURS = 3L

    /** Rounds per background run; each round polls up to three sources within their request budgets. */
    const val MAX_BACKGROUND_ROUNDS = 3

    data class Plan(val scheduleChanges: Boolean, val scheduleRefresh: Boolean)

    fun plan(autoEnabled: Boolean, isPaused: Boolean, isManualMode: Boolean) = Plan(
        scheduleChanges = autoEnabled && !isPaused && !isManualMode,
        scheduleRefresh = !isPaused
    )

    /**
     * Whether a finished change attempt may enqueue the next transition itself. A temporary failure may not: the
     * next transition is enqueued under the running job's own unique name with REPLACE, which would cancel the job
     * and throw away the `retry()` it is about to return. Retries re-arm through the give-up path instead.
     */
    fun shouldRearmAfterChange(status: WallpaperStatus?): Boolean = status != WallpaperStatus.FAILED_TEMPORARILY

    /**
     * When no solar or clock transition exists to wait for, how long until the next look. Follow-OS mode has none (the
     * system flips its theme on its own schedule), so it polls often enough to feel responsive in the background.
     */
    fun fallbackChangeDelayMs(mode: ScheduleMode?): Long =
        if (mode == ScheduleMode.FOLLOW_OS_THEME) FOLLOW_OS_POLL_MINUTES * 60_000L else 6L * 3_600_000L

    /** A follow-OS poll must not rotate the wallpaper when the one on screen already suits the current theme. */
    fun isRedundantFollowOsChange(mode: ScheduleMode, currentSuitsTarget: Boolean?): Boolean =
        mode == ScheduleMode.FOLLOW_OS_THEME && currentSuitsTarget == true

    const val FOLLOW_OS_POLL_MINUTES = 15L

    /**
     * What a Follow-OS poll last verified: the theme and the wallpaper on screen (or the system default). While both are
     * still exactly that, nothing needs doing, and the poll can end without reading the library.
     */
    fun followOsVerifiedKey(target: BrightnessClass, currentId: String?): String = FollowOsVerification.key(target, currentId)

    fun canSkipFollowOsPoll(mode: ScheduleMode, storedKey: String?, target: BrightnessClass, currentId: String?): Boolean =
        mode == ScheduleMode.FOLLOW_OS_THEME && storedKey != null && storedKey == followOsVerifiedKey(target, currentId)

    /**
     * The inexact alarm backstops a delayed change job that Doze might hold for hours. Follow-OS has no transition to be
     * late for - it looks every few minutes anyway - so the alarm would only add wake-ups.
     */
    fun usesBackstopAlarm(mode: ScheduleMode?): Boolean = mode != ScheduleMode.FOLLOW_OS_THEME

    /** While the app is running, a change of the system theme is acted on at once instead of at the next poll. */
    fun shouldReactToThemeChange(mode: ScheduleMode, previousNight: Boolean?, nowNight: Boolean): Boolean =
        mode == ScheduleMode.FOLLOW_OS_THEME && previousNight != null && previousNight != nowNight

    /** How often a suspended automation checks whether the library store opens again (Article VII §6). */
    const val STORE_WATCH_INTERVAL_HOURS = 6L

    /** The backstop alarm replaces the held change job, but never a change that is running right now. */
    fun shouldReplaceChangeOnAlarm(changeRunning: Boolean): Boolean = !changeRunning
}
