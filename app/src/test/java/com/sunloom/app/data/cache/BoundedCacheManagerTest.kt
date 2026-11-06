package com.sunloom.app.data.cache

import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.ImageEvaluationResult
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.WallpaperRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedCacheManagerTest {

    private val cacheManager = BoundedCacheManager()

    private fun record(
        id: String,
        sizeMb: Int,
        brightness: BrightnessClass = BrightnessClass.LIGHT,
        isFavorite: Boolean = false,
        isBaselineRestore: Boolean = false,
        retrievedAt: Long = 1000L
    ): WallpaperRecord {
        return WallpaperRecord(
            id = id,
            title = "Wallpaper $id",
            category = SafeCategory.LANDSCAPES_NATURE,
            localFilePath = "/tmp/$id.jpg",
            croppedFilePath = "/tmp/${id}_crop.jpg",
            fileSizeBytes = sizeMb.toLong() * 1024L * 1024L,
            isFavorite = isFavorite,
            isBaselineRestore = isBaselineRestore,
            provenance = ProvenanceRecord(
                sourceId = "wikimedia_featured",
                sourceName = "Wikimedia Commons",
                assetId = id,
                originalPageUrl = "https://commons.wikimedia.org/wiki/$id",
                creatorName = "Author $id",
                creatorPageUrl = "https://commons.wikimedia.org",
                licenseType = LicenseType.CC0,
                attributionText = "CC0",
                retrievedAtEpochMs = retrievedAt,
                originalWidth = 3840,
                originalHeight = 2400,
                renditionWidth = 2560,
                renditionHeight = 1600,
                safetyClassification = SafetyClassification.SAFE,
                safetyClassificationSource = "Verified",
                sha256Checksum = "123456"
            ),
            evaluation = ImageEvaluationResult(
                isEligible = true,
                rejectionReasonCode = null,
                globalLuminance = if (brightness == BrightnessClass.DARK) 0.15 else 0.75,
                desktopZoneLuminance = 0.25,
                lockClockZoneLuminance = 0.25,
                glareFractionInSafeZones = 0.0,
                contrastStdDev = 0.10,
                brightnessClass = brightness,
                cropRect = CropRect(0, 0, 2560, 1600, 0.5, 0.5, false, 1.0),
                explanation = "OK"
            )
        )
    }

    @Test
    fun `eviction keeps one light and one dark seed per category beyond the global top three`() {
        fun seed(id: String, category: SafeCategory, brightness: BrightnessClass, retrievedAt: Long): WallpaperRecord {
            val base = record(id, sizeMb = 1, brightness = brightness, retrievedAt = retrievedAt)
            return base.copy(
                category = category,
                provenance = base.provenance.copy(sourceId = "seed_reserve")
            )
        }
        val records = buildList {
            // Newer landscape seeds fill the global top-3 slots...
            repeat(3) { add(seed("land-l$it", SafeCategory.LANDSCAPES_NATURE, BrightnessClass.LIGHT, 9_000L + it)) }
            repeat(3) { add(seed("land-d$it", SafeCategory.LANDSCAPES_NATURE, BrightnessClass.DARK, 9_000L + it)) }
            // ...so these older botanical seeds would otherwise be evictable.
            add(seed("bot-l", SafeCategory.BOTANICAL_FLORA, BrightnessClass.LIGHT, 1_000L))
            add(seed("bot-d", SafeCategory.BOTANICAL_FLORA, BrightnessClass.DARK, 1_000L))
        }
        val report = cacheManager.selectEvictions(records, activeWallpaperIds = emptySet(), maxCacheBytes = 1L)
        assertTrue("bot-l" in report.protectedIds)
        assertTrue("bot-d" in report.protectedIds)
        assertFalse("bot-l" in report.evictedIds)
        assertFalse("bot-d" in report.evictedIds)
    }

    @Test
    fun `selectEvictions never deletes active wallpaper, favorites, baseline restore, or minimum light and dark reserve`() {
        val records = listOf(
            record("active-wall", sizeMb = 10, brightness = BrightnessClass.LIGHT, retrievedAt = 100L),
            record("favorite-wall", sizeMb = 10, brightness = BrightnessClass.LIGHT, isFavorite = true, retrievedAt = 200L),
            record("restore-wall", sizeMb = 10, brightness = BrightnessClass.DARK, isBaselineRestore = true, retrievedAt = 300L),
            record("reserve-light-1", sizeMb = 10, brightness = BrightnessClass.LIGHT, retrievedAt = 900L),
            record("reserve-light-2", sizeMb = 10, brightness = BrightnessClass.LIGHT, retrievedAt = 910L),
            record("reserve-light-3", sizeMb = 10, brightness = BrightnessClass.LIGHT, retrievedAt = 920L),
            record("reserve-dark-1", sizeMb = 10, brightness = BrightnessClass.DARK, retrievedAt = 930L),
            record("reserve-dark-2", sizeMb = 10, brightness = BrightnessClass.DARK, retrievedAt = 940L),
            record("reserve-dark-3", sizeMb = 10, brightness = BrightnessClass.DARK, retrievedAt = 950L),
            record("old-evictable-1", sizeMb = 30, brightness = BrightnessClass.LIGHT, retrievedAt = 400L),
            record("old-evictable-2", sizeMb = 30, brightness = BrightnessClass.DARK, retrievedAt = 500L)
        )

        // Cap at 95 MB so both 30 MB evictable entries must be removed (total goes from 150 MB -> 90 MB)
        val report = cacheManager.selectEvictions(
            records = records,
            activeWallpaperIds = setOf("active-wall"),
            maxCacheBytes = 95L * 1024L * 1024L
        )

        assertEquals(listOf("old-evictable-1", "old-evictable-2"), report.evictedIds)
        assertFalse(report.evictedIds.contains("active-wall"))
        assertFalse(report.evictedIds.contains("favorite-wall"))
        assertFalse(report.evictedIds.contains("restore-wall"))
        assertFalse(report.evictedIds.contains("reserve-light-1"))
        assertFalse(report.evictedIds.contains("reserve-light-2"))
        assertFalse(report.evictedIds.contains("reserve-light-3"))
        assertFalse(report.evictedIds.contains("reserve-dark-1"))
        assertFalse(report.evictedIds.contains("reserve-dark-2"))
        assertFalse(report.evictedIds.contains("reserve-dark-3"))
        assertTrue(report.bytesAfter <= 95L * 1024L * 1024L)
    }

    @Test
    fun `testProtectsThreeLightAndThreeDarkWallpapers even under aggressive cache pressure`() {
        val records = listOf(
            record("light-1", sizeMb = 15, brightness = BrightnessClass.LIGHT, retrievedAt = 100L),
            record("light-2", sizeMb = 15, brightness = BrightnessClass.LIGHT, retrievedAt = 200L),
            record("light-3", sizeMb = 15, brightness = BrightnessClass.LIGHT, retrievedAt = 300L),
            record("dark-1", sizeMb = 15, brightness = BrightnessClass.DARK, retrievedAt = 400L),
            record("dark-2", sizeMb = 15, brightness = BrightnessClass.DARK, retrievedAt = 500L),
            record("dark-3", sizeMb = 15, brightness = BrightnessClass.DARK, retrievedAt = 600L)
        )

        val report = cacheManager.selectEvictions(
            records = records,
            activeWallpaperIds = emptySet(),
            maxCacheBytes = 10L * 1024L * 1024L
        )

        assertEquals(emptyList<String>(), report.evictedIds)
        assertEquals(6, report.protectedIds.size)
    }
}
