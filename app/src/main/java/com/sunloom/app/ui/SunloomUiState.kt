package com.sunloom.app.ui

import com.sunloom.app.domain.model.SourceIds
import com.sunloom.app.domain.matcher.BrightnessFit
import com.sunloom.app.domain.matcher.ReserveBreakdown
import com.sunloom.app.domain.matcher.ReserveBuffer
import com.sunloom.app.domain.matcher.SourceRejectionGroup
import com.sunloom.app.domain.matcher.SourceStockRow
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.PlatformCapabilities
import com.sunloom.app.domain.model.RejectionRecord
import com.sunloom.app.domain.model.SourceDiagnostics
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.solar.DaylightWindow
import java.time.LocalTime

enum class DesktopNavTab(val label: String) {
    TODAY_CANVAS("Home"),
    RESERVE_AND_HISTORY("Reserve"),
    SOURCES_AND_CATEGORIES("Sources"),
    DISPLAYS_AND_SCHEDULE("Schedule"),
    SAFETY_AND_DECISION_LOG("Storage & About")
}

enum class DiscardKind { DISLIKE, REMOVE }

/** A Dislike/Remove that has been performed from the user's point of view but can still be undone. */
data class PendingDiscard(
    val token: Long,
    val recordId: String,
    val title: String,
    val kind: DiscardKind,
    val wasCurrent: Boolean,
    /** Extra records removed in the same batch (multi-select). Empty for a single Dislike/Remove. */
    val additionalRecordIds: List<String> = emptyList(),
    /** The active wallpaper at the time of the action, restored by Undo (may be in [additionalRecordIds]). */
    val currentRecordId: String? = null
) {
    /** Every record hidden from the UI while this action can still be undone. */
    val allRecordIds: List<String> get() = listOf(recordId) + additionalRecordIds
}

data class SunloomUiState(
    val activeTab: DesktopNavTab = DesktopNavTab.TODAY_CANVAS,
    val isInitialLoading: Boolean = false,
    /** "Setup Guide" was pressed: show first-run setup again. A screen, not a saved state (a relaunch starts normally). */
    val isSetupReopened: Boolean = false,
    val isStorageUnavailable: Boolean = false,
    /** The result of the last "Check for updates" (GitHub build only); shown in About. */
    val updateCheck: UpdateCheckUiState = UpdateCheckUiState.Idle,
    val preferences: UserPreferences = UserPreferences(),
    val capabilities: PlatformCapabilities = PlatformCapabilities(
        isWallpaperSupported = true,
        isSetWallpaperAllowed = true,
        supportsIndependentLockWallpaper = true,
        supportsPerDisplayWallpaper = false,
        connectedDisplays = emptyList(),
        capabilityDisclosureNote = "Probing connected displays…"
    ),
    val currentWallpaper: WallpaperRecord? = null,
    val isCurrentWallpaperAppliedToSystem: Boolean = true,
    val previousWallpaperId: String? = null,
    val baselineWallpaper: WallpaperRecord? = null,
    val libraryWallpapers: List<WallpaperRecord> = emptyList(),
    val disabledReserveCount: Int = 0,
    val allCachedBytesUsed: Long = -1L,
    val rejectionLog: List<RejectionRecord> = emptyList(),
    val sourceDiagnostics: List<SourceDiagnostics> = emptyList(),
    val targetBrightnessNow: BrightnessClass = BrightnessClass.LIGHT,
    val daylightWindow: DaylightWindow = DaylightWindow(LocalTime.of(6, 40), LocalTime.of(17, 15)),
    val showLegibilityZonesOverlay: Boolean = false,
    val isSyncingOnline: Boolean = false,
    /** A destructive action the user can still undo; it commits when its snackbar times out. */
    val pendingDiscard: PendingDiscard? = null,
    /** True while a wallpaper is being decoded/cropped/applied, so controls can show progress. */
    val isApplyingWallpaper: Boolean = false,
    val statusBannerMessage: String = "Ready",
    /** Set when the last apply failed in a way that may succeed on a second try; drives the banner's Retry. */
    val retryApplyRecordId: String? = null
) {
    val totalCacheBytesUsed: Long
        get() = if (allCachedBytesUsed >= 0L) allCachedBytesUsed else libraryWallpapers.sumOf { it.fileSizeBytes }

    val lightReserveCount: Int
        get() = libraryWallpapers.count {
            it.isReserveImage && it.evaluation.brightnessClass == BrightnessClass.LIGHT
        }

    val darkReserveCount: Int
        get() = libraryWallpapers.count {
            it.isReserveImage && it.evaluation.brightnessClass == BrightnessClass.DARK
        }

    /** What each enabled source (and each fallback) holds, for Diagnostics. */
    val reserveBySource: List<SourceStockRow>
        get() = ReserveBreakdown.bySource(
            libraryWallpapers,
            preferences.enabledCategories,
            knownSources = sourceDiagnostics.filter { it.isEnabled && it.isAdmitted }.map { it.sourceId to it.sourceName }
        )

    /** Skipped candidates grouped by source and reason, for Diagnostics. */
    val rejectionSummary: List<SourceRejectionGroup>
        get() = ReserveBreakdown.rejectionsBySourceAndReason(rejectionLog)

    /** Fetched images ready to use (a file, eligible, not rejected), seen or not. */
    val fetchedStockCount: Int
        get() = ReserveBuffer.stockCount(libraryWallpapers, preferences.enabledCategories)

    /** True while Light or Dark has fewer unseen fetched images than the target and the cache has room. */
    val reserveNeedsTopUp: Boolean
        get() = ReserveBuffer.needsTopUp(
            libraryWallpapers, preferences.enabledCategories, totalCacheBytesUsed, preferences.maxCacheBytes
        )

    val unseenCurrentBrightnessCount: Int
        get() = libraryWallpapers.count {
            it.isReserveImage &&
                it.provenance.sourceId != SourceIds.SEED_RESERVE &&
                BrightnessFit.accepts(it.evaluation, targetBrightnessNow) &&
                it.appliedCount == 0
        }
}

/** Rows restored from backup or evicted from the cache keep their metadata but have no image. */
private val WallpaperRecord.hasImage: Boolean get() = fileSizeBytes > 0L

/** An image the user can be shown from the reserve: it has a file and is neither rejected nor the restore point. */
private val WallpaperRecord.isReserveImage: Boolean get() = hasImage && !isRejected && !isBaselineRestore

/** Where "Check for updates" is: not asked, running, or answered. */
sealed interface UpdateCheckUiState {
    data object Idle : UpdateCheckUiState
    data object Checking : UpdateCheckUiState
    data class Done(val result: com.sunloom.app.update.UpdateCheckResult) : UpdateCheckUiState
}
