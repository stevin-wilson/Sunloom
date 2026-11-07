package com.sunloom.app.platform.scheduler

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
}
