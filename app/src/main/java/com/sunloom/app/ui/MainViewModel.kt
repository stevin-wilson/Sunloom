package com.sunloom.app.ui

import android.content.Context
import android.content.res.Configuration
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sunloom.app.data.repository.LibraryStoreUnavailableException
import com.sunloom.app.data.repository.LocalLibraryRepository
import com.sunloom.app.domain.matcher.WallpaperMatcher
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.PlatformCapabilities
import com.sunloom.app.domain.model.RejectionRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.SourceDiagnostics
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.model.WallpaperStatus
import com.sunloom.app.domain.solar.CityCoordinates
import com.sunloom.app.domain.solar.DaylightWindow
import com.sunloom.app.domain.solar.SolarCalculator
import com.sunloom.app.platform.GoogleBookPlatformAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    val wasCurrent: Boolean
)

data class SunloomUiState(
    val activeTab: DesktopNavTab = DesktopNavTab.TODAY_CANVAS,
    val isInitialLoading: Boolean = false,
    val isStorageUnavailable: Boolean = false,
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
    val statusBannerMessage: String = "Ready"
) {
    val totalCacheBytesUsed: Long
        get() = if (allCachedBytesUsed >= 0L) allCachedBytesUsed else libraryWallpapers.sumOf { it.fileSizeBytes }

    val lightReserveCount: Int
        get() = libraryWallpapers.count { !it.isRejected && !it.isBaselineRestore && it.evaluation.brightnessClass == BrightnessClass.LIGHT }

    val darkReserveCount: Int
        get() = libraryWallpapers.count { !it.isRejected && !it.isBaselineRestore && it.evaluation.brightnessClass == BrightnessClass.DARK }

    val unseenCurrentBrightnessCount: Int
        get() = libraryWallpapers.count {
            !it.isRejected &&
                !it.isBaselineRestore &&
                it.provenance.sourceId != "seed_reserve" &&
                (it.evaluation.brightnessClass == targetBrightnessNow ||
                    it.evaluation.brightnessClass == BrightnessClass.NEUTRAL) &&
                it.appliedCount == 0
        }
}

/**
 * Pure suspend interface abstracting repository and platform operations so [MainViewModel]
 * executes all I/O, bitmap decode, SQLite, and `WallpaperManager` calls off the main thread
 * (Article XII §2) while remaining 100% JVM-testable.
 */
interface SunloomGateway {
    suspend fun loadInitialSnapshot(): SunloomUiState
    suspend fun savePreferences(preferences: UserPreferences): SunloomUiState
    suspend fun applyWallpaper(recordId: String): SunloomUiState
    suspend fun applyNextWallpaper(): SunloomUiState
    suspend fun applyPreviousWallpaper(): SunloomUiState
    suspend fun restoreBaselineWallpaper(): SunloomUiState
    suspend fun restoreSystemDefaultWallpaper(): SunloomUiState = restoreBaselineWallpaper()
    suspend fun toggleKeepWallpaper(recordId: String): SunloomUiState
    suspend fun rejectWallpaperForever(recordId: String): SunloomUiState
    suspend fun deleteWallpaperFromReserve(recordId: String): SunloomUiState = rejectWallpaperForever(recordId)
    suspend fun refreshFromInternet(): SunloomUiState
    suspend fun importLocalUri(uriString: String, asBaselineRestore: Boolean): SunloomUiState
    suspend fun resetSourceFailureIfTransient(sourceId: String) {}
    suspend fun onDisplaysChanged(): SunloomUiState = loadInitialSnapshot()
    suspend fun forgetRememberedDisplay(key: String): SunloomUiState = loadInitialSnapshot()
    suspend fun checkpointDatabaseForBackup() {}
}

