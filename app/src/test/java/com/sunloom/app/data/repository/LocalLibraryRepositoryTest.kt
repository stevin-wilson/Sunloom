package com.sunloom.app.data.repository

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.WallpaperStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LocalLibraryRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: LocalLibraryRepository
    private val display = DisplayProfile(0, "GoogleBook Built-in Display", 2560, 1600)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = LocalLibraryRepository(context)
    }

    @Test
    fun `ensureSeedReserveLoaded populates 3 Light and 3 Dark CC0 wallpapers into SQLite`() {
        val loaded = repository.ensureSeedReserveLoaded(display)
        assertEquals(6, loaded.size)
        val lightCount = loaded.count { it.evaluation.brightnessClass == BrightnessClass.LIGHT }
        val darkCount = loaded.count { it.evaluation.brightnessClass == BrightnessClass.DARK }
        assertEquals(3, lightCount)
        assertEquals(3, darkCount)
    }

    @Test
    fun `savePreferences and loadPreferences round-trip user choices accurately`() {
        val initial = repository.loadPreferences()
        val updated = initial.copy(
            isSetupCompleted = true,
            scheduleMode = ScheduleMode.FOLLOW_OS_THEME,
            selectedCityName = "Tokyo, Japan",
            latitude = 35.6762,
            longitude = 139.6503,
            wifiOnlyDownloads = true
        )
        repository.savePreferences(updated)

        val reloaded = repository.loadPreferences()
        assertTrue(reloaded.isSetupCompleted)
        assertEquals(ScheduleMode.FOLLOW_OS_THEME, reloaded.scheduleMode)
        assertEquals("Tokyo, Japan", reloaded.selectedCityName)
        assertEquals(35.6762, reloaded.latitude, 0.0001)
        assertEquals(139.6503, reloaded.longitude, 0.0001)
        assertTrue(reloaded.wifiOnlyDownloads)
    }

    @Test
    fun `recordWallpaperApplied updates current and previous wallpaper history stack`() {
        repository.ensureSeedReserveLoaded(display)
        repository.recordWallpaperApplied("seed-light-alpine-mist")
        assertEquals("seed-light-alpine-mist", repository.getCurrentWallpaperId())

        repository.recordWallpaperApplied("seed-dark-orion-nebula")
        assertEquals("seed-dark-orion-nebula", repository.getCurrentWallpaperId())
        assertEquals("seed-light-alpine-mist", repository.getPreviousWallpaperId())
    }

    @Test
    fun `rejectWallpaperForever marks item rejected and logs rejection reason without retaining file`() {
        repository.ensureSeedReserveLoaded(display)
        repository.rejectWallpaperForever("seed-light-solstice-dunes", "User disliked composition")

        val rejectedRecord = repository.getWallpaperById("seed-light-solstice-dunes")
        assertNotNull(rejectedRecord)
        assertTrue(rejectedRecord!!.isRejected)
        assertEquals("", rejectedRecord.localFilePath)

        val rejections = repository.getAllRejections()
        assertTrue(rejections.any { it.assetId == "seed-light-solstice-dunes" && it.reasonCode == "USER_REJECTED" })
        assertTrue(repository.getPermanentBlockedAssetIds().contains("seed-light-solstice-dunes"))
    }

    @Test
    fun `importUserLocalBitmap saves baseline restore wallpaper and updates preferences`() {
        val bmp = Bitmap.createBitmap(64, 40, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF1E293B.toInt())

        val baseline = repository.importUserLocalBitmap(
            title = "My Original GoogleBook Wallpaper",
            bitmap = bmp,
            display = display,
            asBaselineRestore = true
        )

        assertTrue(baseline.isBaselineRestore)
        assertNotNull(repository.loadPreferences().baselineRestorePath)
    }

    @Test
    fun `testFailClosedEnumDecodingMarksCorruptedRowUnsafe`() {
        val seeds = repository.ensureSeedReserveLoaded(display)
        val validRow = WallpaperRowMapper.toColumnMap(seeds.first()).toMutableMap()
        validRow["license_type"] = "CORRUPTED_LICENSE_ENUM"
        validRow["safety_class"] = "CORRUPTED_SAFETY_CLASS"
        validRow["status"] = "CORRUPTED_STATUS"

        val decoded = WallpaperRowMapper.fromColumnMap(validRow)
        assertEquals(LicenseType.UNKNOWN_OR_RESTRICTED, decoded.provenance.licenseType)
        assertEquals(SafetyClassification.UNCERTAIN, decoded.provenance.safetyClassification)
        assertEquals(WallpaperStatus.REJECTED_SAFETY_OR_QUALITY, decoded.status)
        assertTrue(decoded.isRejected)
        assertFalse(decoded.evaluation.isEligible)
    }

    @Test
    fun `testRowMapperPreservesCropGeometryAndFocalPoint`() {
        val seeds = repository.ensureSeedReserveLoaded(display)
        val first = seeds.first()
        val customCrop = first.evaluation.cropRect!!.copy(
            left = 412,
            top = 128,
            width = 2560,
            height = 1600,
            focalX = 0.78,
            focalY = 0.34,
            usesBlurredFrameFill = true,
            pixelCoverageRatio = 0.82
        )
        val updated = first.copy(
            rawCategoriesAndTags = listOf("alpine", "mountain", "glacier"),
            evaluation = first.evaluation.copy(cropRect = customCrop)
        )
        val colMap = WallpaperRowMapper.toColumnMap(updated)
        val roundTripped = WallpaperRowMapper.fromColumnMap(colMap)

        val rtCrop = roundTripped.evaluation.cropRect!!
        assertEquals(412, rtCrop.left)
        assertEquals(128, rtCrop.top)
        assertEquals(2560, rtCrop.width)
        assertEquals(1600, rtCrop.height)
        assertEquals(0.78, rtCrop.focalX, 0.001)
        assertEquals(0.34, rtCrop.focalY, 0.001)
        assertTrue(rtCrop.usesBlurredFrameFill)
        assertEquals(0.82, rtCrop.pixelCoverageRatio, 0.001)
        assertEquals(listOf("alpine", "mountain", "glacier"), roundTripped.rawCategoriesAndTags)
    }

    @Test
    fun `testQuarantineCleanupOnStartupSweep`() {
        val staleFile = File(repository.quarantineDir, "stale_quarantine_test.tmp")
        staleFile.writeBytes(ByteArray(128) { 0x11 })
        staleFile.setLastModified(System.currentTimeMillis() - LocalLibraryRepository.QUARANTINE_MAX_AGE_MS - 10_000L)

        val deletedCount = repository.sweepStaleQuarantineFiles()
        assertTrue(deletedCount >= 1)
        assertFalse(staleFile.exists())
    }

    @Test
    fun `testGateVersionBumpRevalidatesAndEvictsUnsafeActiveWallpaper`() {
        val seeds = repository.ensureSeedReserveLoaded(display)
        val unsafeRecord = seeds.first().copy(
            id = "legacy-unsafe-wall",
            title = "Classical Nude Marble Statue",
            provenance = seeds.first().provenance.copy(
                sourceId = "wikimedia_featured",
                assetId = "legacy-unsafe-wall",
                safetyGateVersion = "v0.1.0"
            )
        )
        repository.upsertWallpaper(unsafeRecord)
        repository.recordWallpaperApplied("legacy-unsafe-wall")

        val evicted = repository.revalidateGateVersionIfNeeded(display)
        assertTrue(evicted >= 1)
        val after = repository.getWallpaperById("legacy-unsafe-wall")
        assertNotNull(after)
        assertTrue(after!!.isRejected)
        assertTrue(repository.getCurrentWallpaperId() != "legacy-unsafe-wall")
    }

    @Test
    fun `refreshOnlineCatalog uses fallbackDownloadUrls when primary IIIF rendition fails`() = kotlinx.coroutines.test.runTest {
        val validBmp = Bitmap.createBitmap(1700, 1100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF4A7C9B.toInt())
        }
        val jpegBytes = java.io.ByteArrayOutputStream().also {
            validBmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        validBmp.recycle()

        val failingPrimaryUrl = "https://www.artic.edu/iiif/2/abc/full/1686,/0/default.jpg"
        val workingFallbackUrl = "https://www.artic.edu/iiif/2/abc/full/843,/0/default.jpg"

        val fakeClient = object : com.sunloom.app.data.sources.HttpClient {
            override suspend fun getText(url: String): String = "{}"
            override suspend fun getBytes(url: String, maxBytes: Int): ByteArray {
                if (url == failingPrimaryUrl) throw IllegalStateException("HTTP 403 Forbidden")
                if (url == workingFallbackUrl) return jpegBytes
                throw IllegalStateException("Unexpected URL $url")
            }
        }
        val fakeAdapter = object : com.sunloom.app.data.sources.WallpaperSourceAdapter {
            override val sourceId: String = "artic_cc0"
            override val sourceName: String = "ArtIC"
            override val licenseSummary: String = "CC0"
            override suspend fun fetchCandidates(
                enabledCategories: Set<com.sunloom.app.domain.model.SafeCategory>,
                limit: Int,
                offset: Int,
                targetWidthPx: Int
            ) = listOf(
                com.sunloom.app.domain.model.CandidateAsset(
                    id = "artic-fallback-test",
                    title = "Mountain Valley Clouds",
                    description = "Landscape",
                    category = com.sunloom.app.domain.model.SafeCategory.SCENIC_FINE_ART,
                    rawCategoriesAndTags = listOf("landscape", "mountains"),
                    downloadUrl = failingPrimaryUrl,
                    fallbackDownloadUrls = listOf(workingFallbackUrl),
                    provenance = com.sunloom.app.domain.model.ProvenanceRecord(
                        sourceId = "artic_cc0",
                        sourceName = "ArtIC",
                        assetId = "artic-fallback-test",
                        originalPageUrl = "https://www.artic.edu/artworks/1",
                        creatorName = "Artist",
                        creatorPageUrl = "",
                        licenseType = LicenseType.CC0,
                        attributionText = "CC0",
                        retrievedAtEpochMs = 1762280000000L,
                        originalWidth = 0,
                        originalHeight = 0,
                        renditionWidth = 0,
                        renditionHeight = 0,
                        safetyClassification = SafetyClassification.SAFE,
                        safetyClassificationSource = "ArtIC",
                        sha256Checksum = ""
                    )
                )
            )
        }

        val highDpiDisplay = DisplayProfile(0, "GoogleBook Retina", 2560, 1600, densityDpi = 220)
        val customRepo = LocalLibraryRepository(
            context = context,
            httpClient = fakeClient,
            sourceAdapters = listOf(fakeAdapter)
        )
        val added = customRepo.refreshOnlineCatalog(highDpiDisplay)
        assertEquals(1, added)
        assertNotNull(customRepo.getWallpaperById("artic-fallback-test"))
    }

    @Test
    fun `selectAdaptersForSync caps batch to 3 sources, prioritizes dark photography on deficit, and rotates cursor`() {
        val prefs = repository.loadPreferences()
        val now = 1762280000000L

        // When unseenDarkCount < 3, both dark-capable photographic sources (wikimedia + nasa) are prioritized
        val firstBatch = repository.selectAdaptersForSync(
            preferences = prefs,
            nowEpochMs = now,
            unseenDarkCount = 0,
            maxSourcesPerSync = 3
        ).map { it.sourceId }
        assertEquals(3, firstBatch.size)
        assertTrue(firstBatch.contains("wikimedia_featured"))
        assertTrue(firstBatch.contains("nasa_library"))

        // When dark reserve is healthy (unseenDarkCount >= 3), round-robin rotates across all 6 online sources including CMA & SMK
        val seenAcrossCycles = mutableSetOf<String>()
        repeat(3) {
            seenAcrossCycles += repository.selectAdaptersForSync(
                preferences = prefs,
                nowEpochMs = now,
                unseenDarkCount = 4,
                maxSourcesPerSync = 3
            ).map { it.sourceId }
        }
        assertTrue(seenAcrossCycles.containsAll(listOf("cma_cc0", "smk_open", "artic_cc0", "met_museum_cc0")))
    }

    @Test
    fun `loadPreferences enables newly admitted CMA and SMK on upgrade while preserving user-disabled sources`() {
        val rawPrefs = context.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE)
        // Simulate v1.3.0 user who disabled nasa_library before upgrading to v1.4.0 (no known_admitted_sources saved yet)
        rawPrefs.edit()
            .putStringSet(
                "enabled_sources",
                setOf("seed_reserve", "wikimedia_featured", "artic_cc0", "met_museum_cc0", "user_local")
            )
            .remove("known_admitted_sources")
            .commit()

        val upgraded = repository.loadPreferences()
        assertFalse("User-disabled nasa_library must stay disabled", "nasa_library" in upgraded.enabledSources)
        assertTrue("Newly admitted cma_cc0 must be enabled on upgrade", "cma_cc0" in upgraded.enabledSources)
        assertTrue("Newly admitted smk_open must be enabled on upgrade", "smk_open" in upgraded.enabledSources)
    }

    @Test
    fun `refreshOnlineCatalog rethrows CancellationException without marking Wikimedia or NASA degraded`() = kotlinx.coroutines.test.runTest {
        var nasaCalledAfterCancel = false
        val cancellingWikiAdapter = object : com.sunloom.app.data.sources.WallpaperSourceAdapter {
            override val sourceId: String = "wikimedia_featured"
            override val sourceName: String = "Wikimedia Commons Featured Photography"
            override val licenseSummary: String = "CC BY-SA 4.0"
            override suspend fun fetchCandidates(
                enabledCategories: Set<com.sunloom.app.domain.model.SafeCategory>,
                limit: Int,
                offset: Int,
                targetWidthPx: Int
            ): List<com.sunloom.app.domain.model.CandidateAsset> {
                throw java.util.concurrent.CancellationException("Job was cancelled")
            }
        }
        val nasaAdapter = object : com.sunloom.app.data.sources.WallpaperSourceAdapter {
            override val sourceId: String = "nasa_library"
            override val sourceName: String = "NASA Space & Earth Image Library"
            override val licenseSummary: String = "Public Domain"
            override suspend fun fetchCandidates(
                enabledCategories: Set<com.sunloom.app.domain.model.SafeCategory>,
                limit: Int,
                offset: Int,
                targetWidthPx: Int
            ): List<com.sunloom.app.domain.model.CandidateAsset> {
                nasaCalledAfterCancel = true
                throw java.util.concurrent.CancellationException("Job was cancelled")
            }
        }

        val customRepo = LocalLibraryRepository(
            context = context,
            sourceAdapters = listOf(cancellingWikiAdapter, nasaAdapter)
        )

        var caughtCancellation = false
        repeat(4) {
            try {
                customRepo.refreshOnlineCatalog(display)
            } catch (_: java.util.concurrent.CancellationException) {
                caughtCancellation = true
            }
        }

        assertTrue("refreshOnlineCatalog must rethrow CancellationException", caughtCancellation)
        assertFalse("Must not continue polling subsequent adapters after cancellation", nasaCalledAfterCancel)

        val diags = customRepo.getSourceDiagnostics().associateBy { it.sourceId }
        val wikiDiag = diags.getValue("wikimedia_featured")
        val nasaDiag = diags.getValue("nasa_library")
        assertEquals(com.sunloom.app.domain.model.SourceHealthState.HEALTHY, wikiDiag.healthState)
        assertEquals(0, wikiDiag.consecutiveFailures)
        assertEquals("", wikiDiag.lastError)
        assertEquals(com.sunloom.app.domain.model.SourceHealthState.HEALTHY, nasaDiag.healthState)
        assertEquals(0, nasaDiag.consecutiveFailures)
        assertEquals("", nasaDiag.lastError)
    }

    @Test
    fun `persisted Job was cancelled source diagnostics self-heal to Healthy while real HTTP errors remain tracked`() {
        val rawPrefs = context.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE)
        val now = 1762280000000L
        rawPrefs.edit()
            .putInt("source_fail_count_wikimedia_featured", 4)
            .putLong("source_backoff_until_wikimedia_featured", now + 600_000L)
            .putString("source_last_error_wikimedia_featured", "Job was cancelled")
            .putInt("source_fail_count_nasa_library", 3)
            .putLong("source_backoff_until_nasa_library", now + 300_000L)
            .putString("source_last_error_nasa_library", "StandaloneCoroutine was cancelled")
            .putInt("source_fail_count_artic_cc0", 3)
            .putLong("source_backoff_until_artic_cc0", now + 300_000L)
            .putString("source_last_error_artic_cc0", "HTTP 503 from https://api.artic.edu")
            .commit()

        val healedRepo = LocalLibraryRepository(context)
        val diags = healedRepo.getSourceDiagnostics(now).associateBy { it.sourceId }

        val wiki = diags.getValue("wikimedia_featured")
        assertEquals(com.sunloom.app.domain.model.SourceHealthState.HEALTHY, wiki.healthState)
        assertEquals(0, wiki.consecutiveFailures)
        assertEquals("", wiki.lastError)

        val nasa = diags.getValue("nasa_library")
        assertEquals(com.sunloom.app.domain.model.SourceHealthState.HEALTHY, nasa.healthState)
        assertEquals(0, nasa.consecutiveFailures)
        assertEquals("", nasa.lastError)

        // Genuine HTTP 503 failure remains degraded as expected
        val artic = diags.getValue("artic_cc0")
        assertEquals(com.sunloom.app.domain.model.SourceHealthState.DEGRADED, artic.healthState)
        assertEquals(3, artic.consecutiveFailures)

        // Both healed dark-capable sources are immediately eligible for sync again
        val selectedIds = healedRepo.selectAdaptersForSync(
            preferences = healedRepo.loadPreferences(),
            nowEpochMs = now,
            unseenDarkCount = 0,
            maxSourcesPerSync = 3
        ).map { it.sourceId }
        assertTrue(selectedIds.contains("wikimedia_featured"))
        assertTrue(selectedIds.contains("nasa_library"))
    }

    @Test
    fun `turning off a source disables all reserve images from that source and re-enabling restores them`() {
        repository.ensureSeedReserveLoaded(display)
        val initialPrefs = repository.loadPreferences()

        // Turn off seed_reserve
        repository.savePreferences(
            initialPrefs.copy(enabledSources = initialPrefs.enabledSources - "seed_reserve")
        )
        val afterDisable = repository.getAllWallpapers().filter { it.provenance.sourceId == "seed_reserve" }
        assertTrue(afterDisable.isNotEmpty())
        assertTrue(
            "All images from disabled source must be marked DISABLED_BY_USER",
            afterDisable.all { it.status == WallpaperStatus.DISABLED_BY_USER }
        )

        // Turn seed_reserve back on
        repository.savePreferences(initialPrefs)
        val afterReenable = repository.getAllWallpapers().filter { it.provenance.sourceId == "seed_reserve" }
        assertTrue(
            "Re-enabling source must restore reserve images to PREPARED",
            afterReenable.all { it.status == WallpaperStatus.PREPARED }
        )
    }

    @Test
    fun `deleteWallpaperFromReserve removes file, marks rejected, and rotates active wallpaper if needed`() {
        val loaded = repository.ensureSeedReserveLoaded(display)
        val target = loaded.first()
        repository.recordWallpaperApplied(target.id)
        assertEquals(target.id, repository.getCurrentWallpaperId())

        repository.deleteWallpaperFromReserve(target.id)
        val deletedRecord = repository.getWallpaperById(target.id)
        assertNotNull(deletedRecord)
        assertTrue(deletedRecord!!.isRejected)
        assertEquals("", deletedRecord.localFilePath)
        assertTrue(repository.getPermanentBlockedAssetIds().contains(target.id))
        assertTrue(repository.getCurrentWallpaperId() != target.id)
    }

    @Test
    fun `enforceCacheLimit skips database upserts when no wallpapers are evicted`() {
        var upsertCalls = 0
        val baseStore = InMemoryLibraryStore()
        val countingStore = object : LibraryStore by baseStore {
            override fun upsertWallpaper(record: com.sunloom.app.domain.model.WallpaperRecord) {
                upsertCalls++
                baseStore.upsertWallpaper(record)
            }
        }
        val repoWithCountingStore = LocalLibraryRepository(
            context = context,
            store = countingStore
        )
        repoWithCountingStore.ensureSeedReserveLoaded(display)
        assertTrue("Expected initial seed load to upsert wallpapers", upsertCalls >= 6)

        upsertCalls = 0
        repoWithCountingStore.enforceCacheLimit()
        assertEquals(
            "Expected 0 database upserts during enforceCacheLimit when cache is under maxCacheBytes",
            0,
            upsertCalls
        )
    }

    @Test
    fun `docking and undocking across laptop UWQHD 32_9 and portrait displays reconciles seeds and cached eligibility non-destructively`() {
        val laptop = DisplayProfile(0, "GoogleBook Retina", 2560, 1600, densityDpi = 240)
        val uwqhd = DisplayProfile(1, "External UWQHD", 3440, 1440, densityDpi = 160)
        val superUltrawide = DisplayProfile(2, "External 32:9 DQHD", 5120, 1440, densityDpi = 160)
        val portraitQhd = DisplayProfile(3, "External Portrait QHD", 1440, 2560, densityDpi = 160)

        // 1. Start on laptop: load seeds + insert a 1686x1124 ArtIC landscape and a 4000x2600 SMK landscape
        val initialSeeds = repository.ensureSeedReserveLoaded(laptop)
        val template = initialSeeds.first()
        val articFile = File(repository.wallpaperDir, "artic-1686.jpg").apply { writeBytes(ByteArray(64) { 0x22 }) }
        val smkFile = File(repository.wallpaperDir, "smk-4000.jpg").apply { writeBytes(ByteArray(64) { 0x33 }) }

        val articRecord = template.copy(
            id = "artic-1686",
            title = "Monet Cliff Walk",
            localFilePath = articFile.absolutePath,
            croppedFilePath = articFile.absolutePath,
            fileSizeBytes = articFile.length(),
            provenance = template.provenance.copy(
                sourceId = "artic_cc0",
                assetId = "artic-1686",
                originalWidth = 1686,
                originalHeight = 1124,
                renditionWidth = 1686,
                renditionHeight = 1054
            ),
            evaluation = template.evaluation.copy(
                isEligible = true,
                cropRect = template.evaluation.cropRect!!.copy(
                    width = 1686,
                    height = 1054,
                    focalX = 0.72,
                    focalY = 0.44
                )
            )
        )
        val smkRecord = template.copy(
            id = "smk-4000",
            title = "Købke North Coast",
            localFilePath = smkFile.absolutePath,
            croppedFilePath = smkFile.absolutePath,
            fileSizeBytes = smkFile.length(),
            provenance = template.provenance.copy(
                sourceId = "smk_open",
                assetId = "smk-4000",
                originalWidth = 4000,
                originalHeight = 2600,
                renditionWidth = 2560,
                renditionHeight = 1600
            )
        )
        repository.upsertWallpaper(articRecord)
        repository.upsertWallpaper(smkRecord)

        // 2. Dock to UWQHD (3440x1440 @ 160dpi) with allowBlurredFrameFill = false
        repository.ensureSeedReserveLoaded(uwqhd)
        repository.reconcileReserveForDisplay(uwqhd)

        val uwqhdSeed = repository.getWallpaperById("seed-light-alpine-mist")!!
        assertEquals(3440, uwqhdSeed.provenance.originalWidth)
        assertEquals(1440, uwqhdSeed.provenance.originalHeight)
        assertTrue(uwqhdSeed.evaluation.isEligible)

        val articOnUwqhd = repository.getWallpaperById("artic-1686")!!
        assertFalse("1686x1124 must be ineligible on UWQHD without blurred fill", articOnUwqhd.evaluation.isEligible)
        assertFalse("Temporarily under-sized cached art must NOT be permanently rejected", articOnUwqhd.isRejected)
        assertTrue("Cached file must remain intact on disk while docked", articFile.exists())

        val smkOnUwqhd = repository.getWallpaperById("smk-4000")!!
        assertTrue("4000x2600 must remain eligible on UWQHD", smkOnUwqhd.evaluation.isEligible)
        val smkCrop = requireNotNull(smkOnUwqhd.evaluation.cropRect)
        val smkCropAspect = smkCrop.width.toDouble() / smkCrop.height.toDouble()
        assertEquals(uwqhd.aspectRatio, smkCropAspect, 0.01)

        // Also verify selectAdaptersForSync skips artic_cc0 when targeting UWQHD with blurred fill disabled
        val prefsNoBlur = repository.loadPreferences().copy(allowBlurredFrameFill = false)
        val uwqhdSyncedSources = mutableSetOf<String>()
        repeat(4) {
            uwqhdSyncedSources += repository.selectAdaptersForSync(
                preferences = prefsNoBlur,
                nowEpochMs = 1762280000000L,
                unseenDarkCount = 4,
                maxSourcesPerSync = 3,
                display = uwqhd
            ).map { it.sourceId }
        }
        assertFalse("artic_cc0 (max 1686px) should be skipped for 3440x1440 sync when blurred fill is off", "artic_cc0" in uwqhdSyncedSources)

        // 3. Dock to 32:9 Super-Ultrawide (5120x1440) and Portrait QHD (1440x2560): seeds adapt without aspect clamping
        repository.ensureSeedReserveLoaded(superUltrawide)
        val dqhdSeed = repository.getWallpaperById("seed-light-alpine-mist")!!
        assertEquals(5120, dqhdSeed.provenance.originalWidth)
        assertEquals(1440, dqhdSeed.provenance.originalHeight)
        assertTrue(dqhdSeed.evaluation.isEligible)

        repository.ensureSeedReserveLoaded(portraitQhd)
        repository.reconcileReserveForDisplay(portraitQhd)
        val portraitSeed = repository.getWallpaperById("seed-light-alpine-mist")!!
        assertEquals(1440, portraitSeed.provenance.originalWidth)
        assertEquals(2560, portraitSeed.provenance.originalHeight)
        assertTrue(portraitSeed.evaluation.isEligible)

        // 4. Enable allowBlurredFrameFill on UWQHD -> 1686x1124 immediately becomes eligible with blurred fill!
        val prefsWithBlur = prefsNoBlur.copy(allowBlurredFrameFill = true)
        repository.reconcileReserveForDisplay(uwqhd, prefsWithBlur)
        val articWithBlur = repository.getWallpaperById("artic-1686")!!
        assertTrue(articWithBlur.evaluation.isEligible)
        assertTrue(articWithBlur.evaluation.cropRect!!.usesBlurredFrameFill)

        // 5. Undock back to laptop (2560x1600 @ 240dpi) with allowBlurredFrameFill = false -> native crop & original focal point restored!
        repository.reconcileReserveForDisplay(laptop, prefsNoBlur)
        val articUndocked = repository.getWallpaperById("artic-1686")!!
        assertTrue(articUndocked.evaluation.isEligible)
        val undockedCrop = requireNotNull(articUndocked.evaluation.cropRect)
        assertFalse(undockedCrop.usesBlurredFrameFill)
        assertEquals(0.72, undockedCrop.focalX, 0.01)
        assertEquals(0.44, undockedCrop.focalY, 0.01)
    }

    @Test
    fun `revalidateGateVersionIfNeeded re-runs L3 and L4 on local bytes and never fakes recordWallpaperApplied on replacement`() {
        val seeds = repository.ensureSeedReserveLoaded(display)
        val corruptFile = File(repository.wallpaperDir, "corrupt-reval.jpg").apply {
            writeBytes(ByteArray(128) { 0x55 }) // < 4096 bytes -> must fail L3 payload bounds!
        }
        val staleCorrupt = seeds.first().copy(
            id = "corrupt-reval",
            title = "Glacier Horizon Sunrise",
            rawCategoriesAndTags = listOf("landscape", "glacier"),
            localFilePath = corruptFile.absolutePath,
            croppedFilePath = corruptFile.absolutePath,
            fileSizeBytes = corruptFile.length(),
            provenance = seeds.first().provenance.copy(
                sourceId = "wikimedia_featured",
                assetId = "corrupt-reval",
                originalPageUrl = "https://commons.wikimedia.org/wiki/File:Glacier.jpg",
                safetyGateVersion = "v0.1.0"
            )
        )
        repository.upsertWallpaper(staleCorrupt)
        repository.recordWallpaperApplied("corrupt-reval")

        val evicted = repository.revalidateGateVersionIfNeeded(display)
        assertTrue("Expected corrupt local file to be evicted at L3 during gate re-validation", evicted >= 1)
        val evictedRec = requireNotNull(repository.getWallpaperById("corrupt-reval"))
        assertTrue(evictedRec.isRejected)
        assertTrue(evictedRec.provenance.gateLayerOutcomes.contains("L3:FAIL"))
        // C1 honesty invariant: repository alone must NOT claim another wallpaper was applied without WallpaperManager!
        assertEquals(
            "Repository must clear invalidated currentWallpaperId instead of calling recordWallpaperApplied without WallpaperManager",
            null,
            repository.getCurrentWallpaperId()
        )
    }

    @Test
    fun `importUserUri preserves original JPEG bytes and 4K header dimensions without 2048px PNG re-encode`() {
        val uhdDisplay = DisplayProfile(0, "4K Display", 3840, 2160, densityDpi = 160)
        // Generate a real 3840x2160 JPEG file in cacheDir
        val bmp = Bitmap.createBitmap(3840, 2160, Bitmap.Config.RGB_565).apply {
            eraseColor(0xFF2E5A88.toInt())
        }
        val srcJpeg = File(context.cacheDir, "user_4k_photo.jpg")
        java.io.FileOutputStream(srcJpeg).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        bmp.recycle()
        val expectedSha = com.sunloom.app.domain.safety.SafetyGate.sha256Hex(srcJpeg.readBytes())

        val imported = repository.importUserUri(
            uri = android.net.Uri.fromFile(srcJpeg),
            display = uhdDisplay,
            asBaselineRestore = false
        )
        assertNotNull("Expected 4K JPEG user import to succeed", imported)
        val rec = requireNotNull(imported)
        assertTrue("Expected original .jpg extension to be preserved, got ${rec.localFilePath}", rec.localFilePath.endsWith(".jpg"))
        assertEquals("Expected exact original JPEG bytes without PNG re-encoding", expectedSha, rec.provenance.sha256Checksum)
        assertEquals(3840, rec.provenance.originalWidth)
        assertEquals(2160, rec.provenance.originalHeight)
        assertFalse("4K user JPEG on 4K display must achieve 100% native coverage without blurred fill", rec.evaluation.cropRect!!.usesBlurredFrameFill)
        assertEquals("L1:USER_EXEMPT;L2:USER_EXEMPT;L3:PASS;L4:PASS", rec.provenance.gateLayerOutcomes)
    }

    @Test
    fun `legacy SharedPreferences rejected_perceptual_hashes migrate once into LibraryStore blocklist`() {
        val prefs = context.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet("rejected_perceptual_hashes", setOf("0011223344556677", "8899aabbccddeeff"))
            .commit()

        val migratedStore = InMemoryLibraryStore()
        val migratedRepo = LocalLibraryRepository(context, store = migratedStore)

        val blockedHashes = migratedRepo.getPermanentBlockedPerceptualHashes()
        assertTrue(blockedHashes.contains("0011223344556677"))
        assertTrue(blockedHashes.contains("8899aabbccddeeff"))
        assertFalse(
            "Duplicate SharedPreferences key must be removed after SQLite migration",
            prefs.contains("rejected_perceptual_hashes")
        )
        assertEquals(2, migratedStore.getAllBlocklistEntries().size)
    }

    @Test
    fun `createDefaultStore fails closed with LibraryStoreUnavailableException instead of silent in-memory fallback`() {
        val prevFactory = LocalLibraryRepository.testStoreFactoryOverride
        try {
            LocalLibraryRepository.clearCachedDefaultStoreForTests()
            LocalLibraryRepository.testStoreFactoryOverride = {
                throw IllegalStateException("Simulated SQLite disk I/O corruption")
            }
            var threwFailClosed = false
            try {
                LocalLibraryRepository.createDefaultStore(context)
            } catch (_: LibraryStoreUnavailableException) {
                threwFailClosed = true
            }
            assertTrue("Expected LibraryStoreUnavailableException instead of silent InMemoryLibraryStore fallback", threwFailClosed)
        } finally {
            LocalLibraryRepository.clearCachedDefaultStoreForTests()
            LocalLibraryRepository.testStoreFactoryOverride = prevFactory
        }
    }
}
