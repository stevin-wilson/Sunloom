package com.sunloom.app.data.repository

import com.sunloom.app.domain.model.SourceIds
import com.sunloom.app.domain.model.WallpaperRecord
import java.io.File

/** What one hygiene pass removed. */
data class StorageHygieneReport(val orphanFilesRemoved: Int, val staleRowsRemoved: Int)

/**
 * Keeps the image cache from leaking (Article X §3). Two things pile up quietly: image files no row points to (a
 * process killed between writing a file and its row, or a row and its file's deletion), and rows that only remember
 * a picture that was evicted long ago and never kept. This removes both. It never touches the blocklist or the
 * rejection log, which are what keep a rejected picture from coming back.
 */
class StorageHygiene(
    private val wallpaperDir: File,
    private val records: () -> List<WallpaperRecord>,
    private val deleteRow: (String) -> Unit,
    private val writeLock: Any
) {
    /**
     * Deletes files in the image folder that no row refers to and that are at least [minAgeMs] old. The age is a grace
     * period: a download is written before its row, so a very recent file may be about to be claimed. Does nothing
     * while the library has no rows at all, because an empty library next to a full folder looks like a lost
     * database rather than a folder of orphans. Returns how many files were removed.
     */
    fun sweepOrphanedFiles(nowEpochMs: Long, minAgeMs: Long = ORPHAN_GRACE_MS): Int = synchronized(writeLock) {
        val rows = records()
        if (rows.isEmpty()) return@synchronized 0
        val referenced = rows.flatMap { listOf(it.localFilePath, it.croppedFilePath) }
            .filter { it.isNotBlank() }
            .map { File(it).absolutePath }
            .toSet()
        wallpaperDir.listFiles().orEmpty()
            .filter { it.isFile && it.absolutePath !in referenced && nowEpochMs - it.lastModified() >= minAgeMs }
            .count { it.delete() }
    }

    /**
     * Drops rows that record nothing but an evicted picture: no file left, not kept, not the baseline, not a personal
     * photo, not a seed, not rejected, not shown on screen, and neither fetched nor shown within [maxAgeMs]. Returns
     * how many rows were removed.
     */
    fun compactStaleRows(nowEpochMs: Long, activeIds: Set<String>, maxAgeMs: Long = STALE_ROW_MS): Int = synchronized(writeLock) {
        val cutoff = nowEpochMs - maxAgeMs
        val stale = records().filter { r ->
            !r.isFavorite && !r.isBaselineRestore && !r.isRejected && r.id !in activeIds &&
                r.provenance.sourceId != SourceIds.USER_LOCAL && r.provenance.sourceId != SourceIds.SEED_RESERVE &&
                r.localFilePath.isBlank() && r.fileSizeBytes == 0L &&
                maxOf(r.lastAppliedEpochMs, r.provenance.retrievedAtEpochMs) <= cutoff
        }
        stale.forEach { deleteRow(it.id) }
        stale.size
    }

    /** Bytes the row's files really take on disk (a file shared by the local and cropped paths counts once). */
    fun diskBytes(record: WallpaperRecord): Long =
        listOf(record.localFilePath, record.croppedFilePath)
            .filter { it.isNotBlank() }
            .map { File(it).absolutePath }
            .distinct()
            .sumOf { path -> File(path).let { if (it.isFile) it.length() else 0L } }

    companion object {
        const val ORPHAN_GRACE_MS = 60L * 60 * 1000
        const val STALE_ROW_MS = 90L * 24 * 60 * 60 * 1000
    }
}
