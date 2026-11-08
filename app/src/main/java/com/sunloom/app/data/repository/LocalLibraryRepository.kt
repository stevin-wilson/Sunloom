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
import com.sunloom.app.data.sources.RateLimitException
import com.sunloom.app.data.sources.SeedRenderer
import com.sunloom.app.data.sources.SmkOpenSourceAdapter
import com.sunloom.app.data.sources.SourceBudgetExceededException
import com.sunloom.app.data.sources.SourceRegistry
import com.sunloom.app.data.sources.WallpaperSourceAdapter
import com.sunloom.app.data.sources.WikimediaFeaturedSourceAdapter
import com.sunloom.app.domain.evaluator.ImageEvaluator
import com.sunloom.app.domain.model.BlocklistEntry
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.LockWorkspacePolicy
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.RejectionRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.SafetyVerdict
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.SourceDiagnostics
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.model.WallpaperStatus
import com.sunloom.app.domain.model.displaySetSignature
import com.sunloom.app.domain.renderer.CropRenderer
import com.sunloom.app.domain.renderer.EnvelopePlanner
import com.sunloom.app.domain.safety.GateTrace
import com.sunloom.app.domain.safety.SafetyDenylist
import com.sunloom.app.domain.safety.SafetyGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

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
        private const val KEY_CACHE_DEFAULT_MIGRATED = "cache_default_migrated_v1"
        /**
         * Outcomes of heuristic keyword checks, not verdicts about the content. They are recorded as
         * display-scoped, retryable rejections and swept whenever the denylist version changes (Article VII).
         */
        internal val SOFT_SAFETY_REASONS = setOf("UNRECOGNIZED_SAFE_DOMAIN", "MISSING_SAFETY_TAGS")
        private const val KEY_SOFT_SWEEP_VERSION = "soft_rejections_denylist_version"
        private const val TAG = "LocalLibraryRepository"
        private val LIBRARY_WRITE_LOCK = Any()
        private val SYNC_MUTEX = kotlinx.coroutines.sync.Mutex()
        private val HEAL_EXEMPT_SOURCES = setOf("seed_reserve", "user_local")
        private val LEGACY_V1_3_SOURCES = setOf(
            "seed_reserve",
            "wikimedia_featured",
            "nasa_library",
            "artic_cc0",
            "met_museum_cc0",
            "user_local"
        )

        private data class CachedSeedAsset(
            val pngBytes: ByteArray,
            val sha256: String,
            val dHash: String,
            val rgbGrid: IntArray,
            val gridW: Int,
            val gridH: Int,
            val width: Int,
            val height: Int
        )

        /** Rendered seed PNGs are large; keep a small byte-bounded LRU instead of an unbounded map. */
        internal const val SEED_RENDER_CACHE_MAX_BYTES = 16 * 1024 * 1024

        private val seedRenderCache = object : android.util.LruCache<String, CachedSeedAsset>(SEED_RENDER_CACHE_MAX_BYTES) {
            override fun sizeOf(key: String, value: CachedSeedAsset): Int =
                (value.pngBytes.size + value.rgbGrid.size * 4).coerceAtLeast(1)
        }

        internal fun seedRenderCacheSizeBytes(): Int = synchronized(seedRenderCache) { seedRenderCache.size() }

        internal fun clearSeedRenderCache() = synchronized(seedRenderCache) { seedRenderCache.evictAll() }

        private inline fun cachedSeedAsset(key: String, build: () -> CachedSeedAsset): CachedSeedAsset {
            synchronized(seedRenderCache) { seedRenderCache.get(key) }?.let { return it }
            val built = build()
            synchronized(seedRenderCache) { seedRenderCache.put(key, built) }
            return built
        }
        private val SAFE_FILENAME_REGEX = Regex("[^a-zA-Z0-9._-]")

        @Volatile
        internal var testStoreFactoryOverride: ((Context) -> LibraryStore)? = null

        @Volatile
        internal var defaultMaxSeedRenderPixels: Int = CropRenderer.MAX_OUTPUT_PIXELS

        @Volatile
        private var sharedDefaultStore: Pair<String, LibraryStore>? = null

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
    private val sourceHealth = SourceHealthStore(prefs, random)
    val wallpaperDir: File = File(appContext.filesDir, "wallpapers").apply { mkdirs() }
    val quarantineDir: File = File(appContext.noBackupFilesDir, "quarantine").apply { mkdirs() }
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
        healCancelledSourceDiagnostics()
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

    fun loadPreferences(): UserPreferences {
        migrateLegacyCacheDefaultIfNeeded()
        val catsRaw = prefs.getStringSet("enabled_categories", null)
        val categories = if (catsRaw.isNullOrEmpty()) {
            UserPreferences().enabledCategories
        } else {
            catsRaw.mapNotNull { name -> SafeCategory.entries.firstOrNull { it.name == name } }.toSet()
        }.ifEmpty { UserPreferences().enabledCategories }

        val currentAdmittedIds = SourceRegistry.admittedSources().map { it.sourceId }.toSet()
        val sourcesRaw = prefs.getStringSet("enabled_sources", null)
        val sources = if (sourcesRaw.isNullOrEmpty()) {
            UserPreferences().enabledSources
        } else {
            val knownRaw = prefs.getStringSet("known_admitted_sources", null) ?: LEGACY_V1_3_SOURCES
            val newlyAdmitted = currentAdmittedIds - knownRaw
            if (newlyAdmitted.isNotEmpty()) {
                val merged = (sourcesRaw + newlyAdmitted).filter { SourceRegistry.isSourceAdmitted(it) }.toSet()
                prefs.edit {
                    putStringSet("enabled_sources", merged)
                    putStringSet("known_admitted_sources", currentAdmittedIds)
                }
                merged
            } else {
                sourcesRaw.filter { SourceRegistry.isSourceAdmitted(it) }.toSet()
            }
        }.ifEmpty { UserPreferences().enabledSources }

        return UserPreferences(
            isSetupCompleted = prefs.getBoolean("setup_completed", false),
            autoChangeEnabled = prefs.getBoolean("auto_change_enabled", true),
            isPaused = prefs.getBoolean("is_paused", false),
            scheduleMode = ScheduleMode.entries.firstOrNull {
                it.name == prefs.getString("schedule_mode", ScheduleMode.SUNRISE_SUNSET.name)
            } ?: ScheduleMode.SUNRISE_SUNSET,
            selectedCityName = prefs.getString("selected_city", "San Francisco, United States") ?: "San Francisco, United States",
            latitude = Double.fromBits(prefs.getLong("latitude_bits", (37.7749).toRawBits())),
            longitude = Double.fromBits(prefs.getLong("longitude_bits", (-122.4194).toRawBits())),
            fixedLightHour = prefs.getInt("fixed_light_hour", 7).coerceIn(0, 23),
            fixedDarkHour = prefs.getInt("fixed_dark_hour", 19).coerceIn(0, 23),
            rotationIntervalHours = prefs.getInt("rotation_interval_hours", 0).coerceIn(0, 24),
            enabledCategories = categories,
            enabledSources = sources,
            displayPolicy = DisplayPolicy.entries.firstOrNull {
                it.name == prefs.getString("display_policy", DisplayPolicy.SAME_ON_ALL_DISPLAYS.name)
            } ?: DisplayPolicy.SAME_ON_ALL_DISPLAYS,
            lockWorkspacePolicy = LockWorkspacePolicy.entries.firstOrNull {
                it.name == prefs.getString("lock_workspace_policy", LockWorkspacePolicy.SAME_ON_BOTH.name)
            } ?: LockWorkspacePolicy.SAME_ON_BOTH,
            allowBlurredFrameFill = prefs.getBoolean("allow_blurred_fill", false),
            wifiOnlyDownloads = prefs.getBoolean("wifi_only", false),
            deferOnBatterySaver = prefs.getBoolean("defer_battery_saver", true),
            maxCacheBytes = prefs.getLong("max_cache_bytes", UserPreferences.DEFAULT_MAX_CACHE_BYTES),
            baselineRestorePath = prefs.getString("baseline_restore_path", null),
            dryRunPreviewOnly = false
        )
    }

    /**
     * One-time (Article IX §3, v0.14): installs that still hold the old 250 MB default move to the new
     * 1 GB default. Any other stored value is a deliberate choice and is kept; once the flag is set a
     * later explicit choice of 250 MB is respected.
     */
    private fun migrateLegacyCacheDefaultIfNeeded() {
        if (prefs.getBoolean(KEY_CACHE_DEFAULT_MIGRATED, false)) return
        prefs.edit {
            if (prefs.getLong("max_cache_bytes", -1L) == UserPreferences.LEGACY_MAX_CACHE_BYTES) {
                putLong("max_cache_bytes", UserPreferences.DEFAULT_MAX_CACHE_BYTES)
            }
            putBoolean(KEY_CACHE_DEFAULT_MIGRATED, true)
        }
    }

    fun savePreferences(updated: UserPreferences) {
        // Resolution rejections made under the old blur-fill setting no longer apply (Article XI §3).
        if (prefs.getBoolean("allow_blurred_fill", false) != updated.allowBlurredFrameFill) {
            store.clearTransientRejections()
        }
        val enabledAdmittedSources = updated.enabledSources.filter { SourceRegistry.isSourceAdmitted(it) }.toSet()
        prefs.edit {
            putBoolean("setup_completed", updated.isSetupCompleted)
            putBoolean("auto_change_enabled", updated.autoChangeEnabled)
            putBoolean("is_paused", updated.isPaused)
            putString("schedule_mode", updated.scheduleMode.name)
            putString("selected_city", updated.selectedCityName)
            putLong("latitude_bits", updated.latitude.toRawBits())
            putLong("longitude_bits", updated.longitude.toRawBits())
            putInt("fixed_light_hour", updated.fixedLightHour.coerceIn(0, 23))
            putInt("fixed_dark_hour", updated.fixedDarkHour.coerceIn(0, 23))
            remove("quiet_hours_enabled")
            remove("quiet_hours_start_hour")
            remove("quiet_hours_end_hour")
            putInt("rotation_interval_hours", updated.rotationIntervalHours.coerceIn(0, 24))
            putStringSet("enabled_categories", updated.enabledCategories.map { it.name }.toSet())
            putStringSet("enabled_sources", enabledAdmittedSources)
            putStringSet("known_admitted_sources", SourceRegistry.admittedSources().map { it.sourceId }.toSet())
            putString("display_policy", updated.displayPolicy.name)
            putString("lock_workspace_policy", updated.lockWorkspacePolicy.name)
            putBoolean("allow_blurred_fill", updated.allowBlurredFrameFill)
            putBoolean("wifi_only", updated.wifiOnlyDownloads)
            putBoolean("defer_battery_saver", updated.deferOnBatterySaver)
            putLong("max_cache_bytes", updated.maxCacheBytes)
            putString("baseline_restore_path", updated.baselineRestorePath)
            remove("dry_run_preview_only")
        }

        syncSourceEnabledStates(enabledAdmittedSources)
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

    fun recordWallpaperApplied(wallpaperId: String) {
        val current = getCurrentWallpaperId()
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
        if (existing.localFilePath.isNotBlank()) runCatching { File(existing.localFilePath).delete() }
        if (existing.croppedFilePath.isNotBlank()) runCatching { File(existing.croppedFilePath).delete() }

        val now = System.currentTimeMillis()
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
    ) {
        val cleanAsset = assetId?.trim().orEmpty()
        val cleanSha = sha256?.trim().orEmpty()
        val cleanPhash = perceptualHash?.trim().orEmpty()
        store.addBlocklistEntry(
            BlocklistEntry(
                assetId = cleanAsset,
                sha256 = cleanSha,
                dHash = cleanPhash,
                sourceId = sourceId,
                reason = reasonCode,
                createdAtEpochMs = timestampEpochMs
            )
        )
    }

    fun getPermanentBlockedAssetIds(): Set<String> {
        val fromBlocklist = store.getAllBlocklistEntries()
            .map { it.assetId }
            .filter { it.isNotBlank() }
            .toSet()
        val fromPermanentRejections = store.getAllRejections()
            .filter { it.isPermanent }
            .map { it.assetId }
            .filter { it.isNotBlank() }
            .toSet()
        return fromBlocklist + fromPermanentRejections
    }

    fun getPermanentBlockedSha256s(): Set<String> {
        return store.getAllBlocklistEntries()
            .map { it.sha256 }
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun getPermanentBlockedPerceptualHashes(): Set<String> {
        return store.getAllBlocklistEntries()
            .map { it.dHash }
            .filter { it.isNotBlank() }
            .toSet()
    }

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
     * Article VII §5–6: Re-validates any non-rejected wallpapers whose stored
     * `safetyGateVersion` differs from [SafetyGate.COMPOSITE_GATE_VERSION], running L1–L4
     * against both stored metadata and local payload bytes (including seeds).
     */
    fun revalidateGateVersionIfNeeded(display: DisplayProfile? = null): Int {
        var evictedCount = 0
        val preferences = loadPreferences()
        val blockedIds = getPermanentBlockedAssetIds().toMutableSet()
        val blockedPhashes = getPermanentBlockedPerceptualHashes().toMutableSet()
        val blockedSha256s = getPermanentBlockedSha256s().toMutableSet()

        for (record in getAllWallpapers()) {
            if (record.isRejected || record.isBaselineRestore) continue
            if (record.provenance.safetyGateVersion == SafetyGate.COMPOSITE_GATE_VERSION) continue

            val isUserLocal = record.provenance.sourceId == "user_local"
            val effectiveTags = record.rawCategoriesAndTags.ifEmpty {
                listOf(record.category.displayName.lowercase())
            }
            val admittedHost = SourceRegistry.get(record.provenance.sourceId)?.allowedHosts?.firstOrNull()
            val syntheticCandidate = CandidateAsset(
                id = record.id,
                title = record.title,
                description = record.description,
                category = record.category,
                rawCategoriesAndTags = effectiveTags,
                downloadUrl = when {
                    isUserLocal || record.provenance.sourceId == "seed_reserve" -> ""
                    record.provenance.originalPageUrl.startsWith("https://") -> record.provenance.originalPageUrl
                    !admittedHost.isNullOrBlank() -> "https://$admittedHost/${record.id}"
                    else -> ""
                },
                isUserLocal = isUserLocal,
                provenance = record.provenance
            )

            val metaVerdict = safetyGate.evaluateMetadata(
                candidate = syntheticCandidate,
                blockedAssetIds = blockedIds,
                blockedPerceptualHashes = blockedPhashes,
                blockedSha256Checksums = blockedSha256s
            )
            if (metaVerdict is SafetyVerdict.Rejected) {
                evictFailedRevalidationRecord(
                    record = record,
                    failedLayer = metaVerdict.failedLayer,
                    reasonCode = metaVerdict.reasonCode,
                    explanation = metaVerdict.explanation,
                    isUserLocal = isUserLocal
                )
                blockedIds.add(record.id)
                evictedCount++
                continue
            }

            val localFile = record.localFilePath.takeIf { it.isNotBlank() }?.let(::File)
            val bytes = if (localFile != null && localFile.exists() && localFile.isFile) {
                runCatching { localFile.readBytes() }.getOrNull()
            } else {
                null
            }
            if (bytes == null) {
                evictFailedRevalidationRecord(
                    record = record,
                    failedLayer = "L3",
                    reasonCode = "MISSING_LOCAL_PAYLOAD",
                    explanation = "Cached wallpaper file is missing or unreadable on disk.",
                    isUserLocal = isUserLocal,
                    addToPermanentBlocklist = false
                )
                evictedCount++
                continue
            }

            val headerBounds = SafetyGate.inspectImageHeaderBounds(bytes)
            val payloadVerdict = safetyGate.evaluatePayload(
                candidate = syntheticCandidate,
                bytes = bytes,
                blockedAssetIds = blockedIds,
                blockedPerceptualHashes = blockedPhashes,
                blockedSha256Checksums = blockedSha256s,
                headerBounds = headerBounds
            )
            if (payloadVerdict is SafetyVerdict.Rejected) {
                val computedSha = SafetyGate.sha256Hex(bytes)
                evictFailedRevalidationRecord(
                    record = record,
                    failedLayer = payloadVerdict.failedLayer,
                    reasonCode = payloadVerdict.reasonCode,
                    explanation = payloadVerdict.explanation,
                    isUserLocal = isUserLocal,
                    sha256Override = computedSha
                )
                blockedIds.add(record.id)
                blockedSha256s.add(computedSha)
                evictedCount++
                continue
            }
            val approved = payloadVerdict as SafetyVerdict.Approved

            val decoded = decodeSampledBitmapSafely(
                bytes = bytes,
                headerW = headerBounds?.width ?: 0,
                headerH = headerBounds?.height ?: 0,
                maxEdgePx = 512
            )
            if (decoded == null) {
                evictFailedRevalidationRecord(
                    record = record,
                    failedLayer = "L3",
                    reasonCode = "CORRUPT_BITMAP",
                    explanation = "BitmapFactory could not safely decode cached payload during re-validation.",
                    isUserLocal = isUserLocal,
                    sha256Override = approved.sha256Checksum
                )
                blockedIds.add(record.id)
                blockedSha256s.add(approved.sha256Checksum)
                evictedCount++
                continue
            }

            val gridW = 32
            val gridH = 20
            val rgbGrid = sampleBitmapToRgbGrid(decoded, gridW, gridH)
            val evalGrid = sampleBitmapToEvaluationGrid(decoded, gridW, gridH)
            decoded.recycle()
            val dHash = SafetyGate.computePerceptualDHashFromPixels(rgbGrid, gridW, gridH)
            if (!record.isBaselineRestore &&
                dHash.isNotBlank() &&
                blockedPhashes.any { SafetyGate.isNearDuplicateDHash(dHash, it) }
            ) {
                evictFailedRevalidationRecord(
                    record = record,
                    failedLayer = "L4",
                    reasonCode = "BLOCKLISTED_PERCEPTUAL_HASH",
                    explanation = "Perceptual dHash ($dHash) matches a permanently blocklisted wallpaper.",
                    isUserLocal = isUserLocal,
                    sha256Override = approved.sha256Checksum,
                    dHashOverride = dHash
                )
                blockedIds.add(record.id)
                blockedSha256s.add(approved.sha256Checksum)
                blockedPhashes.add(dHash)
                evictedCount++
                continue
            }

            if (display != null) {
                val origW = record.provenance.originalWidth.takeIf { it > 0 }
                    ?: headerBounds?.width?.takeIf { it > 0 }
                    ?: 1
                val origH = record.provenance.originalHeight.takeIf { it > 0 }
                    ?: headerBounds?.height?.takeIf { it > 0 }
                    ?: 1
                val eval = imageEvaluator.evaluateRgbGrid(
                    imageWidth = origW,
                    imageHeight = origH,
                    rgbGrid = evalGrid,
                    gridWidth = gridW,
                    gridHeight = gridH,
                    display = display,
                    allowBlurredFrameFill = isUserLocal || preferences.allowBlurredFrameFill
                )
                val effectiveEval = if (!eval.isEligible && isUserLocal) {
                    eval.copy(
                        isEligible = true,
                        cropRect = eval.cropRect ?: com.sunloom.app.domain.model.CropRect(
                            left = 0,
                            top = 0,
                            width = origW,
                            height = origH,
                            focalX = 0.5,
                            focalY = 0.5,
                            usesBlurredFrameFill = true,
                            pixelCoverageRatio = minOf(
                                origW.toDouble() / display.widthPx.coerceAtLeast(1),
                                origH.toDouble() / display.heightPx.coerceAtLeast(1)
                            )
                        )
                    )
                } else {
                    eval
                }
                val trace = if (effectiveEval.isEligible) {
                    GateTrace.fullPass(isUserLocal)
                } else {
                    GateTrace.failedAt(
                        layer = "L4",
                        reasonCode = effectiveEval.rejectionReasonCode ?: "QUALITY_THRESHOLD_NOT_MET",
                        isUserLocal = isUserLocal
                    )
                }
                upsertWallpaper(
                    record.copy(
                        provenance = record.provenance.copy(
                            sha256Checksum = approved.sha256Checksum,
                            perceptualHash = dHash.ifBlank { record.provenance.perceptualHash },
                            safetyGateVersion = SafetyGate.COMPOSITE_GATE_VERSION,
                            gateLayerOutcomes = trace
                        ),
                        evaluation = effectiveEval
                    )
                )
                if (!effectiveEval.isEligible) {
                    clearInvalidatedActiveWallpaper(record.id)
                }
            } else {
                // Without a known display the brightness and crop evaluation cannot be redone. The safety layers
                // passed, but the record keeps its old gate version so the next call that has a display evaluates
                // it; stamping it now would leave labels from an older evaluator in place for good. What was
                // worked out on the way (checksum, perceptual hash) is kept so the near-duplicate guard has it.
                if (record.provenance.sha256Checksum != approved.sha256Checksum ||
                    (dHash.isNotBlank() && record.provenance.perceptualHash != dHash)
                ) {
                    upsertWallpaper(
                        record.copy(
                            provenance = record.provenance.copy(
                                sha256Checksum = approved.sha256Checksum,
                                perceptualHash = dHash.ifBlank { record.provenance.perceptualHash }
                            )
                        )
                    )
                }
            }
        }
        return evictedCount
    }

    private fun evictFailedRevalidationRecord(
        record: WallpaperRecord,
        failedLayer: String,
        reasonCode: String,
        explanation: String,
        isUserLocal: Boolean,
        sha256Override: String? = null,
        dHashOverride: String? = null,
        addToPermanentBlocklist: Boolean = true
    ) {
        if (record.localFilePath.isNotBlank()) runCatching { File(record.localFilePath).delete() }
        if (record.croppedFilePath.isNotBlank()) runCatching { File(record.croppedFilePath).delete() }
        val now = System.currentTimeMillis()
        val effectiveSha = sha256Override ?: record.provenance.sha256Checksum
        val effectiveDHash = dHashOverride ?: record.provenance.perceptualHash
        upsertWallpaper(
            record.copy(
                isRejected = true,
                isFavorite = false,
                localFilePath = "",
                croppedFilePath = "",
                fileSizeBytes = 0L,
                provenance = record.provenance.copy(
                    sha256Checksum = effectiveSha,
                    perceptualHash = effectiveDHash,
                    safetyGateVersion = SafetyGate.COMPOSITE_GATE_VERSION,
                    gateLayerOutcomes = GateTrace.failedAt(failedLayer, reasonCode, isUserLocal)
                ),
                status = WallpaperStatus.REJECTED_SAFETY_OR_QUALITY,
                decisionExplanation = "Evicted on safety gate (${SafetyGate.COMPOSITE_GATE_VERSION}) re-validation: $explanation"
            )
        )
        if (addToPermanentBlocklist) {
            addPermanentBlocklistEntry(
                assetId = record.id,
                sha256 = effectiveSha,
                perceptualHash = effectiveDHash,
                sourceId = record.provenance.sourceId,
                reasonCode = reasonCode,
                timestampEpochMs = now
            )
        }
        recordRejection(
            RejectionRecord(
                assetId = record.id,
                sourceId = record.provenance.sourceId,
                reasonCode = reasonCode,
                explanation = explanation,
                timestampEpochMs = now,
                isPermanent = addToPermanentBlocklist
            )
        )
        clearInvalidatedActiveWallpaper(record.id)
    }

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

    private fun seedTargetSizeForDisplay(display: DisplayProfile): Pair<Int, Int> {
        val rawW = display.widthPx.coerceAtLeast(1)
        val rawH = display.heightPx.coerceAtLeast(1)
        val minEdge = minOf(rawW, rawH)
        val (scaledUpW, scaledUpH) = if (minEdge < 400) {
            val factor = 400.0 / minEdge.toDouble()
            kotlin.math.round(rawW * factor).toInt().coerceAtLeast(400) to
                kotlin.math.round(rawH * factor).toInt().coerceAtLeast(400)
        } else {
            rawW to rawH
        }
        return CropRenderer.budgetedSize(scaledUpW, scaledUpH)
    }

    fun ensureSeedReserveLoadedForDisplays(
        displays: List<DisplayProfile>,
        displayPolicy: DisplayPolicy = DisplayPolicy.SAME_ON_ALL_DISPLAYS
    ): List<WallpaperRecord> {
        val valid = displays.filter { it.widthPx > 0 && it.heightPx > 0 }
        if (valid.isEmpty()) return getAllWallpapers()
        val sharpest = valid.maxBy { it.widthPx.toLong() * it.heightPx.toLong() }
        if (valid.size == 1 || displayPolicy == DisplayPolicy.OPTIMIZE_PRIMARY_SHARPEST) {
            return ensureSeedReserveLoaded(sharpest)
        }
        val anchor = valid.firstOrNull { it.isPrimary } ?: sharpest
        val compatible = valid.filter { d ->
            val sameOrientation = (d.widthPx < d.heightPx) == (anchor.widthPx < anchor.heightPx) ||
                d.widthPx == d.heightPx || anchor.widthPx == anchor.heightPx
            val spread = maxOf(
                d.aspectRatio / anchor.aspectRatio.coerceAtLeast(1e-4),
                anchor.aspectRatio / d.aspectRatio.coerceAtLeast(1e-4)
            )
            sameOrientation && spread <= 2.2
        }.ifEmpty { listOf(sharpest) }
        val envW = compatible.maxOf { it.widthPx }
        val envH = compatible.maxOf { it.heightPx }
        val (budgetW, budgetH) = CropRenderer.budgetedSize(envW, envH)
        val syntheticTarget = DisplayProfile(
            displayId = anchor.displayId,
            name = if (compatible.size > 1) "Multi-Display Envelope" else anchor.name,
            widthPx = budgetW,
            heightPx = budgetH,
            densityDpi = compatible.minOf { it.densityDpi },
            isPrimary = true,
            physicalPpi = compatible.mapNotNull { it.physicalPpi }.minOrNull()
        )
        return ensureSeedReserveLoaded(syntheticTarget)
    }

    fun ensureSeedReserveLoaded(display: DisplayProfile): List<WallpaperRecord> {
        purgeRevokedSources()
        val existing = getAllWallpapers()
        val (targetW, targetH) = seedTargetSizeForDisplay(display)
        val (renderW, renderH) = CropRenderer.budgetedSize(
            targetW,
            targetH,
            maxPixels = maxSeedRenderPixels
        )
        val blockedIds = getPermanentBlockedAssetIds()
        val blockedPhashes = getPermanentBlockedPerceptualHashes()
        val blockedSha256s = getPermanentBlockedSha256s()

        for (spec in BundledSeedSourceAdapter.SEED_SPECS) {
            val existingRecord = existing.firstOrNull { it.id == spec.id }
            if (existingRecord != null && existingRecord.isRejected) {
                continue
            }
            if (existingRecord != null &&
                File(existingRecord.localFilePath).exists() &&
                existingRecord.provenance.originalWidth == targetW &&
                existingRecord.provenance.originalHeight == targetH &&
                existingRecord.provenance.renditionWidth == display.widthPx &&
                existingRecord.provenance.renditionHeight == display.heightPx &&
                existingRecord.provenance.safetyGateVersion == SafetyGate.COMPOSITE_GATE_VERSION &&
                existingRecord.evaluation.isEligible
            ) {
                continue
            }

            val cacheKey = "${spec.id}_${renderW}x${renderH}_${targetW}x${targetH}"
            val cachedAsset = cachedSeedAsset(cacheKey) {
                val gridW = 48
                val gridH = 30
                val grid = BundledSeedSourceAdapter.generateRgbGrid(spec, gridW, gridH)
                val dHash = SafetyGate.computePerceptualDHashFromPixels(grid, gridW, gridH)
                val bmp = SeedRenderer.render(spec, widthPx = renderW, heightPx = renderH)
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                bmp.recycle()
                val pngBytes = baos.toByteArray()
                val sha256 = SafetyGate.sha256Hex(pngBytes)
                CachedSeedAsset(
                    pngBytes = pngBytes,
                    sha256 = sha256,
                    dHash = dHash,
                    rgbGrid = grid,
                    gridW = gridW,
                    gridH = gridH,
                    width = targetW,
                    height = targetH
                )
            }

            val seedProvenance = ProvenanceRecord(
                sourceId = "seed_reserve",
                sourceName = "Sunloom On-Device Procedural Reserve",
                assetId = spec.id,
                originalPageUrl = "",
                creatorName = "Generated on device by Sunloom",
                creatorPageUrl = "",
                licenseType = LicenseType.CC0,
                licenseUrl = LicenseType.CC0.defaultUrl,
                licenseVersion = "1.0",
                attributionText = "${spec.title} — Generated on device by Sunloom under CC0 1.0 Public Domain Dedication",
                retrievedAtEpochMs = existingRecord?.provenance?.retrievedAtEpochMs ?: System.currentTimeMillis(),
                originalWidth = targetW,
                originalHeight = targetH,
                renditionWidth = display.widthPx,
                renditionHeight = display.heightPx,
                safetyClassification = SafetyClassification.SAFE,
                safetyClassificationSource = "Sunloom On-Device Procedural CC0 Reserve",
                sha256Checksum = cachedAsset.sha256,
                perceptualHash = cachedAsset.dHash,
                adapterVersion = SourceRegistry.get("seed_reserve")?.adapterVersion ?: ProvenanceRecord.DEFAULT_ADAPTER_VERSION,
                safetyGateVersion = SafetyGate.COMPOSITE_GATE_VERSION,
                gateLayerOutcomes = GateTrace.fullPass()
            )
            val seedCandidate = CandidateAsset(
                id = spec.id,
                title = spec.title,
                description = spec.description,
                category = spec.category,
                rawCategoriesAndTags = spec.subjectTags,
                downloadUrl = "",
                provenance = seedProvenance
            )
            val seedVerdict = safetyGate.evaluatePayload(
                candidate = seedCandidate,
                bytes = cachedAsset.pngBytes,
                blockedAssetIds = blockedIds,
                blockedPerceptualHashes = blockedPhashes,
                blockedSha256Checksums = blockedSha256s
            )
            if (seedVerdict !is SafetyVerdict.Approved) {
                continue
            }

            val eval = imageEvaluator.evaluateRgbGrid(
                imageWidth = targetW,
                imageHeight = targetH,
                rgbGrid = cachedAsset.rgbGrid,
                gridWidth = cachedAsset.gridW,
                gridHeight = cachedAsset.gridH,
                display = display,
                allowBlurredFrameFill = false
            )
            if (!eval.isEligible) {
                continue
            }

            val file = File(wallpaperDir, "${spec.id}.png")
            FileOutputStream(file).use { out ->
                out.write(cachedAsset.pngBytes)
            }

            val record = WallpaperRecord(
                id = spec.id,
                title = spec.title,
                description = spec.description,
                rawCategoriesAndTags = spec.subjectTags,
                category = spec.category,
                localFilePath = file.absolutePath,
                croppedFilePath = file.absolutePath,
                fileSizeBytes = file.length(),
                isFavorite = existingRecord?.isFavorite ?: false,
                appliedCount = existingRecord?.appliedCount ?: 0,
                lastAppliedEpochMs = existingRecord?.lastAppliedEpochMs ?: 0L,
                provenance = seedProvenance,
                evaluation = eval,
                status = existingRecord?.status ?: WallpaperStatus.SAFETY_APPROVED,
                decisionExplanation = "Bundled CC0 offline reserve verified for ${display.resolutionLabel}. ${eval.explanation}"
            )
            upsertWallpaper(record)
        }

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
        val primaryOrSharpest = validDisplays.maxBy { it.widthPx.toLong() * it.heightPx.toLong() }

        var changedAny = false
        for (record in getAllWallpapers()) {
            if (record.isRejected || record.isBaselineRestore || record.provenance.sourceId == "seed_reserve") {
                continue
            }
            val srcW = record.provenance.originalWidth
            val srcH = record.provenance.originalHeight
            if (srcW <= 0 || srcH <= 0) continue

            val focalX = record.evaluation.cropRect?.focalX ?: 0.5
            val focalY = record.evaluation.cropRect?.focalY ?: 0.5
            val isUserLocal = record.provenance.sourceId == "user_local"

            val plan = envelopePlanner.planForRecord(record, validDisplays, preferences)

            val updatedEval: com.sunloom.app.domain.model.ImageEvaluationResult
            val newRendW: Int
            val newRendH: Int

            if (plan != null) {
                newRendW = plan.outputWidthPx
                newRendH = plan.outputHeightPx
                updatedEval = record.evaluation.copy(
                    isEligible = true,
                    rejectionReasonCode = null,
                    cropRect = plan.envelopeCrop,
                    explanation = plan.disclosureNote
                )
            } else if (isUserLocal) {
                val localFallback = com.sunloom.app.domain.model.CropRect(
                    left = 0,
                    top = 0,
                    width = srcW,
                    height = srcH,
                    focalX = focalX,
                    focalY = focalY,
                    usesBlurredFrameFill = true,
                    pixelCoverageRatio = minOf(
                        srcW.toDouble() / primaryOrSharpest.widthPx.coerceAtLeast(1),
                        srcH.toDouble() / primaryOrSharpest.heightPx.coerceAtLeast(1)
                    )
                )
                newRendW = primaryOrSharpest.widthPx
                newRendH = primaryOrSharpest.heightPx
                updatedEval = record.evaluation.copy(
                    isEligible = true,
                    rejectionReasonCode = null,
                    cropRect = localFallback
                )
            } else {
                // Preserve focalX/focalY in a non-null crop bounds object while marking ineligible for this display set
                val preservedBounds = cropRenderer.computeAspectCropBounds(
                    sourceWidth = srcW,
                    sourceHeight = srcH,
                    focalX = focalX,
                    focalY = focalY,
                    targetAspect = primaryOrSharpest.aspectRatio
                )
                val coverage = minOf(
                    preservedBounds.width.toDouble() / primaryOrSharpest.widthPx.coerceAtLeast(1),
                    preservedBounds.height.toDouble() / primaryOrSharpest.heightPx.coerceAtLeast(1)
                )
                newRendW = preservedBounds.width
                newRendH = preservedBounds.height
                val targetLabel = if (validDisplays.size > 1) {
                    validDisplays.joinToString(" + ") { it.resolutionLabel }
                } else {
                    primaryOrSharpest.resolutionLabel
                }
                updatedEval = record.evaluation.copy(
                    isEligible = false,
                    rejectionReasonCode = "RESOLUTION_BELOW_DISPLAY_TARGET",
                    cropRect = preservedBounds.copy(pixelCoverageRatio = coverage),
                    explanation = "Temporarily paused for $targetLabel: source (${srcW}×${srcH}) is below native sharpness floor."
                )
            }

            if (record.evaluation.isEligible != updatedEval.isEligible ||
                record.evaluation.rejectionReasonCode != updatedEval.rejectionReasonCode ||
                record.evaluation.cropRect != updatedEval.cropRect ||
                record.provenance.renditionWidth != newRendW ||
                record.provenance.renditionHeight != newRendH
            ) {
                upsertWallpaper(
                    record.copy(
                        provenance = record.provenance.copy(
                            renditionWidth = newRendW,
                            renditionHeight = newRendH
                        ),
                        evaluation = updatedEval
                    )
                )
                changedAny = true
            }
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
        healCancelledSourceDiagnostics()
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
                it.provenance.sourceId != "seed_reserve" &&
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

        lastSyncAttemptedSources = selectedAdapters.size
        lastSyncFailedSources = 0
        for (adapter in selectedAdapters) {
            coroutineContext.ensureActive()
            val reg = SourceRegistry.get(adapter.sourceId)
            val budgetedClient = BudgetedSourceHttpClient(
                delegate = httpClient,
                sourceId = adapter.sourceId,
                maxRequests = reg?.maxRequestsPerSync ?: 6,
                minSpacingMs = reg?.minRequestSpacingMs ?: 150L
            )
            val scopedAdapter = adapter.withHttpClient(budgetedClient)

            val cursor = sourceHealth.fetchCursor(adapter.sourceId)
            var fetchFailure: Throwable? = null
            val candidates = try {
                scopedAdapter.fetchCandidates(
                    enabledCategories = preferences.enabledCategories,
                    limit = 8,
                    offset = cursor,
                    targetWidthPx = effectiveTargetWidthPx
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: SourceBudgetExceededException) {
                sourceHealth.advanceCursor(adapter.sourceId, cursor)
                continue
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
                continue
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
                continue
            }
            sourceHealth.advanceCursor(adapter.sourceId, cursor)
            sourceHealth.recordSuccess(adapter.sourceId)
            val addedBefore = newlyAdded
            var alreadyHad = 0

            candidateLoop@ for (candidate in candidates) {
                coroutineContext.ensureActive()
                pausedRows[candidate.id]?.let { previous ->
                    if (!offersLargerRendition(candidate, previous)) {
                        alreadyHad++
                        continue@candidateLoop
                    }
                }
                if (candidate.id in existingIds ||
                    candidate.id in permanentlyBlockedIds ||
                    candidate.id in displayScopedRejectedIds
                ) {
                    if (candidate.id in existingIds) alreadyHad++
                    continue
                }

                val metaVerdict = safetyGate.evaluateMetadata(
                    candidate = candidate,
                    blockedAssetIds = permanentlyBlockedIds,
                    blockedPerceptualHashes = blockedPhashes,
                    blockedSha256Checksums = blockedSha256s
                )
                if (metaVerdict is SafetyVerdict.Rejected && metaVerdict.reasonCode in SOFT_SAFETY_REASONS) {
                    recordRejection(
                        Rejections.displayScoped(
                            candidate.id, adapter.sourceId, metaVerdict.reasonCode,
                            metaVerdict.explanation,
                            currentSetSignature
                        )
                    )
                    displayScopedRejectedIds.add(candidate.id)
                    continue
                }
                if (metaVerdict is SafetyVerdict.Rejected) {
                    val ts = System.currentTimeMillis()
                    addPermanentBlocklistEntry(
                        assetId = candidate.id,
                        sha256 = candidate.provenance.sha256Checksum,
                        perceptualHash = candidate.provenance.perceptualHash,
                        sourceId = adapter.sourceId,
                        reasonCode = metaVerdict.reasonCode,
                        timestampEpochMs = ts
                    )
                    recordRejection(
                        Rejections.permanent(
                            candidate.id, adapter.sourceId, metaVerdict.reasonCode,
                            metaVerdict.explanation, nowMs = ts
                        )
                    )
                    permanentlyBlockedIds.add(candidate.id)
                    continue
                }

                // Cheap checks first: they cost no download budget (Article X §3–4).
                if (diversityGuard.creatorCapReached(adapter.sourceId, candidate.provenance.creatorName)) {
                    continue
                }
                val advertisedW = candidate.provenance.originalWidth
                val advertisedH = candidate.provenance.originalHeight
                val sizeRejection = CropRenderer.advertisedSizeRejection(
                    advertisedW, advertisedH, display, preferences.allowBlurredFrameFill
                )
                if (sizeRejection != null) {
                    recordRejection(
                        Rejections.displayScoped(
                            candidate.id, adapter.sourceId, sizeRejection,
                            if (sizeRejection == "COMPOSITION_CROPS_TOO_MUCH") {
                                "A ${display.resolutionLabel} crop would keep under half of the ${advertisedW}×${advertisedH} picture; not downloaded."
                            } else {
                                "Source advertises ${advertisedW}×${advertisedH}, too small for ${display.resolutionLabel}; not downloaded."
                            },
                            currentSetSignature
                        )
                    )
                    displayScopedRejectedIds.add(candidate.id)
                    continue
                }

                val quarantineFile = File(
                    quarantineDir,
                    "stage_${candidate.id.replace(SAFE_FILENAME_REGEX, "_")}_${System.nanoTime()}.tmp"
                )
                try {
                    val downloadChain = buildList {
                        add(candidate.downloadUrl)
                        addAll(candidate.fallbackDownloadUrls)
                        val legacyLarge = candidate.downloadUrl.replace("~orig.jpg", "~large.jpg")
                        if (legacyLarge != candidate.downloadUrl) add(legacyLarge)
                    }.filter { it.isNotBlank() }.distinct()

                    var payloadBytes: ByteArray? = null
                    for (attemptUrl in downloadChain) {
                        try {
                            val fetched = budgetedClient.getBytes(attemptUrl)
                            // A rendition too large to decode is not the picture's fault: try the next, smaller one.
                            val tooLarge = SafetyGate.inspectImageHeaderBounds(fetched)?.let(SafetyGate::exceedsDecodeBounds) == true
                            if (tooLarge && attemptUrl != downloadChain.last()) continue
                            payloadBytes = fetched
                            break
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (rateLimit: RateLimitException) {
                            sourceHealth.recordFailure(adapter.sourceId, rateLimit)
                            break@candidateLoop
                        } catch (_: SourceBudgetExceededException) {
                            break@candidateLoop
                        } catch (err: Exception) {
                            if (SourceHealthStore.isCancellation(err)) {
                                throw CancellationException(err.message).apply { initCause(err) }
                            }
                            // Try next rendition URL in downloadChain
                        } catch (_: OutOfMemoryError) {
                            // A payload too large to hold in memory: treat like a failed rendition and try the next.
                        }
                    }
                    if (payloadBytes == null) continue

                    // Stage raw bytes in quarantine before any decoding
                    FileOutputStream(quarantineFile).use { it.write(payloadBytes) }

                    val headerBounds = SafetyGate.inspectImageHeaderBounds(payloadBytes)
                    val payloadVerdict = safetyGate.evaluatePayload(
                        candidate = candidate,
                        bytes = payloadBytes,
                        blockedSha256Checksums = blockedSha256s,
                        headerBounds = headerBounds
                    )
                    if (payloadVerdict is SafetyVerdict.Rejected) {
                        val ts = System.currentTimeMillis()
                        addPermanentBlocklistEntry(
                            assetId = candidate.id,
                            sha256 = SafetyGate.sha256Hex(payloadBytes),
                            perceptualHash = null,
                            sourceId = adapter.sourceId,
                            reasonCode = payloadVerdict.reasonCode,
                            timestampEpochMs = ts
                        )
                        recordRejection(
                            Rejections.permanent(
                                candidate.id, adapter.sourceId, payloadVerdict.reasonCode,
                                payloadVerdict.explanation, nowMs = ts
                            )
                        )
                        permanentlyBlockedIds.add(candidate.id)
                        continue
                    }
                    val approved = payloadVerdict as SafetyVerdict.Approved

                    val decoded = decodeSampledBitmapSafely(
                        payloadBytes,
                        headerBounds?.width ?: 0,
                        headerBounds?.height ?: 0
                    )
                    if (decoded == null) {
                        recordRejection(
                            Rejections.permanent(
                                candidate.id, adapter.sourceId, "CORRUPT_BITMAP",
                                "BitmapFactory could not safely decode image payload."
                            )
                        )
                        permanentlyBlockedIds.add(candidate.id)
                        continue
                    }

                    // Phase 0.1: Always use REAL decoded/header dimensions — NEVER maxOf(..., provenance.originalWidth)!
                    val srcW = headerBounds?.width?.takeIf { it > 0 } ?: decoded.width
                    val srcH = headerBounds?.height?.takeIf { it > 0 } ?: decoded.height

                    val gridW = 32
                    val gridH = 20
                    val rgbGrid = sampleBitmapToRgbGrid(decoded, gridW, gridH)
                    val evalGrid = sampleBitmapToEvaluationGrid(decoded, gridW, gridH)
                    decoded.recycle()

                    val dHash = SafetyGate.computePerceptualDHashFromPixels(rgbGrid, gridW, gridH)
                    if (dHash.isNotBlank() && blockedPhashes.any { SafetyGate.isNearDuplicateDHash(dHash, it) }) {
                        val ts = System.currentTimeMillis()
                        addPermanentBlocklistEntry(
                            assetId = candidate.id,
                            sha256 = approved.sha256Checksum,
                            perceptualHash = dHash,
                            sourceId = adapter.sourceId,
                            reasonCode = "BLOCKLISTED_PERCEPTUAL_HASH",
                            timestampEpochMs = ts
                        )
                        recordRejection(
                            Rejections.permanent(
                                candidate.id, adapter.sourceId, "BLOCKLISTED_PERCEPTUAL_HASH",
                                "Perceptual dHash ($dHash) matches a permanently rejected wallpaper.", nowMs = ts
                            )
                        )
                        permanentlyBlockedIds.add(candidate.id)
                        blockedPhashes.add(dHash)
                        continue
                    }

                    if (diversityGuard.isNearDuplicate(dHash)) {
                        recordRejection(
                            Rejections.displayScoped(
                                candidate.id, adapter.sourceId, "NEAR_DUPLICATE_OF_LIBRARY",
                                "Perceptual hash ($dHash) is almost identical to a wallpaper already in the library.",
                                currentSetSignature
                            )
                        )
                        displayScopedRejectedIds.add(candidate.id)
                        continue
                    }

                    val eval = imageEvaluator.evaluateRgbGrid(
                        imageWidth = srcW,
                        imageHeight = srcH,
                        rgbGrid = evalGrid,
                        gridWidth = gridW,
                        gridHeight = gridH,
                        display = display,
                        allowBlurredFrameFill = preferences.allowBlurredFrameFill
                    )

                    if (!eval.isEligible) {
                        val reason = eval.rejectionReasonCode ?: "QUALITY_THRESHOLD_NOT_MET"
                        // Quality/resolution rejections are display-scoped (isPermanent = false)
                        recordRejection(
                            Rejections.displayScoped(
                                candidate.id, adapter.sourceId, reason,
                                eval.explanation,
                                currentSetSignature
                            )
                        )
                        displayScopedRejectedIds.add(candidate.id)
                        continue
                    }

                    val envelopePlan = envelopePlanner.planEnvelope(
                        sourceWidth = srcW,
                        sourceHeight = srcH,
                        focalX = eval.cropRect?.focalX ?: 0.5,
                        focalY = eval.cropRect?.focalY ?: 0.5,
                        connectedDisplays = activeDisplaySet,
                        policy = preferences.displayPolicy,
                        allowBlurredFrameFill = preferences.allowBlurredFrameFill
                    )
                    if (envelopePlan == null) {
                        recordRejection(
                            Rejections.displayScoped(
                                candidate.id, adapter.sourceId, "INSUFFICIENT_ENVELOPE_RESOLUTION",
                                "Source (${srcW}×${srcH}) cannot satisfy the connected multi-display envelope without upscaling.",
                                currentSetSignature
                            )
                        )
                        displayScopedRejectedIds.add(candidate.id)
                        continue
                    }

                    val finalEval = eval.copy(cropRect = envelopePlan.envelopeCrop)

                    val ext = approved.detectedFormat.fileExtension
                    val outFile = File(wallpaperDir, "${candidate.id}.$ext")
                    // The bytes are already on disk in quarantine and have passed every check: move them into place
                    // instead of writing the (possibly tens of MB) payload a second time. A move across file systems
                    // can fail, so fall back to a plain write.
                    if (!quarantineFile.renameTo(outFile)) {
                        FileOutputStream(outFile).use { it.write(payloadBytes) }
                    }

                    val record = WallpaperRecord(
                        id = candidate.id,
                        title = candidate.title,
                        description = candidate.description,
                        rawCategoriesAndTags = candidate.rawCategoriesAndTags,
                        category = candidate.category,
                        localFilePath = outFile.absolutePath,
                        croppedFilePath = outFile.absolutePath,
                        fileSizeBytes = outFile.length(),
                        provenance = candidate.provenance.copy(
                            sha256Checksum = approved.sha256Checksum,
                            originalWidth = srcW,
                            originalHeight = srcH,
                            renditionWidth = envelopePlan.outputWidthPx,
                            renditionHeight = envelopePlan.outputHeightPx,
                            perceptualHash = dHash,
                            safetyGateVersion = SafetyGate.COMPOSITE_GATE_VERSION,
                            gateLayerOutcomes = GateTrace.fullPass()
                        ),
                        evaluation = finalEval,
                        status = WallpaperStatus.PREPARED,
                        decisionExplanation = "Safety Gate: ${approved.auditNotes.joinToString("; ")}. Evaluator: ${finalEval.explanation}"
                    ).let { fresh ->
                        redownloadRows[candidate.id]?.let { previous ->
                            fresh.copy(
                                isFavorite = previous.isFavorite,
                                appliedCount = previous.appliedCount,
                                lastAppliedEpochMs = previous.lastAppliedEpochMs
                            )
                        } ?: fresh
                    }
                    try {
                        synchronized(LIBRARY_WRITE_LOCK) { upsertWallpaper(record) }
                    } catch (e: Exception) {
                        runCatching { outFile.delete() } // do not leave an image no row refers to
                        throw e
                    }
                    // A larger rendition may use another file type; the old copy is no longer referenced.
                    pausedRows[candidate.id]?.localFilePath
                        ?.takeIf { it.isNotBlank() && it != outFile.absolutePath }
                        ?.let { runCatching { File(it).delete() } }
                    existingIds.add(candidate.id)
                    diversityGuard.noteAdded(adapter.sourceId, candidate.provenance.creatorName, dHash)
                    newlyAdded++
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (err: Exception) {
                    if (SourceHealthStore.isCancellation(err)) {
                        throw CancellationException(err.message).apply { initCause(err) }
                    }
                    // One picture that cannot be staged, evaluated or stored (a full disk, a store error) is skipped;
                    // the other candidates, the sync summary and the cache cleanup still run.
                    android.util.Log.w(TAG, "candidate_skipped", err)
                } finally {
                    runCatching { quarantineFile.delete() }
                }
            }
            sourceHealth.recordSyncSummary(
                adapter.sourceId,
                fetched = candidates.size,
                alreadyHad = alreadyHad,
                added = newlyAdded - addedBefore,
                httpErrors = budgetedClient.httpErrorCount
            )
        }

        enforceCacheLimit()
        return newlyAdded
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
            record.provenance.sourceId != "seed_reserve" &&
            record.provenance.sourceId != "user_local" &&
            !record.evaluation.isEligible &&
            record.evaluation.rejectionReasonCode == "RESOLUTION_BELOW_DISPLAY_TARGET" &&
            hasUsableFile(record)

    /** Whether the source now advertises more pixels than the copy we hold, so fetching it again can help. */
    private fun offersLargerRendition(candidate: CandidateAsset, held: WallpaperRecord): Boolean {
        val advertised = candidate.provenance.originalWidth.toLong() * candidate.provenance.originalHeight
        val heldPixels = held.provenance.originalWidth.toLong() * held.provenance.originalHeight
        return advertised > 0L && advertised > heldPixels
    }

    /** True when the record still has a readable, non-empty image file on disk. */
    internal fun hasUsableFile(record: WallpaperRecord): Boolean =
        record.localFilePath.isNotBlank() &&
            record.fileSizeBytes > 0L &&
            File(record.localFilePath).let { it.isFile && it.length() > 0L }

    /**
     * Auto Backup restores the library database but not the image files (Article IX §3), and the cache
     * manager evicts files too. Rows left without a file are marked evicted so counts are honest and the
     * next sync downloads them again. Seeds are re-rendered by [ensureSeedReserveLoaded]; the baseline
     * restore copy is left untouched. Returns how many rows changed.
     */
    fun healMissingFiles(): Int {
        var healed = 0
        for (record in getAllWallpapers()) {
            // Seeds are re-rendered, the baseline copy is kept, and a missing personal photo cannot be fetched again.
            if (record.isRejected || record.isBaselineRestore || record.provenance.sourceId in HEAL_EXEMPT_SOURCES) continue
            if (record.localFilePath.isBlank() && record.fileSizeBytes == 0L) continue
            if (hasUsableFile(record)) continue
            // Re-read the row: a sync may have re-downloaded it since the list was read, and blanking a fresh
            // record would orphan its new file.
            // The lock is shared with the sync's write of a downloaded record (any repository instance), so the
            // check and the blanking write cannot interleave with a fresh download.
            synchronized(LIBRARY_WRITE_LOCK) {
                val current = getWallpaperById(record.id) ?: return@synchronized
                if (current.isRejected || current.isBaselineRestore || current.provenance.sourceId in HEAL_EXEMPT_SOURCES ||
                    hasUsableFile(current)
                ) return@synchronized
                upsertWallpaper(current.copy(localFilePath = "", croppedFilePath = "", fileSizeBytes = 0L))
                healed++
            }
        }
        return healed
    }

    fun enforceCacheLimit() {
        val prefs = loadPreferences()
        val activeIds = setOfNotNull(getCurrentWallpaperId(), getPreviousWallpaperId())
        val (updated, report) = cacheManager.executeCleanupOnDisk(
            records = getAllWallpapers(),
            activeWallpaperIds = activeIds,
            maxCacheBytes = prefs.maxCacheBytes
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

    fun healCancelledSourceDiagnostics() =
        sourceHealth.healCancelledDiagnostics(SourceRegistry.admittedSources().map { it.sourceId })

    fun clearTransientSourceFailureOnReenable(sourceId: String) = sourceHealth.clearTransientFailureOnReenable(sourceId)

    fun getSourceDiagnostics(nowEpochMs: Long = System.currentTimeMillis()): List<SourceDiagnostics> {
        healCancelledSourceDiagnostics()
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
