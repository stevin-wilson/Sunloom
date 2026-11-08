package com.sunloom.app.data.repository

import com.sunloom.app.domain.model.SourceIds
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.sunloom.app.domain.evaluator.ImageEvaluator
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.RejectionRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.SafetyVerdict
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.model.WallpaperStatus
import com.sunloom.app.domain.safety.GateTrace
import com.sunloom.app.domain.safety.SafetyGate
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * User-local import pipeline (Article VI §10): `user_local` imports keep original bytes and run
 * L3–L5 safety validation (L1/L2 metadata N/A). Extracted from [LocalLibraryRepository], which
 * exposes the public API and delegates here.
 */
internal class UserImportPipeline(
    private val repository: LocalLibraryRepository,
    private val appContext: Context,
    private val wallpaperDir: File,
    private val quarantineDir: File,
    private val imageEvaluator: ImageEvaluator,
    private val safetyGate: SafetyGate
) {
    fun importUserLocalBitmap(
        title: String,
        bitmap: Bitmap,
        display: DisplayProfile,
        asBaselineRestore: Boolean = false
    ): WallpaperRecord {
        val id = if (asBaselineRestore) "baseline-restore-original" else "local-${System.currentTimeMillis()}"
        val srcW = bitmap.width
        val srcH = bitmap.height

        val exceedsBounds = srcW <= 0 || srcH <= 0 ||
            srcW > SafetyGate.MAX_DIMENSION_PX ||
            srcH > SafetyGate.MAX_DIMENSION_PX ||
            srcW.toLong() * srcH.toLong() > SafetyGate.MAX_TOTAL_PIXELS

        val stageFile = File(quarantineDir, "stage_${id}_${System.nanoTime()}.png")
        try {
            if (exceedsBounds) {
                val reason = "Image dimensions (${srcW}x${srcH}) exceed safe decode bounds."
                repository.recordRejection(
                    RejectionRecord(
                        assetId = id,
                        sourceId = SourceIds.USER_LOCAL,
                        reasonCode = "IMAGE_DIMENSIONS_OUT_OF_BOUNDS",
                        explanation = reason,
                        timestampEpochMs = System.currentTimeMillis(),
                        isPermanent = false
                    )
                )
                return createRejectedUserImportRecord(id, title, srcW, srcH, display, reason)
            }

            FileOutputStream(stageFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val bytes = stageFile.readBytes()
            val sha256 = SafetyGate.sha256Hex(bytes)
            val gridW = 32
            val gridH = 20
            val rgbGrid = sampleBitmapToRgbGrid(bitmap, gridW, gridH)
            val evalGrid = sampleBitmapToEvaluationGrid(bitmap, gridW, gridH)
            val dHash = SafetyGate.computePerceptualDHashFromPixels(rgbGrid, gridW, gridH)

            if (!asBaselineRestore) {
                val blockedShas = repository.getPermanentBlockedSha256s()
                val blockedPhashes = repository.getPermanentBlockedPerceptualHashes()
                if (sha256 in blockedShas ||
                    (dHash.isNotBlank() && blockedPhashes.any { SafetyGate.isNearDuplicateDHash(dHash, it) })
                ) {
                    val reason = "Imported image matches a permanently rejected wallpaper in your blocklist."
                    repository.recordRejection(
                        RejectionRecord(
                            assetId = id,
                            sourceId = SourceIds.USER_LOCAL,
                            reasonCode = "BLOCKLISTED_USER_IMPORT",
                            explanation = reason,
                            timestampEpochMs = System.currentTimeMillis(),
                            isPermanent = true
                        )
                    )
                    return createRejectedUserImportRecord(id, title, srcW, srcH, display, reason, sha256, dHash)
                }
            }

            val file = File(wallpaperDir, "$id.png")
            FileOutputStream(file).use { it.write(bytes) }

            // Phase 1.2: Pass REAL bitmap.width and bitmap.height (never maxOf(bitmap.width, display.widthPx))
            val eval = imageEvaluator.evaluateRgbGrid(
                imageWidth = srcW,
                imageHeight = srcH,
                rgbGrid = evalGrid,
                gridWidth = gridW,
                gridHeight = gridH,
                display = display,
                allowBlurredFrameFill = true
            )

            // Even small user bitmaps (e.g. 64x40 in baseline/local tests) are permitted for USER_LOCAL
            // via blurred frame fill when explicitly selected by the user.
            val userEval = if (!eval.isEligible) {
                eval.copy(
                    isEligible = true,
                    cropRect = eval.cropRect ?: com.sunloom.app.domain.model.CropRect(
                        left = 0,
                        top = 0,
                        width = srcW,
                        height = srcH,
                        focalX = 0.5,
                        focalY = 0.5,
                        usesBlurredFrameFill = true,
                        pixelCoverageRatio = minOf(
                            srcW.toDouble() / display.widthPx.coerceAtLeast(1),
                            srcH.toDouble() / display.heightPx.coerceAtLeast(1)
                        )
                    )
                )
            } else {
                eval
            }

            val record = WallpaperRecord(
                id = id,
                title = title,
                category = SafeCategory.USER_LOCAL,
                localFilePath = file.absolutePath,
                croppedFilePath = file.absolutePath,
                fileSizeBytes = file.length(),
                isBaselineRestore = asBaselineRestore,
                provenance = ProvenanceRecord(
                    sourceId = SourceIds.USER_LOCAL,
                    sourceName = "User Local File",
                    assetId = id,
                    originalPageUrl = "",
                    creatorName = "User-provided",
                    creatorPageUrl = "",
                    licenseType = LicenseType.USER_OWNED,
                    attributionText = "User-provided — not verified by Sunloom's automated license gate ($title)",
                    retrievedAtEpochMs = System.currentTimeMillis(),
                    originalWidth = srcW,
                    originalHeight = srcH,
                    renditionWidth = display.widthPx,
                    renditionHeight = display.heightPx,
                    safetyClassification = SafetyClassification.SAFE,
                    safetyClassificationSource = "Direct User Selection",
                    sha256Checksum = sha256,
                    perceptualHash = dHash,
                    safetyGateVersion = SafetyGate.COMPOSITE_GATE_VERSION,
                    gateLayerOutcomes = GateTrace.userLocalPass()
                ),
                evaluation = userEval,
                status = WallpaperStatus.PREPARED,
                decisionExplanation = if (asBaselineRestore) {
                    "Saved as your permanent 'Home Base' Original Wallpaper Restore Point (Article II.2)."
                } else {
                    "Imported from local storage (${srcW}×${srcH}). ${userEval.explanation}"
                }
            )
            repository.upsertWallpaper(record)
            if (asBaselineRestore) {
                repository.savePreferences(repository.loadPreferences().copy(baselineRestorePath = file.absolutePath))
            }
            return record
        } finally {
            runCatching { stageFile.delete() }
        }
    }

    private fun createRejectedUserImportRecord(
        id: String,
        title: String,
        srcW: Int,
        srcH: Int,
        display: DisplayProfile,
        reason: String,
        sha256: String = "",
        dHash: String = ""
    ): WallpaperRecord {
        return WallpaperRecord(
            id = id,
            title = title,
            category = SafeCategory.USER_LOCAL,
            localFilePath = "",
            croppedFilePath = "",
            fileSizeBytes = 0L,
            isRejected = true,
            provenance = ProvenanceRecord(
                sourceId = SourceIds.USER_LOCAL,
                sourceName = "User Local File",
                assetId = id,
                originalPageUrl = "",
                creatorName = "User-provided",
                creatorPageUrl = "",
                licenseType = LicenseType.USER_OWNED,
                attributionText = "User-provided — not verified by Sunloom's automated license gate",
                retrievedAtEpochMs = System.currentTimeMillis(),
                originalWidth = srcW,
                originalHeight = srcH,
                renditionWidth = display.widthPx,
                renditionHeight = display.heightPx,
                safetyClassification = SafetyClassification.UNCERTAIN,
                safetyClassificationSource = "Direct User Selection",
                sha256Checksum = sha256,
                perceptualHash = dHash,
                safetyGateVersion = SafetyGate.COMPOSITE_GATE_VERSION,
                gateLayerOutcomes = GateTrace.failedAt("L3", "REJECTED_USER_IMPORT", isUserLocal = true)
            ),
            evaluation = com.sunloom.app.domain.model.ImageEvaluationResult(
                isEligible = false,
                rejectionReasonCode = "REJECTED_USER_IMPORT",
                globalLuminance = 0.0,
                desktopZoneLuminance = 0.0,
                lockClockZoneLuminance = 0.0,
                glareFractionInSafeZones = 0.0,
                contrastStdDev = 0.0,
                brightnessClass = com.sunloom.app.domain.model.BrightnessClass.NEUTRAL,
                cropRect = null,
                explanation = reason
            ),
            status = WallpaperStatus.REJECTED_SAFETY_OR_QUALITY,
            decisionExplanation = reason
        )
    }

    /**
     * Article VI §10 & IV §2: Preserves the user's original JPEG/PNG/WebP bytes and full header
     * resolution (no 2048 px downsample or PNG re-encode) so 4K/5K monitors achieve native sharpness.
     */
    fun importUserUri(uri: Uri, display: DisplayProfile, asBaselineRestore: Boolean = false): WallpaperRecord? {
        val id = if (asBaselineRestore) "baseline-restore-original" else "local-${System.currentTimeMillis()}"
        val title = if (asBaselineRestore) {
            "Original Baseline Wallpaper"
        } else {
            "Local Wallpaper (${uri.lastPathSegment ?: "Photo"})"
        }
        val stageFile = File(quarantineDir, "uri_stage_${System.nanoTime()}.tmp")
        try {
            val bytes = runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { stream ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > SafetyGate.MAX_PAYLOAD_BYTES) {
                            throw IllegalArgumentException("File exceeds maximum payload size.")
                        }
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                }
            }.getOrNull() ?: return null

            FileOutputStream(stageFile).use { it.write(bytes) }

            val bounds = SafetyGate.inspectImageHeaderBounds(bytes) ?: return null
            val candidate = CandidateAsset(
                id = id,
                title = title,
                description = "",
                category = SafeCategory.USER_LOCAL,
                rawCategoriesAndTags = emptyList(),
                downloadUrl = "",
                isUserLocal = true,
                provenance = ProvenanceRecord(
                    sourceId = SourceIds.USER_LOCAL,
                    sourceName = "User Local File",
                    assetId = id,
                    originalPageUrl = "",
                    creatorName = "User-provided",
                    creatorPageUrl = "",
                    licenseType = LicenseType.USER_OWNED,
                    attributionText = "User-provided — not verified by Sunloom's automated license gate ($title)",
                    retrievedAtEpochMs = System.currentTimeMillis(),
                    originalWidth = bounds.width,
                    originalHeight = bounds.height,
                    renditionWidth = display.widthPx,
                    renditionHeight = display.heightPx,
                    safetyClassification = SafetyClassification.SAFE,
                    safetyClassificationSource = "Direct User Selection",
                    sha256Checksum = ""
                )
            )

            val payloadVerdict = safetyGate.evaluatePayload(
                candidate = candidate,
                bytes = bytes,
                blockedAssetIds = if (asBaselineRestore) emptySet() else repository.getPermanentBlockedAssetIds(),
                blockedPerceptualHashes = if (asBaselineRestore) emptySet() else repository.getPermanentBlockedPerceptualHashes(),
                blockedSha256Checksums = if (asBaselineRestore) emptySet() else repository.getPermanentBlockedSha256s(),
                headerBounds = bounds
            )
            if (payloadVerdict !is SafetyVerdict.Approved) {
                if (payloadVerdict is SafetyVerdict.Rejected) {
                    repository.recordRejection(
                        RejectionRecord(
                            assetId = id,
                            sourceId = SourceIds.USER_LOCAL,
                            reasonCode = payloadVerdict.reasonCode,
                            explanation = payloadVerdict.explanation,
                            timestampEpochMs = System.currentTimeMillis(),
                            isPermanent = payloadVerdict.reasonCode.startsWith("BLOCKLISTED")
                        )
                    )
                }
                return null
            }

            val exifOrientation = readExifOrientation(stageFile)
            val (origW, origH) = orientedDimensions(bounds.width, bounds.height, exifOrientation)

            val sampled = decodeSampledBitmapSafely(bytes, bounds.width, bounds.height, maxEdgePx = 512) ?: return null
            val orientedSample = applyExifOrientationIfNeeded(stageFile, sampled)
            if (orientedSample != sampled) sampled.recycle()

            val gridW = 32
            val gridH = 20
            val rgbGrid = sampleBitmapToRgbGrid(orientedSample, gridW, gridH)
            val evalGrid = sampleBitmapToEvaluationGrid(orientedSample, gridW, gridH)
            orientedSample.recycle()

            val dHash = SafetyGate.computePerceptualDHashFromPixels(rgbGrid, gridW, gridH)
            if (!asBaselineRestore && dHash.isNotBlank()) {
                val blockedPhashes = repository.getPermanentBlockedPerceptualHashes()
                if (blockedPhashes.any { SafetyGate.isNearDuplicateDHash(dHash, it) }) {
                    repository.recordRejection(
                        RejectionRecord(
                            assetId = id,
                            sourceId = SourceIds.USER_LOCAL,
                            reasonCode = "BLOCKLISTED_USER_IMPORT",
                            explanation = "Imported image matches a permanently rejected wallpaper in your blocklist.",
                            timestampEpochMs = System.currentTimeMillis(),
                            isPermanent = true
                        )
                    )
                    return null
                }
            }

            val eval = imageEvaluator.evaluateRgbGrid(
                imageWidth = origW,
                imageHeight = origH,
                rgbGrid = evalGrid,
                gridWidth = gridW,
                gridHeight = gridH,
                display = display,
                allowBlurredFrameFill = true
            )
            val userEval = if (!eval.isEligible) {
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

            val ext = payloadVerdict.detectedFormat.fileExtension
            val outFile = File(wallpaperDir, "$id.$ext")
            FileOutputStream(outFile).use { it.write(bytes) }

            val rendW = userEval.cropRect?.let { if (it.usesBlurredFrameFill) display.widthPx else it.width } ?: display.widthPx
            val rendH = userEval.cropRect?.let { if (it.usesBlurredFrameFill) display.heightPx else it.height } ?: display.heightPx

            val record = WallpaperRecord(
                id = id,
                title = title,
                category = SafeCategory.USER_LOCAL,
                localFilePath = outFile.absolutePath,
                croppedFilePath = outFile.absolutePath,
                fileSizeBytes = outFile.length(),
                isBaselineRestore = asBaselineRestore,
                provenance = candidate.provenance.copy(
                    originalWidth = origW,
                    originalHeight = origH,
                    renditionWidth = rendW,
                    renditionHeight = rendH,
                    sha256Checksum = payloadVerdict.sha256Checksum,
                    perceptualHash = dHash,
                    safetyGateVersion = SafetyGate.COMPOSITE_GATE_VERSION,
                    gateLayerOutcomes = GateTrace.userLocalPass()
                ),
                evaluation = userEval,
                status = WallpaperStatus.PREPARED,
                decisionExplanation = if (asBaselineRestore) {
                    "Saved as your permanent 'Home Base' Original Wallpaper Restore Point (Article II.2)."
                } else {
                    "Imported from local storage (${origW}×${origH}). ${userEval.explanation}"
                }
            )
            repository.upsertWallpaper(record)
            if (asBaselineRestore) {
                repository.savePreferences(repository.loadPreferences().copy(baselineRestorePath = outFile.absolutePath))
            }
            return record
        } finally {
            runCatching { stageFile.delete() }
        }
    }
}
