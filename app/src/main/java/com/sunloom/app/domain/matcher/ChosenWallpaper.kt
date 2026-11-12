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

    /** What a change run does about the chosen picture for the current side. */
    enum class Decision { APPLY_CHOSEN, LEAVE_ALONE, AUTOMATIC }

    /** A period starts when the side differs from the one last seen; with none remembered, it counts as starting. */
    fun periodStarted(lastTargetSeen: BrightnessClass?, target: BrightnessClass): Boolean = lastTargetSeen != target

    /**
     * A chosen picture is applied when its period starts and left alone inside it (so the interval does not rotate it);
     * without a usable one the normal choice applies.
     */
    fun decide(chosenUsable: Boolean, periodStarted: Boolean): Decision = when {
        !chosenUsable -> Decision.AUTOMATIC
        periodStarted -> Decision.APPLY_CHOSEN
        else -> Decision.LEAVE_ALONE
    }
}
