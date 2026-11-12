package com.sunloom.app.domain.matcher

import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.model.WallpaperStatus

/** The two places a picture can be chosen for: the daytime (the light side) and the evening (the dark side). */
enum class ChosenSlot(val side: BrightnessClass) {
    DAYTIME(BrightnessClass.LIGHT),
    EVENING(BrightnessClass.DARK);

    fun pickOf(preferences: UserPreferences): String? = when (this) {
        DAYTIME -> preferences.daytimePickId
        EVENING -> preferences.eveningPickId
    }

    fun with(preferences: UserPreferences, id: String?): UserPreferences = when (this) {
        DAYTIME -> preferences.copy(daytimePickId = id)
        EVENING -> preferences.copy(eveningPickId = id)
    }
}

/**
 * Article III §1 (v0.59): the picture a person chose for the daytime or the evening. It is used whenever it can be
 * shown at all: its size, its brightness class and the source and category switches do not count against it. It is
 * left alone (Automatic is used for that change) when it is gone, rejected, the baseline restore, switched off, or
 * has no file.
 */
object ChosenWallpaper {
    /** The slot for [target]: the light side is the daytime, the dark side the evening, a balanced target has none. */
    fun slotId(preferences: UserPreferences, target: BrightnessClass): String? =
        ChosenSlot.entries.firstOrNull { it.side == target }?.pickOf(preferences)?.takeIf { it.isNotBlank() }

    /** Whether [id] is the picture of either slot. */
    fun isChosen(preferences: UserPreferences, id: String): Boolean = ChosenSlot.entries.any { it.pickOf(preferences) == id }

    /**
     * The evaluation verdicts that are about resolution and composition only. A chosen picture overrides these (it is
     * shown with a blurred frame fill instead); no other verdict, and no missing one, is overridden.
     */
    private val QUALITY_VERDICTS = setOf(
        "INSUFFICIENT_POST_CROP_RESOLUTION",
        "COMPOSITION_CROPS_TOO_MUCH",
        "RESOLUTION_BELOW_DISPLAY_TARGET"
    )

    fun isQualityVerdict(reasonCode: String?): Boolean = reasonCode in QUALITY_VERDICTS

    /** Whether [record] can be shown as a chosen picture. [fileExists] is asked about the stored path. */
    fun isUsable(record: WallpaperRecord, fileExists: (String) -> Boolean): Boolean =
        !record.isRejected && !record.isBaselineRestore && record.status != WallpaperStatus.DISABLED_BY_USER &&
            record.localFilePath.isNotBlank() && fileExists(record.localFilePath)

    fun resolve(
        preferences: UserPreferences,
        target: BrightnessClass,
        lookup: (String) -> WallpaperRecord?,
        fileExists: (String) -> Boolean
    ): WallpaperRecord? {
        val id = slotId(preferences, target) ?: return null
        return lookup(id)?.takeIf { isUsable(it, fileExists) }
    }

    /** A period starts when the side differs from the one last seen; with none remembered, it counts as starting. */
    fun periodStarted(lastTargetSeen: BrightnessClass?, target: BrightnessClass): Boolean = lastTargetSeen != target

    /** What one change run does: show the chosen [Chosen] picture, [LeaveAlone] the one on screen, or pick as usual. */
    sealed interface Choice {
        data class Chosen(val record: WallpaperRecord) : Choice
        data object LeaveAlone : Choice
        data object Automatic : Choice
    }

    /**
     * The one place a change run asks what to do about the chosen picture for [target], given the side last handled: a usable
     * one is applied when its period starts and left alone inside it; without one the normal choice applies.
     */
    fun choose(
        preferences: UserPreferences,
        target: BrightnessClass,
        lastTargetSeen: BrightnessClass?,
        lookup: (String) -> WallpaperRecord?,
        fileExists: (String) -> Boolean
    ): Choice {
        val chosen = resolve(preferences, target, lookup, fileExists)
        return when {
            chosen == null -> Choice.Automatic
            periodStarted(lastTargetSeen, target) -> Choice.Chosen(chosen)
            else -> Choice.LeaveAlone
        }
    }

    /**
     * Whether the run may record [Choice]'s side as handled: once something reached the screen. A chosen picture that
     * failed and was replaced is not retried at every look (that would change the wallpaper each Follow-OS poll); the
     * period keeps the replacement. When nothing reached the screen the period stays open.
     */
    fun shouldRememberTarget(choice: Choice, status: WallpaperStatus?): Boolean =
        choice == Choice.LeaveAlone || status == WallpaperStatus.APPLIED_SUCCESSFULLY
}