class MainViewModel(
    private val gateway: SunloomGateway,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SunloomUiState(
            isInitialLoading = true,
            statusBannerMessage = "Loading wallpaper library…"
        )
    )
    val uiState: StateFlow<SunloomUiState> = _uiState.asStateFlow()
    private val stateMutex = Mutex()
    private val syncMutex = Mutex()

    init {
        viewModelScope.launch(workDispatcher) {
            stateMutex.withLock {
                val before = _uiState.value
                val loaded = gateway.loadInitialSnapshot()
                val after = _uiState.value
                _uiState.value = loaded.copy(
                    activeTab = if (after.activeTab != before.activeTab) after.activeTab else loaded.activeTab,
                    showLegibilityZonesOverlay = if (after.showLegibilityZonesOverlay != before.showLegibilityZonesOverlay) {
                        after.showLegibilityZonesOverlay
                    } else {
                        loaded.showLegibilityZonesOverlay
                    },
                    isInitialLoading = false
                )
            }
        }
    }

    private fun launchGatewayMutation(
        preserveNavigation: Boolean = true,
        block: suspend (SunloomUiState) -> SunloomUiState
    ): Job {
        return viewModelScope.launch(workDispatcher) {
            stateMutex.withLock {
                val before = _uiState.value
                val next = block(before)
                _uiState.value = if (preserveNavigation) {
                    next.copy(
                        activeTab = _uiState.value.activeTab,
                        showLegibilityZonesOverlay = _uiState.value.showLegibilityZonesOverlay,
                        pendingDiscard = _uiState.value.pendingDiscard,
                        isInitialLoading = false
                    )
                } else {
                    next.copy(pendingDiscard = _uiState.value.pendingDiscard, isInitialLoading = false)
                }
            }
        }
    }

    /** Like [launchGatewayMutation] but surfaces `isApplyingWallpaper` while the (slow) apply runs. */
    private fun launchApplyMutation(block: suspend (SunloomUiState) -> SunloomUiState) {
        _uiState.value = _uiState.value.copy(isApplyingWallpaper = true)
        viewModelScope.launch(workDispatcher) {
            try {
                stateMutex.withLock {
                    val next = try {
                        block(_uiState.value)
                    } catch (e: Throwable) {
                        _uiState.value = _uiState.value.copy(isApplyingWallpaper = false)
                        throw e
                    }
                    _uiState.value = next.copy(
                        activeTab = _uiState.value.activeTab,
                        showLegibilityZonesOverlay = _uiState.value.showLegibilityZonesOverlay,
                        pendingDiscard = _uiState.value.pendingDiscard,
                        isApplyingWallpaper = false,
                        isInitialLoading = false
                    )
                }
            } finally {
                if (_uiState.value.isApplyingWallpaper) {
                    _uiState.value = _uiState.value.copy(isApplyingWallpaper = false)
                }
            }
        }
    }

    fun selectTab(tab: DesktopNavTab) {
        _uiState.value = _uiState.value.copy(activeTab = tab)
    }

    fun toggleLegibilityOverlay() {
        _uiState.value = _uiState.value.copy(
            showLegibilityZonesOverlay = !_uiState.value.showLegibilityZonesOverlay
        )
    }

    fun completeFirstRunSetup(updatedPreferences: UserPreferences) {
        val completed = updatedPreferences.copy(isSetupCompleted = true)
        launchGatewayMutation(preserveNavigation = false) {
            val newState = gateway.savePreferences(completed)
            newState.copy(
                activeTab = DesktopNavTab.TODAY_CANVAS,
                showLegibilityZonesOverlay = _uiState.value.showLegibilityZonesOverlay,
                statusBannerMessage = if (completed.autoChangeEnabled) {
                    "Automatic wallpaper schedule enabled (${completed.scheduleMode.label})"
                } else {
                    "Manual mode enabled — click Next Wallpaper whenever you'd like a change"
                }
            )
        }
    }

    fun updatePreferences(transform: (UserPreferences) -> UserPreferences) {
        // Optimistic: reflect the change in the controls immediately (Doherty threshold) while the
        // persisted save + reconcile runs; the gateway snapshot then replaces this state.
        val previous = _uiState.value.preferences
        val updated = transform(previous)
        if (updated != previous) {
            _uiState.value = _uiState.value.copy(preferences = updated)
        }
        launchGatewayMutation { current ->
            try {
                gateway.savePreferences(updated)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                current.copy(
                    preferences = previous,
                    statusBannerMessage = "Couldn't save that setting. Your previous choice was kept."
                )
            }
        }
    }

    fun selectCity(city: CityCoordinates) {
        updatePreferences {
            it.copy(
                selectedCityName = city.name,
                latitude = city.latitude,
                longitude = city.longitude
            )
        }
    }

    fun updateCustomCoordinates(latitude: Double, longitude: Double, label: String = "Custom Coordinates") {
        val clampedLat = latitude.coerceIn(-90.0, 90.0)
        val clampedLon = longitude.coerceIn(-180.0, 180.0)
        updatePreferences {
            it.copy(
                selectedCityName = label.ifBlank { "Custom Coordinates" },
                latitude = clampedLat,
                longitude = clampedLon
            )
        }
    }

    fun updateFixedHours(lightHour: Int, darkHour: Int) {
        updatePreferences {
            it.copy(
                fixedLightHour = lightHour.coerceIn(0, 23),
                fixedDarkHour = darkHour.coerceIn(0, 23)
            )
        }
    }

    fun updateMaxCacheBytes(maxBytes: Long) {
        val clamped = maxBytes.coerceIn(50L * 1024L * 1024L, 2048L * 1024L * 1024L)
        updatePreferences { it.copy(maxCacheBytes = clamped) }
    }

    fun toggleBatterySaverDeferral(enabled: Boolean) {
        updatePreferences { it.copy(deferOnBatterySaver = enabled) }
    }

    fun toggleCategory(category: SafeCategory) {
        updatePreferences { prefs ->
            val nextSet = if (category in prefs.enabledCategories && prefs.enabledCategories.size > 1) {
                prefs.enabledCategories - category
            } else {
                prefs.enabledCategories + category
            }
            prefs.copy(enabledCategories = nextSet)
        }
    }

    fun toggleSource(sourceId: String) {
        launchGatewayMutation { current ->
            val prefs = current.preferences
            val isReenabling = sourceId !in prefs.enabledSources
            if (isReenabling) {
                gateway.resetSourceFailureIfTransient(sourceId)
            }
            val nextSet = if (sourceId in prefs.enabledSources && prefs.enabledSources.size > 1) {
                prefs.enabledSources - sourceId
            } else {
                prefs.enabledSources + sourceId
            }
            gateway.savePreferences(prefs.copy(enabledSources = nextSet))
        }
    }

    fun togglePauseAutomation() {
        updatePreferences { it.copy(isPaused = !it.isPaused) }
    }

    fun changeToNextWallpaper() {
        launchGatewayMutation { gateway.applyNextWallpaper() }
    }

    private val nextInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Coalesces rapid Next presses (button mashing / key repeat) into a single in-flight apply. */
    fun triggerNextWallpaperSmart() {
        if (!nextInFlight.compareAndSet(false, true)) return
        viewModelScope.launch(workDispatcher) {
            try {
                advanceToNextWallpaperSmart()
            } finally {
                nextInFlight.set(false)
            }
        }
    }

    fun triggerOnlineSync() {
        if (_uiState.value.isSyncingOnline) return
        viewModelScope.launch(workDispatcher) {
            syncOnlineCatalogNow()
        }
    }

    /**
     * Immediately switches to the next wallpaper from the local reserve off the main thread,
     * then replenishes online reserve in the background if unseen count is low.
     */
    suspend fun advanceToNextWallpaperSmart() {
        _uiState.value = _uiState.value.copy(isApplyingWallpaper = true)
        try {
            stateMutex.withLock {
                val prevTab = _uiState.value.activeTab
                val prevOverlay = _uiState.value.showLegibilityZonesOverlay
                val applied = withContext(workDispatcher) {
                    gateway.applyNextWallpaper()
                }
                _uiState.value = applied.copy(
                    activeTab = prevTab,
                    showLegibilityZonesOverlay = prevOverlay,
                    pendingDiscard = _uiState.value.pendingDiscard,
                    isApplyingWallpaper = false,
                    isInitialLoading = false
                )
            }
        } finally {
            if (_uiState.value.isApplyingWallpaper) {
                _uiState.value = _uiState.value.copy(isApplyingWallpaper = false)
            }
        }
        if (_uiState.value.unseenCurrentBrightnessCount <= 1 && !_uiState.value.isSyncingOnline) {
            // Replenish in the background so local Next presses are never blocked by the network.
            viewModelScope.launch(workDispatcher) { syncOnlineCatalogNow() }
        }
    }

    fun returnToPreviousWallpaper() {
        launchApplyMutation { gateway.applyPreviousWallpaper() }
    }

    fun applySpecificWallpaper(recordId: String) {
        launchApplyMutation { gateway.applyWallpaper(recordId) }
    }

    fun toggleKeepCurrentWallpaper() {
        val current = _uiState.value.currentWallpaper ?: return
        toggleKeepSpecificWallpaper(current.id)
    }

    fun toggleKeepSpecificWallpaper(recordId: String) {
        launchGatewayMutation { gateway.toggleKeepWallpaper(recordId) }
    }

    fun rejectCurrentWallpaperForever() {
        launchGatewayMutation { current ->
            val active = current.currentWallpaper ?: return@launchGatewayMutation current
            gateway.rejectWallpaperForever(active.id)
        }
    }

    fun deleteFromReserve(recordId: String) {
        launchGatewayMutation { gateway.deleteWallpaperFromReserve(recordId) }
    }

    private var nextDiscardToken = 0L

    /** Dislike the current wallpaper now (switching away immediately), finalizing after the undo window. */
    fun dislikeCurrentWithUndo() {
        val current = _uiState.value.currentWallpaper ?: return
        beginDiscard(current.id, current.title, DiscardKind.DISLIKE)
    }

    /** Remove a reserve item now (hiding it immediately), finalizing after the undo window. */
    fun removeFromReserveWithUndo(recordId: String) {
        val record = _uiState.value.libraryWallpapers.firstOrNull { it.id == recordId }
        if (record == null) {
            deleteFromReserve(recordId)
            return
        }
        beginDiscard(record.id, record.title, DiscardKind.REMOVE)
    }

    private fun beginDiscard(recordId: String, title: String, kind: DiscardKind) {
        commitPendingDiscard() // only one undoable action at a time; finalize the previous one
        val wasCurrent = _uiState.value.currentWallpaper?.id == recordId
        _uiState.value = _uiState.value.copy(
            pendingDiscard = PendingDiscard(++nextDiscardToken, recordId, title, kind, wasCurrent)
        )
        if (wasCurrent) launchGatewayMutation { gateway.applyNextWallpaper() }
    }

    /** Finalizes the pending Dislike/Remove (snackbar timed out, new action started, or app stopped). */
    fun commitPendingDiscard() {
        val pending = _uiState.value.pendingDiscard ?: return
        _uiState.value = _uiState.value.copy(pendingDiscard = null)
        when (pending.kind) {
            DiscardKind.DISLIKE -> launchGatewayMutation { gateway.rejectWallpaperForever(pending.recordId) }
            DiscardKind.REMOVE -> launchGatewayMutation { gateway.deleteWallpaperFromReserve(pending.recordId) }
        }
    }

    /** Cancels the pending Dislike/Remove and, if it was the active wallpaper, brings it back. */
    fun undoPendingDiscard() {
        val pending = _uiState.value.pendingDiscard ?: return
        _uiState.value = _uiState.value.copy(pendingDiscard = null)
        if (pending.wasCurrent) {
            launchGatewayMutation { gateway.applyWallpaper(pending.recordId) }
        }
    }

    fun restoreOriginalBaselineWallpaper() {
        launchApplyMutation { gateway.restoreBaselineWallpaper() }
    }

    fun restoreSystemDefaultWallpaper() {
        launchGatewayMutation { gateway.restoreSystemDefaultWallpaper() }
    }

    /**
     * Wrapped in `try / finally` so `isSyncingOnline` is guaranteed to reset to `false`
     * even if the coroutine is cancelled or an exception is thrown, while preserving structured
     * concurrency on [CancellationException].
     */
    suspend fun syncOnlineCatalogNow() {
        if (!syncMutex.tryLock()) return
        val prevTab = _uiState.value.activeTab
        val prevOverlay = _uiState.value.showLegibilityZonesOverlay
        val prevBanner = _uiState.value.statusBannerMessage
        _uiState.value = _uiState.value.copy(
            isSyncingOnline = true,
            statusBannerMessage = "Fetching high-resolution wallpapers from online catalogs..."
        )
        try {
            val refreshed = withContext(workDispatcher) {
                gateway.refreshFromInternet()
            }
            stateMutex.withLock {
                _uiState.value = refreshed.copy(
                    activeTab = _uiState.value.activeTab.takeIf { it != prevTab } ?: prevTab,
                    showLegibilityZonesOverlay = _uiState.value.showLegibilityZonesOverlay.takeIf { it != prevOverlay } ?: prevOverlay,
                    isInitialLoading = false,
                    isSyncingOnline = false
                )
            }
        } catch (cancel: CancellationException) {
            _uiState.value = _uiState.value.copy(
                isSyncingOnline = false,
                statusBannerMessage = prevBanner
            )
            throw cancel
        } catch (t: Throwable) {
            _uiState.value = _uiState.value.copy(
                isSyncingOnline = false,
                statusBannerMessage = "Online sync interrupted: ${t.message ?: "check connection"}"
            )
        } finally {
            if (_uiState.value.isSyncingOnline) {
                _uiState.value = _uiState.value.copy(isSyncingOnline = false)
            }
            syncMutex.unlock()
        }
    }

    fun importLocalUri(uriString: String, asBaselineRestore: Boolean) {
        launchGatewayMutation { gateway.importLocalUri(uriString, asBaselineRestore) }
    }

    /**
     * Imports several user-provided images (drag-and-drop, Share sheet, Open with) in one serialized
     * mutation. Each URI still goes through the full L3-L5 import pipeline; [onFinished] runs when the
     * batch ends (success or failure) so callers can release one-shot URI grants.
     */
    fun importLocalUris(uriStrings: List<String>, onFinished: () -> Unit = {}) {
        if (uriStrings.isEmpty()) {
            onFinished()
            return
        }
        launchGatewayMutation { current ->
            var state = current
            for (uri in uriStrings) {
                state = gateway.importLocalUri(uri, false)
            }
            if (uriStrings.size > 1) {
                state = state.copy(
                    statusBannerMessage = "Checked ${uriStrings.size} images — approved ones are in your reserve."
                )
            }
            state
        }.invokeOnCompletion { onFinished() }
    }

    /**
     * Responds to live OS Light/Dark theme changes when `FOLLOW_OS_THEME` is active.
     */
    fun onSystemUiModeChanged() {
        launchGatewayMutation { currentState ->
            val refreshed = gateway.loadInitialSnapshot()
            if (currentState.preferences.autoChangeEnabled &&
                !currentState.preferences.isPaused &&
                currentState.preferences.scheduleMode == ScheduleMode.FOLLOW_OS_THEME &&
                refreshed.targetBrightnessNow != currentState.targetBrightnessNow
            ) {
                gateway.applyNextWallpaper()
            } else {
                refreshed
            }
        }
    }

    /**
     * Responds to external monitor dock/undock or resolution/orientation changes by reconciling
     * reserve eligibility, re-cropping/rotating the active wallpaper, and refreshing UI state.
     */
    fun onDisplaysChanged() {
        launchGatewayMutation { gateway.onDisplaysChanged() }
    }

    fun forgetRememberedDisplay(key: String) {
        launchGatewayMutation { gateway.forgetRememberedDisplay(key) }
    }

    fun updateDisplayPolicy(policy: DisplayPolicy) {
        if (policy == DisplayPolicy.INDEPENDENT_PER_DISPLAY) return
        updatePreferences { it.copy(displayPolicy = policy) }
    }
}

