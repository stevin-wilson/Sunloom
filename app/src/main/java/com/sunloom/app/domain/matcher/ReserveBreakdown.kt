package com.sunloom.app.domain.matcher

import com.sunloom.app.domain.model.SourceIds
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.RejectionRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.model.WallpaperStatus

/** What one source contributes to the reserve (Article X §4). */
data class SourceStockRow(
    val sourceId: String,
    val sourceName: String,
    val light: Int,
    val dark: Int,
    val balanced: Int,
    /** Never-shown images; these are what the top-up target counts. */
    val unseen: Int,
    /** Offline seeds and the user's own images: they win the fallback but are not fetched reserve stock. */
    val isFallback: Boolean
) {
    val total: Int get() = light + dark + balanced
}

/** How many candidates from [sourceId] were skipped for [reasonCode]. */
data class SourceRejectionGroup(val sourceId: String, val reasonCode: String, val count: Int)

/** Pure summaries for Diagnostics: where the reserve comes from, and why other candidates did not make it. */
object ReserveBreakdown {

    private val FALLBACK_SOURCES = setOf(SourceIds.SEED_RESERVE, SourceIds.USER_LOCAL)

    /**
     * Usable images per source, split by brightness class. [knownSources] (id to display name) adds a row of
     * zeros for an enabled source that has contributed nothing, which is exactly the case worth seeing.
     * Online sources come first, most images first; fallbacks last.
     */
    fun bySource(
        records: List<WallpaperRecord>,
        enabledCategories: Set<SafeCategory>,
        knownSources: List<Pair<String, String>> = emptyList()
    ): List<SourceStockRow> {
        val usable = records.filter { it.isUsable(enabledCategories) }
        val rows = usable.groupBy { it.provenance.sourceId }.map { (id, group) ->
            SourceStockRow(
                sourceId = id,
                sourceName = group.first().provenance.sourceName.ifBlank { id },
                light = group.count { it.evaluation.brightnessClass == BrightnessClass.LIGHT },
                dark = group.count { it.evaluation.brightnessClass == BrightnessClass.DARK },
                balanced = group.count { it.evaluation.brightnessClass == BrightnessClass.NEUTRAL },
                unseen = group.count { it.appliedCount == 0 },
                isFallback = id in FALLBACK_SOURCES
            )
        }
        val present = rows.mapTo(mutableSetOf()) { it.sourceId }
        val empty = knownSources
            .filter { (id, _) -> id !in present && id !in FALLBACK_SOURCES }
            .map { (id, name) -> SourceStockRow(id, name, 0, 0, 0, 0, isFallback = false) }
        val (fallbacks, online) = (rows + empty).partition { it.isFallback }
        return online.sortedWith(compareByDescending<SourceStockRow> { it.total }.thenBy { it.sourceName }) +
            fallbacks.sortedBy { it.sourceName }
    }

    /** Skipped candidates grouped by source and reason, most frequent first. */
    fun rejectionsBySourceAndReason(log: List<RejectionRecord>): List<SourceRejectionGroup> =
        log.groupingBy { it.sourceId to it.reasonCode }.eachCount()
            .map { (key, count) -> SourceRejectionGroup(key.first, key.second, count) }
            .sortedWith(
                compareByDescending<SourceRejectionGroup> { it.count }
                    .thenBy { it.sourceId }
                    .thenBy { it.reasonCode }
            )

    private fun WallpaperRecord.isUsable(enabledCategories: Set<SafeCategory>): Boolean =
        fileSizeBytes > 0L &&
            !isRejected &&
            !isBaselineRestore &&
            status != WallpaperStatus.DISABLED_BY_USER &&
            evaluation.isEligible &&
            category in enabledCategories
}
