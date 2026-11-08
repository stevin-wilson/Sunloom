package com.sunloom.app.domain.matcher

import com.sunloom.app.domain.model.SourceIds
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.renderer.EnvelopePlanner
import java.util.concurrent.ConcurrentHashMap

/**
 * Article IV §5 & XI §2: whether a wallpaper's subject survives the system's centre-crop on every
 * connected display. The matcher prefers wallpapers that fit, so a picture whose subject would be cut
 * off on one of the screens only appears when nothing else suits the moment. Nothing is excluded: an
 * image that does not fit stays in the pool as a fallback.
 */
class SubjectFit(private val fits: (WallpaperRecord) -> Boolean) {

    fun accepts(record: WallpaperRecord): Boolean = fits(record)

    companion object {
        /** Treats every wallpaper as fitting (single display, or nothing known about the displays). */
        val ANY = SubjectFit { true }

        /**
         * Plans each wallpaper against [displays] exactly as the reserve reconcile does and checks which
         * displays would lose the subject. Bundled seeds are rendered per display set and always fit.
         */
        fun forDisplays(
            displays: List<DisplayProfile>,
            preferences: UserPreferences,
            planner: EnvelopePlanner = EnvelopePlanner()
        ): SubjectFit {
            val valid = displays.filter { it.widthPx > 0 && it.heightPx > 0 }
            if (valid.size < 2) return ANY
            val verdicts = ConcurrentHashMap<String, Boolean>()
            return SubjectFit { record ->
                verdicts.computeIfAbsent(record.id) { planFits(record, valid, preferences, planner) }
            }
        }

        private fun planFits(
            record: WallpaperRecord,
            displays: List<DisplayProfile>,
            preferences: UserPreferences,
            planner: EnvelopePlanner
        ): Boolean {
            val renderedPerDisplaySet = record.provenance.sourceId == SourceIds.SEED_RESERVE || record.isBaselineRestore
            if (renderedPerDisplaySet) return true
            val plan = planner.planForRecord(record, displays, preferences)
            return plan == null || plan.subjectClippedDisplays.isEmpty()
        }
    }
}
