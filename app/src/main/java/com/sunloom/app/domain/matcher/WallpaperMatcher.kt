package com.sunloom.app.domain.matcher

import com.sunloom.app.data.sources.SourceRegistry
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import java.io.File

/**
 * Article VII §3, IX §3, XI §1–2 (Constitution v0.4):
 * Matches approved wallpapers to target displays and time periods while
 * enforcing file-existence integrity, repetition control, rejection lists,
 * and strict Light/Dark separation.
 */
class WallpaperMatcher(
    private val fileExistsVerifier: (WallpaperRecord) -> Boolean = { record ->
        record.localFilePath.isNotBlank() &&
            record.fileSizeBytes > 0L &&
            File(record.localFilePath).let { it.exists() && it.length() > 0L }
    }
) {

    fun filterEligiblePool(
        candidates: List<WallpaperRecord>,
        preferences: UserPreferences
    ): List<WallpaperRecord> {
        return candidates.filter { record ->
            !record.isRejected &&
                !record.isBaselineRestore &&
                record.status != com.sunloom.app.domain.model.WallpaperStatus.DISABLED_BY_USER &&
                record.evaluation.isEligible &&
                fileExistsVerifier(record) &&
                SourceRegistry.isSourceAdmitted(record.provenance.sourceId) &&
                (record.category in preferences.enabledCategories || record.provenance.sourceId == "user_local") &&
                record.provenance.sourceId in preferences.enabledSources
        }
    }

    /**
     * Counts how many eligible non-seed wallpapers matching [targetBrightness] (or [BrightnessClass.NEUTRAL])
     * have not yet been applied (`appliedCount == 0`), used to trigger proactive background replenishment.
     * Excludes `seed_reserve` so bundled procedural wallpapers never mask a low online reserve.
     */
    fun countUnseenForBrightness(
        candidates: List<WallpaperRecord>,
        targetBrightness: BrightnessClass,
        preferences: UserPreferences
    ): Int {
        val pool = filterEligiblePool(candidates, preferences)
            .filter { it.provenance.sourceId != "seed_reserve" }
        return pool.count {
            (it.evaluation.brightnessClass == targetBrightness ||
                it.evaluation.brightnessClass == BrightnessClass.NEUTRAL) &&
                it.appliedCount == 0
        }
    }

    fun selectNextWallpaper(
        candidates: List<WallpaperRecord>,
        targetBrightness: BrightnessClass,
        preferences: UserPreferences,
        currentWallpaperId: String? = null,
        recentlyUsedIds: Set<String> = emptySet()
    ): WallpaperRecord? {
        return rankCandidatesForSelection(
            candidates = candidates,
            targetBrightness = targetBrightness,
            preferences = preferences,
            currentWallpaperId = currentWallpaperId,
            recentlyUsedIds = recentlyUsedIds,
            maxResults = 1
        ).firstOrNull()
    }

    /**
     * Returns up to [maxResults] ranked eligible wallpapers so scheduled background switches
     * (Article IX §3) can advance cleanly to the next reserve wallpaper if applying the top
     * candidate fails at the platform layer.
     */
    fun rankCandidatesForSelection(
        candidates: List<WallpaperRecord>,
        targetBrightness: BrightnessClass,
        preferences: UserPreferences,
        currentWallpaperId: String? = null,
        recentlyUsedIds: Set<String> = emptySet(),
        maxResults: Int = 3
    ): List<WallpaperRecord> {
        if (maxResults <= 0) return emptyList()
        val out = ArrayList<WallpaperRecord>(maxResults)
        var remaining = candidates
        val excluded = recentlyUsedIds.toMutableSet()
        repeat(maxResults) {
            val next = selectSingleNextWallpaper(
                candidates = remaining,
                targetBrightness = targetBrightness,
                preferences = preferences,
                currentWallpaperId = currentWallpaperId,
                recentlyUsedIds = excluded
            ) ?: return out
            out.add(next)
            excluded.add(next.id)
            remaining = remaining.filterNot { it.id == next.id }
        }
        return out
    }

    private fun selectSingleNextWallpaper(
        candidates: List<WallpaperRecord>,
        targetBrightness: BrightnessClass,
        preferences: UserPreferences,
        currentWallpaperId: String? = null,
        recentlyUsedIds: Set<String> = emptySet()
    ): WallpaperRecord? {
        val eligiblePool = filterEligiblePool(candidates, preferences)
        if (eligiblePool.isEmpty()) return null

        val nonSeedPool = eligiblePool.filter { it.provenance.sourceId != "seed_reserve" }
        val seedPool = eligiblePool.filter { it.provenance.sourceId == "seed_reserve" }

        if (nonSeedPool.isNotEmpty()) {
            selectFromPool(
                pool = nonSeedPool,
                allCandidates = candidates,
                targetBrightness = targetBrightness,
                currentWallpaperId = currentWallpaperId,
                recentlyUsedIds = recentlyUsedIds
            )?.let { return it }
        }

        return selectFromPool(
            pool = seedPool,
            allCandidates = candidates,
            targetBrightness = targetBrightness,
            currentWallpaperId = currentWallpaperId,
            recentlyUsedIds = recentlyUsedIds
        )
    }

    private fun selectFromPool(
        pool: List<WallpaperRecord>,
        allCandidates: List<WallpaperRecord>,
        targetBrightness: BrightnessClass,
        currentWallpaperId: String?,
        recentlyUsedIds: Set<String>
    ): WallpaperRecord? {
        if (pool.isEmpty()) return null

        val currentSourceId = currentWallpaperId?.let { activeId ->
            allCandidates.firstOrNull { it.id == activeId }?.provenance?.sourceId
        }

        val rotationComparator = compareBy<WallpaperRecord> { it.appliedCount }
            .thenBy { if (currentSourceId != null && it.provenance.sourceId == currentSourceId) 1 else 0 }
            .thenBy { it.lastAppliedEpochMs }
            .thenByDescending { it.isFavorite }

        // 1. Prefer exact brightness match + not current + not recently used
        val strictMatches = pool.filter {
            it.evaluation.brightnessClass == targetBrightness &&
                it.id != currentWallpaperId &&
                it.id !in recentlyUsedIds
        }
        if (strictMatches.isNotEmpty()) {
            return strictMatches.sortedWith(rotationComparator).first()
        }

        // 2. Fallback: exact brightness match (excluding current if possible)
        val brightnessMatches = pool.filter {
            it.evaluation.brightnessClass == targetBrightness && it.id != currentWallpaperId
        }
        if (brightnessMatches.isNotEmpty()) {
            return brightnessMatches.sortedWith(rotationComparator).first()
        }

        // 3. Fallback: Neutral brightness class (prefer not recently used, then any non-current)
        val neutralStrictMatches = pool.filter {
            it.evaluation.brightnessClass == BrightnessClass.NEUTRAL &&
                it.id != currentWallpaperId &&
                it.id !in recentlyUsedIds
        }
        if (neutralStrictMatches.isNotEmpty()) {
            return neutralStrictMatches.sortedWith(rotationComparator).first()
        }
        val neutralMatches = pool.filter {
            it.evaluation.brightnessClass == BrightnessClass.NEUTRAL && it.id != currentWallpaperId
        }
        if (neutralMatches.isNotEmpty()) {
            return neutralMatches.sortedWith(rotationComparator).first()
        }

        // 4. Keep current if it is in this pool and matches targetBrightness or NEUTRAL before crossing opposite brightness
        val sameBrightnessCurrent = pool.firstOrNull {
            it.id == currentWallpaperId &&
                (it.evaluation.brightnessClass == targetBrightness || it.evaluation.brightnessClass == BrightnessClass.NEUTRAL)
        }
        if (sameBrightnessCurrent != null) {
            return sameBrightnessCurrent
        }

        // 5. Last resort if this pool only contains wallpapers of the other brightness
        val nonCurrent = pool.filter { it.id != currentWallpaperId }
        if (nonCurrent.isNotEmpty()) {
            return nonCurrent.sortedWith(rotationComparator).first()
        }

        return pool.firstOrNull()
    }

    fun assignWallpapersToDisplays(
        displays: List<DisplayProfile>,
        candidates: List<WallpaperRecord>,
        targetBrightness: BrightnessClass,
        preferences: UserPreferences,
        currentWallpaperId: String? = null
    ): Map<Int, WallpaperRecord> {
        if (displays.isEmpty()) return emptyMap()

        val first = selectNextWallpaper(
            candidates = candidates,
            targetBrightness = targetBrightness,
            preferences = preferences,
            currentWallpaperId = currentWallpaperId
        ) ?: return emptyMap()

        if (preferences.displayPolicy != DisplayPolicy.INDEPENDENT_PER_DISPLAY || displays.size == 1) {
            return displays.associate { it.displayId to first }
        }

        val usedIds = mutableSetOf(first.id)
        if (currentWallpaperId != null) usedIds.add(currentWallpaperId)

        val result = LinkedHashMap<Int, WallpaperRecord>()
        result[displays.first().displayId] = first

        for (display in displays.drop(1)) {
            val next = selectNextWallpaper(
                candidates = candidates,
                targetBrightness = targetBrightness,
                preferences = preferences,
                currentWallpaperId = currentWallpaperId,
                recentlyUsedIds = usedIds
            ) ?: first
            result[display.displayId] = next
            usedIds.add(next.id)
        }
        return result
    }
}
