package com.sunloom.app.domain.matcher

import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.WallpaperRecord

/**
 * Article III §3 (local-first personalisation): turns the device's own Kept and disliked counts into a
 * gentle weight per category and per source. Nothing leaves the device, nothing is ever excluded; the
 * multiplier is bounded so taste can tilt a pick but cannot starve a category.
 */
class TasteProfile(
    private val categoryMultiplier: Map<SafeCategory, Double> = emptyMap(),
    private val sourceMultiplier: Map<String, Double> = emptyMap()
) {
    fun multiplier(record: WallpaperRecord): Double {
        val value = (categoryMultiplier[record.category] ?: 1.0) * (sourceMultiplier[record.provenance.sourceId] ?: 1.0)
        return value.coerceIn(MIN, MAX)
    }

    companion object {
        val NEUTRAL = TasteProfile()
        const val MIN = 0.6
        const val MAX = 1.6
        private const val STEP = 0.08

        fun from(records: List<WallpaperRecord>): TasteProfile {
            val signals = records.filter { it.provenance.sourceId != "seed_reserve" && (it.isFavorite || it.isRejected) }
            if (signals.isEmpty()) return NEUTRAL
            fun weight(group: List<WallpaperRecord>) =
                (1.0 + STEP * (group.count { it.isFavorite } - group.count { it.isRejected })).coerceIn(MIN, MAX)
            return TasteProfile(
                categoryMultiplier = signals.groupBy { it.category }.mapValues { weight(it.value) },
                sourceMultiplier = signals.groupBy { it.provenance.sourceId }.mapValues { weight(it.value) }
            )
        }
    }
}
