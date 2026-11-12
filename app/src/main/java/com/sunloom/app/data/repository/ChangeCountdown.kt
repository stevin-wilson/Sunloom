package com.sunloom.app.data.repository

import android.content.Context
import androidx.core.content.edit

/**
 * When the "Change every" wait started (Article X §1, v0.58): at the last change of any kind, and when the interval
 * was chosen. Derived state, so it lives in the file Auto Backup does not restore: a restored start would count a wait
 * that happened on another device.
 */
internal object ChangeCountdown {
    private const val KEY = "change_countdown_start_ms"

    private fun state(context: Context) =
        context.applicationContext.getSharedPreferences(LocalLibraryRepository.STATE_FILE, Context.MODE_PRIVATE)

    /** The start in epoch milliseconds, or null when nothing has started a countdown on this device. */
    fun read(context: Context): Long? = state(context).takeIf { it.contains(KEY) }?.getLong(KEY, 0L)

    fun start(context: Context, nowMs: Long) = state(context).edit { putLong(KEY, nowMs) }

    /** Gives an interval that was restored from a backup (or never started) a countdown, without moving a running one. */
    fun startIfMissing(context: Context, nowMs: Long) {
        if (read(context) == null) start(context, nowMs)
    }
}
