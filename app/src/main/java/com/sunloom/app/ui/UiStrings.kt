package com.sunloom.app.ui

import android.content.Context
import com.sunloom.app.R

/**
 * The few status messages the ViewModel builds itself. The ViewModel has no Context, so it asks this interface;
 * production passes [AndroidUiStrings] (text from `strings.xml`, translatable) and JVM tests use [English].
 */
interface UiStrings {
    val loadingLibrary: String
    val manualModeEnabled: String
    val settingNotSaved: String
    val storageProblem: String
    val resetDone: String
    val meteredFetchWarning: String
    val diagnosticsCopied: String
    val fetchingCatalogs: String
    fun scheduleEnabled(modeLabel: String): String

    /** [reason] is the failure text, or null when there is none to show. */
    fun syncInterrupted(reason: String?): String
    fun batchChecked(count: Int): String
    fun wallpaperCount(count: Int): String

    companion object {
        /** Must stay identical to the English text in `strings.xml` (checked by `UiStringsTest`). */
        val English: UiStrings = object : UiStrings {
            override val loadingLibrary = "Loading wallpaper library…"
            override val manualModeEnabled = "Manual mode enabled — click Next Wallpaper whenever you'd like a change"
            override val settingNotSaved = "Couldn't save that setting. Your previous choice was kept."
            override val storageProblem = "Couldn't finish that. Check free space and your connection, then try again."
            override val diagnosticsCopied = "Diagnostics copied. Paste them into your report."
            override val meteredFetchWarning =
                "You're on a metered connection, so fetching may use mobile data. Tap Refresh again to go ahead."
            override val resetDone = "Sunloom was reset. Set it up again whenever you like."
            override val fetchingCatalogs = "Fetching high-resolution wallpapers from online catalogs…"
            override fun scheduleEnabled(modeLabel: String) = "Automatic wallpaper schedule enabled ($modeLabel)"
            override fun syncInterrupted(reason: String?) = "Online sync interrupted: ${reason ?: "check connection"}"
            override fun batchChecked(count: Int) = if (count == 1) "Checked 1 image — approved ones are in your reserve." else "Checked $count images — approved ones are in your reserve."
            override fun wallpaperCount(count: Int) = if (count == 1) "1 wallpaper" else "$count wallpapers"
        }
    }
}

class AndroidUiStrings(private val context: Context) : UiStrings {
    override val loadingLibrary: String get() = context.getString(R.string.msg_loading_library)
    override val manualModeEnabled: String get() = context.getString(R.string.msg_manual_mode_enabled)
    override val settingNotSaved: String get() = context.getString(R.string.msg_setting_not_saved)
    override val storageProblem: String get() = context.getString(R.string.msg_storage_problem)
    override val diagnosticsCopied: String get() = context.getString(R.string.msg_diagnostics_copied)
    override val meteredFetchWarning: String get() = context.getString(R.string.msg_metered_fetch_warning)
    override val resetDone: String get() = context.getString(R.string.msg_reset_done)
    override val fetchingCatalogs: String get() = context.getString(R.string.msg_fetching_catalogs)
    override fun scheduleEnabled(modeLabel: String): String = context.getString(R.string.msg_schedule_enabled, modeLabel)
    override fun syncInterrupted(reason: String?): String =
        context.getString(R.string.msg_sync_interrupted, reason ?: context.getString(R.string.msg_check_connection))
    override fun batchChecked(count: Int): String = context.resources.getQuantityString(R.plurals.msg_batch_checked, count, count)
    override fun wallpaperCount(count: Int): String =
        context.resources.getQuantityString(R.plurals.msg_wallpaper_count, count, count)
}
