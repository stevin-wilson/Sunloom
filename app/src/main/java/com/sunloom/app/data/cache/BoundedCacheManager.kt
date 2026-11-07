package com.sunloom.app.data.cache

import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.WallpaperRecord
import java.io.File

data class CacheCleanupReport(
    val bytesBefore: Long,
    val bytesAfter: Long,
    val evictedIds: List<String>,
    val protectedIds: Set<String>
)

/**
 * Article IX §2 & X §3 (Constitution v0.4): Bounded Cache & Offline Reserve Manager.
 * Enforces user-configured storage limits while guaranteeing that active wallpapers,
 * favorites ("Kept"), baseline restoration copies, minimum Light/Dark offline reserves
 * (up to 3 Light + 3 Dark), one Light and one Dark bundled seed per category (the offline fallback
 * for users who enable only that category), and provenance records are never deleted.
 */
class BoundedCacheManager(
    private val minProtectedPerBrightness: Int = DEFAULT_PROTECTED_PER_BRIGHTNESS
) {

    fun selectEvictions(
        records: List<WallpaperRecord>,
        activeWallpaperIds: Set<String>,
        maxCacheBytes: Long
    ): CacheCleanupReport {
        val cachedRecords = records.filter { it.fileSizeBytes > 0L && !it.isRejected }
        val bytesBefore = cachedRecords.sumOf { it.fileSizeBytes }

        // Always protect:
        // 1. Active wallpapers
        // 2. Favorites (user clicked Keep)
        // 3. Baseline restoration wallpaper
        // 4. Up to minProtectedPerBrightness (default 3) Light and 3 Dark approved reserve wallpapers (Article IX §2)
        val protectedIds = LinkedHashSet<String>()
        protectedIds.addAll(activeWallpaperIds)
        // A user-provided photo cannot be fetched again, so evicting it would lose it for good.
        cachedRecords.filter { it.isFavorite || it.isBaselineRestore || it.provenance.sourceId == "user_local" }.forEach {
            protectedIds.add(it.id)
        }

        val protectionPriority = compareBy<WallpaperRecord> {
            if (it.provenance.sourceId == "seed_reserve") 1 else 0
        }.thenByDescending { it.provenance.retrievedAtEpochMs }

        cachedRecords
            .filter { it.evaluation.brightnessClass == BrightnessClass.LIGHT && !it.isBaselineRestore }
            .sortedWith(protectionPriority)
            .take(minProtectedPerBrightness)
            .forEach { protectedIds.add(it.id) }

        cachedRecords
            .filter { it.evaluation.brightnessClass == BrightnessClass.DARK && !it.isBaselineRestore }
            .sortedWith(protectionPriority)
            .take(minProtectedPerBrightness)
            .forEach { protectedIds.add(it.id) }

        // Seeds are the offline fallback for their own category (the matcher ignores other categories),
        // so the global top-3 above must not leave a category without any seed.
        cachedRecords
            .filter {
                it.provenance.sourceId == "seed_reserve" && !it.isBaselineRestore &&
                    (it.evaluation.brightnessClass == BrightnessClass.LIGHT ||
                        it.evaluation.brightnessClass == BrightnessClass.DARK)
            }
            .groupBy { it.category to it.evaluation.brightnessClass }
            .values
            .forEach { group -> group.minByOrNull { it.id }?.let { protectedIds.add(it.id) } }

        cachedRecords
            .filter {
                it.evaluation.brightnessClass == BrightnessClass.NEUTRAL &&
                    !it.isBaselineRestore &&
                    it.provenance.sourceId != "seed_reserve"
            }
            .sortedByDescending { it.provenance.retrievedAtEpochMs }
            .take(minProtectedPerBrightness)
            .forEach { protectedIds.add(it.id) }

        if (bytesBefore <= maxCacheBytes) {
            return CacheCleanupReport(
                bytesBefore = bytesBefore,
                bytesAfter = bytesBefore,
                evictedIds = emptyList(),
                protectedIds = protectedIds
            )
        }

        // Evict what the user has already seen first (least recently applied), and only then images that
        // were never shown: those are the fresh ones the rotation still owes the user (Article IX §3).
        val evictable = cachedRecords
            .filter { it.id !in protectedIds }
            .sortedWith(
                compareBy<WallpaperRecord> { if (it.appliedCount > 0 || it.lastAppliedEpochMs > 0L) 0 else 1 }
                    .thenBy { it.lastAppliedEpochMs }
                    .thenBy { it.provenance.retrievedAtEpochMs }
            )

        var currentBytes = bytesBefore
        val evictedIds = ArrayList<String>()

        for (candidate in evictable) {
            if (currentBytes <= maxCacheBytes) break
            evictedIds.add(candidate.id)
            currentBytes -= candidate.fileSizeBytes
        }

        return CacheCleanupReport(
            bytesBefore = bytesBefore,
            bytesAfter = currentBytes.coerceAtLeast(0L),
            evictedIds = evictedIds,
            protectedIds = protectedIds
        )
    }

    fun executeCleanupOnDisk(
        records: List<WallpaperRecord>,
        activeWallpaperIds: Set<String>,
        maxCacheBytes: Long
    ): Pair<List<WallpaperRecord>, CacheCleanupReport> {
        val report = selectEvictions(records, activeWallpaperIds, maxCacheBytes)
        val evictedSet = report.evictedIds.toSet()

        val updatedRecords = records.map { record ->
            if (record.id in evictedSet) {
                if (record.localFilePath.isNotBlank()) {
                    runCatching { File(record.localFilePath).delete() }
                }
                if (record.croppedFilePath.isNotBlank()) {
                    runCatching { File(record.croppedFilePath).delete() }
                }
                // Article X §3: Retain provenance record even when cached image file is evicted
                record.copy(
                    localFilePath = "",
                    croppedFilePath = "",
                    fileSizeBytes = 0L
                )
            } else {
                record
            }
        }
        return updatedRecords to report
    }

    companion object {
        const val DEFAULT_PROTECTED_PER_BRIGHTNESS = 3
    }
}
