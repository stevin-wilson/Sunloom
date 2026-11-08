package com.sunloom.app.domain.matcher

import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.ImageEvaluationResult
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class WallpaperMatcherTest {

    private val matcher = WallpaperMatcher(
        fileExistsVerifier = { record ->
            record.localFilePath.isNotBlank() && record.fileSizeBytes > 0L
        }
    )

    private fun sampleRecord(
        id: String,
        brightness: BrightnessClass,
        category: SafeCategory = SafeCategory.LANDSCAPES_NATURE,
        isRejected: Boolean = false,
        isFavorite: Boolean = false,
        appliedCount: Int = 0,
        fileSizeBytes: Long = 2048L,
        localFilePath: String = "/tmp/$id.jpg"
    ): WallpaperRecord {
        return WallpaperRecord(
            id = id,
            title = "Sample $id",
            category = category,
            localFilePath = localFilePath,
            croppedFilePath = "/tmp/${id}_crop.jpg",
            fileSizeBytes = fileSizeBytes,
            isRejected = isRejected,
            isFavorite = isFavorite,
            appliedCount = appliedCount,
            provenance = ProvenanceRecord(
                sourceId = "wikimedia_featured",
                sourceName = "Wikimedia Commons",
                assetId = id,
                originalPageUrl = "https://commons.wikimedia.org/wiki/$id",
                creatorName = "Creator $id",
                creatorPageUrl = "https://commons.wikimedia.org",
                licenseType = LicenseType.CC0,
                attributionText = "CC0",
                retrievedAtEpochMs = 1762280000000L,
                originalWidth = 3840,
                originalHeight = 2400,
                renditionWidth = 2560,
                renditionHeight = 1600,
                safetyClassification = SafetyClassification.SAFE,
                safetyClassificationSource = "Verified",
                sha256Checksum = "abcd1234"
            ),
            evaluation = ImageEvaluationResult(
                isEligible = true,
                rejectionReasonCode = null,
                globalLuminance = if (brightness == BrightnessClass.DARK) 0.18 else 0.72,
                desktopZoneLuminance = if (brightness == BrightnessClass.DARK) 0.18 else 0.70,
                lockClockZoneLuminance = if (brightness == BrightnessClass.DARK) 0.16 else 0.68,
                glareFractionInSafeZones = 0.0,
                contrastStdDev = 0.12,
                brightnessClass = brightness,
                cropRect = CropRect(0, 0, 2560, 1600, 0.5, 0.5, false, 1.0),
                explanation = "Sample"
            )
        )
    }

    @Test
    fun `selectNextWallpaper excludes rejected and recently used items and matches target brightness`() {
        val pool = listOf(
            sampleRecord("dark-rejected", BrightnessClass.DARK, isRejected = true),
            sampleRecord("dark-recent", BrightnessClass.DARK),
            sampleRecord("dark-fresh", BrightnessClass.DARK),
            sampleRecord("light-fresh", BrightnessClass.LIGHT)
        )

        val selected = matcher.selectNextWallpaper(
            candidates = pool,
            targetBrightness = BrightnessClass.DARK,
            preferences = UserPreferences(),
            currentWallpaperId = "dark-recent",
            recentlyUsedIds = setOf("dark-recent")
        )

        assertNotNull(selected)
        assertEquals("dark-fresh", selected!!.id)
    }

    @Test
    fun `selectNextWallpaper never falls back to DARK wallpapers when targetBrightness is LIGHT`() {
        val pool = listOf(
            sampleRecord("light-seen-1", BrightnessClass.LIGHT, appliedCount = 2),
            sampleRecord("light-seen-2", BrightnessClass.LIGHT, appliedCount = 1),
            sampleRecord("dark-unseen-1", BrightnessClass.DARK, appliedCount = 0)
        )

        val selected = matcher.selectNextWallpaper(
            candidates = pool,
            targetBrightness = BrightnessClass.LIGHT,
            preferences = UserPreferences(),
            currentWallpaperId = "light-seen-1"
        )

        assertNotNull(selected)
        assertEquals(BrightnessClass.LIGHT, selected!!.evaluation.brightnessClass)
        assertEquals("light-seen-2", selected.id)
        assertEquals(0, matcher.countUnseenForBrightness(pool, BrightnessClass.LIGHT, UserPreferences()))
        assertEquals(1, matcher.countUnseenForBrightness(pool, BrightnessClass.DARK, UserPreferences()))
    }

    @Test
    fun `testExcludesRecordsWhoseLocalFileIsMissingOnDisk`() {
        val realFile = java.io.File.createTempFile("sunloom_matcher_valid_", ".jpg").apply {
            writeBytes(ByteArray(128) { 0x42 })
            deleteOnExit()
        }
        val emptyFile = java.io.File.createTempFile("sunloom_matcher_empty_", ".jpg").apply {
            deleteOnExit()
        }
        val defaultDiskMatcher = WallpaperMatcher()
        val pool = listOf(
            sampleRecord(
                id = "missing-on-disk",
                brightness = BrightnessClass.LIGHT,
                fileSizeBytes = 1024L,
                localFilePath = "/tmp/definitely_missing_sunloom_file_99999.jpg"
            ),
            sampleRecord(
                id = "zero-metadata-size",
                brightness = BrightnessClass.LIGHT,
                fileSizeBytes = 0L,
                localFilePath = realFile.absolutePath
            ),
            sampleRecord(
                id = "zero-disk-bytes",
                brightness = BrightnessClass.LIGHT,
                fileSizeBytes = 512L,
                localFilePath = emptyFile.absolutePath
            ),
            sampleRecord(
                id = "valid-record",
                brightness = BrightnessClass.LIGHT,
                fileSizeBytes = realFile.length(),
                localFilePath = realFile.absolutePath
            )
        )
        val eligible = defaultDiskMatcher.filterEligiblePool(pool, UserPreferences())
        assertEquals(listOf("valid-record"), eligible.map { it.id })
        val selected = defaultDiskMatcher.selectNextWallpaper(
            candidates = pool,
            targetBrightness = BrightnessClass.LIGHT,
            preferences = UserPreferences()
        )
        assertNotNull(selected)
        assertEquals("valid-record", selected!!.id)
    }

    @Test
    fun `selectNextWallpaper falls back to seed_reserve only when no non-seed wallpapers are eligible`() {
        val seedLight1 = sampleRecord("seed-light-1", BrightnessClass.LIGHT).let {
            it.copy(provenance = it.provenance.copy(sourceId = "seed_reserve"))
        }
        val seedLight2 = sampleRecord("seed-light-2", BrightnessClass.LIGHT).let {
            it.copy(provenance = it.provenance.copy(sourceId = "seed_reserve"))
        }
        val pool = listOf(seedLight1, seedLight2)

        val selected = matcher.selectNextWallpaper(
            candidates = pool,
            targetBrightness = BrightnessClass.LIGHT,
            preferences = UserPreferences(),
            currentWallpaperId = "seed-light-1"
        )
        assertNotNull(selected)
        assertEquals("seed-light-2", selected!!.id)
    }

    @Test
    fun `an exact-brightness offline seed beats an online picture of the opposite brightness`() {
        // At night with only Light online pictures, showing a Light one breaks "Light and Dark describe the image".
        val onlineLight = sampleRecord("online-light", BrightnessClass.LIGHT)
        val seedDark = sampleRecord("seed-dark", BrightnessClass.DARK).let {
            it.copy(provenance = it.provenance.copy(sourceId = "seed_reserve"))
        }
        val selected = matcher.selectNextWallpaper(
            candidates = listOf(onlineLight, seedDark),
            targetBrightness = BrightnessClass.DARK,
            preferences = UserPreferences()
        )
        assertEquals("seed-dark", selected?.id)
    }

    @Test
    fun `an online picture that fits still beats a seed that fits`() {
        val onlineDark = sampleRecord("online-dark", BrightnessClass.DARK)
        val seedDark = sampleRecord("seed-dark", BrightnessClass.DARK).let {
            it.copy(provenance = it.provenance.copy(sourceId = "seed_reserve"))
        }
        val selected = matcher.selectNextWallpaper(
            candidates = listOf(seedDark, onlineDark),
            targetBrightness = BrightnessClass.DARK,
            preferences = UserPreferences()
        )
        assertEquals("online-dark", selected?.id)
    }

    @Test
    fun `selectNextWallpaper interleaves sources instead of repeating active source when unseen items tie`() {
        val cmaCurrent = sampleRecord("cma-1", BrightnessClass.LIGHT, SafeCategory.SCENIC_FINE_ART).let {
            it.copy(provenance = it.provenance.copy(sourceId = "cma_cc0"))
        }
        val cmaNext = sampleRecord("cma-2", BrightnessClass.LIGHT, SafeCategory.SCENIC_FINE_ART).let {
            it.copy(provenance = it.provenance.copy(sourceId = "cma_cc0"))
        }
        val wikiPhoto = sampleRecord("wiki-1", BrightnessClass.LIGHT, SafeCategory.LANDSCAPES_NATURE).let {
            it.copy(provenance = it.provenance.copy(sourceId = "wikimedia_featured"))
        }
        val pool = listOf(cmaCurrent, cmaNext, wikiPhoto)

        val selected = matcher.selectNextWallpaper(
            candidates = pool,
            targetBrightness = BrightnessClass.LIGHT,
            preferences = UserPreferences(
                enabledSources = setOf("cma_cc0", "wikimedia_featured")
            ),
            currentWallpaperId = "cma-1"
        )
        assertNotNull(selected)
        assertEquals("wiki-1", selected!!.id)
    }

    @Test
    fun `filterEligiblePool excludes images from disabled sources including seed_reserve and DISABLED_BY_USER status`() {
        val seedWall = sampleRecord("seed-1", BrightnessClass.LIGHT).let {
            it.copy(provenance = it.provenance.copy(sourceId = "seed_reserve"))
        }
        val disabledStatusWall = sampleRecord("wiki-disabled", BrightnessClass.LIGHT).copy(
            status = com.sunloom.app.domain.model.WallpaperStatus.DISABLED_BY_USER
        )
        val enabledWikiWall = sampleRecord("wiki-enabled", BrightnessClass.LIGHT)
        val pool = listOf(seedWall, disabledStatusWall, enabledWikiWall)

        val eligible = matcher.filterEligiblePool(
            candidates = pool,
            preferences = UserPreferences(enabledSources = setOf("wikimedia_featured"))
        )
        assertEquals(listOf("wiki-enabled"), eligible.map { it.id })
    }

    @Test
    fun `selectNextWallpaper prioritizes fine art and other non-seed sources over seed_reserve even when fine art is NEUTRAL`() {
        val seedLight = sampleRecord("seed-light-1", BrightnessClass.LIGHT, appliedCount = 0).let {
            it.copy(provenance = it.provenance.copy(sourceId = "seed_reserve"))
        }
        val seedDark = sampleRecord("seed-dark-1", BrightnessClass.DARK, appliedCount = 0).let {
            it.copy(provenance = it.provenance.copy(sourceId = "seed_reserve"))
        }
        val fineArtNeutral = sampleRecord("artic-1", BrightnessClass.NEUTRAL, SafeCategory.SCENIC_FINE_ART, appliedCount = 0).let {
            it.copy(provenance = it.provenance.copy(sourceId = "artic_cc0"))
        }
        val pool = listOf(seedLight, seedDark, fineArtNeutral)

        val selectedDay = matcher.selectNextWallpaper(
            candidates = pool,
            targetBrightness = BrightnessClass.LIGHT,
            preferences = UserPreferences()
        )
        assertNotNull(selectedDay)
        assertEquals("artic-1", selectedDay!!.id)

        val selectedNight = matcher.selectNextWallpaper(
            candidates = pool,
            targetBrightness = BrightnessClass.DARK,
            preferences = UserPreferences()
        )
        assertNotNull(selectedNight)
        assertEquals("artic-1", selectedNight!!.id)

        // Unseen count must not count seed_reserve items as unseen online wallpapers
        assertEquals(1, matcher.countUnseenForBrightness(pool, BrightnessClass.LIGHT, UserPreferences()))
    }

    private fun withSubject(record: WallpaperRecord, focalX: Double): WallpaperRecord = record.copy(
        provenance = record.provenance.copy(originalWidth = 4800, originalHeight = 3000),
        evaluation = record.evaluation.copy(cropRect = CropRect(0, 0, 4800, 3000, focalX, 0.5, false, 1.0))
    )

    @Test
    fun `a wallpaper whose subject would be cut off is passed over while a fitting one exists`() {
        val cutOff = sampleRecord("cut-off", BrightnessClass.DARK)
        val fitting = sampleRecord("fitting", BrightnessClass.DARK)
        val fit = SubjectFit { it.id != "cut-off" }

        repeat(10) {
            val picked = matcher.selectNextWallpaper(
                candidates = listOf(cutOff, fitting),
                targetBrightness = BrightnessClass.DARK,
                preferences = UserPreferences(),
                subjectFit = fit
            )
            assertEquals("fitting", picked!!.id)
        }
    }

    @Test
    fun `a wallpaper whose subject would be cut off is still used when nothing else is left`() {
        val onlyOne = sampleRecord("cut-off", BrightnessClass.DARK)

        val picked = matcher.selectNextWallpaper(
            candidates = listOf(onlyOne),
            targetBrightness = BrightnessClass.DARK,
            preferences = UserPreferences(),
            subjectFit = SubjectFit { false }
        )

        assertEquals("cut-off", picked!!.id)
    }

    @Test
    fun `SubjectFit flags an edge subject on a laptop plus portrait monitor but not a centred one`() {
        val laptop = DisplayProfile(0, "Laptop", 2560, 1600, densityDpi = 160, isPrimary = true)
        val portrait = DisplayProfile(1, "Portrait", 1200, 1920, densityDpi = 160, isPrimary = false)
        val fit = SubjectFit.forDisplays(listOf(laptop, portrait), UserPreferences())

        val edge = withSubject(sampleRecord("edge", BrightnessClass.DARK), focalX = 0.02)
        val centred = withSubject(sampleRecord("centred", BrightnessClass.DARK), focalX = 0.5)

        assertEquals(false, fit.accepts(edge))
        assertEquals(true, fit.accepts(centred))
    }

    @Test
    fun `SubjectFit accepts everything on a single display and for bundled seeds`() {
        val laptop = DisplayProfile(0, "Laptop", 2560, 1600, densityDpi = 160, isPrimary = true)
        val portrait = DisplayProfile(1, "Portrait", 1200, 1920, densityDpi = 160, isPrimary = false)
        val edge = withSubject(sampleRecord("edge", BrightnessClass.DARK), focalX = 0.02)
        val seed = edge.copy(provenance = edge.provenance.copy(sourceId = "seed_reserve"))

        assertEquals(true, SubjectFit.forDisplays(listOf(laptop), UserPreferences()).accepts(edge))
        assertEquals(true, SubjectFit.forDisplays(listOf(laptop, portrait), UserPreferences()).accepts(seed))
    }
}
