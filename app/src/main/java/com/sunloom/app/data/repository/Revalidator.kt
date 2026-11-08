package com.sunloom.app.data.repository

import com.sunloom.app.data.sources.SourceRegistry
import com.sunloom.app.domain.evaluator.ImageEvaluator
import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.RejectionRecord
import com.sunloom.app.domain.model.SafetyVerdict
import com.sunloom.app.domain.model.SourceIds
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.model.WallpaperStatus
import com.sunloom.app.domain.safety.GateTrace
import com.sunloom.app.domain.safety.ImageHeaderBounds
import com.sunloom.app.domain.safety.SafetyGate
import java.io.File

/** Recording what was refused and why; shared by everything that can refuse a picture. */
internal interface RejectionLog {
    fun recordRejection(rejection: RejectionRecord)

    fun blocklist(
        assetId: String?,
        sha256: String?,
        perceptualHash: String?,
        sourceId: String,
        reasonCode: String,
        timestampEpochMs: Long = System.currentTimeMillis()
    )
}

/** What the revalidator may do to the library; it never reaches into the repository itself. */
internal interface RevalidationHost : RejectionLog {
    fun upsert(record: WallpaperRecord)

    /** Clears the active wallpaper id if [wallpaperId] is the active one, without claiming a replacement was applied. */
    fun invalidateActive(wallpaperId: String)
}

/**
 * Article VII §§5–6 and Schedule B: when the gate version changes, every stored picture is re-judged from its stored
 * metadata and its local bytes (L1–L4). What fails is evicted; when the picture itself is at fault it is also refused for
 * good. A picture that could not be judged for a reason that is not its fault (a missing file) is evicted without a ban,
 * and one that only ran the decoder out of memory is left to be checked again.
 */
