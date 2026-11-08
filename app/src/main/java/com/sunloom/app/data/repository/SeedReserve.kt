package com.sunloom.app.data.repository

import android.graphics.Bitmap
import com.sunloom.app.data.sources.BundledSeedSourceAdapter
import com.sunloom.app.data.sources.SeedRenderer
import com.sunloom.app.data.sources.SeedWallpaperSpec
import com.sunloom.app.data.sources.SourceRegistry
import com.sunloom.app.domain.evaluator.ImageEvaluator
import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.DisplayPolicy
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.SafetyVerdict
import com.sunloom.app.domain.model.SourceIds
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.model.WallpaperStatus
import com.sunloom.app.domain.renderer.CropRenderer
import com.sunloom.app.domain.safety.GateTrace
import com.sunloom.app.domain.safety.SafetyGate
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * The bundled offline seed pictures (Article IX §1): which display they are rendered for, rendering and vetting them,
 * and writing them to the wallpaper directory. It knows nothing of the repository: the records already in the library,
 * the blocklists and the way to store a record are handed in.
 */
internal class SeedReserve(
    private val wallpaperDir: File,
    private val safetyGate: SafetyGate,
    private val imageEvaluator: ImageEvaluator,
    private val maxRenderPixels: Int
) {

    /** What one run is for: the display, the size the seeds stand for and the size they are drawn at. */
    private class Plan(
        val display: DisplayProfile,
        val targetW: Int,
        val targetH: Int,
        val renderW: Int,
        val renderH: Int
    )

    private class Blocked(val ids: Set<String>, val perceptualHashes: Set<String>, val sha256s: Set<String>)

    /** Renders, vets and stores every seed that is missing or no longer fits [display]; [store] writes each record. */
    fun ensureLoaded(
        display: DisplayProfile,
        existing: List<WallpaperRecord>,
        blockedIds: Set<String>,
        blockedPhashes: Set<String>,
        blockedSha256s: Set<String>,
        store: (WallpaperRecord) -> Unit
    ) {
        val (targetW, targetH) = targetSize(display)
        val (renderW, renderH) = CropRenderer.budgetedSize(targetW, targetH, maxPixels = maxRenderPixels)
        val plan = Plan(display, targetW, targetH, renderW, renderH)
        val blocked = Blocked(blockedIds, blockedPhashes, blockedSha256s)
        for (spec in BundledSeedSourceAdapter.SEED_SPECS) {
            buildRecord(spec, plan, existing.firstOrNull { it.id == spec.id }, blocked)?.let(store)
        }
    }

    /**
     * The record to store for [spec], or null when it is rejected, already right, or fails the gate or the evaluator.
     * It writes the seed's PNG to the wallpaper directory before returning, so the file is new when [ensureLoaded]'s
     * `store` runs (the same order as before this was extracted).
     */
    private fun buildRecord(
        spec: SeedWallpaperSpec,
        plan: Plan,
        existingRecord: WallpaperRecord?,
        blocked: Blocked
    ): WallpaperRecord? {
        if (existingRecord != null && (existingRecord.isRejected || isCurrent(existingRecord, plan))) return null

        val asset = renderedAsset(spec, plan)
        val provenance = provenanceFor(spec, plan, existingRecord, asset)
        if (!approvedByGate(spec, provenance, asset, blocked)) return null

        val eval = imageEvaluator.evaluateRgbGrid(
            imageWidth = plan.targetW,
            imageHeight = plan.targetH,
            rgbGrid = asset.rgbGrid,
            gridWidth = asset.gridW,
            gridHeight = asset.gridH,
            display = plan.display,
            allowBlurredFrameFill = false
        )
        if (!eval.isEligible) return null

        val file = File(wallpaperDir, "${spec.id}.png")
        FileOutputStream(file).use { out -> out.write(asset.pngBytes) }

        return WallpaperRecord(
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
            provenance = provenance,
            evaluation = eval,
            status = existingRecord?.status ?: WallpaperStatus.SAFETY_APPROVED,
            decisionExplanation = "Bundled CC0 offline reserve verified for ${plan.display.resolutionLabel}. ${eval.explanation}"
        )
    }

    /** True when the stored seed already has its file, matches this run's sizes and passed the current gate. */
    private fun isCurrent(record: WallpaperRecord, plan: Plan): Boolean =
        File(record.localFilePath).exists() &&
            sameGeometry(record, plan) &&
            record.provenance.safetyGateVersion == SafetyGate.COMPOSITE_GATE_VERSION &&
            record.evaluation.isEligible

    private fun sameGeometry(record: WallpaperRecord, plan: Plan): Boolean =
        record.provenance.originalWidth == plan.targetW &&
            record.provenance.originalHeight == plan.targetH &&
            record.provenance.renditionWidth == plan.display.widthPx &&
            record.provenance.renditionHeight == plan.display.heightPx

    private fun renderedAsset(spec: SeedWallpaperSpec, plan: Plan): CachedSeedAsset {
        val cacheKey = "${spec.id}_${plan.renderW}x${plan.renderH}_${plan.targetW}x${plan.targetH}"
        return cachedSeedAsset(cacheKey) {
            val gridW = 48
            val gridH = 30
            val grid = BundledSeedSourceAdapter.generateRgbGrid(spec, gridW, gridH)
            val dHash = SafetyGate.computePerceptualDHashFromPixels(grid, gridW, gridH)
            val bmp = SeedRenderer.render(spec, widthPx = plan.renderW, heightPx = plan.renderH)
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
            bmp.recycle()
            val pngBytes = baos.toByteArray()
            CachedSeedAsset(
                pngBytes = pngBytes,
                sha256 = SafetyGate.sha256Hex(pngBytes),
                dHash = dHash,
                rgbGrid = grid,
                gridW = gridW,
                gridH = gridH
            )
        }
    }

    private fun provenanceFor(
        spec: SeedWallpaperSpec,
        plan: Plan,
        existingRecord: WallpaperRecord?,
        asset: CachedSeedAsset
    ) = ProvenanceRecord(
        sourceId = SourceIds.SEED_RESERVE,
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
        originalWidth = plan.targetW,
        originalHeight = plan.targetH,
        renditionWidth = plan.display.widthPx,
        renditionHeight = plan.display.heightPx,
        safetyClassification = SafetyClassification.SAFE,
        safetyClassificationSource = "Sunloom On-Device Procedural CC0 Reserve",
        sha256Checksum = asset.sha256,
        perceptualHash = asset.dHash,
        adapterVersion = SourceRegistry.get(SourceIds.SEED_RESERVE)?.adapterVersion
            ?: ProvenanceRecord.DEFAULT_ADAPTER_VERSION,
        safetyGateVersion = SafetyGate.COMPOSITE_GATE_VERSION,
        gateLayerOutcomes = GateTrace.fullPass()
    )

    private fun approvedByGate(
        spec: SeedWallpaperSpec,
        provenance: ProvenanceRecord,
        asset: CachedSeedAsset,
        blocked: Blocked
    ): Boolean {
        val candidate = CandidateAsset(
            id = spec.id,
            title = spec.title,
            description = spec.description,
            category = spec.category,
            rawCategoriesAndTags = spec.subjectTags,
            downloadUrl = "",
            provenance = provenance
        )
        return safetyGate.evaluatePayload(
            candidate = candidate,
            bytes = asset.pngBytes,
            blockedAssetIds = blocked.ids,
            blockedPerceptualHashes = blocked.perceptualHashes,
            blockedSha256Checksums = blocked.sha256s
        ) is SafetyVerdict.Approved
    }

    companion object {
        private data class CachedSeedAsset(
            val pngBytes: ByteArray,
            val sha256: String,
            val dHash: String,
            val rgbGrid: IntArray,
            val gridW: Int,
            val gridH: Int
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

        /** The size a seed is rendered at for [display]: never tiny, and within the render budget. */
        fun targetSize(display: DisplayProfile): Pair<Int, Int> {
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

        /**
         * The display the seeds are rendered for, given every connected display: the sharpest alone, or one envelope as
         * wide as the widest and as tall as the tallest compatible display. Null when no display has a usable size.
         */
        fun renderTarget(displays: List<DisplayProfile>, displayPolicy: DisplayPolicy): DisplayProfile? {
            val valid = displays.filter { it.widthPx > 0 && it.heightPx > 0 }
            if (valid.isEmpty()) return null
            val sharpest = valid.maxBy { it.widthPx.toLong() * it.heightPx.toLong() }
            if (valid.size == 1 || displayPolicy == DisplayPolicy.OPTIMIZE_PRIMARY_SHARPEST) {
                return sharpest
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
            return DisplayProfile(
                displayId = anchor.displayId,
                name = if (compatible.size > 1) "Multi-Display Envelope" else anchor.name,
                widthPx = budgetW,
                heightPx = budgetH,
                densityDpi = compatible.minOf { it.densityDpi },
                isPrimary = true,
                physicalPpi = compatible.mapNotNull { it.physicalPpi }.minOrNull()
            )
        }
    }
}
