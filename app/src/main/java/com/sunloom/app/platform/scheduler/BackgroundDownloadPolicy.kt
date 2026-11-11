package com.sunloom.app.platform.scheduler

import android.content.SharedPreferences
import androidx.core.content.edit
import java.time.Instant
import java.time.ZoneId

/** Why a background download is held back. */
enum class DownloadBlock { NONE, METERED_NETWORK, DATA_SAVER, BATTERY_SAVER, DAILY_BUDGET }

/**
 * Whether a background download may run (Article X §2). It is deliberately a pure decision so each rule can be tested on
 * its own; the caller supplies what the device reports.
 *
 * An explicit tap on Refresh is the person's own request and does not go through this.
 */
object BackgroundDownloadPolicy {
    /** Most data the app downloads in the background in one local day. */
    const val DAILY_BUDGET_BYTES: Long = 300L * 1024L * 1024L

    /**
     * The first rule that holds back a download, or [DownloadBlock.NONE]. A metered network (a phone hotspot counts) is
     * refused unless the person allowed it, and even then not while Data Saver is on. Data Saver says nothing about an
     * unmetered network.
     */
    fun blockedBy(
        unmeteredOnly: Boolean,
        deferOnBatterySaver: Boolean,
        unmeteredNetwork: Boolean,
        dataSaverRestricting: Boolean,
        batterySaverActive: Boolean,
        bytesUsedToday: Long,
        dailyBudgetBytes: Long = DAILY_BUDGET_BYTES
    ): DownloadBlock = when {
        networkBlock(unmeteredOnly, unmeteredNetwork, dataSaverRestricting) != DownloadBlock.NONE ->
            networkBlock(unmeteredOnly, unmeteredNetwork, dataSaverRestricting)
        deferOnBatterySaver && batterySaverActive -> DownloadBlock.BATTERY_SAVER
        bytesUsedToday >= dailyBudgetBytes -> DownloadBlock.DAILY_BUDGET
        else -> DownloadBlock.NONE
    }

    /** The network part alone, for callers (the foreground top-up) that have no battery or budget concern. */
    fun networkBlock(unmeteredOnly: Boolean, unmeteredNetwork: Boolean, dataSaverRestricting: Boolean): DownloadBlock = when {
        unmeteredNetwork -> DownloadBlock.NONE
        unmeteredOnly -> DownloadBlock.METERED_NETWORK
        dataSaverRestricting -> DownloadBlock.DATA_SAVER
        else -> DownloadBlock.NONE
    }
}

/**
 * How much the app has downloaded in the background today (local day), kept on the device so it survives the process
 * being killed between runs.
 */
class BackgroundDataBudget(
    private val prefs: SharedPreferences,
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    fun usedToday(nowEpochMs: Long): Long =
        if (prefs.getLong(KEY_DAY, NO_DAY) == dayOf(nowEpochMs)) prefs.getLong(KEY_BYTES, 0L) else 0L

    fun record(bytes: Long, nowEpochMs: Long) {
        if (bytes <= 0L) return
        val total = usedToday(nowEpochMs) + bytes
        prefs.edit {
            putLong(KEY_DAY, dayOf(nowEpochMs))
            putLong(KEY_BYTES, total)
        }
    }

    private fun dayOf(epochMs: Long): Long = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toEpochDay()

    private companion object {
        const val KEY_DAY = "bg_data_day"
        const val KEY_BYTES = "bg_data_bytes"
        const val NO_DAY = Long.MIN_VALUE
    }
}
