package com.sunloom.app.ui

import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.ImageEvaluationResult
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.WallpaperRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Rows restored from backup or evicted by the cache manager have no image file. They must not count
 * towards the reserve, otherwise the "running low, fetch more" trigger never fires (Article X §3).
 */
class ReserveCountsTest {

    private fun row(
        id: String,
        brightness: BrightnessClass,
        sizeBytes: Long,
        sourceId: String = "wikimedia_featured",
        applied: Int = 0
    ) = WallpaperRecord(
        id = id,
        title = id,
        category = SafeCategory.LANDSCAPES_NATURE,
        localFilePath = if (sizeBytes > 0) "/tmp/$id.jpg" else "",
        croppedFilePath = "",
        fileSizeBytes = sizeBytes,
        appliedCount = applied,
        provenance = ProvenanceRecord(
            sourceId = sourceId, sourceName = sourceId, assetId = id,
            originalPageUrl = "https://example.org", creatorName = "c", creatorPageUrl = "",
            licenseType = LicenseType.CC0, attributionText = "CC0", retrievedAtEpochMs = 1L,
            originalWidth = 3840, originalHeight = 2400, renditionWidth = 2560, renditionHeight = 1600,
            safetyClassification = SafetyClassification.SAFE, safetyClassificationSource = "t", sha256Checksum = "x"
        ),
        evaluation = ImageEvaluationResult(
            isEligible = true, rejectionReasonCode = null, globalLuminance = 0.5,
            desktopZoneLuminance = 0.5, lockClockZoneLuminance = 0.5, glareFractionInSafeZones = 0.0,
            contrastStdDev = 0.1, brightnessClass = brightness,
            cropRect = CropRect(0, 0, 2560, 1600, 0.5, 0.5, false, 1.0), explanation = "t"
        )
    )

    @Test
    fun `unseen count ignores rows whose image file is gone`() {
        val state = SunloomUiState(
            targetBrightnessNow = BrightnessClass.LIGHT,
            libraryWallpapers = listOf(
                row("ghost-1", BrightnessClass.LIGHT, sizeBytes = 0),
                row("ghost-2", BrightnessClass.NEUTRAL, sizeBytes = 0),
                row("real-1", BrightnessClass.LIGHT, sizeBytes = 4096),
                row("seen", BrightnessClass.LIGHT, sizeBytes = 4096, applied = 2),
                row("seed", BrightnessClass.LIGHT, sizeBytes = 4096, sourceId = "seed_reserve")
            )
        )
        assertEquals(1, state.unseenCurrentBrightnessCount)
    }

    @Test
    fun `light and dark reserve counts only include rows with an image`() {
        val state = SunloomUiState(
            libraryWallpapers = listOf(
                row("l-ghost", BrightnessClass.LIGHT, sizeBytes = 0),
                row("l-ok", BrightnessClass.LIGHT, sizeBytes = 10),
                row("d-ghost", BrightnessClass.DARK, sizeBytes = 0),
                row("d-ok", BrightnessClass.DARK, sizeBytes = 10)
            )
        )
        assertEquals(1, state.lightReserveCount)
        assertEquals(1, state.darkReserveCount)
    }
}
