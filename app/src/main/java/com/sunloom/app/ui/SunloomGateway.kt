package com.sunloom.app.ui

import com.sunloom.app.domain.model.UserPreferences

/**
 * Pure suspend interface abstracting repository and platform operations so [MainViewModel]
 * executes all I/O, bitmap decode, SQLite, and `WallpaperManager` calls off the main thread
 * (Article XII §2) while remaining 100% JVM-testable.
 */
interface SunloomGateway {
    suspend fun loadInitialSnapshot(): SunloomUiState
    suspend fun savePreferences(preferences: UserPreferences): SunloomUiState
    suspend fun applyWallpaper(recordId: String): SunloomUiState
    /** [excludeIds] are records the caller is about to remove and must not be chosen. */
    suspend fun applyNextWallpaper(excludeIds: Set<String> = emptySet()): SunloomUiState
    suspend fun applyPreviousWallpaper(): SunloomUiState
    suspend fun restoreBaselineWallpaper(): SunloomUiState
    suspend fun restoreSystemDefaultWallpaper(): SunloomUiState = restoreBaselineWallpaper()
    suspend fun toggleKeepWallpaper(recordId: String): SunloomUiState
    suspend fun rejectWallpaperForever(recordId: String): SunloomUiState
    suspend fun deleteWallpaperFromReserve(recordId: String): SunloomUiState = rejectWallpaperForever(recordId)
    suspend fun refreshFromInternet(): SunloomUiState
    /** [applyAfterImport] false adds the image to the reserve without changing the wallpaper (batch imports). */
    suspend fun importLocalUri(uriString: String, asBaselineRestore: Boolean, applyAfterImport: Boolean = true): SunloomUiState
    suspend fun resetSourceFailureIfTransient(sourceId: String) {}
    suspend fun onDisplaysChanged(): SunloomUiState = loadInitialSnapshot()
    suspend fun forgetRememberedDisplay(key: String): SunloomUiState = loadInitialSnapshot()
    suspend fun checkpointDatabaseForBackup() {}
    /** The crashes recorded on this device, newest first (for "Copy diagnostics"). */
    suspend fun recentCrashes(): List<com.sunloom.app.diagnostics.CrashEntry> = emptyList()
    /** Erases everything the app keeps and returns to a freshly installed state (first-run setup). */
    suspend fun resetSunloom(): SunloomUiState = loadInitialSnapshot()
}