internal class Revalidator(
    private val safetyGate: SafetyGate,
    private val imageEvaluator: ImageEvaluator,
    private val host: RevalidationHost
) {

    /** The id, checksum and perceptual-hash blocklists, extended as pictures are refused during the pass. */
    class Blocked(val ids: MutableSet<String>, val perceptualHashes: MutableSet<String>, val sha256s: MutableSet<String>)

    /** The safe picture's decoded samples, ready for the duplicate check and the evaluator. */
    private class Sampled(
        val approved: SafetyVerdict.Approved,
        val headerBounds: ImageHeaderBounds?,
        val dHash: String,
        val evalGrid: IntArray
    )

    private sealed interface Sampling {
        class Ok(val sampled: Sampled) : Sampling

        /** The decoder ran out of memory: says nothing about the picture, so leave it for the next pass. */
        data object LeaveForLater : Sampling
        data object Evicted : Sampling
    }

    /** Re-judges every record not on the current gate version and returns how many were evicted. */
    fun run(
        records: List<WallpaperRecord>,
        display: DisplayProfile?,
        preferences: UserPreferences,
        blocked: Blocked
    ): Int {
        return records
            .filter { !it.isRejected && !it.isBaselineRestore }
            .filter { it.provenance.safetyGateVersion != SafetyGate.COMPOSITE_GATE_VERSION }
            .count { revalidate(it, display, preferences, blocked) }
    }

    /** True when [record] was evicted. */
    private fun revalidate(
        record: WallpaperRecord,
        display: DisplayProfile?,
        preferences: UserPreferences,
        blocked: Blocked
    ): Boolean {
        val isUserLocal = record.provenance.sourceId == SourceIds.USER_LOCAL
        val candidate = syntheticCandidate(record, isUserLocal)

        if (!passesMetadata(record, candidate, isUserLocal, blocked)) return true

        val bytes = readPayload(record)
        if (bytes == null) {
            evict(
                record = record,
                failedLayer = "L3",
                reasonCode = "MISSING_LOCAL_PAYLOAD",
                explanation = "Cached wallpaper file is missing or unreadable on disk.",
                isUserLocal = isUserLocal,
                addToPermanentBlocklist = false
            )
            return true
        }

        val headerBounds = SafetyGate.inspectImageHeaderBounds(bytes)
        val approved = approvedPayload(record, candidate, bytes, headerBounds, isUserLocal, blocked) ?: return true

        val sampled = when (val sampling = sample(record, bytes, headerBounds, approved, isUserLocal, blocked)) {
            Sampling.LeaveForLater -> return false
            Sampling.Evicted -> return true
            is Sampling.Ok -> sampling.sampled
        }
        if (isBlocklistedDuplicate(record, sampled, isUserLocal, blocked)) return true

        if (display != null) {
            reevaluate(record, display, preferences, sampled, isUserLocal)
        } else {
            keepWhatWasLearned(record, sampled)
        }
        return false
    }

    private fun syntheticCandidate(record: WallpaperRecord, isUserLocal: Boolean): CandidateAsset {
        val effectiveTags = record.rawCategoriesAndTags.ifEmpty {
            listOf(record.category.displayName.lowercase())
        }
        val admittedHost = SourceRegistry.get(record.provenance.sourceId)?.allowedHosts?.firstOrNull()
        return CandidateAsset(
            id = record.id,
            title = record.title,
            description = record.description,
            category = record.category,
            rawCategoriesAndTags = effectiveTags,
            downloadUrl = when {
                isUserLocal || record.provenance.sourceId == SourceIds.SEED_RESERVE -> ""
                record.provenance.originalPageUrl.startsWith("https://") -> record.provenance.originalPageUrl
                !admittedHost.isNullOrBlank() -> "https://$admittedHost/${record.id}"
                else -> ""
            },
            isUserLocal = isUserLocal,
            provenance = record.provenance
        )
    }

    /** L1–L2 from stored metadata; false means it was evicted. */
    private fun passesMetadata(
        record: WallpaperRecord,
        candidate: CandidateAsset,
        isUserLocal: Boolean,
        blocked: Blocked
    ): Boolean {
        val verdict = safetyGate.evaluateMetadata(
            candidate = candidate,
            blockedAssetIds = blocked.ids,
            blockedPerceptualHashes = blocked.perceptualHashes,
            blockedSha256Checksums = blocked.sha256s
        )
        if (verdict !is SafetyVerdict.Rejected) return true
        evict(
            record = record,
            failedLayer = verdict.failedLayer,
            reasonCode = verdict.reasonCode,
            explanation = verdict.explanation,
            isUserLocal = isUserLocal
        )
        blocked.ids.add(record.id)
        return false
    }

    private fun readPayload(record: WallpaperRecord): ByteArray? {
        val localFile = record.localFilePath.takeIf { it.isNotBlank() }?.let(::File)
        return if (localFile != null && localFile.exists() && localFile.isFile) {
            runCatching { localFile.readBytes() }.getOrNull()
        } else {
            null
        }
    }

    /** L3 on the local bytes; null means it was evicted. */
    private fun approvedPayload(
        record: WallpaperRecord,
        candidate: CandidateAsset,
        bytes: ByteArray,
        headerBounds: ImageHeaderBounds?,
        isUserLocal: Boolean,
        blocked: Blocked
    ): SafetyVerdict.Approved? {
        val verdict = safetyGate.evaluatePayload(
            candidate = candidate,
            bytes = bytes,
            blockedAssetIds = blocked.ids,
            blockedPerceptualHashes = blocked.perceptualHashes,
            blockedSha256Checksums = blocked.sha256s,
            headerBounds = headerBounds
        )
        if (verdict is SafetyVerdict.Rejected) {
            val computedSha = SafetyGate.sha256Hex(bytes)
            evict(
                record = record,
                failedLayer = verdict.failedLayer,
                reasonCode = verdict.reasonCode,
                explanation = verdict.explanation,
                isUserLocal = isUserLocal,
                sha256Override = computedSha
            )
            blocked.ids.add(record.id)
            blocked.sha256s.add(computedSha)
            return null
        }
        return verdict as SafetyVerdict.Approved
    }

    private fun sample(
        record: WallpaperRecord,
        bytes: ByteArray,
        headerBounds: ImageHeaderBounds?,
        approved: SafetyVerdict.Approved,
        isUserLocal: Boolean,
        blocked: Blocked
    ): Sampling {
        val outcome = decodeSampledOutcome(
            bytes = bytes,
            headerW = headerBounds?.width ?: 0,
            headerH = headerBounds?.height ?: 0,
            maxEdgePx = 512
        )
        // Out of memory says nothing about the picture: leave it cached and check it again next time.
        if (outcome is DecodeOutcome.OutOfMemory) return Sampling.LeaveForLater
        val decoded = (outcome as? DecodeOutcome.Decoded)?.bitmap
        if (decoded == null) {
            evict(
                record = record,
                failedLayer = "L3",
                reasonCode = "CORRUPT_BITMAP",
                explanation = "BitmapFactory could not safely decode cached payload during re-validation.",
                isUserLocal = isUserLocal,
                sha256Override = approved.sha256Checksum
            )
            blocked.ids.add(record.id)
            blocked.sha256s.add(approved.sha256Checksum)
            return Sampling.Evicted
        }
        try {
            val rgbGrid = sampleBitmapToRgbGrid(decoded, GRID_W, GRID_H)
            val evalGrid = sampleBitmapToEvaluationGrid(decoded, GRID_W, GRID_H)
            val dHash = SafetyGate.computePerceptualDHashFromPixels(rgbGrid, GRID_W, GRID_H)
            return Sampling.Ok(Sampled(approved, headerBounds, dHash, evalGrid))
        } finally {
            decoded.recycle()
        }
    }

    /** L4: the picture matches a perceptually blocklisted one; true means it was evicted. */
    private fun isBlocklistedDuplicate(
        record: WallpaperRecord,
        sampled: Sampled,
        isUserLocal: Boolean,
        blocked: Blocked
    ): Boolean {
        val dHash = sampled.dHash
        if (dHash.isBlank() || blocked.perceptualHashes.none { SafetyGate.isNearDuplicateDHash(dHash, it) }) return false
        evict(
            record = record,
            failedLayer = "L4",
            reasonCode = "BLOCKLISTED_PERCEPTUAL_HASH",
            explanation = "Perceptual dHash ($dHash) matches a permanently blocklisted wallpaper.",
            isUserLocal = isUserLocal,
            sha256Override = sampled.approved.sha256Checksum,
            dHashOverride = dHash
        )
        blocked.ids.add(record.id)
        blocked.sha256s.add(sampled.approved.sha256Checksum)
        blocked.perceptualHashes.add(dHash)
        return true
    }

    /** The safety layers passed and a display is known: redo the brightness and crop evaluation and stamp the gate version. */
    private fun reevaluate(
        record: WallpaperRecord,
        display: DisplayProfile,
        preferences: UserPreferences,
        sampled: Sampled,
        isUserLocal: Boolean
    ) {
        val headerBounds = sampled.headerBounds
        val origW = record.provenance.originalWidth.takeIf { it > 0 }
            ?: headerBounds?.width?.takeIf { it > 0 }
            ?: 1
        val origH = record.provenance.originalHeight.takeIf { it > 0 }
            ?: headerBounds?.height?.takeIf { it > 0 }
            ?: 1
        val eval = imageEvaluator.evaluateRgbGrid(
            imageWidth = origW,
            imageHeight = origH,
            rgbGrid = sampled.evalGrid,
            gridWidth = GRID_W,
            gridHeight = GRID_H,
            display = display,
            allowBlurredFrameFill = isUserLocal || preferences.allowBlurredFrameFill
        )
        val effectiveEval = if (!eval.isEligible && isUserLocal) {
            eval.copy(
                isEligible = true,
                cropRect = eval.cropRect ?: CropRect.wholePictureBlurred(origW, origH, display)
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
        host.upsert(
            record.copy(
                provenance = record.provenance.copy(
                    sha256Checksum = sampled.approved.sha256Checksum,
                    perceptualHash = sampled.dHash.ifBlank { record.provenance.perceptualHash },
                    safetyGateVersion = SafetyGate.COMPOSITE_GATE_VERSION,
                    gateLayerOutcomes = trace
                ),
                evaluation = effectiveEval
            )
        )
        if (!effectiveEval.isEligible) {
            host.invalidateActive(record.id)
        }
    }

    /**
     * Without a known display the brightness and crop evaluation cannot be redone. The safety layers passed, but the
     * record keeps its old gate version so the next call that has a display evaluates it; stamping it now would leave
     * labels from an older evaluator in place for good. What was worked out on the way (checksum, perceptual hash) is kept
     * so the near-duplicate guard has it.
     */
    private fun keepWhatWasLearned(record: WallpaperRecord, sampled: Sampled) {
        val sha = sampled.approved.sha256Checksum
        val dHash = sampled.dHash
        if (record.provenance.sha256Checksum != sha || (dHash.isNotBlank() && record.provenance.perceptualHash != dHash)) {
            host.upsert(
                record.copy(
                    provenance = record.provenance.copy(
                        sha256Checksum = sha,
                        perceptualHash = dHash.ifBlank { record.provenance.perceptualHash }
                    )
                )
            )
        }
    }

    private fun evict(
        record: WallpaperRecord,
        failedLayer: String,
        reasonCode: String,
        explanation: String,
        isUserLocal: Boolean,
        sha256Override: String? = null,
        dHashOverride: String? = null,
        addToPermanentBlocklist: Boolean = true
    ) {
        val now = System.currentTimeMillis()
        val effectiveSha = sha256Override ?: record.provenance.sha256Checksum
        val effectiveDHash = dHashOverride ?: record.provenance.perceptualHash
        host.upsert(
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
            host.blocklist(
                assetId = record.id,
                sha256 = effectiveSha,
                perceptualHash = effectiveDHash,
                sourceId = record.provenance.sourceId,
                reasonCode = reasonCode,
                timestampEpochMs = now
            )
        }
        host.recordRejection(
            RejectionRecord(
                assetId = record.id,
                sourceId = record.provenance.sourceId,
                reasonCode = reasonCode,
                explanation = explanation,
                timestampEpochMs = now,
                isPermanent = addToPermanentBlocklist
            )
        )
        // Files last: a crash before this leaves an orphan file, not a deleted picture with no record of why.
        if (record.localFilePath.isNotBlank()) runCatching { File(record.localFilePath).delete() }
        if (record.croppedFilePath.isNotBlank()) runCatching { File(record.croppedFilePath).delete() }
        host.invalidateActive(record.id)
    }

    private companion object {
        const val GRID_W = 32
        const val GRID_H = 20
    }
}
