package com.sunloom.app.data.repository

import com.sunloom.app.data.sources.BudgetedSourceHttpClient
import com.sunloom.app.data.sources.RateLimitException
import com.sunloom.app.data.sources.SourceBudgetExceededException
import com.sunloom.app.domain.evaluator.ImageEvaluator
import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.ImageEvaluationResult
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafetyVerdict
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.model.WallpaperStatus
import com.sunloom.app.domain.renderer.CropRenderer
import com.sunloom.app.domain.renderer.EnvelopePlan
import com.sunloom.app.domain.renderer.EnvelopePlanner
import com.sunloom.app.domain.safety.GateTrace
import com.sunloom.app.domain.safety.SafetyGate
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException

/** What the repository lets the ingestor do; the ingestor never reaches into the repository itself. */
internal interface IngestHost : RejectionLog {
    /** Stores a new library row (under the library write lock). */
    fun store(record: WallpaperRecord)

    fun recordSourceFailure(sourceId: String, error: Throwable)
}

/** What one sync knows about the library and the connected displays; the sets grow as candidates are decided. */
internal class SyncState(
    val display: DisplayProfile,
    val activeDisplaySet: List<DisplayProfile>,
    val currentSetSignature: String,
    val preferences: UserPreferences,
    val existingIds: MutableSet<String>,
    val permanentlyBlockedIds: MutableSet<String>,
    val displayScopedRejectedIds: MutableSet<String>,
    val blockedSha256s: MutableSet<String>,
    val blockedPhashes: MutableSet<String>,
    val diversityGuard: LibraryDiversityGuard,
    val pausedRows: Map<String, WallpaperRecord>,
    val redownloadRows: Map<String, WallpaperRecord>
)

/** The counters and flags for one source within a sync. */
internal class SourceRun(val sourceId: String, val client: BudgetedSourceHttpClient) {
    var alreadyHad = 0
    var added = 0
    var downloadsTried = 0
    var downloadsOk = 0
    var failureRecorded = false
    var budgetStopped = false
}

/**
 * Decides one fetched candidate: screens it before any download, downloads it, vets the bytes through the safety gate,
 * decoder, duplicate checks, evaluator and envelope planner, and stores it (Article VII §4, X §§3–4). It reports
 * through [host] and the [SyncState] it is given.
 */
