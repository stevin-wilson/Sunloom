package com.sunloom.app.ui

import android.content.Context
import android.content.res.Configuration
import androidx.core.net.toUri
import com.sunloom.app.R
import com.sunloom.app.appContainer
import com.sunloom.app.data.repository.LibraryStoreUnavailableException
import com.sunloom.app.data.repository.LocalLibraryRepository
import com.sunloom.app.domain.matcher.SubjectFit
import com.sunloom.app.domain.matcher.TasteProfile
import com.sunloom.app.domain.matcher.WallpaperMatcher
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.model.WallpaperStatus
import com.sunloom.app.domain.solar.SolarCalculator
import com.sunloom.app.platform.GoogleBookPlatformAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            resolvedRepository ?: context.appContainer().newRepository().also {
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
                    statusBannerMessage = context.getString(R.string.msg_local_wallpaper_database_is_unavailable)
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
        repo.healMissingFiles()
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
                bannerMessage = context.getString(R.string.msg_display_dimensions_unknown_waiting_for),
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
                bannerMessage = context.getString(R.string.msg_display_dimensions_unknown_waiting_for),
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
                context.resources.getQuantityString(R.plurals.msg_adapted_multi_display, connectedDisplays.size, connectedDisplays.size)
            } else {
                context.getString(R.string.msg_adapted_wallpaper_for, display.name, display.resolutionLabel)
            }
        } else {
            "Ready"
        }
        buildSnapshot(repo, banner, preloadedPrefs = prefs)
    }

    override suspend fun forgetRememberedDisplay(key: String): SunloomUiState = withGatewayIo { repo ->
        platformAdapter.forgetRememberedDisplay(key)
        buildSnapshot(repo, context.getString(R.string.msg_removed_remembered_display_from_history))
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
                maxResults = 3,
                taste = TasteProfile.from(repo.getAllWallpapers()),
                subjectFit = SubjectFit.forDisplays(connectedDisplays, prefs)
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
                    currentWallpaperId = currentId,
                    subjectFit = SubjectFit.forDisplays(connectedDisplays, prefs)
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
            cleanPreferences.isPaused -> context.getString(R.string.msg_automatic_changes_paused)
            cleanPreferences.autoChangeEnabled -> context.getString(R.string.msg_schedule_active, cleanPreferences.scheduleMode.label)
            else -> context.getString(R.string.msg_manual_mode_active)
        }
        buildSnapshot(repo, msg, preloadedPrefs = cleanPreferences)
    }

    override suspend fun applyWallpaper(recordId: String): SunloomUiState = withGatewayIo { repo ->
        applyWallpaperInternal(repo, recordId)
    }

    private fun applyWallpaperInternal(repo: LocalLibraryRepository, recordId: String): SunloomUiState {
        val record = repo.getWallpaperById(recordId)
            ?: return buildSnapshot(repo, context.getString(R.string.msg_wallpaper_record_not_found_in))
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
                bannerMessage = context.getString(R.string.msg_display_dimensions_unknown_waiting_for_2, record.title),
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
                context.resources.getQuantityString(R.plurals.msg_connected_displays, connectedDisplays.size, connectedDisplays.size)
            } else {
                display.name
            }
            buildSnapshot(
                repo = repo,
                bannerMessage = context.getString(R.string.msg_applied_for, record.title, record.evaluation.brightnessClass.displayName, targetName),
                preloadedPrefs = prefs
            )
        } else {
            // Say what happened in plain words (Article V §2). Only a temporary failure is worth retrying;
            // an unsupported host will refuse again, so it gets an explanation instead of a button.
            val retryable = status == WallpaperStatus.FAILED_TEMPORARILY
            buildSnapshot(
                repo = repo,
                bannerMessage = when (status) {
                    WallpaperStatus.FAILED_TEMPORARILY ->
                        context.getString(R.string.msg_couldn_t_set_just_now, record.title)
                    WallpaperStatus.UNSUPPORTED_BY_SYSTEM ->
                        context.getString(R.string.msg_this_system_doesn_t_allow)
                    else -> context.getString(R.string.msg_status_while_applying, status.label, record.title)
                },
                preloadedPrefs = prefs,
                retryApplyRecordId = record.id.takeIf { retryable }
            )
        }
    }

    override suspend fun applyNextWallpaper(excludeIds: Set<String>): SunloomUiState = withGatewayIo { repo ->
        applyNextInternal(repo, excludeIds)
    }

    private fun applyNextInternal(repo: LocalLibraryRepository, excludeIds: Set<String> = emptySet()): SunloomUiState {
        val prefs = repo.loadPreferences()
        val all = repo.getAllWallpapers()
        val activePool = filterActiveLibraryPool(all, prefs).filter { it.id !in excludeIds }
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
            recentlyUsedIds = setOfNotNull(currentId, prevId),
            taste = TasteProfile.from(all),
            subjectFit = SubjectFit.forDisplays(platformAdapter.getConnectedDisplays(), prefs)
        ) ?: return buildSnapshot(repo, context.getString(R.string.msg_no_additional_eligible_wallpapers_found), preloadedPrefs = prefs)

        return applyRecordAndBuildSnapshot(repo, next, prefs)
    }

    override suspend fun applyPreviousWallpaper(): SunloomUiState = withGatewayIo { repo ->
        val prevId = repo.getPreviousWallpaperId()
            ?: return@withGatewayIo buildSnapshot(repo, context.getString(R.string.msg_no_previous_wallpaper_recorded_in))
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
            buildSnapshot(repo, context.getString(R.string.msg_restored_googlebookos_system_default_wallpaper), preloadedPrefs = prefs)
        } else {
            buildSnapshot(repo, context.getString(R.string.msg_system_wallpaper_restore_was_not), preloadedPrefs = prefs)
        }
    }

    override suspend fun toggleKeepWallpaper(recordId: String): SunloomUiState = withGatewayIo { repo ->
        val current = repo.getWallpaperById(recordId) ?: return@withGatewayIo buildSnapshot(repo, context.getString(R.string.msg_wallpaper_not_found))
        val nextFav = !current.isFavorite
        repo.setFavorite(recordId, nextFav)
        val verb = if (nextFav) context.getString(R.string.msg_added_to_favorites, current.title) else context.getString(R.string.msg_removed_from_favorites, current.title)
        buildSnapshot(repo, verb)
    }

    override suspend fun rejectWallpaperForever(recordId: String): SunloomUiState = withGatewayIo { repo ->
        val current = repo.getWallpaperById(recordId) ?: return@withGatewayIo buildSnapshot(repo, context.getString(R.string.msg_wallpaper_not_found))
        val wasActive = repo.getCurrentWallpaperId() == recordId
        repo.rejectWallpaperForever(recordId, "Rejected by user — permanently excluded from rotation")
        if (!wasActive) {
            // Already switched away (undo window elapsed): just finalize the exclusion.
            return@withGatewayIo buildSnapshot(repo, context.getString(R.string.msg_disliked_it_won_t_be, current.title))
        }
        val nextState = applyNextInternal(repo)
        nextState.copy(
            statusBannerMessage = context.getString(R.string.msg_disliked_and_switched_to, current.title, nextState.currentWallpaper?.title ?: context.getString(R.string.msg_next_wallpaper_fallback)),
            retryApplyRecordId = null
        )
    }

    override suspend fun deleteWallpaperFromReserve(recordId: String): SunloomUiState = withGatewayIo { repo ->
        val target = repo.getWallpaperById(recordId) ?: return@withGatewayIo buildSnapshot(repo, context.getString(R.string.msg_wallpaper_not_found))
        val wasActive = repo.getCurrentWallpaperId() == recordId
        repo.deleteWallpaperFromReserve(recordId)
        if (wasActive) {
            val nextState = applyNextInternal(repo)
            nextState.copy(
                statusBannerMessage = context.getString(R.string.msg_deleted_from_reserve, target.title),
                retryApplyRecordId = null
            )
        } else {
            buildSnapshot(repo, context.getString(R.string.msg_deleted_from_reserve, target.title))
        }
    }

    override suspend fun refreshFromInternet(): SunloomUiState = withGatewayIo { repo ->
        val connectedDisplays = platformAdapter.getConnectedDisplays()
        val display = platformAdapter.getPrimaryOrSharpestDisplayOrNull(connectedDisplays)
            ?: return@withGatewayIo buildSnapshot(repo, context.getString(R.string.msg_display_dimensions_unknown_connect_an))
        var syncFailed = false
        val added = try {
            repo.refreshOnlineCatalog(display, connectedDisplays = connectedDisplays)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            syncFailed = true
            0
        } catch (_: OutOfMemoryError) {
            syncFailed = true
            0
        }
        if (repo.lastSyncAttemptedSources > 0 && repo.lastSyncFailedSources >= repo.lastSyncAttemptedSources) {
            syncFailed = true
        }
        val prefs = repo.loadPreferences()
        promoteNonSeedOverSeedIfNeeded(repo, prefs, display, connectedDisplays)
        val targetLabel = if (connectedDisplays.size > 1 && prefs.displayPolicy == DisplayPolicy.SAME_ON_ALL_DISPLAYS) {
            context.resources.getQuantityString(R.plurals.msg_connected_displays, connectedDisplays.size, connectedDisplays.size)
        } else {
            display.resolutionLabel
        }
        val msg = if (added > 0) {
            context.resources.getQuantityString(R.plurals.msg_prepared_new, added, added, targetLabel)
        } else if (syncFailed) {
            context.getString(R.string.msg_couldn_t_reach_the_online)
        } else {
            context.getString(R.string.msg_reserve_is_up_to_date, targetLabel)
        }
        buildSnapshot(repo, msg, preloadedPrefs = prefs)
    }

    override suspend fun resetSourceFailureIfTransient(sourceId: String) {
        withContext(ioDispatcher) {
            runCatching { obtainRepository().clearTransientSourceFailureOnReenable(sourceId) }
        }
    }

    override suspend fun importLocalUri(uriString: String, asBaselineRestore: Boolean, applyAfterImport: Boolean): SunloomUiState = withGatewayIo { repo ->
        val display = platformAdapter.getPrimaryOrSharpestDisplayOrNull()
            ?: return@withGatewayIo buildSnapshot(repo, context.getString(R.string.msg_display_dimensions_unknown_waiting_for))
        val uri = uriString.toUri()
        val record = repo.importUserUri(uri, display, asBaselineRestore)
            ?: return@withGatewayIo buildSnapshot(repo, context.getString(R.string.msg_could_not_import_selected_image))
        if (asBaselineRestore) {
            buildSnapshot(repo, context.getString(R.string.msg_saved_as_your_permanent_original, record.title))
        } else if (!applyAfterImport) {
            buildSnapshot(repo, context.getString(R.string.msg_added_to_your_reserve, record.title))
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
        preloadedPrefs: UserPreferences? = null,
        retryApplyRecordId: String? = null
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
            // A preview of "what would be next" must not change on every snapshot rebuild.
            matcher.selectNextWallpaper(
                candidates = activePool,
                targetBrightness = targetBrightness,
                preferences = prefs,
                deterministic = true,
                taste = TasteProfile.from(all),
                subjectFit = SubjectFit.forDisplays(caps.connectedDisplays, prefs)
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
            statusBannerMessage = bannerMessage,
            retryApplyRecordId = retryApplyRecordId
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
