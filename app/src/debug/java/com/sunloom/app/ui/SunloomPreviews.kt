package com.sunloom.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.sunloom.app.domain.model.UserPreferences

// Previews live in the debug source set: the preview library is a debug-only dependency, so a @Preview in main
// would stop the release build from compiling.

private val readyState = SunloomUiState(preferences = UserPreferences(isSetupCompleted = true), statusBannerMessage = "Ready")

@Composable
private fun PreviewApp(state: SunloomUiState, dark: Boolean) =
    SunloomApp(uiState = state, actions = SunloomActions.None, darkTheme = dark)

@Preview(name = "Setup, light", widthDp = 960, heightDp = 640)
@Composable
private fun SetupLight() = PreviewApp(SunloomUiState(), dark = false)

@Preview(name = "Setup, dark", widthDp = 960, heightDp = 640)
@Composable
private fun SetupDark() = PreviewApp(SunloomUiState(), dark = true)

@Preview(name = "Home, light, desktop", widthDp = 1280, heightDp = 800)
@Composable
private fun HomeLight() = PreviewApp(readyState, dark = false)

@Preview(name = "Home, dark, desktop", widthDp = 1280, heightDp = 800)
@Composable
private fun HomeDark() = PreviewApp(readyState, dark = true)

@Preview(name = "Home, compact window", widthDp = 400, heightDp = 640)
@Composable
private fun HomeCompact() = PreviewApp(readyState, dark = false)

@Preview(name = "Reserve tab, desktop", widthDp = 1280, heightDp = 800)
@Composable
private fun ReserveTab() = PreviewApp(readyState.copy(activeTab = DesktopNavTab.RESERVE_AND_HISTORY), dark = false)
