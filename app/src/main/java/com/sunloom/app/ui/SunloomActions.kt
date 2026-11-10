package com.sunloom.app.ui

import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.LockWorkspacePolicy
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.solar.CityCoordinates

/**
 * Everything the workspace can ask its host to do. One object instead of three dozen loose callbacks: the host
 * (MainActivity) wires each action once, previews and tests pass the ones they care about, and adding an action
 * cannot silently be forgotten because there are no defaults.
 */
@Suppress("LongParameterList") // one named callback per user action; a holder, not logic
data class SunloomActions(
    val onCompleteSetup: (UserPreferences) -> Unit,
    val onSelectTab: (DesktopNavTab) -> Unit,
    val onNextWallpaper: () -> Unit,
    val onPreviousWallpaper: () -> Unit,
    val onToggleKeep: () -> Unit,
    val onRejectForever: () -> Unit,
    val onTogglePause: () -> Unit,
    val onRestoreOriginal: () -> Unit,
    val onApplySpecific: (String) -> Unit,
    val onToggleLegibilityOverlay: () -> Unit,
    val onSyncOnline: () -> Unit,
    val onToggleCategory: (SafeCategory) -> Unit,
    val onToggleSource: (String) -> Unit,
    val onSelectCity: (CityCoordinates) -> Unit,
    val onUpdateScheduleMode: (ScheduleMode) -> Unit,
    val onUpdateLockPolicy: (LockWorkspacePolicy) -> Unit,
    val onToggleBlurredFill: (Boolean) -> Unit,
    val onToggleWifiOnly: (Boolean) -> Unit,
    val onPickLocalPhoto: (asBaselineRestore: Boolean) -> Unit,
    val onDeleteFromReserve: (String) -> Unit,
    val onToggleKeepSpecific: (String) -> Unit,
    val onUpdateFixedHours: (lightHour: Int, darkHour: Int) -> Unit,
    val onUpdateCustomCoordinates: (lat: Double, lon: Double, label: String) -> Unit,
    val onUpdateMaxCacheBytes: (Long) -> Unit,
    val onToggleBatterySaverDeferral: (Boolean) -> Unit,
    val onUpdateDisplayPolicy: (DisplayPolicy) -> Unit,
    val onForgetRememberedDisplay: (String) -> Unit,
    val onRestoreSystemDefault: () -> Unit,
    val onToggleFullscreen: () -> Unit,
    val onDropImages: (android.view.DragEvent) -> Boolean,
    val onSaveAs: (WallpaperRecord) -> Unit,
    val onDeleteManyFromReserve: (Collection<String>) -> Unit,
    val onUndoDiscard: () -> Unit,
    val onCommitDiscard: () -> Unit,
    val onToggleAutoSwitch: (Boolean) -> Unit,
    val onReopenSetup: () -> Unit
) {
    companion object {
        /**
         * Actions that do nothing. For previews and for tests that observe only some actions:
         * `SunloomActions.None.copy(onSelectTab = { ... })`. The real host never uses it, so a forgotten action in
         * `MainActivity` still cannot hide behind a default.
         */
        val None = SunloomActions(
            onCompleteSetup = { _ -> },
            onSelectTab = { _ -> },
            onNextWallpaper = {},
            onPreviousWallpaper = {},
            onToggleKeep = {},
            onRejectForever = {},
            onTogglePause = {},
            onRestoreOriginal = {},
            onApplySpecific = { _ -> },
            onToggleLegibilityOverlay = {},
            onSyncOnline = {},
            onToggleCategory = { _ -> },
            onToggleSource = { _ -> },
            onSelectCity = { _ -> },
            onUpdateScheduleMode = { _ -> },
            onUpdateLockPolicy = { _ -> },
            onToggleBlurredFill = { _ -> },
            onToggleWifiOnly = { _ -> },
            onPickLocalPhoto = { _ -> },
            onDeleteFromReserve = { _ -> },
            onToggleKeepSpecific = { _ -> },
            onUpdateFixedHours = { _, _ -> },
            onUpdateCustomCoordinates = { _, _, _ -> },
            onUpdateMaxCacheBytes = { _ -> },
            onToggleBatterySaverDeferral = { _ -> },
            onUpdateDisplayPolicy = { _ -> },
            onForgetRememberedDisplay = { _ -> },
            onRestoreSystemDefault = {},
            onToggleFullscreen = {},
            onDropImages = { _ -> false },
            onSaveAs = { _ -> },
            onDeleteManyFromReserve = { _ -> },
            onUndoDiscard = {},
            onCommitDiscard = {},
            onToggleAutoSwitch = { _ -> },
            onReopenSetup = {}
        )
    }
}