internal class CandidateIngestor(
    private val safetyGate: SafetyGate,
    private val imageEvaluator: ImageEvaluator,
    private val envelopePlanner: EnvelopePlanner,
    private val wallpaperDir: File,
    private val quarantineDir: File,
    private val host: IngestHost
) {

    /** The result of trying to download a candidate. */
    private sealed interface Download {
        class Bytes(val data: ByteArray) : Download
        data object Failed : Download

        /** The rest of this source's candidates should not be tried (rate limit or request budget reached). */
        data object StopSource : Download
    }

    /** A downloaded picture that passed the payload gate and was decoded and sampled. */
    private class Sampled(
        val approved: SafetyVerdict.Approved,
        val srcW: Int,
        val srcH: Int,
        val rgbGrid: IntArray,
        val evalGrid: IntArray
    )

    /** A picture that also passed the duplicate checks, the evaluator and the envelope planner. */
    private class Judged(
        val approved: SafetyVerdict.Approved,
        val srcW: Int,
        val srcH: Int,
        val dHash: String,
        val finalEval: ImageEvaluationResult,
        val envelopePlan: EnvelopePlan
    )

    /** Returns false when the rest of this source's candidates should not be tried (rate limit or budget reached). */
    suspend fun ingest(candidate: CandidateAsset, state: SyncState, run: SourceRun): Boolean {
        if (!screen(candidate, state, run)) return true

        val quarantineFile = File(
            quarantineDir,
            "stage_${candidate.id.replace(SAFE_FILENAME_REGEX, "_")}_${System.nanoTime()}.tmp"
        )
        try {
            var payload: ByteArray? = when (val result = download(candidate, run)) {
                is Download.Bytes -> result.data
                Download.Failed -> return true
                Download.StopSource -> return false
            }
            run.downloadsOk++

            // Stage raw bytes in quarantine before any decoding
            FileOutputStream(quarantineFile).use { it.write(payload!!) }

            val sampled = sample(candidate, payload!!, state, run)
            // The payload (up to 35 MB) is needed no longer: judging works on the small grids, and an accepted picture
            // is stored from the staged file. Let it go before the evaluator and the store allocate.
            payload = null
            if (sampled == null) return true
            val judged = judge(candidate, sampled, state, run) ?: return true
            store(candidate, judged, quarantineFile, state, run)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (unavailable: LibraryStoreUnavailableException) {
            // The store itself is gone: the worker must see it to retry and suspend, not skip every picture.
            throw unavailable
        } catch (err: Exception) {
            if (SourceHealthStore.isCancellation(err)) {
                throw CancellationException(err.message).apply { initCause(err) }
            }
            // One picture that cannot be staged, evaluated or stored (a full disk, a store error) is skipped;
            // the other candidates, the sync summary and the cache cleanup still run.
            android.util.Log.w(TAG, "candidate_skipped", err)
            // Make the skip visible in Diagnostics. The store may be what is failing, so this must not throw.
            runCatching {
                host.recordRejection(
                    Rejections.transient(
                        candidate.id, run.sourceId, "STORAGE_ERROR",
                        "Skipped after an error while staging or storing it: ${err.message ?: err.javaClass.simpleName}"
                    )
                )
            }
        } finally {
            runCatching { quarantineFile.delete() }
        }
        return true
    }

    /** The cheap checks that cost no download budget (Article X §§3–4). False means skip the candidate. */
    private fun screen(candidate: CandidateAsset, state: SyncState, run: SourceRun): Boolean {
        state.pausedRows[candidate.id]?.let { previous ->
            if (!offersLargerRendition(candidate, previous.provenance)) {
                run.alreadyHad++
                return false
            }
        }
        if (candidate.id in state.existingIds ||
            candidate.id in state.permanentlyBlockedIds ||
            candidate.id in state.displayScopedRejectedIds
        ) {
            if (candidate.id in state.existingIds) run.alreadyHad++
            return false
        }
        if (!passesMetadataGate(candidate, state, run)) return false

        if (state.diversityGuard.creatorCapReached(run.sourceId, candidate.provenance.creatorName)) return false
        return advertisedSizeFits(candidate, state, run)
    }

    private fun passesMetadataGate(candidate: CandidateAsset, state: SyncState, run: SourceRun): Boolean {
        val metaVerdict = safetyGate.evaluateMetadata(
            candidate = candidate,
            blockedAssetIds = state.permanentlyBlockedIds,
            blockedPerceptualHashes = state.blockedPhashes,
            blockedSha256Checksums = state.blockedSha256s
        )
        if (metaVerdict !is SafetyVerdict.Rejected) return true
        if (metaVerdict.reasonCode in LocalLibraryRepository.SOFT_SAFETY_REASONS) {
            host.recordRejection(
                Rejections.displayScoped(
                    candidate.id, run.sourceId, metaVerdict.reasonCode,
                    metaVerdict.explanation,
                    state.currentSetSignature
                )
            )
            state.displayScopedRejectedIds.add(candidate.id)
            return false
        }
        val ts = System.currentTimeMillis()
        host.blocklist(
            assetId = candidate.id,
            sha256 = candidate.provenance.sha256Checksum,
            perceptualHash = candidate.provenance.perceptualHash,
            sourceId = run.sourceId,
            reasonCode = metaVerdict.reasonCode,
            timestampEpochMs = ts
        )
        host.recordRejection(
            Rejections.permanent(candidate.id, run.sourceId, metaVerdict.reasonCode, metaVerdict.explanation, nowMs = ts)
        )
        state.permanentlyBlockedIds.add(candidate.id)
        return false
    }

    private fun advertisedSizeFits(candidate: CandidateAsset, state: SyncState, run: SourceRun): Boolean {
        val advertisedW = candidate.provenance.originalWidth
        val advertisedH = candidate.provenance.originalHeight
        val sizeRejection = CropRenderer.advertisedSizeRejection(
            advertisedW, advertisedH, state.display, state.preferences.allowBlurredFrameFill
        ) ?: return true
        host.recordRejection(
            Rejections.displayScoped(
                candidate.id, run.sourceId, sizeRejection,
                if (sizeRejection == "COMPOSITION_CROPS_TOO_MUCH") {
                    "A ${state.display.resolutionLabel} crop would keep under half of the " +
                        "${advertisedW}×${advertisedH} picture; not downloaded."
                } else {
                    "Source advertises ${advertisedW}×${advertisedH}, too small for " +
                        "${state.display.resolutionLabel}; not downloaded."
                },
                state.currentSetSignature
            )
        )
        state.displayScopedRejectedIds.add(candidate.id)
        return false
    }

    /** Tries the candidate's renditions in order; a rate limit or an exhausted budget stops the whole source. */
    private suspend fun download(candidate: CandidateAsset, run: SourceRun): Download {
        val downloadChain = buildList {
            add(candidate.downloadUrl)
            addAll(candidate.fallbackDownloadUrls)
            val legacyLarge = candidate.downloadUrl.replace("~orig.jpg", "~large.jpg")
            if (legacyLarge != candidate.downloadUrl) add(legacyLarge)
        }.filter { it.isNotBlank() }.distinct()

        run.downloadsTried++
        for ((index, attemptUrl) in downloadChain.withIndex()) {
            tryRendition(attemptUrl, isLast = index == downloadChain.lastIndex, run)?.let { return it }
        }
        return Download.Failed
    }

    /** One rendition: bytes on success, [Download.StopSource] to give up on the source, null to try the next URL. */
    private suspend fun tryRendition(url: String, isLast: Boolean, run: SourceRun): Download? {
        return try {
            val fetched = run.client.getBytes(url)
            // A rendition too large to decode is not the picture's fault: try the next, smaller one.
            val tooLarge = SafetyGate.inspectImageHeaderBounds(fetched)?.let(SafetyGate::exceedsDecodeBounds) == true
            if (tooLarge && !isLast) null else Download.Bytes(fetched)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (rateLimit: RateLimitException) {
            host.recordSourceFailure(run.sourceId, rateLimit)
            run.failureRecorded = true
            Download.StopSource
        } catch (_: SourceBudgetExceededException) {
            // Our own request budget ran out; that says nothing about the source's health.
            run.budgetStopped = true
            Download.StopSource
        } catch (err: Exception) {
            if (SourceHealthStore.isCancellation(err)) {
                throw CancellationException(err.message).apply { initCause(err) }
            }
            null // try the next rendition URL
        } catch (_: OutOfMemoryError) {
            null // a payload too large to hold in memory: treat like a failed rendition and try the next
        }
    }

    /** Runs the payload gate and decodes the picture into the grids the evaluator reads; null means skip it. */
    private fun sample(candidate: CandidateAsset, payloadBytes: ByteArray, state: SyncState, run: SourceRun): Sampled? {
        val headerBounds = SafetyGate.inspectImageHeaderBounds(payloadBytes)
        val payloadVerdict = safetyGate.evaluatePayload(
            candidate = candidate,
            bytes = payloadBytes,
            blockedSha256Checksums = state.blockedSha256s,
            headerBounds = headerBounds
        )
        if (payloadVerdict is SafetyVerdict.Rejected) {
            val ts = System.currentTimeMillis()
            host.blocklist(
                assetId = candidate.id,
                sha256 = SafetyGate.sha256Hex(payloadBytes),
                perceptualHash = null,
                sourceId = run.sourceId,
                reasonCode = payloadVerdict.reasonCode,
                timestampEpochMs = ts
            )
            host.recordRejection(
                Rejections.permanent(
                    candidate.id, run.sourceId, payloadVerdict.reasonCode,
                    payloadVerdict.explanation, nowMs = ts
                )
            )
            state.permanentlyBlockedIds.add(candidate.id)
            return null
        }
        val approved = payloadVerdict as SafetyVerdict.Approved

        val outcome = decodeSampledOutcome(payloadBytes, headerBounds?.width ?: 0, headerBounds?.height ?: 0)
        // Out of memory says nothing about the picture: skip it now and see it again on a later sync.
        if (outcome is DecodeOutcome.OutOfMemory) return null
        val decoded = (outcome as? DecodeOutcome.Decoded)?.bitmap
        if (decoded == null) {
            host.recordRejection(
                Rejections.permanent(
                    candidate.id, run.sourceId, "CORRUPT_BITMAP",
                    "BitmapFactory could not safely decode image payload."
                )
            )
            state.permanentlyBlockedIds.add(candidate.id)
            return null
        }

        // Phase 0.1: Always use REAL decoded/header dimensions — NEVER maxOf(..., provenance.originalWidth)!
        val srcW = headerBounds?.width?.takeIf { it > 0 } ?: decoded.width
        val srcH = headerBounds?.height?.takeIf { it > 0 } ?: decoded.height
        try {
            val rgbGrid = sampleBitmapToRgbGrid(decoded, GRID_W, GRID_H)
            val evalGrid = sampleBitmapToEvaluationGrid(decoded, GRID_W, GRID_H)
            return Sampled(approved, srcW, srcH, rgbGrid, evalGrid)
        } finally {
            decoded.recycle() // also when sampling throws, so a failing sync does not hold decoded bitmaps
        }
    }

    /** Duplicate checks, the evaluator and the envelope planner; null means skip it (the reason is recorded). */
    private fun judge(candidate: CandidateAsset, sampled: Sampled, state: SyncState, run: SourceRun): Judged? {
        val dHash = SafetyGate.computePerceptualDHashFromPixels(sampled.rgbGrid, GRID_W, GRID_H)
        if (dHash.isNotBlank() && state.blockedPhashes.any { SafetyGate.isNearDuplicateDHash(dHash, it) }) {
            val ts = System.currentTimeMillis()
            host.blocklist(
                assetId = candidate.id,
                sha256 = sampled.approved.sha256Checksum,
                perceptualHash = dHash,
                sourceId = run.sourceId,
                reasonCode = "BLOCKLISTED_PERCEPTUAL_HASH",
                timestampEpochMs = ts
            )
            host.recordRejection(
                Rejections.permanent(
                    candidate.id, run.sourceId, "BLOCKLISTED_PERCEPTUAL_HASH",
                    "Perceptual dHash ($dHash) matches a permanently rejected wallpaper.", nowMs = ts
                )
            )
            state.permanentlyBlockedIds.add(candidate.id)
            state.blockedPhashes.add(dHash)
            return null
        }

        if (state.diversityGuard.isNearDuplicate(dHash)) {
            host.recordRejection(
                Rejections.displayScoped(
                    candidate.id, run.sourceId, "NEAR_DUPLICATE_OF_LIBRARY",
                    "Perceptual hash ($dHash) is almost identical to a wallpaper already in the library.",
                    state.currentSetSignature
                )
            )
            state.displayScopedRejectedIds.add(candidate.id)
            return null
        }

        val eval = imageEvaluator.evaluateRgbGrid(
            imageWidth = sampled.srcW,
            imageHeight = sampled.srcH,
            rgbGrid = sampled.evalGrid,
            gridWidth = GRID_W,
            gridHeight = GRID_H,
            display = state.display,
            allowBlurredFrameFill = state.preferences.allowBlurredFrameFill
        )
        if (!eval.isEligible) {
            val reason = eval.rejectionReasonCode ?: "QUALITY_THRESHOLD_NOT_MET"
            // Quality/resolution rejections are display-scoped (isPermanent = false)
            host.recordRejection(
                Rejections.displayScoped(candidate.id, run.sourceId, reason, eval.explanation, state.currentSetSignature)
            )
            state.displayScopedRejectedIds.add(candidate.id)
            return null
        }

        val envelopePlan = envelopePlanner.planEnvelope(
            sourceWidth = sampled.srcW,
            sourceHeight = sampled.srcH,
            focalX = eval.cropRect?.focalX ?: 0.5,
            focalY = eval.cropRect?.focalY ?: 0.5,
            connectedDisplays = state.activeDisplaySet,
            policy = state.preferences.displayPolicy,
            allowBlurredFrameFill = state.preferences.allowBlurredFrameFill
        )
        if (envelopePlan == null) {
            host.recordRejection(
                Rejections.displayScoped(
                    candidate.id, run.sourceId, "INSUFFICIENT_ENVELOPE_RESOLUTION",
                    "Source (${sampled.srcW}×${sampled.srcH}) cannot satisfy the connected multi-display envelope without upscaling.",
                    state.currentSetSignature
                )
            )
            state.displayScopedRejectedIds.add(candidate.id)
            return null
        }
        val finalEval = eval.copy(cropRect = envelopePlan.envelopeCrop)
        return Judged(sampled.approved, sampled.srcW, sampled.srcH, dHash, finalEval, envelopePlan)
    }

    /** Moves the vetted bytes into the wallpaper directory and stores the library row. */
    private fun store(
        candidate: CandidateAsset,
        judged: Judged,
        quarantineFile: File,
        state: SyncState,
        run: SourceRun
    ) {
        val ext = judged.approved.detectedFormat.fileExtension
        val outFile = File(wallpaperDir, "${candidate.id}.$ext")
        // The bytes are already on disk in quarantine and have passed every check: move them into place
        // instead of writing the (possibly tens of MB) payload a second time.
        placeStagedFile(quarantineFile, outFile)

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
                sha256Checksum = judged.approved.sha256Checksum,
                originalWidth = judged.srcW,
                originalHeight = judged.srcH,
                renditionWidth = judged.envelopePlan.outputWidthPx,
                renditionHeight = judged.envelopePlan.outputHeightPx,
                perceptualHash = judged.dHash,
                safetyGateVersion = SafetyGate.COMPOSITE_GATE_VERSION,
                gateLayerOutcomes = GateTrace.fullPass()
            ),
            evaluation = judged.finalEval,
            status = WallpaperStatus.PREPARED,
            decisionExplanation = "Safety Gate: ${judged.approved.auditNotes.joinToString("; ")}. " +
                "Evaluator: ${judged.finalEval.explanation}"
        ).let { fresh ->
            state.redownloadRows[candidate.id]?.let { previous ->
                fresh.copy(
                    isFavorite = previous.isFavorite,
                    appliedCount = previous.appliedCount,
                    lastAppliedEpochMs = previous.lastAppliedEpochMs
                )
            } ?: fresh
        }
        try {
            host.store(record)
        } catch (e: Exception) {
            runCatching { outFile.delete() } // do not leave an image no row refers to
            throw e
        }
        // A larger rendition may use another file type; the old copy is no longer referenced.
        state.pausedRows[candidate.id]?.localFilePath
            ?.takeIf { it.isNotBlank() && it != outFile.absolutePath }
            ?.let { runCatching { File(it).delete() } }
        state.existingIds.add(candidate.id)
        state.diversityGuard.noteAdded(run.sourceId, candidate.provenance.creatorName, judged.dHash)
        run.added++
    }

    companion object {
        /**
         * Moves a staged, vetted file into place. A move across file systems can fail, so it falls back to copying from
         * the staged file on disk (the caller removes the staged file afterwards) rather than from a payload kept in memory.
         */
        internal fun placeStagedFile(staged: File, dest: File, rename: (File, File) -> Boolean = { a, b -> a.renameTo(b) }) {
            if (!rename(staged, dest)) staged.copyTo(dest, overwrite = true)
        }

        private const val TAG = "CandidateIngestor"
        private const val GRID_W = 32
        private const val GRID_H = 20
        private val SAFE_FILENAME_REGEX = Regex("[^a-zA-Z0-9._-]")

        /** True when [candidate] advertises more pixels than the picture already held, so fetching again may help. */
        fun offersLargerRendition(candidate: CandidateAsset, held: ProvenanceRecord): Boolean {
            val advertised = candidate.provenance.originalWidth.toLong() * candidate.provenance.originalHeight
            val heldPixels = held.originalWidth.toLong() * held.originalHeight
            return advertised > 0L && advertised > heldPixels
        }
    }
}
