package com.sunloom.app.ui

import com.sunloom.app.platform.scheduler.BackgroundDownloadPolicy
import com.sunloom.app.platform.scheduler.DownloadBlock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sunloom.app.domain.matcher.ReserveBuffer
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.displaySetGeometryKey
import com.sunloom.app.domain.solar.CityCoordinates
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Automatic top-ups (launch, setup, Next) are spaced at least this far apart after one that added images. */
internal const val AUTO_REPLENISH_MIN_INTERVAL_MS = 10L * 60L * 1000L

/** ... and this far apart after one that found nothing new, so a dead or exhausted source is not hammered. */
internal const val AUTO_REPLENISH_IDLE_INTERVAL_MS = 60L * 60L * 1000L
private const val NEVER = Long.MIN_VALUE

class MainViewModel(
    private val gateway: SunloomGateway,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val roundPauseMs: Long = 2_000L,
    private val strings: UiStrings = UiStrings.English,
    private val appInfo: () -> com.sunloom.app.diagnostics.AppInfo = { com.sunloom.app.diagnostics.AppInfo.current() },
    /** The update check, or null in a build that has none (the Play build). */
    private val updateCheck: com.sunloom.app.update.UpdateCheckFeature? = com.sunloom.app.update.updateCheckFeature()
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SunloomUiState(
            isInitialLoading = true,
            statusBannerMessage = strings.loadingLibrary
        )
    )
    val uiState: StateFlow<SunloomUiState> = _uiState.asStateFlow()
    private val stateMutex = Mutex()
    private val syncMutex = Mutex()

    companion object {
        /** Fetch rounds per top-up; each round polls up to three sources within their request budgets. */
        const val MAX_TOP_UP_ROUNDS = 4

        /** The opt-in update check asks at most this often. */
        private const val UPDATE_CHECK_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    }

    /** Preference edits whose save has not landed yet; their optimistic values must outlive other results. */
    private val pendingPreferenceEdits = AtomicInteger(0)
    // Declared before `init`: with an eager dispatcher the init coroutine publishes state immediately.

    /** The display-set geometry the reserve was last topped up for; swapped atomically so a burst fetches once. */
    private val lastDisplayGeometryKey = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** When the last automatic fetch started; declared before `init`, which may trigger one. */
    @Volatile
    private var lastAutoReplenishMs: Long = NEVER
    private val replenishMutex = Mutex()
    @Volatile
    private var lastAutoReplenishInterval: Long = AUTO_REPLENISH_MIN_INTERVAL_MS

    init {
        viewModelScope.launch(workDispatcher) {
            stateMutex.withLock {
                val before = _uiState.value
                val loaded = gateway.loadInitialSnapshot()
                _uiState.update { cur ->
                    loaded.copy(
                        activeTab = if (cur.activeTab != before.activeTab) cur.activeTab else loaded.activeTab,
                        showLegibilityZonesOverlay = if (cur.showLegibilityZonesOverlay != before.showLegibilityZonesOverlay) {
                            cur.showLegibilityZonesOverlay
                        } else {
                            loaded.showLegibilityZonesOverlay
                        },
                        isInitialLoading = false
                    )
                }
            }
            replenishIfLow()
            checkForUpdatesIfDue()
        }
    }

    /**
     * Article X §3: keep a buffer of unseen images. Fetches in the background when it runs low. Setups that
     * restrict downloads to Wi-Fi are left to the scheduled worker, which can check the connection.
     */
    private suspend fun replenishIfLow(requireSetup: Boolean = true) {
        val state = _uiState.value
        if (requireSetup && !state.preferences.isSetupCompleted) return
        if (state.isSyncingOnline) return
        if (!state.reserveNeedsTopUp) return
        // Unmetered-only (the default) means not over a metered network, not "never from here": at home this still runs.
        val network = gateway.networkConditions()
        if (BackgroundDownloadPolicy.networkBlock(state.preferences.wifiOnlyDownloads, network.unmetered, network.dataSaverRestricting) !=
            DownloadBlock.NONE
        ) return
        // Filters (resolution, near-duplicates, creator cap) can leave the count low after a fetch; do not
        // start another one on every launch or Next press.
        val now = clockMs()
        if (lastAutoReplenishMs != NEVER && now - lastAutoReplenishMs < lastAutoReplenishInterval) return
        if (!replenishMutex.tryLock()) return // another trigger is already topping up
        try {
            val added = topUpReserve(MAX_TOP_UP_ROUNDS, forceFirstRound = false)
            // A sync that never ran (another one held the lock) must not start the throttle.
            if (added != null) {
                lastAutoReplenishMs = now
                lastAutoReplenishInterval =
                    if (added > 0) AUTO_REPLENISH_MIN_INTERVAL_MS else AUTO_REPLENISH_IDLE_INTERVAL_MS
            }
        } finally {
            replenishMutex.unlock()
        }
    }

    /**
     * Fetches in rounds until each mode holds [ReserveBuffer.TARGET_UNSEEN_PER_MODE] unseen images, a round adds
     * nothing, or [maxRounds] is reached. One round polls at most three sources within their request budgets
     * (Article X §4), so filling the buffer takes several. Returns how many images were added, or null when the
     * first round could not run because another sync was in progress.
     */
    private suspend fun topUpReserve(maxRounds: Int, forceFirstRound: Boolean): Int? {
        var total = 0
        for (round in 1..maxRounds) {
            val skipCheck = round == 1 && forceFirstRound
            if (!skipCheck && !_uiState.value.reserveNeedsTopUp) break
            val before = _uiState.value.fetchedStockCount
            if (!syncOnlineCatalogNow()) return if (round == 1) null else total
            val gained = _uiState.value.fetchedStockCount - before
            if (gained <= 0) break
            total += gained
            if (!_uiState.value.reserveNeedsTopUp) break
            if (round < maxRounds && roundPauseMs > 0L) delay(roundPauseMs)
        }
        return total
    }


    /**
     * Publishes a gateway result without undoing what the user did while it was being computed: the tab,
     * overlay and pending undo come from the state at the moment of writing, as do the preferences when a
     * newer edit is still being saved (otherwise a late result would flip that control back for a moment).
     * Runs inside [MutableStateFlow.update], so the merge is atomic with respect to main-thread edits.
     */
    private fun publish(
        next: SunloomUiState,
        preserveNavigation: Boolean = true,
        ownPreferenceEdit: Boolean = false,
        finish: (SunloomUiState) -> SunloomUiState = { it }
    ) {
        val newerEdits = pendingPreferenceEdits.get() - if (ownPreferenceEdit) 1 else 0
        _uiState.update { cur ->
            var merged = next.copy(
                pendingDiscard = cur.pendingDiscard,
                isSetupReopened = cur.isSetupReopened,
                updateCheck = cur.updateCheck,
                isInitialLoading = false
            )
            if (preserveNavigation) {
                merged = merged.copy(
                    activeTab = cur.activeTab,
                    showLegibilityZonesOverlay = cur.showLegibilityZonesOverlay
                )
            }
            if (newerEdits > 0) merged = merged.copy(preferences = cur.preferences)
            finish(merged)
        }
    }

    private fun launchGatewayMutation(
        preserveNavigation: Boolean = true,
        preferenceEdit: Boolean = false,
        block: suspend (SunloomUiState) -> SunloomUiState
    ): Job {
        if (preferenceEdit) pendingPreferenceEdits.incrementAndGet()
        return viewModelScope.launch(workDispatcher) {
            try {
                stateMutex.withLock {
                    val next = try {
                        block(_uiState.value)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Nothing above this scope handles exceptions, so a storage error (disk full, SQLite) would
                        // end the process. Keep the screen as it was and say so.
                        android.util.Log.w("MainViewModel", "action_failed", e)
                        _uiState.value.copy(statusBannerMessage = strings.storageProblem, importProgress = null)
                    }
                    publish(next, preserveNavigation, ownPreferenceEdit = preferenceEdit)
                }
            } finally {
                if (preferenceEdit) pendingPreferenceEdits.decrementAndGet()
            }
        }
    }

    /** Like [launchGatewayMutation] but surfaces `isApplyingWallpaper` while the (slow) apply runs. */
    private fun launchApplyMutation(block: suspend (SunloomUiState) -> SunloomUiState) {
        _uiState.update { s -> s.copy(isApplyingWallpaper = true) }
        viewModelScope.launch(workDispatcher) {
            try {
                stateMutex.withLock {
                    val next = try {
                        block(_uiState.value)
                    } catch (e: CancellationException) {
                        _uiState.update { s -> s.copy(isApplyingWallpaper = false) }
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("MainViewModel", "apply_failed", e)
                        _uiState.value.copy(statusBannerMessage = strings.storageProblem)
                    }
                    publish(next) { it.copy(isApplyingWallpaper = false) }
                }
            } finally {
                if (_uiState.value.isApplyingWallpaper) {
                    _uiState.update { s -> s.copy(isApplyingWallpaper = false) }
                }
            }
        }
    }

    /** Shows a short result message in the status banner (used by one-shot platform actions like export). */
    fun announce(message: String) {
        _uiState.update { s -> s.copy(statusBannerMessage = message, retryApplyRecordId = null) }
    }

    fun selectTab(tab: DesktopNavTab) {
        _uiState.update { s -> s.copy(activeTab = tab) }
    }

    fun toggleLegibilityOverlay() {
        _uiState.update { s -> s.copy(
            showLegibilityZonesOverlay = !s.showLegibilityZonesOverlay
        ) }
    }

    fun completeFirstRunSetup(updatedPreferences: UserPreferences) {
        val completed = updatedPreferences.copy(isSetupCompleted = true)
        _uiState.update { s -> s.copy(isSetupReopened = false) }
        val saved = launchGatewayMutation(preserveNavigation = false) {
            val newState = gateway.savePreferences(completed)
            newState.copy(
                activeTab = DesktopNavTab.TODAY_CANVAS,
                showLegibilityZonesOverlay = _uiState.value.showLegibilityZonesOverlay,
                statusBannerMessage = if (completed.autoChangeEnabled) {
                    strings.scheduleEnabled(completed.scheduleMode.label)
                } else {
                    strings.manualModeEnabled
                }
            )
        }
        viewModelScope.launch(workDispatcher) {
            saved.join()
            replenishIfLow()
        }
    }

    fun updatePreferences(transform: (UserPreferences) -> UserPreferences) {
        // Optimistic: reflect the change in the controls immediately (Doherty threshold) while the
        // persisted save + reconcile runs; the gateway snapshot then replaces this state.
        val previous = _uiState.value.preferences
        val updated = transform(previous)
        if (updated != previous) {
            _uiState.update { s -> s.copy(preferences = updated) }
        }
        launchGatewayMutation(preferenceEdit = true) { current ->
            try {
                gateway.savePreferences(updated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                current.copy(
                    preferences = previous,
                    statusBannerMessage = strings.settingNotSaved,
                    retryApplyRecordId = null
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

    /**
     * The "Automatic switching" switch. Setup is the only other place this is chosen, so without this switch
     * someone who turned it off there could never turn it back on.
     */
    fun setAutoChangeEnabled(enabled: Boolean) {
        updatePreferences { it.copy(autoChangeEnabled = enabled) }
    }

    /**
     * "Copy diagnostics": builds the report off the main thread (it reads the crash files) and hands it to [onReady],
     * which puts it on the clipboard. Nothing is sent anywhere.
     */
    fun copyDiagnostics(onReady: (String) -> Unit) {
        viewModelScope.launch(workDispatcher) {
            val report = com.sunloom.app.diagnostics.DiagnosticsReport.build(appInfo(), _uiState.value, gateway.recentCrashes())
            onReady(report)
            _uiState.update { s -> s.copy(statusBannerMessage = strings.diagnosticsCopied) }
        }
    }

    /** "Check for updates" (GitHub build): asks, shows that it is checking, then what was found. A no-op where there is none. */
    fun checkForUpdates() {
        val feature = updateCheck ?: return
        if (!beginUpdateCheck()) return
        viewModelScope.launch(workDispatcher) { finishUpdateCheck(feature) }
    }

    /** Marks a check as running; false if one already is. */
    private fun beginUpdateCheck(): Boolean {
        if (_uiState.value.updateCheck == UpdateCheckUiState.Checking) return false
        _uiState.update { s -> s.copy(updateCheck = UpdateCheckUiState.Checking) }
        return true
    }

    private suspend fun finishUpdateCheck(feature: com.sunloom.app.update.UpdateCheckFeature): com.sunloom.app.update.UpdateCheckResult {
        val result = try {
            feature.check(appInfo().versionName)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            com.sunloom.app.update.UpdateCheckResult.Failed
        }
        _uiState.update { s -> s.copy(updateCheck = UpdateCheckUiState.Done(result)) }
        return result
    }

    /**
     * The opt-in weekly check (GitHub build): when the app is opened, if the person turned it on and the last successful
     * check is a week old. The time is recorded only for a check that was actually made, so one that failed offline is
     * tried again at the next opening. An available update is announced once; there is no background job, which would need a
     * notification permission Sunloom never asks for.
     */
    private suspend fun checkForUpdatesIfDue() {
        val feature = updateCheck ?: return
        val prefs = _uiState.value.preferences
        if (!prefs.isSetupCompleted || !prefs.weeklyUpdateCheck) return
        val now = clockMs()
        if (prefs.lastUpdateCheckEpochMs != 0L && now - prefs.lastUpdateCheckEpochMs < UPDATE_CHECK_INTERVAL_MS) return
        if (!beginUpdateCheck()) return
        val result = finishUpdateCheck(feature)
        if (result is com.sunloom.app.update.UpdateCheckResult.Failed) return
        stateMutex.withLock {
            val saved = gateway.savePreferences(_uiState.value.preferences.copy(lastUpdateCheckEpochMs = now))
            _uiState.update { s -> s.copy(preferences = saved.preferences) }
        }
        if (result is com.sunloom.app.update.UpdateCheckResult.Available) {
            _uiState.update { s -> s.copy(statusBannerMessage = strings.updateAvailableNotice(result.version)) }
        }
    }

    /** "Reset Sunloom": erase everything and start again from first-run setup. */
    fun resetSunloom() {
        // Whatever was waiting to be undone goes with the data it referred to.
        _uiState.update { s -> s.copy(pendingDiscard = null, isSetupReopened = false) }
        launchGatewayMutation(preserveNavigation = false) {
            gateway.resetSunloom().copy(activeTab = DesktopNavTab.TODAY_CANVAS, statusBannerMessage = strings.resetDone)
        }
    }

    /**
     * "Setup Guide": show the first-run screen again, starting from the current choices. Nothing is saved until the
     * person finishes it, so leaving mid-way or relaunching costs nothing and no reserve work runs.
     */
    fun reopenSetup() {
        _uiState.update { s -> s.copy(isSetupReopened = true) }
    }

    fun togglePauseAutomation() {
        updatePreferences { it.copy(isPaused = !it.isPaused) }
    }

    fun changeToNextWallpaper() {
        launchGatewayMutation { gateway.applyNextWallpaper() }
    }

    private val nextInFlight = AtomicBoolean(false)

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

    /** Set once a metered-network warning has been shown, so the next tap goes ahead; cleared on an unmetered network. */
    @Volatile
    private var meteredWarningShown = false

    fun triggerOnlineSync() {
        if (_uiState.value.isSyncingOnline) return
        viewModelScope.launch(workDispatcher) {
            if (warnOnceOnMeteredNetwork()) return@launch
            // The user asked for fresh images: always fetch once, then keep going while the buffer is short.
            topUpReserve(MAX_TOP_UP_ROUNDS, forceFirstRound = true)
        }
    }

    /**
     * An explicit Refresh is the person's own request, so it is never refused; but on a metered network the first tap
     * says that it may use mobile data and waits for a second one. Returns true when it warned (and so did not fetch).
     */
    private suspend fun warnOnceOnMeteredNetwork(): Boolean {
        val network = gateway.networkConditions()
        if (network.unmetered) {
            meteredWarningShown = false
            return false
        }
        if (meteredWarningShown) return false
        meteredWarningShown = true
        _uiState.update { s -> s.copy(statusBannerMessage = strings.meteredFetchWarning) }
        return true
    }

    /**
     * Immediately switches to the next wallpaper from the local reserve off the main thread,
     * then replenishes online reserve in the background if unseen count is low.
     */
    suspend fun advanceToNextWallpaperSmart() {
        _uiState.update { s -> s.copy(isApplyingWallpaper = true) }
        try {
            stateMutex.withLock {
                val applied = withContext(workDispatcher) {
                    gateway.applyNextWallpaper()
                }
                publish(applied) { it.copy(isApplyingWallpaper = false) }
            }
        } finally {
            if (_uiState.value.isApplyingWallpaper) {
                _uiState.update { s -> s.copy(isApplyingWallpaper = false) }
            }
        }
        if (_uiState.value.reserveNeedsTopUp && !_uiState.value.isSyncingOnline) {
            // Replenish in the background so local Next presses are never blocked by the network.
            viewModelScope.launch(workDispatcher) { replenishIfLow(requireSetup = false) }
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

    /**
     * Removes several reserve items at once (multi-select) behind a single timed Undo, matching the
     * single-item flow of Article XII §3. Unknown ids are ignored.
     */
    fun removeManyFromReserveWithUndo(recordIds: Collection<String>) {
        val known = _uiState.value.libraryWallpapers.filter { it.id in recordIds }
        if (known.isEmpty()) return
        if (known.size == 1) {
            beginDiscard(known.first().id, known.first().title, DiscardKind.REMOVE)
            return
        }
        beginDiscard(
            recordId = known.first().id,
            title = strings.wallpaperCount(known.size),
            kind = DiscardKind.REMOVE,
            additionalRecordIds = known.drop(1).map { it.id }
        )
    }

    private fun beginDiscard(
        recordId: String,
        title: String,
        kind: DiscardKind,
        additionalRecordIds: List<String> = emptyList()
    ) {
        commitPendingDiscard() // only one undoable action at a time; finalize the previous one
        val currentId = _uiState.value.currentWallpaper?.id
        val wasCurrent = currentId != null && (currentId == recordId || currentId in additionalRecordIds)
        _uiState.update { s -> s.copy(
            pendingDiscard = PendingDiscard(
                token = ++nextDiscardToken,
                recordId = recordId,
                title = title,
                kind = kind,
                wasCurrent = wasCurrent,
                additionalRecordIds = additionalRecordIds,
                currentRecordId = currentId
            )
        ) }
        if (wasCurrent) {
            val hidden = (listOf(recordId) + additionalRecordIds).toSet()
            launchGatewayMutation { gateway.applyNextWallpaper(hidden) }
        }
    }

    /** Finalizes the pending Dislike/Remove (snackbar timed out, new action started, or app stopped). */
    fun commitPendingDiscard() {
        val pending = _uiState.value.pendingDiscard ?: return
        _uiState.update { s -> s.copy(pendingDiscard = null) }
        when (pending.kind) {
            DiscardKind.DISLIKE -> launchGatewayMutation { gateway.rejectWallpaperForever(pending.recordId) }
            DiscardKind.REMOVE -> launchGatewayMutation { current ->
                var state = current
                for (id in pending.allRecordIds) state = gateway.deleteWallpaperFromReserve(id)
                state
            }
        }
    }

    /** Cancels the pending Dislike/Remove and, if it was the active wallpaper, brings it back. */
    fun undoPendingDiscard() {
        val pending = _uiState.value.pendingDiscard ?: return
        _uiState.update { s -> s.copy(pendingDiscard = null) }
        if (pending.wasCurrent) {
            launchGatewayMutation { gateway.applyWallpaper(pending.currentRecordId ?: pending.recordId) }
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
    suspend fun syncOnlineCatalogNow(): Boolean {
        if (!syncMutex.tryLock()) return false
        val prevBanner = _uiState.value.statusBannerMessage
        val prevRetry = _uiState.value.retryApplyRecordId
        val prevCanRetrySync = _uiState.value.canRetrySync
        _uiState.update { s -> s.copy(
            isSyncingOnline = true,
            statusBannerMessage = strings.fetchingCatalogs,
            retryApplyRecordId = null,
            canRetrySync = false
        ) }
        try {
            val refreshed = withContext(workDispatcher) {
                gateway.refreshFromInternet()
            }
            stateMutex.withLock {
                publish(refreshed) { it.copy(isSyncingOnline = false) }
            }
        } catch (cancel: CancellationException) {
            _uiState.update { s -> s.copy(
                isSyncingOnline = false,
                statusBannerMessage = prevBanner,
                retryApplyRecordId = prevRetry,
                canRetrySync = prevCanRetrySync
            ) }
            throw cancel
        } catch (t: Exception) {
            _uiState.update { s -> s.copy(
                isSyncingOnline = false,
                statusBannerMessage = strings.syncInterrupted(t.message),
                retryApplyRecordId = null,
                canRetrySync = true
            ) }
        } finally {
            if (_uiState.value.isSyncingOnline) {
                _uiState.update { s -> s.copy(isSyncingOnline = false) }
            }
            syncMutex.unlock()
        }
        return true
    }

    fun importLocalUri(uriString: String, asBaselineRestore: Boolean) {
        launchGatewayMutation {
            _uiState.update { s -> s.copy(importProgress = ImportProgress(0, 1), statusBannerMessage = strings.importingPhoto(1, 1)) }
            // Adding a photo must not change the wallpaper behind the person's back: it joins the reserve, and the
            // banner offers Apply now.
            gateway.importLocalUri(uriString, asBaselineRestore, applyAfterImport = false)
        }
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
            val images = gateway.filterImageUris(uriStrings)
            if (images.isEmpty()) return@launchGatewayMutation current
            var state = current
            val total = images.size
            // One image behaves like the picker (imported and applied). A batch only fills the reserve:
            // changing the system wallpaper once per image would flash through all of them.
            val applyAfterImport = images.size == 1
            for ((index, uri) in images.withIndex()) {
                _uiState.update { s ->
                    s.copy(importProgress = ImportProgress(index, total), statusBannerMessage = strings.importingPhoto(index + 1, total))
                }
                state = gateway.importLocalUri(uri, false, applyAfterImport)
            }
            if (images.size > 1) {
                state = state.copy(
                    statusBannerMessage = strings.batchChecked(images.size),
                    applyOfferRecordId = null
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
        val before = displaySetGeometryKey(_uiState.value.capabilities.connectedDisplays)
        val mutation = launchGatewayMutation { gateway.onDisplaysChanged() }
        viewModelScope.launch(workDispatcher) {
            mutation.join()
            // A dock can pause much of the pool (images too small for the new screen), so top it up now instead
            // of waiting for the next launch or the 3-hour worker. A real geometry change is a fresh reason to
            // fetch, so it is not held back by the throttle; a rotation or replug that changes nothing is not.
            // Swapping the remembered key atomically means a burst of events for one dock fetches once.
            val now = displaySetGeometryKey(_uiState.value.capabilities.connectedDisplays)
            if (lastDisplayGeometryKey.getAndSet(now) ?: before != now) {
                lastAutoReplenishMs = NEVER
                replenishIfLow()
            }
        }
    }

    fun forgetRememberedDisplay(key: String) {
        launchGatewayMutation { gateway.forgetRememberedDisplay(key) }
    }

    fun updateDisplayPolicy(policy: DisplayPolicy) {
        if (policy == DisplayPolicy.INDEPENDENT_PER_DISPLAY) return
        updatePreferences { it.copy(displayPolicy = policy) }
    }
}
