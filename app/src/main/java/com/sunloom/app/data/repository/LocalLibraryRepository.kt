package com.sunloom.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.edit
import com.sunloom.app.data.cache.BoundedCacheManager
import com.sunloom.app.data.sources.ArtInstituteCc0SourceAdapter
import com.sunloom.app.data.sources.BudgetedSourceHttpClient
import com.sunloom.app.data.sources.BundledSeedSourceAdapter
import com.sunloom.app.data.sources.ClevelandMuseumCc0SourceAdapter
import com.sunloom.app.data.sources.DefaultHttpsClient
import com.sunloom.app.data.sources.HttpClient
import com.sunloom.app.data.sources.MetMuseumCc0SourceAdapter
import com.sunloom.app.data.sources.NasaLibrarySourceAdapter
import com.sunloom.app.data.sources.SmkOpenSourceAdapter
import com.sunloom.app.data.sources.SourceBudgetExceededException
import com.sunloom.app.data.sources.SourceRegistry
import com.sunloom.app.data.sources.WallpaperSourceAdapter
import com.sunloom.app.data.sources.WikimediaFeaturedSourceAdapter
import com.sunloom.app.domain.evaluator.ImageEvaluator
import com.sunloom.app.domain.model.BlocklistEntry
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.FollowOsVerification
import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.RejectionRecord
import com.sunloom.app.domain.model.SourceDiagnostics
import com.sunloom.app.domain.model.SourceIds
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.model.WallpaperStatus
import com.sunloom.app.domain.model.displaySetSignature
import com.sunloom.app.domain.renderer.CropRenderer
import com.sunloom.app.domain.renderer.DisplayReconciler
import com.sunloom.app.domain.renderer.EnvelopePlanner
import com.sunloom.app.domain.safety.SafetyDenylist
import com.sunloom.app.domain.safety.SafetyGate
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock

/**
 * Articles VII, VIII, IX, X, XI, XII:
 * Local-first repository managing approved wallpapers, quarantine staging,
 * full provenance records, safety rejection logs, persistent blocklist,
 * history stack, and bounded disk cache.
 */
