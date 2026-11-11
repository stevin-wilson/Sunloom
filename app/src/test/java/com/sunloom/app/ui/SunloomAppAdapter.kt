package com.sunloom.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.sunloom.app.domain.solar.CityCoordinates
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.LockWorkspacePolicy
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences

/**
 * Test-side adapter: the UX tests list only the callbacks they observe, with no-op defaults for the rest, and this
 * packs them into the [SunloomActions] the real composable takes.
 */
@Composable
internal fun SunloomApp(
    uiState: SunloomUiState,
    onCompleteSetup: (UserPreferences) -> Unit,
    onSelectTab: (DesktopNavTab) -> Unit,
    onNextWallpaper: () -> Unit,
    onPreviousWallpaper: () -> Unit,
    onToggleKeep: () -> Unit,
    onRejectForever: () -> Unit,
    onTogglePause: () -> Unit,
    onRestoreOriginal: () -> Unit,
    onApplySpecific: (String) -> Unit,
    onToggleLegibilityOverlay: () -> Unit,
    onSyncOnline: () -> Unit,
    onToggleCategory: (SafeCategory) -> Unit,
    onToggleSource: (String) -> Unit,
    onSelectCity: (CityCoordinates) -> Unit,
    onUpdateScheduleMode: (ScheduleMode) -> Unit,
    onUpdateLockPolicy: (LockWorkspacePolicy) -> Unit,
    onToggleBlurredFill: (Boolean) -> Unit,
    onToggleWifiOnly: (Boolean) -> Unit,
    onPickLocalPhoto: (asBaselineRestore: Boolean) -> Unit,
    onDeleteFromReserve: (String) -> Unit = {},
    onToggleKeepSpecific: (String) -> Unit = {},
    onUpdateFixedHours: (lightHour: Int, darkHour: Int) -> Unit = { _, _ -> },
    onUpdateCustomCoordinates: (lat: Double, lon: Double, label: String) -> Unit = { _, _, _ -> },
    onUpdateMaxCacheBytes: (Long) -> Unit = {},
    onToggleBatterySaverDeferral: (Boolean) -> Unit = {},
    onUpdateDisplayPolicy: (DisplayPolicy) -> Unit = {},
    onForgetRememberedDisplay: (String) -> Unit = {},
    onRestoreSystemDefault: () -> Unit = onRestoreOriginal,
    onToggleFullscreen: () -> Unit = {},
    onDropImages: (android.view.DragEvent) -> Boolean = { false },
    onSaveAs: (com.sunloom.app.domain.model.WallpaperRecord) -> Unit = {},
    onDeleteManyFromReserve: (Collection<String>) -> Unit = { ids -> ids.forEach(onDeleteFromReserve) },
    onUndoDiscard: () -> Unit = {},
    onCommitDiscard: () -> Unit = {},
    onToggleAutoSwitch: (Boolean) -> Unit = {},
    onReopenSetup: () -> Unit = {},
    onResetSunloom: () -> Unit = {},
    onCopyDiagnostics: () -> Unit = {},
    onReportProblem: () -> Unit = {},
    isFullscreen: Boolean = false,
    darkTheme: Boolean = isSystemInDarkTheme()
) {
    SunloomApp(
        uiState = uiState,
        actions = SunloomActions(
            onCompleteSetup = onCompleteSetup,
            onSelectTab = onSelectTab,
            onNextWallpaper = onNextWallpaper,
            onPreviousWallpaper = onPreviousWallpaper,
            onToggleKeep = onToggleKeep,
            onRejectForever = onRejectForever,
            onTogglePause = onTogglePause,
            onRestoreOriginal = onRestoreOriginal,
            onApplySpecific = onApplySpecific,
            onToggleLegibilityOverlay = onToggleLegibilityOverlay,
            onSyncOnline = onSyncOnline,
            onToggleCategory = onToggleCategory,
            onToggleSource = onToggleSource,
            onSelectCity = onSelectCity,
            onUpdateScheduleMode = onUpdateScheduleMode,
            onUpdateLockPolicy = onUpdateLockPolicy,
            onToggleBlurredFill = onToggleBlurredFill,
            onToggleWifiOnly = onToggleWifiOnly,
            onPickLocalPhoto = onPickLocalPhoto,
            onDeleteFromReserve = onDeleteFromReserve,
            onToggleKeepSpecific = onToggleKeepSpecific,
            onUpdateFixedHours = onUpdateFixedHours,
            onUpdateCustomCoordinates = onUpdateCustomCoordinates,
            onUpdateMaxCacheBytes = onUpdateMaxCacheBytes,
            onToggleBatterySaverDeferral = onToggleBatterySaverDeferral,
            onUpdateDisplayPolicy = onUpdateDisplayPolicy,
            onForgetRememberedDisplay = onForgetRememberedDisplay,
            onRestoreSystemDefault = onRestoreSystemDefault,
            onToggleFullscreen = onToggleFullscreen,
            onDropImages = onDropImages,
            onSaveAs = onSaveAs,
            onDeleteManyFromReserve = onDeleteManyFromReserve,
            onUndoDiscard = onUndoDiscard,
            onCommitDiscard = onCommitDiscard,
            onToggleAutoSwitch = onToggleAutoSwitch,
            onReopenSetup = onReopenSetup,
            onResetSunloom = onResetSunloom,
            onCopyDiagnostics = onCopyDiagnostics,
            onReportProblem = onReportProblem
        ),
        isFullscreen = isFullscreen,
        darkTheme = darkTheme
    )
}
