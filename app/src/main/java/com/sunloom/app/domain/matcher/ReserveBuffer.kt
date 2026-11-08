package com.sunloom.app.domain.matcher

import com.sunloom.app.domain.model.SourceIds
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.model.WallpaperStatus

/**
 * Article X §3 (v0.14): how much fresh stock the reserve holds, and whether it should be topped up.
 * Only usable online images count: they have a file, are eligible, are not rejected or disabled, belong to an
 * enabled category, and have never been shown. Offline seeds and the user's own photos are not "fetched stock".
 */
object ReserveBuffer {
    /** Unseen images to keep ready for each of the Light and Dark modes (NEUTRAL counts for both). */
    const val TARGET_UNSEEN_PER_MODE = 12

    /** Above this share of the cache cap, new images would only evict others, so fetching pauses. */
    const val CACHE_FULL_FRACTION = 0.9

    /** Unseen fetched images after which fetching stops, however short one mode is: Dark images can be rare. */
    const val MAX_UNSEEN_STOCK = 100

    fun unseenFor(
        records: List<WallpaperRecord>,
        mode: BrightnessClass,
        enabledCategories: Set<SafeCategory>
    ): Int = records.count {
        it.isFetchedStock(enabledCategories) &&
            it.appliedCount == 0 &&
            BrightnessFit.accepts(it.evaluation, mode)
    }

    /** All usable fetched stock, seen or not; used to tell whether a fetch round actually added images. */
    fun stockCount(records: List<WallpaperRecord>, enabledCategories: Set<SafeCategory>): Int =
        records.count { it.isFetchedStock(enabledCategories) }

    fun needsTopUp(
        records: List<WallpaperRecord>,
        enabledCategories: Set<SafeCategory>,
        bytesUsed: Long,
        maxBytes: Long
    ): Boolean {
        if (maxBytes > 0 && bytesUsed >= maxBytes * CACHE_FULL_FRACTION) return false
        if (records.count { it.isFetchedStock(enabledCategories) && it.appliedCount == 0 } >= MAX_UNSEEN_STOCK) return false
        return minOf(
            unseenFor(records, BrightnessClass.LIGHT, enabledCategories),
            unseenFor(records, BrightnessClass.DARK, enabledCategories)
        ) < TARGET_UNSEEN_PER_MODE
    }

    private fun WallpaperRecord.isFetchedStock(enabledCategories: Set<SafeCategory>): Boolean =
        fileSizeBytes > 0L &&
            !isRejected &&
            !isBaselineRestore &&
            status != WallpaperStatus.DISABLED_BY_USER &&
            evaluation.isEligible &&
            provenance.sourceId != SourceIds.SEED_RESERVE &&
            provenance.sourceId != SourceIds.USER_LOCAL &&
            category in enabledCategories
}
