package com.sunloom.app.ui

import androidx.compose.ui.test.junit4.ComposeContentTestRule

/**
 * Composes [SunloomApp] with no-op callbacks so UX-conduct tests only override what they observe.
 * [state] is read inside composition, so a test that backs it with `mutableStateOf` can change tabs
 * or flags after the single `setContent` call a compose rule allows.
 */
internal fun ComposeContentTestRule.setSunloomApp(
    onSelectTab: (DesktopNavTab) -> Unit = {},
    state: () -> SunloomUiState
) {
    setContent {
        SunloomApp(
            uiState = state(),
            onCompleteSetup = {},
            onSelectTab = onSelectTab,
            onNextWallpaper = {},
            onPreviousWallpaper = {},
            onToggleKeep = {},
            onRejectForever = {},
            onTogglePause = {},
            onRestoreOriginal = {},
            onApplySpecific = {},
            onToggleLegibilityOverlay = {},
            onSyncOnline = {},
            onToggleCategory = {},
            onToggleSource = {},
            onSelectCity = {},
            onUpdateScheduleMode = {},
            onUpdateLockPolicy = {},
            onToggleBlurredFill = {},
            onToggleWifiOnly = {},
            onPickLocalPhoto = {}
        )
    }
    waitForIdle()
}
