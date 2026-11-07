package com.sunloom.app.domain.matcher

import com.sunloom.app.data.sources.SourceRegistry
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.safety.SafetyGate
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
    },
    private val random: kotlin.random.Random = kotlin.random.Random.Default
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
        return pool.count { BrightnessFit.accepts(it.evaluation, targetBrightness) && it.appliedCount == 0 }
    }

    fun selectNextWallpaper(
        candidates: List<WallpaperRecord>,
        targetBrightness: BrightnessClass,
        preferences: UserPreferences,
        currentWallpaperId: String? = null,
        recentlyUsedIds: Set<String> = emptySet(),
        deterministic: Boolean = false,
        taste: TasteProfile = TasteProfile.NEUTRAL,
        subjectFit: SubjectFit = SubjectFit.ANY
    ): WallpaperRecord? {
        return rankCandidatesForSelection(
            candidates = candidates,
            targetBrightness = targetBrightness,
            preferences = preferences,
            currentWallpaperId = currentWallpaperId,
            recentlyUsedIds = recentlyUsedIds,
            maxResults = 1,
            deterministic = deterministic,
            taste = taste,
            subjectFit = subjectFit
        ).firstOrNull()
    }

    /**
     * Returns up to [maxResults] ranked eligible wallpapers so scheduled background switches
     * (Article IX §3) can advance cleanly to the next reserve wallpaper if applying the top
     * candidate fails at the platform layer. File existence is verified once per record.
     *
     * With [deterministic] the best-weighted candidate is returned instead of a random pick, so a UI
     * preview of "what would be next" does not change on every rebuild.
     */
    fun rankCandidatesForSelection(
        candidates: List<WallpaperRecord>,
        targetBrightness: BrightnessClass,
        preferences: UserPreferences,
        currentWallpaperId: String? = null,
        recentlyUsedIds: Set<String> = emptySet(),
        maxResults: Int = 3,
        deterministic: Boolean = false,
        taste: TasteProfile = TasteProfile.NEUTRAL,
        subjectFit: SubjectFit = SubjectFit.ANY
    ): List<WallpaperRecord> {
        if (maxResults <= 0) return emptyList()
        val eligiblePool = filterEligiblePool(candidates, preferences)
        if (eligiblePool.isEmpty()) return emptyList()

        val currentSourceId = currentWallpaperId?.let { activeId ->
            candidates.firstOrNull { it.id == activeId }?.provenance?.sourceId
        }
        // Online and user sources always outrank the bundled seeds (Article XI §2); seeds only serve
        // as the offline fallback once no non-seed wallpaper is eligible.
        val (seedPool, nonSeedPool) = eligiblePool.partition { it.provenance.sourceId == SEED_SOURCE_ID }

        val out = ArrayList<WallpaperRecord>(maxResults)
        val excluded = recentlyUsedIds.toMutableSet()
        val picked = HashSet<String>()
        repeat(maxResults) {
            // Every result must be a different wallpaper: they are fallbacks for a failed apply.
            val nonSeedLeft = nonSeedPool.filter { it.id !in picked }
            val seedLeft = seedPool.filter { it.id !in picked }
            fun pick(pool: List<WallpaperRecord>) = selectFromPool(
                pool, targetBrightness, currentWallpaperId, currentSourceId, excluded, deterministic, taste, subjectFit
            )
            val next = pick(nonSeedLeft) ?: pick(seedLeft) ?: return out
            out.add(next)
            excluded.add(next.id)
            picked.add(next.id)
        }
        return out
    }

    /**
     * Ladder (Article XI §1–2): an unused exact-brightness image, then an unused NEUTRAL one that fits the
     * target (see [BrightnessFit]), and only then a recently used exact or fitting NEUTRAL image. Putting
     * NEUTRAL before "repeat a recent match" is what stops a small pool from ping-ponging between the same
     * two or three wallpapers. A NEUTRAL image on the wrong side of the Balanced band is a last resort: it
     * ranks after repeating a recent match, but before crossing to the opposite brightness.
     */
    private fun selectFromPool(
        pool: List<WallpaperRecord>,
        targetBrightness: BrightnessClass,
        currentWallpaperId: String?,
        currentSourceId: String?,
        recentlyUsedIds: Set<String>,
        deterministic: Boolean,
        taste: TasteProfile,
        subjectFit: SubjectFit
    ): WallpaperRecord? {
        if (pool.isEmpty()) return null
        val recentShown = pool.filter { it.lastAppliedEpochMs > 0L }
            .sortedByDescending { it.lastAppliedEpochMs }
            .take(SIMILARITY_LOOKBACK)

        val recent = recentlyUsedIds + recentWindow(pool)
        val notCurrent = pool.filter { it.id != currentWallpaperId }
        fun exact(list: List<WallpaperRecord>) = list.filter { it.evaluation.brightnessClass == targetBrightness }
        fun neutral(list: List<WallpaperRecord>) = list.filter { BrightnessFit.isFittingNeutral(it.evaluation, targetBrightness) }
        fun illFittingNeutral(list: List<WallpaperRecord>) = list.filter {
            it.evaluation.brightnessClass == BrightnessClass.NEUTRAL && !BrightnessFit.isFittingNeutral(it.evaluation, targetBrightness)
        }
        val notRecent = notCurrent.filter { it.id !in recent }

        val rungs = sequenceOf(
            { exact(notRecent) },
            { neutral(notRecent) },
            { exact(notCurrent) },
            { neutral(notCurrent) }
        )
        for (rung in rungs) {
            val candidates = rung()
            if (candidates.isNotEmpty()) return choose(candidates, currentSourceId, deterministic, recentShown, taste, subjectFit)
        }

        // Keep a current wallpaper that suits the target before settling for a Balanced image that does not.
        pool.firstOrNull { it.id == currentWallpaperId && BrightnessFit.accepts(it.evaluation, targetBrightness) }
            ?.let { return it }

        // Then a Balanced image on the wrong side of the band, or the current one if that is what it is,
        // before crossing to the opposite brightness.
        illFittingNeutral(notCurrent).takeIf { it.isNotEmpty() }
            ?.let { return choose(it, currentSourceId, deterministic, recentShown, taste, subjectFit) }
        pool.firstOrNull { it.id == currentWallpaperId && it.evaluation.brightnessClass == BrightnessClass.NEUTRAL }
            ?.let { return it }

        // Last resort: this pool only holds wallpapers of the other brightness.
        if (notCurrent.isNotEmpty()) return choose(notCurrent, currentSourceId, deterministic, recentShown, taste, subjectFit)
        return pool.firstOrNull()
    }

    /**
     * How much [candidate] resembles what was just on screen (0 = nothing in common, capped below 1 so a
     * look-alike stays possible): same category as the last one shown, same category as the ones before,
     * or a near-identical picture by perceptual hash.
     */
    private fun similarity(candidate: WallpaperRecord, recentShown: List<WallpaperRecord>): Double {
        if (recentShown.isEmpty()) return 0.0
        var score = when {
            candidate.category == recentShown[0].category -> SAME_CATEGORY_AS_LAST
            recentShown.drop(1).any { it.category == candidate.category } -> SAME_CATEGORY_EARLIER
            else -> 0.0
        }
        val lastHue = recentShown[0].evaluation.hueBucket
        if (lastHue >= 0 && candidate.evaluation.hueBucket == lastHue) score += SAME_HUE_AS_LAST
        val hash = candidate.provenance.perceptualHash
        if (SafetyGate.isInformativeDHash(hash) &&
            recentShown.any { SafetyGate.isNearDuplicateDHash(hash, it.provenance.perceptualHash, LOOKALIKE_HAMMING_BITS) }
        ) {
            score += LOOKALIKE_PENALTY
        }
        return score.coerceAtMost(MAX_SIMILARITY)
    }

    /** The most recently applied images, so Next cannot cycle through a handful of recent ones. */
    private fun recentWindow(pool: List<WallpaperRecord>): Set<String> {
        val size = minOf(MAX_RECENT_WINDOW, pool.size / 2)
        if (size <= 0) return emptySet()
        return pool.asSequence()
            .filter { it.lastAppliedEpochMs > 0L }
            .sortedByDescending { it.lastAppliedEpochMs }
            .take(size)
            .mapTo(HashSet()) { it.id }
    }

    /**
     * Picks one of [candidates]: never-shown images first, otherwise the least recently applied half
     * (lifetime `appliedCount` would trap newcomers behind a long-running favourite), then a different
     * source than the active wallpaper when one exists, then a weighted random pick that gently favours
     * Kept images (Article XI §2). Pictures whose subject would be cut off on a display come last ([SubjectFit]).
     */
    private fun choose(
        candidates: List<WallpaperRecord>,
        currentSourceId: String?,
        deterministic: Boolean,
        recentShown: List<WallpaperRecord>,
        taste: TasteProfile,
        subjectFit: SubjectFit
    ): WallpaperRecord {
        // A picture whose subject would be cut off on one of the screens only wins when nothing else in this
        // rung fits, and it never costs a brightness match (the rungs above are decided first).
        val fitting = candidates.filter(subjectFit::accepts)
        val pool = fitting.ifEmpty { candidates }
        val unseen = pool.filter { it.appliedCount == 0 }
        var tier = if (unseen.isNotEmpty()) {
            unseen
        } else {
            val oldestFirst = pool.sortedWith(compareBy({ it.lastAppliedEpochMs }, { it.appliedCount }))
            oldestFirst.take((oldestFirst.size + 1) / 2)
        }
        if (currentSourceId != null) {
            val otherSources = tier.filter { it.provenance.sourceId != currentSourceId }
            if (otherSources.isNotEmpty()) tier = otherSources
        }
        if (tier.size == 1) return tier[0]

        val weights = tier.map {
            (if (it.isFavorite) FAVORITE_WEIGHT else 1.0) * taste.multiplier(it) * (1.0 - similarity(it, recentShown))
        }
        if (deterministic) {
            return tier[weights.indices.maxByOrNull { weights[it] } ?: 0]
        }
        var ticket = random.nextDouble() * weights.sum()
        for (index in tier.indices) {
            ticket -= weights[index]
            if (ticket < 0.0) return tier[index]
        }
        return tier.last()
    }


    private companion object {
        const val SEED_SOURCE_ID = "seed_reserve"
        const val MAX_RECENT_WINDOW = 8
        const val FAVORITE_WEIGHT = 2.0
        const val SIMILARITY_LOOKBACK = 3
        const val SAME_CATEGORY_AS_LAST = 0.6
        const val SAME_CATEGORY_EARLIER = 0.2
        const val SAME_HUE_AS_LAST = 0.15
        const val LOOKALIKE_PENALTY = 0.5
        const val LOOKALIKE_HAMMING_BITS = 12
        const val MAX_SIMILARITY = 0.85
    }
}