class LocalLibraryRepository(
    context: Context,
    private val safetyGate: SafetyGate = SafetyGate(),
    private val imageEvaluator: ImageEvaluator = ImageEvaluator(),
    private val cropRenderer: CropRenderer = CropRenderer(),
    private val envelopePlanner: EnvelopePlanner = EnvelopePlanner(cropRenderer),
    private val cacheManager: BoundedCacheManager = BoundedCacheManager(),
    private val httpClient: HttpClient = DefaultHttpsClient.shared,
    private val sourceAdapters: List<WallpaperSourceAdapter> = listOf(
        BundledSeedSourceAdapter(),
        WikimediaFeaturedSourceAdapter(httpClient),
        NasaLibrarySourceAdapter(httpClient),
        ArtInstituteCc0SourceAdapter(httpClient),
        MetMuseumCc0SourceAdapter(httpClient),
        ClevelandMuseumCc0SourceAdapter(httpClient),
        SmkOpenSourceAdapter(httpClient)
    ),
    val store: LibraryStore = createDefaultStore(context),
    private val maxSeedRenderPixels: Int = defaultMaxSeedRenderPixels,
    private val random: kotlin.random.Random = kotlin.random.Random.Default
) {
    companion object {
        const val QUARANTINE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        const val DEFAULT_MAX_SOURCES_PER_SYNC = 3
        /**
         * Outcomes of heuristic keyword checks, not verdicts about the content. They are recorded as
         * display-scoped, retryable rejections and swept whenever the denylist version changes (Article VII).
         */
        internal val SOFT_SAFETY_REASONS = setOf("UNRECOGNIZED_SAFE_DOMAIN", "MISSING_SAFETY_TAGS")
        private const val KEY_SOFT_SWEEP_VERSION = "soft_rejections_denylist_version"
        private val LIBRARY_WRITE_LOCK = Any()
        private val SYNC_MUTEX = kotlinx.coroutines.sync.Mutex()
        // Seeds are re-rendered rather than healed.
        private val HEAL_EXEMPT_SOURCES = setOf(SourceIds.SEED_RESERVE)
        private const val KEY_LOST_IMPORTS = "lost_imports_notice"
        private const val SEED_RENDER_CACHE_DIR = "seed_renders"
        private const val KEY_FOLLOW_OS_VERIFIED = "follow_os_verified"

        /**
         * Derived state: things the app worked out about this device and this moment. It is deliberately not in
         * `sunloom_preferences`, the file Auto Backup restores - a restored "verified" claim would vouch for images the
         * restore did not bring back. The backup rules list the files they include, and this is not one of them.
         */
        private const val STATE_FILE = "sunloom_state"
        private const val KEY_LAST_HYGIENE = "last_storage_hygiene_ms"
        private const val HYGIENE_INTERVAL_MS = 24L * 60 * 60 * 1000

        @Volatile
        internal var testStoreFactoryOverride: ((Context) -> LibraryStore)? = null

        @Volatile
        internal var defaultMaxSeedRenderPixels: Int = CropRenderer.MAX_OUTPUT_PIXELS

        @Volatile
        private var sharedDefaultStore: Pair<String, LibraryStore>? = null

        /** The preference files the app writes; a reset empties all of them. */
        private val PREFERENCE_FILES = listOf("sunloom_preferences", "sunloom_display_history", "sunloom_ui", STATE_FILE)

        private fun forgetDefaultStore() {
            synchronized(this) { sharedDefaultStore = null }
        }

        internal fun clearCachedDefaultStoreForTests() {
            synchronized(this) {
                sharedDefaultStore = null
            }
        }

        fun createDefaultStore(context: Context): LibraryStore {
            val appCtx = context.applicationContext
            val key = appCtx.filesDir.absolutePath
            sharedDefaultStore?.let { (cachedKey, store) ->
                if (cachedKey == key) return store
            }
            return synchronized(this) {
                sharedDefaultStore?.let { (cachedKey, store) ->
                    if (cachedKey == key) return@synchronized store
                }
                val created = try {
                    val override = testStoreFactoryOverride
                    if (override != null) {
                        override.invoke(appCtx)
                    } else {
                        val sqlite = SqliteLibraryStore.getInstance(appCtx)
                        sqlite.getAllBlocklistEntries()
                        sqlite
                    }
                } catch (unavailable: LibraryStoreUnavailableException) {
                    throw unavailable
                } catch (err: Exception) {
                    throw LibraryStoreUnavailableException(
                        "Failed to open local SQLite library database.",
                        err
                    )
                }
                sharedDefaultStore = key to created
                created
            }
        }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE)
    private val statePrefs = appContext.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)
    private val sourceHealth = SourceHealthStore(prefs, random)
    private val preferencesStore = PreferencesStore(prefs)
    private val displayReconciler = DisplayReconciler(envelopePlanner, cropRenderer)
    private val blocklist = Blocklist(store)
    val wallpaperDir: File = File(appContext.filesDir, "wallpapers").apply { mkdirs() }

    private val seedReserve = SeedReserve(
        wallpaperDir, safetyGate, imageEvaluator, maxSeedRenderPixels,
        renderCacheDir = File(appContext.cacheDir, SEED_RENDER_CACHE_DIR)
    )

    val quarantineDir: File = File(appContext.noBackupFilesDir, "quarantine").apply { mkdirs() }

    private val libraryHost = object : IngestHost, RevalidationHost {
        override fun recordRejection(rejection: RejectionRecord) = this@LocalLibraryRepository.recordRejection(rejection)

        override fun blocklist(
            assetId: String?,
            sha256: String?,
            perceptualHash: String?,
            sourceId: String,
            reasonCode: String,
            timestampEpochMs: Long
        ) = addPermanentBlocklistEntry(assetId, sha256, perceptualHash, sourceId, reasonCode, timestampEpochMs)

        override fun upsert(record: WallpaperRecord) = synchronized(LIBRARY_WRITE_LOCK) { upsertWallpaper(record) }

        override fun <T> withCurrent(id: String, block: (WallpaperRecord) -> T): T? = synchronized(LIBRARY_WRITE_LOCK) {
            getWallpaperById(id)?.let(block)
        }

        override fun invalidateActive(wallpaperId: String) = clearInvalidatedActiveWallpaper(wallpaperId)

        override fun store(record: WallpaperRecord) {
            synchronized(LIBRARY_WRITE_LOCK) { upsertWallpaper(record) }
        }

        override fun recordSourceFailure(sourceId: String, error: Throwable) = sourceHealth.recordFailure(sourceId, error)
    }

    private val hygiene = StorageHygiene(
        wallpaperDir = wallpaperDir,
        records = { store.getAllWallpapers() },
        deleteRow = { store.deleteWallpaper(it) },
        writeLock = LIBRARY_WRITE_LOCK
    )

    private val ingestor = CandidateIngestor(safetyGate, imageEvaluator, envelopePlanner, wallpaperDir, quarantineDir, libraryHost)
    private val revalidator = Revalidator(safetyGate, imageEvaluator, libraryHost)
    private val userImportPipeline = UserImportPipeline(
        repository = this,
        appContext = appContext,
        wallpaperDir = wallpaperDir,
        quarantineDir = quarantineDir,
        imageEvaluator = imageEvaluator,
        safetyGate = safetyGate
    )

    init {
        migrateLegacyPerceptualHashesToStore()
        sweepStaleQuarantineFiles()
    }

    /**
     * Migrates any legacy SharedPreferences `rejected_perceptual_hashes` entries into the
     * canonical SQLite blocklist once, then deletes the duplicate preference key.
     */
    private fun migrateLegacyPerceptualHashesToStore() {
        if (!prefs.contains("rejected_perceptual_hashes")) return
        val legacyHashes = prefs.getStringSet("rejected_perceptual_hashes", emptySet()).orEmpty()
        val now = System.currentTimeMillis()
        for (rawHash in legacyHashes) {
            val cleanHash = rawHash.trim()
            if (cleanHash.isNotEmpty()) {
                store.addBlocklistEntry(
                    BlocklistEntry(
                        assetId = "",
                        sha256 = "",
                        dHash = cleanHash,
                        sourceId = "legacy_migration",
                        reason = "USER_REJECTED",
                        createdAtEpochMs = now
                    )
                )
            }
        }
        prefs.edit { remove("rejected_perceptual_hashes") }
    }

    fun checkpointStore() {
        store.checkpoint()
    }

    /**
     * Sweeps orphaned files in `noBackupFilesDir/quarantine` older than [maxAgeMs].
     */
    fun sweepStaleQuarantineFiles(
        nowEpochMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = QUARANTINE_MAX_AGE_MS
    ): Int {
        var deleted = 0
        val files = quarantineDir.listFiles() ?: return 0
        for (file in files) {
            if (nowEpochMs - file.lastModified() >= maxAgeMs) {
                if (runCatching { file.delete() }.getOrDefault(false)) {
                    deleted++
                }
            }
        }
        return deleted
    }

    fun loadPreferences(): UserPreferences = preferencesStore.load()

    fun savePreferences(updated: UserPreferences) {
        val saved = preferencesStore.save(updated)
        // Resolution rejections made under the old blur-fill setting no longer apply (Article XI §3).
        if (saved.blurredFillChanged) store.clearTransientRejections()
        syncSourceEnabledStates(saved.enabledSources)
    }

    /**
     * Synchronizes reserve wallpaper statuses when sources are enabled or disabled.
     * Any non-rejected, non-baseline wallpaper from a disabled source is marked
     * [WallpaperStatus.DISABLED_BY_USER], and restored to [WallpaperStatus.PREPARED]
     * when its source is re-enabled.
     */
    fun syncSourceEnabledStates(enabledSources: Set<String>) {
        val currentId = getCurrentWallpaperId()
        var currentWasDisabled = false
        for (record in getAllWallpapers()) {
            if (record.isRejected || record.isBaselineRestore) continue
            val isSourceEnabled = record.provenance.sourceId in enabledSources
            if (!isSourceEnabled && record.status != WallpaperStatus.DISABLED_BY_USER) {
                upsertWallpaper(record.copy(status = WallpaperStatus.DISABLED_BY_USER))
                if (record.id == currentId) {
                    currentWasDisabled = true
                }
            } else if (isSourceEnabled && record.status == WallpaperStatus.DISABLED_BY_USER) {
                upsertWallpaper(record.copy(status = WallpaperStatus.PREPARED))
            }
        }
        if (currentWasDisabled && currentId != null) {
            clearInvalidatedActiveWallpaper(currentId)
        }
    }

    fun getCurrentWallpaperId(): String? = prefs.getString("current_wallpaper_id", null)
    fun getPreviousWallpaperId(): String? = prefs.getString("previous_wallpaper_id", null)
    fun getLastAppliedDisplayGeometryKey(): String? = prefs.getString("last_applied_display_geometry", null)
    fun setLastAppliedDisplayGeometryKey(geometryKey: String) {
        prefs.edit { putString("last_applied_display_geometry", geometryKey) }
    }

    fun clearCurrentWallpaperId() {
        val current = getCurrentWallpaperId()
        prefs.edit {
            if (current != null) {
                putString("previous_wallpaper_id", current)
            }
            remove("current_wallpaper_id")
        }
    }

    /** What the last Follow-OS poll verified (see [FollowOsVerification.key]); null when nothing is. */
    fun getFollowOsVerifiedKey(): String? = statePrefs.getString(KEY_FOLLOW_OS_VERIFIED, null)

    fun setFollowOsVerified(target: BrightnessClass, currentId: String?) {
        statePrefs.edit { putString(KEY_FOLLOW_OS_VERIFIED, FollowOsVerification.key(target, currentId)) }
    }

    /**
     * The person put the system's own wallpaper back. A Follow-OS poll must not cover it up with one of ours until the
     * theme changes, so this is recorded as verified for the current theme.
     */
    fun markSystemDefaultChosen(target: BrightnessClass) = setFollowOsVerified(target, null)

    fun recordWallpaperApplied(wallpaperId: String) {
        val current = getCurrentWallpaperId()
        statePrefs.edit { remove(KEY_FOLLOW_OS_VERIFIED) } // a new wallpaper (or leaving the system default) must be verified afresh
        prefs.edit {
            if (current != null && current != wallpaperId) {
                putString("previous_wallpaper_id", current)
            }
            putString("current_wallpaper_id", wallpaperId)
        }

        val existing = getWallpaperById(wallpaperId) ?: return
        upsertWallpaper(
            existing.copy(
                appliedCount = existing.appliedCount + 1,
                lastAppliedEpochMs = System.currentTimeMillis(),
                status = WallpaperStatus.APPLIED_SUCCESSFULLY
            )
        )
    }

    fun setFavorite(wallpaperId: String, favorite: Boolean) {
        val existing = getWallpaperById(wallpaperId) ?: return
        upsertWallpaper(existing.copy(isFavorite = favorite))
    }

    fun rejectWallpaperForever(wallpaperId: String, reason: String = "User clicked Reject Forever") {
        val existing = getWallpaperById(wallpaperId) ?: return

        // Record the rejection first and delete the files last: if the process dies in between, the picture is still
        // refused (an orphan file is harmless), instead of being deleted but not refused and then fetched again.
        val now = System.currentTimeMillis()
        addPermanentBlocklistEntry(
            assetId = existing.id,
            sha256 = existing.provenance.sha256Checksum,
            perceptualHash = existing.provenance.perceptualHash,
            sourceId = existing.provenance.sourceId,
            reasonCode = "USER_REJECTED",
            timestampEpochMs = now
        )
        recordRejection(
            Rejections.permanent(existing.id, existing.provenance.sourceId, "USER_REJECTED", reason, nowMs = now)
        )
        upsertWallpaper(
            existing.copy(
                isRejected = true,
                isFavorite = false,
                localFilePath = "",
                croppedFilePath = "",
                fileSizeBytes = 0L,
                status = WallpaperStatus.REJECTED_SAFETY_OR_QUALITY,
                decisionExplanation = reason
            )
        )
        if (existing.localFilePath.isNotBlank()) runCatching { File(existing.localFilePath).delete() }
        if (existing.croppedFilePath.isNotBlank()) runCatching { File(existing.croppedFilePath).delete() }
        clearInvalidatedActiveWallpaper(existing.id)
    }

    /**
     * Deletes an individual wallpaper from the local reserve, removing its cached files
     * and recording it in the permanent blocklist so it is not re-downloaded.
     */
    fun deleteWallpaperFromReserve(wallpaperId: String) {
        rejectWallpaperForever(wallpaperId, "Deleted from reserve by user")
    }

    private fun addPermanentBlocklistEntry(
        assetId: String?,
        sha256: String?,
        perceptualHash: String?,
        sourceId: String,
        reasonCode: String,
        timestampEpochMs: Long = System.currentTimeMillis()
    ) = blocklist.add(assetId, sha256, perceptualHash, sourceId, reasonCode, timestampEpochMs)

    fun getPermanentBlockedAssetIds(): Set<String> = blocklist.assetIds()

    fun getPermanentBlockedSha256s(): Set<String> = blocklist.sha256s()

    fun getPermanentBlockedPerceptualHashes(): Set<String> = blocklist.perceptualHashes()

    /**
     * Purges any cached wallpaper from a source that is no longer admitted in [SourceRegistry].
     */
    fun purgeRevokedSources(): Int {
        var purged = 0
        for (record in getAllWallpapers()) {
            if (record.isRejected || record.isBaselineRestore) continue
            if (!SourceRegistry.isSourceAdmitted(record.provenance.sourceId)) {
                if (record.localFilePath.isNotBlank()) runCatching { File(record.localFilePath).delete() }
                if (record.croppedFilePath.isNotBlank()) runCatching { File(record.croppedFilePath).delete() }
                upsertWallpaper(
                    record.copy(
                        isRejected = true,
                        isFavorite = false,
                        localFilePath = "",
                        croppedFilePath = "",
                        fileSizeBytes = 0L,
                        status = WallpaperStatus.REJECTED_SAFETY_OR_QUALITY,
                        decisionExplanation = "Purged: source '${record.provenance.sourceId}' is not admitted in Schedule A."
                    )
                )
                clearInvalidatedActiveWallpaper(record.id)
                purged++
            }
        }
        return purged
    }

    /**
     * Article VII §5–6: Re-validates any non-rejected wallpapers whose stored `safetyGateVersion` differs from
     * [SafetyGate.COMPOSITE_GATE_VERSION], running L1–L4 against both stored metadata and local payload bytes
     * (including seeds). See [Revalidator].
     */
    fun revalidateGateVersionIfNeeded(display: DisplayProfile? = null): Int = revalidator.run(
        records = getAllWallpapers(),
        display = display,
        preferences = loadPreferences(),
        blocked = Revalidator.Blocked(
            ids = getPermanentBlockedAssetIds().toMutableSet(),
            perceptualHashes = getPermanentBlockedPerceptualHashes().toMutableSet(),
            sha256s = getPermanentBlockedSha256s().toMutableSet()
        )
    )

    /**
     * Article V §2 & VI §9: Repository invalidation clears `current_wallpaper_id` without claiming
     * a replacement was applied; only platform `WallpaperManager` apply verification may call
     * [recordWallpaperApplied].
     */
    private fun clearInvalidatedActiveWallpaper(revokedId: String) {
        if (getCurrentWallpaperId() == revokedId) {
            prefs.edit { remove("current_wallpaper_id") }
        }
    }

    fun ensureSeedReserveLoadedForDisplays(
        displays: List<DisplayProfile>,
        displayPolicy: DisplayPolicy = DisplayPolicy.SAME_ON_ALL_DISPLAYS
    ): List<WallpaperRecord> {
        val target = SeedReserve.renderTarget(displays, displayPolicy) ?: return getAllWallpapers()
        return ensureSeedReserveLoaded(target)
    }

    fun ensureSeedReserveLoaded(display: DisplayProfile): List<WallpaperRecord> {
        purgeRevokedSources()
        seedReserve.ensureLoaded(
            display = display,
            existing = getAllWallpapers(),
            blockedIds = getPermanentBlockedAssetIds(),
            blockedPhashes = getPermanentBlockedPerceptualHashes(),
            blockedSha256s = getPermanentBlockedSha256s(),
            store = ::upsertWallpaper
        )
        revalidateGateVersionIfNeeded(display)
        return getAllWallpapers()
    }

    /**
     * Article IV §2 & XI §3: Re-evaluates cached non-seed wallpapers against the currently active
     * [display] and [preferences.allowBlurredFrameFill] without deleting or permanently rejecting
     * under-sized assets, so docking/undocking external monitors adapts non-destructively.
     */
    fun reconcileReserveForDisplay(
        display: DisplayProfile,
        preferences: UserPreferences = loadPreferences()
    ): Boolean {
        return reconcileReserveForDisplaySet(listOf(display), preferences)
    }

    /**
     * Article IV §1–2 & XI §3 (Constitution v0.6): Re-evaluates cached non-seed wallpapers against
     * the active [displays] set and [preferences.displayPolicy] using [EnvelopePlanner] without
     * deleting or permanently rejecting under-sized assets.
     */
    fun reconcileReserveForDisplaySet(
        displays: List<DisplayProfile>,
        preferences: UserPreferences = loadPreferences()
    ): Boolean {
        val validDisplays = displays.filter { it.widthPx > 0 && it.heightPx > 0 }
        if (validDisplays.isEmpty()) return false

        var changedAny = false
        for (snapshot in getAllWallpapers()) {
            // Re-read under the write lock: a rejection or a sync that landed since the snapshot must not be
            // overwritten with the stale copy.
            val changed = synchronized(LIBRARY_WRITE_LOCK) {
                val current = getWallpaperById(snapshot.id) ?: return@synchronized false
                val updated = displayReconciler.reconcile(current, validDisplays, preferences) ?: return@synchronized false
                upsertWallpaper(updated)
                true
            }
            if (changed) changedAny = true
        }
        return changedAny
    }

    fun selectAdaptersForSync(
        preferences: UserPreferences,
        nowEpochMs: Long,
        unseenDarkCount: Int,
        maxSourcesPerSync: Int = DEFAULT_MAX_SOURCES_PER_SYNC,
        display: DisplayProfile? = null
    ): List<WallpaperSourceAdapter> {
        val baseEligible = sourceAdapters.filter { adapter ->
            !adapter.isBuiltInOffline &&
                SourceRegistry.isSourceAdmitted(adapter.sourceId) &&
                adapter.sourceId in preferences.enabledSources &&
                !sourceHealth.isInBackoff(adapter.sourceId, nowEpochMs)
        }
        val eligible = if (display != null) {
            val compatible = baseEligible.filter { adapter ->
                val maxEdge = SourceRegistry.get(adapter.sourceId)?.maxRenditionLongEdgePx ?: Int.MAX_VALUE
                CropRenderer.canSourceSatisfyDisplay(maxEdge, display, preferences.allowBlurredFrameFill)
            }
            compatible.ifEmpty { baseEligible }
        } else {
            baseEligible
        }
        if (eligible.isEmpty() || eligible.size <= maxSourcesPerSync) {
            return eligible
        }

        val cursor = prefs.getInt("online_adapter_cursor", 0).coerceAtLeast(0) % eligible.size
        prefs.edit { putInt("online_adapter_cursor", (cursor + maxSourcesPerSync) % eligible.size) }

        val rotated = eligible.drop(cursor) + eligible.take(cursor)
        if (unseenDarkCount >= 3) {
            return rotated.take(maxSourcesPerSync)
        }

        // Prioritize up to 2 dark-capable photographic sources when evening/night unseen reserve is low
        val darkPriority = rotated.filter { SourceRegistry.get(it.sourceId)?.supportsDarkPhotography == true }.take(2)
        val remainder = rotated.filter { it !in darkPriority }
        return (darkPriority + remainder).take(maxSourcesPerSync)
    }

    /** Sources the most recent [refreshOnlineCatalog] tried, and how many of them failed to fetch. */
    @Volatile
    var lastSyncAttemptedSources: Int = 0
        private set

    @Volatile
    var lastSyncFailedSources: Int = 0
        private set

    /** Bytes the most recent [refreshOnlineCatalog] downloaded, counting pictures it then rejected. */
    @Volatile
    var lastSyncBytesDownloaded: Long = 0L
        private set

    /**
     * One sync at a time per process. The app and the background worker each build their own repository, so without
     * this they can poll the same sources together (doubling the request rate the register budgets) and race on the
     * fetch cursors. A second caller waits, then finds the first one's images already in the library.
     */
    suspend fun refreshOnlineCatalog(
        display: DisplayProfile,
        maxSourcesPerSync: Int = DEFAULT_MAX_SOURCES_PER_SYNC,
        connectedDisplays: List<DisplayProfile> = listOf(display)
    ): Int = SYNC_MUTEX.withLock { refreshOnlineCatalogLocked(display, maxSourcesPerSync, connectedDisplays) }

    private suspend fun refreshOnlineCatalogLocked(
        display: DisplayProfile,
        maxSourcesPerSync: Int,
        connectedDisplays: List<DisplayProfile>
    ): Int {
        val activeDisplaySet = connectedDisplays.filter { it.widthPx > 0 && it.heightPx > 0 }
            .ifEmpty { listOf(display) }
        val currentSetSignature = displaySetSignature(activeDisplaySet)

        purgeRevokedSources()
        revalidateGateVersionIfNeeded(display)
        sweepStaleQuarantineFiles()

        val preferences = loadPreferences()
        sweepSoftSafetyRecordsIfDenylistChanged()
        reconcileReserveForDisplaySet(activeDisplaySet, preferences)
        var newlyAdded = 0
        val allActiveWallpapers = getAllWallpapers().filter { !it.isRejected }
        // Rows without an image file (evicted, or restored from backup) are not "already fetched":
        // they are downloaded again and keep their favourite flag and play history.
        val ghostRows = allActiveWallpapers
            .filter { !it.isBaselineRestore && !hasUsableFile(it) }
            .associateBy { it.id }
        // An image downloaded too small for the connected displays is paused, not lost. If the source later
        // offers a larger rendition of it, it is fetched again like a missing file (below); until then it only
        // waits. Both kinds of row are left out of the duplicate and creator-cap checks, which would otherwise
        // refuse the new copy as a duplicate of its own old row.
        val pausedRows = allActiveWallpapers
            .filter { it.id !in ghostRows && isPausedForResolution(it) }
            .associateBy { it.id }
        val redownloadRows = ghostRows + pausedRows
        val existingIds = allActiveWallpapers.filter { it.id !in redownloadRows }.map { it.id }.toMutableSet()
        val diversityGuard = LibraryDiversityGuard(
            allActiveWallpapers.filter { it.id !in redownloadRows && !it.isBaselineRestore }
        )
        val unseenDarkCount = allActiveWallpapers.count {
            it.id !in ghostRows &&
                !it.isBaselineRestore &&
                it.provenance.sourceId != SourceIds.SEED_RESERVE &&
                it.evaluation.isEligible &&
                it.evaluation.brightnessClass == BrightnessClass.DARK &&
                it.appliedCount == 0
        }
        val permanentlyBlockedIds = getPermanentBlockedAssetIds().toMutableSet()
        val displayScopedRejectedIds = store.getAllRejections()
            .filter { !it.isPermanent && it.displaySignature == currentSetSignature }
            .map { it.assetId }
            .toMutableSet()
        val blockedSha256s = getPermanentBlockedSha256s().toMutableSet()
        val blockedPhashes = getPermanentBlockedPerceptualHashes().toMutableSet()

        val now = System.currentTimeMillis()
        val selectedAdapters = selectAdaptersForSync(
            preferences = preferences,
            nowEpochMs = now,
            unseenDarkCount = unseenDarkCount,
            maxSourcesPerSync = maxSourcesPerSync,
            display = display
        )

        val effectiveTargetWidthPx = activeDisplaySet.maxOf { d ->
            if (d.heightPx > d.widthPx) {
                maxOf(d.widthPx, (d.heightPx * 3) / 2)
            } else {
                d.widthPx
            }
        }

        val state = SyncState(
            display = display,
            activeDisplaySet = activeDisplaySet,
            currentSetSignature = currentSetSignature,
            preferences = preferences,
            existingIds = existingIds,
            permanentlyBlockedIds = permanentlyBlockedIds,
            displayScopedRejectedIds = displayScopedRejectedIds,
            blockedSha256s = blockedSha256s,
            blockedPhashes = blockedPhashes,
            diversityGuard = diversityGuard,
            pausedRows = pausedRows,
            redownloadRows = redownloadRows
        )

        lastSyncAttemptedSources = selectedAdapters.size
        lastSyncFailedSources = 0
        lastSyncBytesDownloaded = 0L
        for (adapter in selectedAdapters) {
            coroutineContext.ensureActive()
            newlyAdded += syncSource(adapter, state, effectiveTargetWidthPx)
        }

        enforceCacheLimit()
        return newlyAdded
    }

    /** Fetches one page from [adapter], vets each candidate and records the source's health; returns how many were added. */
    private suspend fun syncSource(adapter: WallpaperSourceAdapter, state: SyncState, targetWidthPx: Int): Int {
        val reg = SourceRegistry.get(adapter.sourceId)
        val budgetedClient = BudgetedSourceHttpClient(
            delegate = httpClient,
            sourceId = adapter.sourceId,
            maxRequests = reg?.maxRequestsPerSync ?: 6,
            minSpacingMs = reg?.minRequestSpacingMs ?: 150L
        )
        try {
            return syncSourceWith(adapter, budgetedClient, state, targetWidthPx)
        } finally {
            // Data used counts even when the sync is cancelled or the pictures are then rejected.
            lastSyncBytesDownloaded += budgetedClient.bytesDownloaded
        }
    }

    private suspend fun syncSourceWith(
        adapter: WallpaperSourceAdapter,
        budgetedClient: BudgetedSourceHttpClient,
        state: SyncState,
        targetWidthPx: Int
    ): Int {
        val cursor = sourceHealth.fetchCursor(adapter.sourceId)
        val candidates = fetchPage(adapter, budgetedClient, state.preferences, cursor, targetWidthPx) ?: return 0
        sourceHealth.advanceCursor(adapter.sourceId, cursor)
        // Success is recorded after the downloads: metadata answering while every image fails is a failing source.
        val run = SourceRun(adapter.sourceId, budgetedClient)

        for (candidate in candidates) {
            coroutineContext.ensureActive()
            if (!ingestor.ingest(candidate, state, run)) break
        }
        if (!run.failureRecorded) {
            if (run.downloadsTried > 0 && run.downloadsOk == 0 && !run.budgetStopped) {
                lastSyncFailedSources++
                sourceHealth.recordFailure(adapter.sourceId, budgetedClient.lastHttpError)
            } else {
                sourceHealth.recordSuccess(adapter.sourceId)
            }
        }
        sourceHealth.recordSyncSummary(
            adapter.sourceId,
            fetched = candidates.size,
            alreadyHad = run.alreadyHad,
            added = run.added,
            httpErrors = budgetedClient.httpErrorCount
        )
        return run.added
    }

    /**
     * One page of candidates, or null when the source is skipped this sync (its request budget ran out, or it failed;
     * the failure is recorded here).
     */
    private suspend fun fetchPage(
        adapter: WallpaperSourceAdapter,
        budgetedClient: BudgetedSourceHttpClient,
        preferences: UserPreferences,
        cursor: Int,
        targetWidthPx: Int
    ): List<CandidateAsset>? {
        var fetchFailure: Throwable? = null
        val candidates = try {
            adapter.withHttpClient(budgetedClient).fetchCandidates(
                enabledCategories = preferences.enabledCategories,
                limit = 8,
                offset = cursor,
                targetWidthPx = targetWidthPx
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: SourceBudgetExceededException) {
            sourceHealth.advanceCursor(adapter.sourceId, cursor)
            return null
        } catch (err: Exception) {
            fetchFailure = err
            emptyList()
        } catch (err: VirtualMachineError) {
            // One source dying, even with a stack overflow on hostile JSON, must not stop the others.
            fetchFailure = err
            emptyList()
        }
        fetchFailure?.let { err ->
            if (SourceHealthStore.isCancellation(err)) {
                throw CancellationException(err.message).apply { initCause(err) }
            }
            lastSyncFailedSources++
            sourceHealth.recordFailure(adapter.sourceId, err)
            sourceHealth.moveOnIfStuck(adapter.sourceId, cursor)
            sourceHealth.recordSyncSummary(
                adapter.sourceId, fetched = 0, alreadyHad = 0, added = 0,
                httpErrors = budgetedClient.httpErrorCount, failure = err
            )
            return null
        }
        // Adapters swallow HTTP errors and return an empty list. When no request of the source got an answer,
        // that is a failed source, not an "up to date" one. Individual 404s among answered requests (a Met
        // object without an image, say) are normal and do not make a source unhealthy.
        if (candidates.isEmpty() && budgetedClient.successfulRequestCount == 0 && budgetedClient.httpErrorCount > 0) {
            lastSyncFailedSources++
            sourceHealth.recordFailure(adapter.sourceId, budgetedClient.lastHttpError)
            sourceHealth.moveOnIfStuck(adapter.sourceId, cursor)
            sourceHealth.recordSyncSummary(
                adapter.sourceId, fetched = 0, alreadyHad = 0, added = 0, httpErrors = budgetedClient.httpErrorCount
            )
            return null
        }
        return candidates
    }

    fun importUserLocalBitmap(
        title: String,
        bitmap: Bitmap,
        display: DisplayProfile,
        asBaselineRestore: Boolean = false
    ): WallpaperRecord = userImportPipeline.importUserLocalBitmap(title, bitmap, display, asBaselineRestore)

    fun importUserUri(uri: Uri, display: DisplayProfile, asBaselineRestore: Boolean = false): WallpaperRecord? =
        userImportPipeline.importUserUri(uri, display, asBaselineRestore)

    /** True while Light or Dark holds fewer unseen fetched images than the target and the cache has room. */
    fun needsTopUp(preferences: UserPreferences = loadPreferences()): Boolean {
        val all = getAllWallpapers()
        val usable = all.filter { it.provenance.sourceId in preferences.enabledSources }
        val bytes = all.filter { !it.isRejected }.sumOf { it.fileSizeBytes }
        return com.sunloom.app.domain.matcher.ReserveBuffer.needsTopUp(
            usable, preferences.enabledCategories, bytes, preferences.maxCacheBytes
        )
    }

    /** A fetched picture kept on disk but paused because it is too small for the connected displays. */
    private fun isPausedForResolution(record: WallpaperRecord): Boolean =
        !record.isRejected &&
            !record.isBaselineRestore &&
            record.provenance.sourceId != SourceIds.SEED_RESERVE &&
            record.provenance.sourceId != SourceIds.USER_LOCAL &&
            !record.evaluation.isEligible &&
            record.evaluation.rejectionReasonCode == "RESOLUTION_BELOW_DISPLAY_TARGET" &&
            hasUsableFile(record)

    /** True when the record still has a readable, non-empty image file on disk. */
    internal fun hasUsableFile(record: WallpaperRecord): Boolean =
        record.localFilePath.isNotBlank() &&
            record.fileSizeBytes > 0L &&
            File(record.localFilePath).let { it.isFile && it.length() > 0L }

    /**
     * Auto Backup restores the library database but not the image files (Article IX §3), and the cache
     * manager evicts files too. Rows left without a file are marked evicted so counts are honest and the
     * next sync downloads them again. Seeds are re-rendered by [ensureSeedReserveLoaded].
     *
     * A personal photo or the baseline copy cannot be fetched again, but its row is healed the same way: left with a
     * size and a path to a file that is gone, it would count as bytes on disk, push real images out of the cache and
     * hold back top-ups. The row and its provenance stay. Each lost personal photo is counted for a one-time notice
     * ([takeLostImportsNotice]). Returns how many rows changed.
     */
    fun healMissingFiles(): Int {
        var healed = 0
        for (record in getAllWallpapers()) {
            if (record.isRejected || record.provenance.sourceId in HEAL_EXEMPT_SOURCES) continue
            if (record.localFilePath.isBlank() && record.fileSizeBytes == 0L) continue
            if (hasUsableFile(record)) continue
            // Re-read the row: a sync may have re-downloaded it since the list was read, and blanking a fresh
            // record would orphan its new file.
            // The lock is shared with the sync's write of a downloaded record (any repository instance), so the
            // check and the blanking write cannot interleave with a fresh download.
            synchronized(LIBRARY_WRITE_LOCK) {
                val current = getWallpaperById(record.id) ?: return@synchronized
                if (current.isRejected || current.provenance.sourceId in HEAL_EXEMPT_SOURCES || hasUsableFile(current)) {
                    return@synchronized
                }
                if (current.localFilePath.isBlank() && current.fileSizeBytes == 0L) return@synchronized
                upsertWallpaper(current.copy(localFilePath = "", croppedFilePath = "", fileSizeBytes = 0L))
                if (current.provenance.sourceId == SourceIds.USER_LOCAL && !current.isBaselineRestore) {
                    prefs.edit { putInt(KEY_LOST_IMPORTS, prefs.getInt(KEY_LOST_IMPORTS, 0) + 1) }
                }
                healed++
            }
        }
        return healed
    }

    /** How many personal photos were found missing since the last call, then forgets them: the notice is shown once. */
    fun takeLostImportsNotice(): Int = synchronized(LIBRARY_WRITE_LOCK) {
        val count = prefs.getInt(KEY_LOST_IMPORTS, 0)
        if (count != 0) prefs.edit { remove(KEY_LOST_IMPORTS) }
        count
    }

    /**
     * Removes orphaned image files and long-forgotten rows, at most once a day (opening the app and every worker run
     * may call this freely). Returns what was removed, or null when it is not yet due.
     */
    fun runStorageHygieneIfDue(nowEpochMs: Long = System.currentTimeMillis()): StorageHygieneReport? {
        val last = prefs.getLong(KEY_LAST_HYGIENE, 0L)
        if (last != 0L && nowEpochMs - last < HYGIENE_INTERVAL_MS) return null
        // Recorded first: a pass that fails must not retry on every launch.
        prefs.edit { putLong(KEY_LAST_HYGIENE, nowEpochMs) }
        val activeIds = setOfNotNull(getCurrentWallpaperId(), getPreviousWallpaperId())
        return StorageHygieneReport(
            orphanFilesRemoved = hygiene.sweepOrphanedFiles(nowEpochMs),
            staleRowsRemoved = hygiene.compactStaleRows(nowEpochMs, activeIds)
        )
    }

    /**
     * "Reset Sunloom": removes everything the app keeps - library, blocklist, settings, images, staging and export
     * files - so it starts again as if freshly installed. Waits for a running sync and blocks library writers while
     * it works. The caller restores the wallpaper and cancels scheduled work first, and drops this repository
     * afterwards (its store is gone).
     */
    suspend fun wipeAllData() {
        SYNC_MUTEX.withLock {
            synchronized(LIBRARY_WRITE_LOCK) {
                store.wipe()
                forgetDefaultStore()
                PREFERENCE_FILES.forEach { appContext.getSharedPreferences(it, Context.MODE_PRIVATE).edit(commit = true) { clear() } }
                listOf(wallpaperDir, quarantineDir).forEach { dir -> dir.listFiles().orEmpty().forEach { it.deleteRecursively() } }
                appContext.cacheDir.listFiles { f -> f.name.startsWith("export-") }.orEmpty().forEach { it.delete() }
                File(appContext.cacheDir, SEED_RENDER_CACHE_DIR).deleteRecursively()
                SeedReserve.clearSeedRenderCache()
            }
        }
    }

    fun enforceCacheLimit() {
        val prefs = loadPreferences()
        val activeIds = setOfNotNull(getCurrentWallpaperId(), getPreviousWallpaperId())
        val (updated, report) = cacheManager.executeCleanupOnDisk(
            records = getAllWallpapers(),
            activeWallpaperIds = activeIds,
            maxCacheBytes = prefs.maxCacheBytes,
            // The limit is about bytes on disk, not the sizes rows recorded (Article X §3).
            sizeOf = hygiene::diskBytes
        )
        if (report.evictedIds.isNotEmpty()) {
            val evictedSet = report.evictedIds.toSet()
            for (record in updated) {
                if (record.id in evictedSet) {
                    upsertWallpaper(record)
                }
            }
        }
    }

    fun upsertWallpaper(record: WallpaperRecord) {
        store.upsertWallpaper(record)
    }

    fun getWallpaperById(id: String): WallpaperRecord? {
        return store.getWallpaperById(id)
    }

    fun getAllWallpapers(): List<WallpaperRecord> {
        return store.getAllWallpapers()
    }

    fun recordRejection(rejection: RejectionRecord) {
        store.recordRejection(rejection)
    }

    fun getAllRejections(): List<RejectionRecord> {
        return store.getAllRejections()
    }

    // --- Source Health & Rate-Limit Backoff Diagnostics (Phase 1.6) ---

    /**
     * Soft outcomes (see [SOFT_SAFETY_REASONS]) from an earlier denylist version may have been stored as
     * permanent bans. Once per denylist version they are lifted so the candidates are evaluated again;
     * bans for prohibited content, licences and user rejections are never touched.
     */
    private fun sweepSoftSafetyRecordsIfDenylistChanged() {
        val current = SafetyDenylist.DENYLIST_VERSION
        if (prefs.getString(KEY_SOFT_SWEEP_VERSION, null) == current) return
        store.removeSoftSafetyRecords(SOFT_SAFETY_REASONS)
        prefs.edit { putString(KEY_SOFT_SWEEP_VERSION, current) }
    }

    // --- Source health, backoff and fetch cursor: see SourceHealthStore ---

    fun clearTransientSourceFailureOnReenable(sourceId: String) = sourceHealth.clearTransientFailureOnReenable(sourceId)

    fun getSourceDiagnostics(nowEpochMs: Long = System.currentTimeMillis()): List<SourceDiagnostics> {
        val enabled = loadPreferences().enabledSources
        return SourceRegistry.admittedSources().map { entry ->
            val sid = entry.sourceId
            SourceDiagnostics(
                sourceId = sid,
                sourceName = entry.sourceName,
                isAdmitted = entry.isAdmitted,
                isEnabled = sid in enabled,
                healthState = sourceHealth.stateFor(sid, SourceRegistry.isSourceAdmitted(sid), nowEpochMs),
                consecutiveFailures = sourceHealth.failureCount(sid),
                lastError = sourceHealth.lastError(sid),
                backoffUntilEpochMs = sourceHealth.backoffUntil(sid),
                lastSyncSummary = sourceHealth.syncSummary(sid)
            )
        }
    }
}
