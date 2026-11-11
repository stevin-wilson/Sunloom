package com.sunloom.app.data

import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.ImageEvaluationResult
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.WallpaperRecord

/** A minimal valid library row for storage tests; override only what the test is about. */
fun testWallpaper(
    id: String,
    localFilePath: String = "",
    croppedFilePath: String = "",
    fileSizeBytes: Long = 0L,
    sourceId: String = "wikimedia_featured",
    isFavorite: Boolean = false,
    isBaselineRestore: Boolean = false,
    isRejected: Boolean = false,
    retrievedAt: Long = 1_000L,
    lastApplied: Long = 0L,
    brightness: BrightnessClass = BrightnessClass.LIGHT
) = WallpaperRecord(
    id = id,
    title = "Wallpaper $id",
    category = SafeCategory.LANDSCAPES_NATURE,
    localFilePath = localFilePath,
    croppedFilePath = croppedFilePath,
    fileSizeBytes = fileSizeBytes,
    isFavorite = isFavorite,
    isBaselineRestore = isBaselineRestore,
    isRejected = isRejected,
    lastAppliedEpochMs = lastApplied,
    provenance = ProvenanceRecord(
        sourceId = sourceId,
        sourceName = sourceId,
        assetId = id,
        originalPageUrl = "https://commons.wikimedia.org/wiki/$id",
        creatorName = "Author $id",
        creatorPageUrl = "",
        licenseType = LicenseType.CC0,
        attributionText = "CC0",
        retrievedAtEpochMs = retrievedAt,
        originalWidth = 3840,
        originalHeight = 2400,
        renditionWidth = 2560,
        renditionHeight = 1600,
        safetyClassification = SafetyClassification.SAFE,
        safetyClassificationSource = "Verified",
        sha256Checksum = "sha-$id"
    ),
    evaluation = ImageEvaluationResult(
        isEligible = true,
        rejectionReasonCode = null,
        globalLuminance = 0.75,
        desktopZoneLuminance = 0.25,
        lockClockZoneLuminance = 0.25,
        glareFractionInSafeZones = 0.0,
        contrastStdDev = 0.10,
        brightnessClass = brightness,
        cropRect = CropRect(0, 0, 2560, 1600, 0.5, 0.5, false, 1.0),
        explanation = "OK"
    )
)
