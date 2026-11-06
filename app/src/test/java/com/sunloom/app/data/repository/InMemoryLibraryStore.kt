package com.sunloom.app.data.repository

import com.sunloom.app.domain.model.BlocklistEntry
import com.sunloom.app.domain.model.RejectionRecord
import com.sunloom.app.domain.model.WallpaperRecord
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Deterministic test-only store for unit & Robolectric tests on hosts without native SQLite
 * JNI binaries (such as `linux-aarch64`).
 *
 * Lives strictly in `src/test` so production code can never fall back to an in-memory store.
 * Exercises [WallpaperRowMapper] identically to [SqliteLibraryStore].
 */
class InMemoryLibraryStore(
    private val backingDirectory: File? = null
) : LibraryStore {
    private val lock = Any()
    private val wallpaperRows = LinkedHashMap<String, Map<String, Any?>>()
    private val rejections = LinkedHashMap<String, RejectionRecord>()
    private val blocklist = LinkedHashMap<String, BlocklistEntry>()

    init {
        backingDirectory?.mkdirs()
        loadFromDiskIfPresent()
    }

    fun putRawRowForTest(id: String, rawRow: Map<String, Any?>) {
        synchronized(lock) {
            wallpaperRows[id] = rawRow.toMap()
            persistToDiskIfConfigured()
        }
    }

    override fun upsertWallpaper(record: WallpaperRecord) {
        synchronized(lock) {
            wallpaperRows[record.id] = WallpaperRowMapper.toColumnMap(record)
            persistToDiskIfConfigured()
        }
    }

    override fun getWallpaperById(id: String): WallpaperRecord? {
        synchronized(lock) {
            val row = wallpaperRows[id] ?: return null
            return WallpaperRowMapper.fromColumnMap(row)
        }
    }

    override fun getAllWallpapers(): List<WallpaperRecord> {
        synchronized(lock) {
            return wallpaperRows.values
                .map { WallpaperRowMapper.fromColumnMap(it) }
                .sortedByDescending { it.provenance.retrievedAtEpochMs }
        }
    }

    override fun deleteWallpaper(id: String) {
        synchronized(lock) {
            wallpaperRows.remove(id)
            persistToDiskIfConfigured()
        }
    }

    override fun recordRejection(rejection: RejectionRecord) {
        synchronized(lock) {
            rejections[rejection.assetId] = rejection
            if (rejections.size > SqliteLibraryStore.MAX_REJECTION_LOG_ENTRIES) {
                val keepIds = rejections.values
                    .sortedByDescending { it.timestampEpochMs }
                    .take(SqliteLibraryStore.MAX_REJECTION_LOG_ENTRIES)
                    .map { it.assetId }
                    .toSet()
                val iterator = rejections.keys.iterator()
                while (iterator.hasNext()) {
                    if (iterator.next() !in keepIds) iterator.remove()
                }
            }
            persistToDiskIfConfigured()
        }
    }

    override fun getAllRejections(): List<RejectionRecord> {
        synchronized(lock) {
            return rejections.values.sortedByDescending { it.timestampEpochMs }
        }
    }

    override fun addBlocklistEntry(entry: BlocklistEntry) {
        synchronized(lock) {
            val key = entry.assetId.ifBlank { entry.sha256.ifBlank { entry.dHash.ifBlank { "blk_${entry.createdAtEpochMs}" } } }
            blocklist[key] = entry
            persistToDiskIfConfigured()
        }
    }

    override fun getAllBlocklistEntries(): List<BlocklistEntry> {
        synchronized(lock) {
            return blocklist.values.sortedByDescending { it.createdAtEpochMs }
        }
    }

    private fun persistToDiskIfConfigured() {
        val dir = backingDirectory ?: return
        dir.mkdirs()
        val stateFile = File(dir, "store_state_v2.bin")
        runCatching {
            DataOutputStream(FileOutputStream(stateFile)).use { out ->
                out.writeInt(wallpaperRows.size)
                for ((id, row) in wallpaperRows) {
                    out.writeUTF(id)
                    out.writeInt(row.size)
                    for ((k, v) in row) {
                        out.writeUTF(k)
                        when (v) {
                            null -> out.writeByte(0)
                            is String -> {
                                out.writeByte(1)
                                out.writeUTF(v)
                            }
                            is Int -> {
                                out.writeByte(2)
                                out.writeInt(v)
                            }
                            is Long -> {
                                out.writeByte(3)
                                out.writeLong(v)
                            }
                            is Double -> {
                                out.writeByte(4)
                                out.writeDouble(v)
                            }
                            is Boolean -> {
                                out.writeByte(5)
                                out.writeBoolean(v)
                            }
                            else -> {
                                out.writeByte(1)
                                out.writeUTF(v.toString())
                            }
                        }
                    }
                }
                out.writeInt(rejections.size)
                for (r in rejections.values) {
                    out.writeUTF(r.assetId)
                    out.writeUTF(r.sourceId)
                    out.writeUTF(r.reasonCode)
                    out.writeUTF(r.explanation)
                    out.writeLong(r.timestampEpochMs)
                    out.writeBoolean(r.isPermanent)
                    out.writeUTF(r.displaySignature)
                }
                out.writeInt(blocklist.size)
                for ((key, b) in blocklist) {
                    out.writeUTF(key)
                    out.writeUTF(b.assetId)
                    out.writeUTF(b.sha256)
                    out.writeUTF(b.dHash)
                    out.writeUTF(b.sourceId)
                    out.writeUTF(b.reason)
                    out.writeLong(b.createdAtEpochMs)
                }
            }
        }
    }

    private fun loadFromDiskIfPresent() {
        val dir = backingDirectory ?: return
        val stateFile = File(dir, "store_state_v2.bin")
        if (!stateFile.exists() || stateFile.length() == 0L) return
        runCatching {
            DataInputStream(FileInputStream(stateFile)).use { inp ->
                val wpCount = inp.readInt()
                repeat(wpCount) {
                    val id = inp.readUTF()
                    val colCount = inp.readInt()
                    val map = HashMap<String, Any?>(colCount)
                    repeat(colCount) {
                        val key = inp.readUTF()
                        val type = inp.readByte().toInt()
                        val value: Any? = when (type) {
                            0 -> null
                            1 -> inp.readUTF()
                            2 -> inp.readInt()
                            3 -> inp.readLong()
                            4 -> inp.readDouble()
                            5 -> inp.readBoolean()
                            else -> null
                        }
                        map[key] = value
                    }
                    wallpaperRows[id] = map
                }
                val rejCount = inp.readInt()
                repeat(rejCount) {
                    val r = RejectionRecord(
                        assetId = inp.readUTF(),
                        sourceId = inp.readUTF(),
                        reasonCode = inp.readUTF(),
                        explanation = inp.readUTF(),
                        timestampEpochMs = inp.readLong(),
                        isPermanent = inp.readBoolean(),
                        displaySignature = inp.readUTF()
                    )
                    rejections[r.assetId] = r
                }
                val blkCount = inp.readInt()
                repeat(blkCount) {
                    val key = inp.readUTF()
                    val b = BlocklistEntry(
                        assetId = inp.readUTF(),
                        sha256 = inp.readUTF(),
                        dHash = inp.readUTF(),
                        sourceId = inp.readUTF(),
                        reason = inp.readUTF(),
                        createdAtEpochMs = inp.readLong()
                    )
                    blocklist[key] = b
                }
            }
        }
    }
}
