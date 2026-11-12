package com.sunloom.app.domain.matcher

import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.model.WallpaperStatus

/**
 * Article III §1 (v0.59): the picture a person chose for the daytime or the evening. It is used whenever it can be
 * shown at all: its size, its brightness class and the source and category switches do not count against it. It is
 * left alone (Automatic is used for that change) when it is gone, rejected, the baseline restore, switched off, or
 * has no file.
 */
object ChosenWallpaper {
    /** The slot for [target]: the light side is the daytime, the dark side the evening, a balanced target has none. */
    fun slotId(preferences: UserPreferences, target: BrightnessClass): String? = when (target) {
        BrightnessClass.LIGHT -> preferences.daytimePickId
        BrightnessClass.DARK -> preferences.eveningPickId
        BrightnessClass.NEUTRAL -> null
    }?.takeIf { it.isNotBlank() }

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
     * Whether the run may record [Choice]'s side as handled. A chosen picture that did not end up on screen keeps the
     * period open, so the next look tries it again instead of giving up on it until the next flip.
     */
    fun shouldRememberTarget(choice: Choice, status: WallpaperStatus?, appliedId: String?): Boolean = when (choice) {
        Choice.LeaveAlone -> true
        Choice.Automatic -> status == WallpaperStatus.APPLIED_SUCCESSFULLY
        is Choice.Chosen -> status == WallpaperStatus.APPLIED_SUCCESSFULLY && appliedId == choice.record.id
    }
}
