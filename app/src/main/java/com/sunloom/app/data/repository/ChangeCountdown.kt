package com.sunloom.app.data.repository

import android.content.Context
import androidx.core.content.edit
import com.sunloom.app.domain.model.UserPreferences

/**
 * When the "Change every" wait started (Article X §1, v0.58): at the last change of any kind, and when the interval
 * became active. Derived state, so it lives in the file Auto Backup does not restore: a restored start would count a
 * wait that happened on another device.
 */
internal object ChangeCountdown {
    private const val KEY = "change_countdown_start_ms"

    private fun state(context: Context) =
        context.applicationContext.getSharedPreferences(LocalLibraryRepository.STATE_FILE, Context.MODE_PRIVATE)

    /** The start in epoch milliseconds, or null when nothing has started a countdown on this device. */
    fun read(context: Context): Long? = state(context).takeIf { it.contains(KEY) }?.getLong(KEY, 0L)

    fun start(context: Context, nowMs: Long) = state(context).edit { putLong(KEY, nowMs) }

    /**
     * Makes sure there is a usable countdown: starts one that is missing (an interval restored from a backup) or that
     * lies in the future (the clock was stepped back, so the wait could never run out), and leaves a running one alone.
     */
    fun ensureStarted(context: Context, nowMs: Long) {
        val started = read(context)
        if (started == null || started > nowMs) start(context, nowMs)
    }

    /**
     * Whether a save should start the wait over: the interval just became active (chosen, or automatic switching came
     * back on, a pause ended, or the schedule left Manual), or a different interval was chosen. Without this a wait
     * that ended hours ago would make the first change come a minute after the person switched things back on.
     */
    fun shouldRestart(before: UserPreferences, after: UserPreferences): Boolean =
        after.changeEveryActive && (!before.changeEveryActive ||
            UserPreferences.sanitizeChangeEveryHours(before.changeEveryHours) !=
            UserPreferences.sanitizeChangeEveryHours(after.changeEveryHours))
}