class AndroidSunloomGateway(
    private val context: Context,
    repository: LocalLibraryRepository? = null,
    private val platformAdapter: GoogleBookPlatformAdapter = GoogleBookPlatformAdapter(context),
    private val solarCalculator: SolarCalculator = SolarCalculator(),
    private val matcher: WallpaperMatcher = WallpaperMatcher(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : SunloomGateway {

    @Volatile
    private var resolvedRepository: LocalLibraryRepository? = repository

    private fun obtainRepository(): LocalLibraryRepository {
        resolvedRepository?.let { return it }
        return synchronized(this) {
            resolvedRepository ?: LocalLibraryRepository(context.applicationContext).also {
                resolvedRepository = it
            }
        }
    }

    private suspend fun withGatewayIo(block: suspend (LocalLibraryRepository) -> SunloomUiState): SunloomUiState {
        return withContext(ioDispatcher) {
            try {
                val repo = obtainRepository()
                block(repo)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: LibraryStoreUnavailableException) {
                platformAdapter.cancelAllScheduledWork()
                SunloomUiState(
                    isInitialLoading = false,
                    isStorageUnavailable = true,
                    capabilities = platformAdapter.probeCapabilities(),
                    statusBannerMessage = "Local wallpaper database is unavailable — automatic changes are suspended."
                )
            }
        }
    }

    override suspend fun loadInitialSnapshot(): SunloomUiState = withGatewayIo { repo ->
        val connectedDisplays = platformAdapter.getConnectedDisplays()
        val display = platformAdapter.getPrimaryOrSharpestDisplayOrNull(connectedDisplays)
        val prefs = repo.loadPreferences()
        val activeBefore = repo.getCurrentWallpaperId()
        repo.purgeRevokedSources()
        if (display != null) {
            repo.ensureSeedReserveLoadedForDisplays(connectedDisplays, prefs.displayPolicy)
            repo.revalidateGateVersionIfNeeded(display)
            repo.reconcileReserveForDisplaySet(connectedDisplays, prefs)
        } else {
            repo.revalidateGateVersionIfNeeded(display = null)
        }
        repo.syncSourceEnabledStates(prefs.enabledSources)
        if (display != null) {
            reconcileActiveWallpaperForDisplay(repo, display, connectedDisplays, prefs, activeBefore)
            promoteNonSeedOverSeedIfNeeded(repo, prefs, display, connectedDisplays)
            buildSnapshot(repo, "Ready", preloadedPrefs = prefs)
        } else {
            buildSnapshot(
                repo = repo,
                bannerMessage = "Display dimensions unknown — waiting for an active display.",
                preloadedPrefs = prefs
            )
        }
    }

    override suspend fun onDisplaysChanged(): SunloomUiState = withGatewayIo { repo ->
        val connectedDisplays = platformAdapter.getConnectedDisplays()
        val display = platformAdapter.getPrimaryOrSharpestDisplayOrNull(connectedDisplays)
        val prevGeometryKey = repo.getLastAppliedDisplayGeometryKey()
        val prefs = repo.loadPreferences()
        val activeBefore = repo.getCurrentWallpaperId()
        repo.purgeRevokedSources()
        if (display == null) {
            repo.revalidateGateVersionIfNeeded(display = null)
            repo.syncSourceEnabledStates(prefs.enabledSources)
            return@withGatewayIo buildSnapshot(
                repo = repo,
                bannerMessage = "Display dimensions unknown — waiting for an active display.",
                preloadedPrefs = prefs
            )
        }
        repo.ensureSeedReserveLoadedForDisplays(connectedDisplays, prefs.displayPolicy)
        repo.revalidateGateVersionIfNeeded(display)
        val reserveChanged = repo.reconcileReserveForDisplaySet(connectedDisplays, prefs)
        repo.syncSourceEnabledStates(prefs.enabledSources)
        val reapplied = reconcileActiveWallpaperForDisplay(repo, display, connectedDisplays, prefs, activeBefore)
        promoteNonSeedOverSeedIfNeeded(repo, prefs, display, connectedDisplays)
        val currentGeometryKey = platformAdapter.computeDisplayGeometryKey(connectedDisplays, prefs.displayPolicy)
        val banner = if (reapplied || reserveChanged || (prevGeometryKey != null && prevGeometryKey != currentGeometryKey)) {
            if (connectedDisplays.size > 1 && prefs.displayPolicy == DisplayPolicy.SAME_ON_ALL_DISPLAYS) {
                "Adapted multi-display wallpaper envelope (${connectedDisplays.size} displays)"
            } else {
                "Adapted wallpaper for ${display.name} (${display.resolutionLabel})"
            }
        } else {
            "Ready"
        }
        buildSnapshot(repo, banner, preloadedPrefs = prefs)
    }

    override suspend fun forgetRememberedDisplay(key: String): SunloomUiState = withGatewayIo { repo ->
        platformAdapter.forgetRememberedDisplay(key)
        buildSnapshot(repo, "Removed remembered display from history.")
    }

    private fun isSystemInDarkTheme(): Boolean {
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Ensures that if an active wallpaper was invalidated (revoked source, gate failure, source
     * disabled, or under-sized after hotplugging a monitor), `WallpaperManager` genuinely applies
     * a replacement or baseline restore before updating `current_wallpaper_id` (Article V §2).
     */
    private fun reconcileActiveWallpaperForDisplay(
        repo: LocalLibraryRepository,
        display: DisplayProfile,
        connectedDisplays: List<DisplayProfile>,
        prefs: UserPreferences,
        activeBeforeInvalidation: String? = repo.getCurrentWallpaperId()
    ): Boolean {
        val currentId = repo.getCurrentWallpaperId()
        if (currentId == null && activeBeforeInvalidation == null) {
            return false
        }
        val currentRecord = currentId?.let { repo.getWallpaperById(it) }
        val lastGeometryKey = repo.getLastAppliedDisplayGeometryKey()
        val currentGeometryKey = platformAdapter.computeDisplayGeometryKey(connectedDisplays, prefs.displayPolicy)

        val needsReplacement = currentRecord == null ||
            currentRecord.isRejected ||
            currentRecord.status == WallpaperStatus.DISABLED_BY_USER ||
            (!currentRecord.isBaselineRestore && !currentRecord.evaluation.isEligible)

        if (needsReplacement) {
            val activePool = filterActiveLibraryPool(repo.getAllWallpapers(), prefs)
            val targetBrightness = solarCalculator.resolveSolarSnapshot(
                preferences = prefs,
                isSystemInDarkTheme = isSystemInDarkTheme()
            ).targetBrightness
            val excludeId = currentId ?: activeBeforeInvalidation
            val replacements = matcher.rankCandidatesForSelection(
                candidates = activePool,
                targetBrightness = targetBrightness,
                preferences = prefs,
                currentWallpaperId = excludeId,
                recentlyUsedIds = setOfNotNull(excludeId, repo.getPreviousWallpaperId()),
                maxResults = 3
            )
            for (replacement in replacements) {
                val status = platformAdapter.applyWallpaperAtomically(
                    record = replacement,
                    policy = prefs.lockWorkspacePolicy,
                    targetDisplay = display,
                    dryRunPreviewOnly = prefs.dryRunPreviewOnly,
                    connectedDisplays = connectedDisplays,
                    displayPolicy = prefs.displayPolicy
                )
                if (status == WallpaperStatus.APPLIED_SUCCESSFULLY) {
                    repo.recordWallpaperApplied(replacement.id)
                    repo.setLastAppliedDisplayGeometryKey(currentGeometryKey)
                    return true
                }
            }
            // Fallback to baseline restore if active wallpaper was invalidated and no reserve item applied
            if (activeBeforeInvalidation != null) {
                val baseline = repo.getAllWallpapers().firstOrNull { it.isBaselineRestore && !it.isRejected }
                if (baseline != null) {
                    val baseStatus = platformAdapter.applyWallpaperAtomically(
                        record = baseline,
                        policy = prefs.lockWorkspacePolicy,
                        targetDisplay = display,
                        dryRunPreviewOnly = prefs.dryRunPreviewOnly,
                        connectedDisplays = connectedDisplays,
                        displayPolicy = prefs.displayPolicy
                    )
                    if (baseStatus == WallpaperStatus.APPLIED_SUCCESSFULLY) {
                        repo.recordWallpaperApplied(baseline.id)
                        repo.setLastAppliedDisplayGeometryKey(currentGeometryKey)
                        return true
                    }
                }
                if (platformAdapter.restoreSystemDefaultWallpaper(prefs.dryRunPreviewOnly)) {
                    repo.clearCurrentWallpaperId()
                    return true
                }
            }
            return false
        }

        if (lastGeometryKey != null && lastGeometryKey != currentGeometryKey) {
            val status = platformAdapter.applyWallpaperAtomically(
                record = currentRecord,
                policy = prefs.lockWorkspacePolicy,
                targetDisplay = display,
                dryRunPreviewOnly = prefs.dryRunPreviewOnly,
                connectedDisplays = connectedDisplays,
                displayPolicy = prefs.displayPolicy
            )
            if (status == WallpaperStatus.APPLIED_SUCCESSFULLY) {
                repo.setLastAppliedDisplayGeometryKey(currentGeometryKey)
                return true
            }
        }
        return false
    }

    private fun promoteNonSeedOverSeedIfNeeded(
        repo: LocalLibraryRepository,
        prefs: UserPreferences,
        targetDisplay: DisplayProfile? = platformAdapter.getPrimaryOrSharpestDisplayOrNull(),
        connectedDisplays: List<DisplayProfile> = platformAdapter.getConnectedDisplays()
    ) {
        val display = targetDisplay ?: return
        val currentId = repo.getCurrentWallpaperId()
        val all = repo.getAllWallpapers()
        val currentRecord = all.firstOrNull { it.id == currentId }
        if (currentRecord == null || currentRecord.provenance.sourceId == "seed_reserve") {
            val nonSeedPool = matcher.filterEligiblePool(all, prefs)
                .filter { it.provenance.sourceId != "seed_reserve" }
            if (nonSeedPool.isNotEmpty()) {
                val targetBrightness = solarCalculator.resolveSolarSnapshot(
                    preferences = prefs,
                    isSystemInDarkTheme = isSystemInDarkTheme()
                ).targetBrightness
                val nextNonSeed = matcher.selectNextWallpaper(
                    candidates = nonSeedPool,
                    targetBrightness = targetBrightness,
                    preferences = prefs,
                    currentWallpaperId = currentId
                )
                if (nextNonSeed != null) {
                    val status = platformAdapter.applyWallpaperAtomically(
                        record = nextNonSeed,
                        policy = prefs.lockWorkspacePolicy,
                        targetDisplay = display,
                        dryRunPreviewOnly = prefs.dryRunPreviewOnly,
                        connectedDisplays = connectedDisplays,
                        displayPolicy = prefs.displayPolicy
                    )
                    if (status == WallpaperStatus.APPLIED_SUCCESSFULLY) {
                        repo.recordWallpaperApplied(nextNonSeed.id)
                        repo.setLastAppliedDisplayGeometryKey(
                            platformAdapter.computeDisplayGeometryKey(connectedDisplays, prefs.displayPolicy)
                        )
                    }
                }
            }
        }
    }

    override suspend fun savePreferences(preferences: UserPreferences): SunloomUiState = withGatewayIo { repo ->
        val activeBefore = repo.getCurrentWallpaperId()
        val cleanPreferences = preferences.copy(dryRunPreviewOnly = false)
        val previousPreferences = repo.loadPreferences()
        // Schedule-only / toggle-only edits (the common case) skip the reserve-wide reconcile.
        val reserveAffected = cleanPreferences.affectsReserve(previousPreferences)
        val connectedDisplays = platformAdapter.getConnectedDisplays()
        val display = platformAdapter.getPrimaryOrSharpestDisplayOrNull(connectedDisplays)
        repo.savePreferences(cleanPreferences)
        if (display != null && reserveAffected) {
            repo.ensureSeedReserveLoadedForDisplays(connectedDisplays, cleanPreferences.displayPolicy)
            repo.reconcileReserveForDisplaySet(connectedDisplays, cleanPreferences)
        }
        if (reserveAffected) repo.enforceCacheLimit()
        platformAdapter.syncPeriodicSchedule(
            autoEnabled = cleanPreferences.autoChangeEnabled,
            isPaused = cleanPreferences.isPaused,
            wifiOnly = cleanPreferences.wifiOnlyDownloads,
            deferBatterySaver = cleanPreferences.deferOnBatterySaver,
            preferences = cleanPreferences
        )
        if (display != null && reserveAffected) {
            reconcileActiveWallpaperForDisplay(repo, display, connectedDisplays, cleanPreferences, activeBefore)
            promoteNonSeedOverSeedIfNeeded(repo, cleanPreferences, display, connectedDisplays)
        }
        val msg = when {
            cleanPreferences.isPaused -> "Automatic changes paused"
            cleanPreferences.autoChangeEnabled -> "Schedule active (${cleanPreferences.scheduleMode.label})"
            else -> "Manual mode active"
        }
        buildSnapshot(repo, msg, preloadedPrefs = cleanPreferences)
    }

    override suspend fun applyWallpaper(recordId: String): SunloomUiState = withGatewayIo { repo ->
        applyWallpaperInternal(repo, recordId)
    }

    private fun applyWallpaperInternal(repo: LocalLibraryRepository, recordId: String): SunloomUiState {
        val record = repo.getWallpaperById(recordId)
            ?: return buildSnapshot(repo, "Wallpaper record not found in local library.")
        val prefs = repo.loadPreferences()
        return applyRecordAndBuildSnapshot(repo, record, prefs)
    }

    private fun applyRecordAndBuildSnapshot(
        repo: LocalLibraryRepository,
        record: WallpaperRecord,
        prefs: UserPreferences
    ): SunloomUiState {
        val connectedDisplays = platformAdapter.getConnectedDisplays()
        val display = platformAdapter.getPrimaryOrSharpestDisplayOrNull(connectedDisplays)
            ?: return buildSnapshot(
                repo = repo,
                bannerMessage = "Display dimensions unknown — waiting for an active display before applying '${record.title}'.",
                preloadedPrefs = prefs
            )
        val status = platformAdapter.applyWallpaperAtomically(
            record = record,
            policy = prefs.lockWorkspacePolicy,
            targetDisplay = display,
            dryRunPreviewOnly = prefs.dryRunPreviewOnly,
            connectedDisplays = connectedDisplays,
            displayPolicy = prefs.displayPolicy
        )
        return if (status == WallpaperStatus.APPLIED_SUCCESSFULLY) {
            repo.recordWallpaperApplied(record.id)
            repo.setLastAppliedDisplayGeometryKey(
                platformAdapter.computeDisplayGeometryKey(connectedDisplays, prefs.displayPolicy)
            )
            val targetName = if (connectedDisplays.size > 1 && prefs.displayPolicy == DisplayPolicy.SAME_ON_ALL_DISPLAYS) {
                "${connectedDisplays.size} connected displays"
            } else {
                display.name
            }
            buildSnapshot(
                repo = repo,
                bannerMessage = "Applied '${record.title}' (${record.evaluation.brightnessClass.displayName}) for $targetName",
                preloadedPrefs = prefs
            )
        } else {
            buildSnapshot(
                repo = repo,
                bannerMessage = "Status: ${status.label} while applying '${record.title}'",
                preloadedPrefs = prefs
            )
        }
    }

    override suspend fun applyNextWallpaper(): SunloomUiState = withGatewayIo { repo ->
        applyNextInternal(repo)
    }

    private fun applyNextInternal(repo: LocalLibraryRepository): SunloomUiState {
        val prefs = repo.loadPreferences()
        val all = repo.getAllWallpapers()
        val activePool = filterActiveLibraryPool(all, prefs)
        val solarSnapshot = solarCalculator.resolveSolarSnapshot(
            preferences = prefs,
            isSystemInDarkTheme = isSystemInDarkTheme()
        )
        val currentId = repo.getCurrentWallpaperId()
        val prevId = repo.getPreviousWallpaperId()
        val next = matcher.selectNextWallpaper(
            candidates = activePool,
            targetBrightness = solarSnapshot.targetBrightness,
            preferences = prefs,
            currentWallpaperId = currentId,
            recentlyUsedIds = setOfNotNull(currentId, prevId)
        ) ?: return buildSnapshot(repo, "No additional eligible wallpapers found for current filter.", preloadedPrefs = prefs)

        return applyRecordAndBuildSnapshot(repo, next, prefs)
    }

    override suspend fun applyPreviousWallpaper(): SunloomUiState = withGatewayIo { repo ->
        val prevId = repo.getPreviousWallpaperId()
            ?: return@withGatewayIo buildSnapshot(repo, "No previous wallpaper recorded in history yet.")
        applyWallpaperInternal(repo, prevId)
    }

    /**
     * Article II §2: Restores the user's saved baseline wallpaper if configured, or clears to the
     * OS system default wallpaper — never silently falling back to `previousWallpaperId`.
     */
    override suspend fun restoreBaselineWallpaper(): SunloomUiState = withGatewayIo { repo ->
        val baseline = repo.getAllWallpapers().firstOrNull { it.isBaselineRestore && !it.isRejected }
        if (baseline != null) {
            applyWallpaperInternal(repo, baseline.id)
        } else {
            restoreSystemDefaultInternal(repo)
        }
    }

    override suspend fun restoreSystemDefaultWallpaper(): SunloomUiState = withGatewayIo { repo ->
        restoreSystemDefaultInternal(repo)
    }

    private fun restoreSystemDefaultInternal(repo: LocalLibraryRepository): SunloomUiState {
        val prefs = repo.loadPreferences()
        val ok = platformAdapter.restoreSystemDefaultWallpaper(dryRunPreviewOnly = prefs.dryRunPreviewOnly)
        return if (ok) {
            repo.clearCurrentWallpaperId()
            buildSnapshot(repo, "Restored GoogleBookOS system default wallpaper.", preloadedPrefs = prefs)
        } else {
            buildSnapshot(repo, "System wallpaper restore was not permitted by OS policy.", preloadedPrefs = prefs)
        }
    }

    override suspend fun toggleKeepWallpaper(recordId: String): SunloomUiState = withGatewayIo { repo ->
        val current = repo.getWallpaperById(recordId) ?: return@withGatewayIo buildSnapshot(repo, "Wallpaper not found.")
        val nextFav = !current.isFavorite
        repo.setFavorite(recordId, nextFav)
        val verb = if (nextFav) "Added '${current.title}' to Favorites" else "Removed '${current.title}' from Favorites"
        buildSnapshot(repo, verb)
    }

    override suspend fun rejectWallpaperForever(recordId: String): SunloomUiState = withGatewayIo { repo ->
        val current = repo.getWallpaperById(recordId) ?: return@withGatewayIo buildSnapshot(repo, "Wallpaper not found.")
        val wasActive = repo.getCurrentWallpaperId() == recordId
        repo.rejectWallpaperForever(recordId, "Rejected by user — permanently excluded from rotation")
        if (!wasActive) {
            // Already switched away (undo window elapsed): just finalize the exclusion.
            return@withGatewayIo buildSnapshot(repo, "Disliked '${current.title}'. It won't be shown again.")
        }
        val nextState = applyNextInternal(repo)
        nextState.copy(
            statusBannerMessage = "Disliked '${current.title}' and switched to '${nextState.currentWallpaper?.title ?: "next wallpaper"}'"
        )
    }

    override suspend fun deleteWallpaperFromReserve(recordId: String): SunloomUiState = withGatewayIo { repo ->
        val target = repo.getWallpaperById(recordId) ?: return@withGatewayIo buildSnapshot(repo, "Wallpaper not found.")
        val wasActive = repo.getCurrentWallpaperId() == recordId
        repo.deleteWallpaperFromReserve(recordId)
        if (wasActive) {
            val nextState = applyNextInternal(repo)
            nextState.copy(
                statusBannerMessage = "Deleted '${target.title}' from reserve"
            )
        } else {
            buildSnapshot(repo, "Deleted '${target.title}' from reserve")
        }
    }

    override suspend fun refreshFromInternet(): SunloomUiState = withGatewayIo { repo ->
        val connectedDisplays = platformAdapter.getConnectedDisplays()
        val display = platformAdapter.getPrimaryOrSharpestDisplayOrNull(connectedDisplays)
            ?: return@withGatewayIo buildSnapshot(repo, "Display dimensions unknown — connect an active display before syncing.")
        var syncFailed = false
        val added = try {
            repo.refreshOnlineCatalog(display, connectedDisplays = connectedDisplays)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            syncFailed = true
            0
        }
        if (repo.lastSyncAttemptedSources > 0 && repo.lastSyncFailedSources >= repo.lastSyncAttemptedSources) {
            syncFailed = true
        }
        val prefs = repo.loadPreferences()
        promoteNonSeedOverSeedIfNeeded(repo, prefs, display, connectedDisplays)
        val targetLabel = if (connectedDisplays.size > 1 && prefs.displayPolicy == DisplayPolicy.SAME_ON_ALL_DISPLAYS) {
            "${connectedDisplays.size} connected displays"
        } else {
            display.resolutionLabel
        }
        val msg = if (added > 0) {
            "Prepared $added new high-resolution wallpaper(s) for $targetLabel"
        } else if (syncFailed) {
            "Couldn't reach the online catalogs. Your saved reserve is still available; try again when you're connected."
        } else {
            "Reserve is up to date for $targetLabel"
        }
        buildSnapshot(repo, msg, preloadedPrefs = prefs)
    }

    override suspend fun resetSourceFailureIfTransient(sourceId: String) {
        withContext(ioDispatcher) {
            runCatching { obtainRepository().clearTransientSourceFailureOnReenable(sourceId) }
        }
    }

    override suspend fun importLocalUri(uriString: String, asBaselineRestore: Boolean): SunloomUiState = withGatewayIo { repo ->
        val display = platformAdapter.getPrimaryOrSharpestDisplayOrNull()
            ?: return@withGatewayIo buildSnapshot(repo, "Display dimensions unknown — waiting for an active display.")
        val uri = uriString.toUri()
        val record = repo.importUserUri(uri, display, asBaselineRestore)
            ?: return@withGatewayIo buildSnapshot(repo, "Could not import selected image (check format, bounds, or blocklist).")
        if (asBaselineRestore) {
            buildSnapshot(repo, "Saved '${record.title}' as your permanent Original Wallpaper Restore Point.")
        } else {
            applyWallpaperInternal(repo, record.id)
        }
    }

    override suspend fun checkpointDatabaseForBackup() {
        withContext(ioDispatcher) {
            runCatching { obtainRepository().checkpointStore() }
        }
    }

    private fun filterActiveLibraryPool(
        all: List<WallpaperRecord>,
        prefs: UserPreferences
    ): List<WallpaperRecord> {
        return all.filter {
            !it.isRejected &&
                !it.isBaselineRestore &&
                it.status != WallpaperStatus.DISABLED_BY_USER &&
                it.provenance.sourceId in prefs.enabledSources
        }.sortedWith(
            compareBy<WallpaperRecord> { if (it.provenance.sourceId == "seed_reserve") 1 else 0 }
                .thenByDescending { it.provenance.retrievedAtEpochMs }
        )
    }

    private fun buildSnapshot(
        repo: LocalLibraryRepository,
        bannerMessage: String,
        preloadedPrefs: UserPreferences? = null
    ): SunloomUiState {
        val prefs = preloadedPrefs ?: repo.loadPreferences()
        val caps = platformAdapter.probeCapabilities(prefs.displayPolicy)
        val all = repo.getAllWallpapers()
        val nonRejectedNonBaselineCount = all.count { !it.isRejected && !it.isBaselineRestore }
        val activePool = filterActiveLibraryPool(all, prefs)
        val disabledCount = nonRejectedNonBaselineCount - activePool.size
        val totalBytesOnDisk = all.filter { !it.isRejected }.sumOf { it.fileSizeBytes }
        val baseline = all.firstOrNull { it.isBaselineRestore && !it.isRejected }

        val solarSnapshot = solarCalculator.resolveSolarSnapshot(
            preferences = prefs,
            isSystemInDarkTheme = isSystemInDarkTheme()
        )
        val daylight = solarSnapshot.daylightWindow
        val targetBrightness = solarSnapshot.targetBrightness

        val currentId = repo.getCurrentWallpaperId()
        val appliedRecord = activePool.firstOrNull {
            it.id == currentId && (it.isBaselineRestore || it.evaluation.isEligible)
        }
        val hasNonSeedEligible = matcher.filterEligiblePool(activePool, prefs)
            .any { it.provenance.sourceId != "seed_reserve" }

        val current = if (appliedRecord != null &&
            (appliedRecord.provenance.sourceId != "seed_reserve" || !hasNonSeedEligible)
        ) {
            appliedRecord
        } else {
            matcher.selectNextWallpaper(
                candidates = activePool,
                targetBrightness = targetBrightness,
                preferences = prefs
            ) ?: appliedRecord ?: activePool.firstOrNull()
        }

        return SunloomUiState(
            isInitialLoading = false,
            isStorageUnavailable = false,
            preferences = prefs,
            capabilities = caps,
            currentWallpaper = current,
            isCurrentWallpaperAppliedToSystem = appliedRecord != null,
            previousWallpaperId = repo.getPreviousWallpaperId(),
            baselineWallpaper = baseline,
            libraryWallpapers = activePool,
            disabledReserveCount = disabledCount,
            allCachedBytesUsed = totalBytesOnDisk,
            rejectionLog = repo.getAllRejections(),
            sourceDiagnostics = repo.getSourceDiagnostics(),
            targetBrightnessNow = targetBrightness,
            daylightWindow = daylight,
            statusBannerMessage = bannerMessage
        )
    }
}

/**
 * True when changing from [previous] to this affects which reserve items are eligible, which are
 * active, or how much cache may be kept, i.e. when the heavy reserve reconcile must run. Pure
 * schedule, location, network and lock-destination edits do not.
 */
internal fun UserPreferences.affectsReserve(previous: UserPreferences): Boolean =
    isSetupCompleted != previous.isSetupCompleted ||
        enabledCategories != previous.enabledCategories ||
        enabledSources != previous.enabledSources ||
        displayPolicy != previous.displayPolicy ||
        allowBlurredFrameFill != previous.allowBlurredFrameFill ||
        maxCacheBytes != previous.maxCacheBytes ||
        baselineRestorePath != previous.baselineRestorePath
