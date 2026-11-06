package com.sunloom.app.platform.export

import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.ImageEvaluationResult
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.WallpaperRecord

internal fun exportTestRecord(
    path: String,
    license: LicenseType = LicenseType.CC_BY_SA,
    creator: String = "Ada Photographer",
    title: String = "Alpine Lake: Dawn/Mist",
    sourceId: String = "wikimedia_featured",
    eligible: Boolean = true,
    rejected: Boolean = false
) = WallpaperRecord(
    id = "rec1",
    title = title,
    category = SafeCategory.LANDSCAPES_NATURE,
    localFilePath = path,
    croppedFilePath = path,
    isRejected = rejected,
    provenance = ProvenanceRecord(
        sourceId = sourceId,
        sourceName = "Wikimedia Commons",
        assetId = "File:Alpine.jpg",
        originalPageUrl = "https://commons.wikimedia.org/wiki/File:Alpine.jpg",
        creatorName = creator,
        creatorPageUrl = "",
        licenseType = license,
        attributionText = "",
        retrievedAtEpochMs = 1L,
        originalWidth = 4000,
        originalHeight = 2500,
        renditionWidth = 2560,
        renditionHeight = 1600,
        safetyClassification = SafetyClassification.SAFE,
        safetyClassificationSource = "test",
        sha256Checksum = "abc",
        licenseVersion = "4.0"
    ),
    evaluation = ImageEvaluationResult(
        isEligible = eligible,
        rejectionReasonCode = null,
        globalLuminance = 0.5,
        desktopZoneLuminance = 0.5,
        lockClockZoneLuminance = 0.5,
        glareFractionInSafeZones = 0.0,
        contrastStdDev = 0.2,
        brightnessClass = BrightnessClass.LIGHT,
        cropRect = null,
        explanation = "ok"
    )
)
