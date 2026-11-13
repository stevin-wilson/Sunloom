package com.sunloom.app.data.repository

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import com.sunloom.app.domain.model.BlocklistEntry
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.ImageEvaluationResult
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.RejectionRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.model.WallpaperStatus
import com.sunloom.app.domain.safety.SafetyGate

/**
 * Thrown when the persistent local SQLite store cannot be opened or queried in production
 * (Article VII §§4–6 fail-closed persistence invariant).
 */
class LibraryStoreUnavailableException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Persistence abstraction for Sunloom's local library, rejection log, and permanent blocklist.
 *
 * Production uses [SqliteLibraryStore] exclusively (failing closed via [LibraryStoreUnavailableException]
 * if SQLite cannot open). Unit/Robolectric tests can inject test implementations sharing [WallpaperRowMapper].
 */
interface LibraryStore {
    fun upsertWallpaper(record: WallpaperRecord)
    fun getWallpaperById(id: String): WallpaperRecord?
    fun getAllWallpapers(): List<WallpaperRecord>
    fun deleteWallpaper(id: String)

    fun recordRejection(rejection: RejectionRecord)
    fun getAllRejections(): List<RejectionRecord>

    /** Forgets display-scoped (non-permanent) rejections so those candidates are evaluated again. */
    fun clearTransientRejections()

    /** Removes blocklist entries and rejection rows recorded with any of [reasonCodes] (soft outcomes only). */
    fun removeSoftSafetyRecords(reasonCodes: Set<String>)

    fun addBlocklistEntry(entry: BlocklistEntry)
    fun getAllBlocklistEntries(): List<BlocklistEntry>

    fun checkpoint() {}

    /** Deletes the whole store - library, rejections and blocklist - so the app can start again from nothing. */
    fun wipe()
}

/**
 * Shared row mapper between [WallpaperRecord] and database column representations.
 * Enforces fail-closed enum decoding (Phase 0.5) and full fidelity for crop geometry,
 * focal point, perceptual hash, and safety gate versions (Phase 0.4).
 */
object WallpaperRowMapper {
    private const val TAG_SEPARATOR = "\u001F"

    fun toColumnMap(record: WallpaperRecord): Map<String, Any?> {
        val crop = record.evaluation.cropRect
        return mapOf(
            "id" to record.id,
            "title" to record.title,
            "description" to record.description,
            "raw_categories_tags" to record.rawCategoriesAndTags.joinToString(TAG_SEPARATOR),
            "category" to record.category.name,
            "local_path" to record.localFilePath,
            "cropped_path" to record.croppedFilePath,
            "file_size" to record.fileSizeBytes,
            "is_favorite" to if (record.isFavorite) 1 else 0,
            "is_rejected" to if (record.isRejected) 1 else 0,
            "is_baseline" to if (record.isBaselineRestore) 1 else 0,
            "applied_count" to record.appliedCount,
            "last_applied" to record.lastAppliedEpochMs,
            "status" to record.status.name,
            "decision_explanation" to record.decisionExplanation,
            // Provenance columns
            "source_id" to record.provenance.sourceId,
            "source_name" to record.provenance.sourceName,
            "original_url" to record.provenance.originalPageUrl,
            "creator_name" to record.provenance.creatorName,
            "creator_url" to record.provenance.creatorPageUrl,
            "license_type" to record.provenance.licenseType.name,
            "license_url" to record.provenance.licenseUrl,
            "license_version" to record.provenance.licenseVersion,
            "attribution" to record.provenance.attributionText,
            "retrieved_at" to record.provenance.retrievedAtEpochMs,
            "orig_w" to record.provenance.originalWidth,
            "orig_h" to record.provenance.originalHeight,
            "rend_w" to record.provenance.renditionWidth,
            "rend_h" to record.provenance.renditionHeight,
            "safety_class" to record.provenance.safetyClassification.name,
            "safety_source" to record.provenance.safetyClassificationSource,
            "sha256" to record.provenance.sha256Checksum,
            "perceptual_hash" to record.provenance.perceptualHash,
            "adapter_version" to record.provenance.adapterVersion,
            "safety_gate_version" to record.provenance.safetyGateVersion,
            "gate_layer_outcomes" to record.provenance.gateLayerOutcomes,
            // Evaluation & crop columns
            "is_eligible" to if (record.evaluation.isEligible) 1 else 0,
            "rejection_reason_code" to record.evaluation.rejectionReasonCode,
            "global_lum" to record.evaluation.globalLuminance,
            "desktop_lum" to record.evaluation.desktopZoneLuminance,
            "clock_lum" to record.evaluation.lockClockZoneLuminance,
            "glare_frac" to record.evaluation.glareFractionInSafeZones,
            "contrast_std" to record.evaluation.contrastStdDev,
            "brightness_class" to record.evaluation.brightnessClass.name,
            "eval_explanation" to record.evaluation.explanation,
            "lightness_p50" to record.evaluation.lightnessP50,
            "label_contrast" to record.evaluation.desktopLabelContrast,
            "warmth" to record.evaluation.warmth,
            "hue_bucket" to record.evaluation.hueBucket,
            "crop_x" to (crop?.left ?: 0),
            "crop_y" to (crop?.top ?: 0),
            "crop_w" to (crop?.width ?: record.provenance.renditionWidth),
            "crop_h" to (crop?.height ?: record.provenance.renditionHeight),
            "focal_x" to (crop?.focalX ?: 0.5),
            "focal_y" to (crop?.focalY ?: 0.5),
            "uses_blurred_fill" to if (crop?.usesBlurredFrameFill == true) 1 else 0,
            "native_coverage" to (crop?.pixelCoverageRatio ?: 1.0)
        )
    }

    // Typed readers for a stored row: a missing column or one of the wrong type reads as its default.
    private fun Map<String, Any?>.text(key: String): String = this[key] as? String ?: ""
    private fun Map<String, Any?>.int(key: String, default: Int = 0): Int = (this[key] as? Number)?.toInt() ?: default
    private fun Map<String, Any?>.long(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L
    private fun Map<String, Any?>.double(key: String, default: Double = 0.0): Double =
        (this[key] as? Number)?.toDouble() ?: default
    private fun Map<String, Any?>.flag(key: String): Boolean = int(key) == 1

    /** The enum constant named by the column, or [fallback] for an unknown or missing name (fail-closed). */
    private inline fun <reified E : Enum<E>> Map<String, Any?>.enumOr(key: String, fallback: E): E {
        val name = this[key] as? String
        return enumValues<E>().firstOrNull { it.name == name } ?: fallback
    }

    fun fromColumnMap(row: Map<String, Any?>): WallpaperRecord {
        val id = row.text("id")
        // Fail-closed: unknown status decodes to REJECTED_SAFETY_OR_QUALITY, unknown license to UNKNOWN_OR_RESTRICTED,
        // unknown safety classification to UNCERTAIN.
        val status = row.enumOr("status", WallpaperStatus.REJECTED_SAFETY_OR_QUALITY)
        val licenseType = row.enumOr("license_type", LicenseType.UNKNOWN_OR_RESTRICTED)
        val safetyClass = row.enumOr("safety_class", SafetyClassification.UNCERTAIN)

        val rawTagsStr = row.text("raw_categories_tags")
        val rawTags = if (rawTagsStr.isBlank()) {
            emptyList()
        } else {
            rawTagsStr.split(TAG_SEPARATOR).filter { it.isNotBlank() }
        }

        val isFailClosedUnsafe = isFailClosedUnsafe(licenseType, safetyClass, status)

        return WallpaperRecord(
            id = id,
            title = row.text("title"),
            description = row.text("description"),
            rawCategoriesAndTags = rawTags,
            category = row.enumOr("category", SafeCategory.LANDSCAPES_NATURE),
            localFilePath = row.text("local_path"),
            croppedFilePath = row.text("cropped_path"),
            fileSizeBytes = row.long("file_size"),
            isFavorite = row.flag("is_favorite"),
            isRejected = row.flag("is_rejected") || isFailClosedUnsafe,
            isBaselineRestore = row.flag("is_baseline"),
            appliedCount = row.int("applied_count"),
            lastAppliedEpochMs = row.long("last_applied"),
            status = status,
            decisionExplanation = row.text("decision_explanation"),
            provenance = decodeProvenance(row, id, licenseType, safetyClass),
            evaluation = decodeEvaluation(row, isFailClosedUnsafe)
        )
    }

    /** A record is unusable when its license or safety verdict is anything but a clear pass. */
    private fun isFailClosedUnsafe(licenseType: LicenseType, safetyClass: SafetyClassification, status: WallpaperStatus): Boolean {
        val unsafeClass = safetyClass in setOf(
            SafetyClassification.UNCERTAIN,
            SafetyClassification.MISSING,
            SafetyClassification.CONFLICTING,
            SafetyClassification.NSFW_OR_UNSAFE
        )
        return licenseType == LicenseType.UNKNOWN_OR_RESTRICTED || unsafeClass || status == WallpaperStatus.REJECTED_SAFETY_OR_QUALITY
    }

    private fun decodeProvenance(
        row: Map<String, Any?>,
        id: String,
        licenseType: LicenseType,
        safetyClass: SafetyClassification
    ): ProvenanceRecord = ProvenanceRecord(
        sourceId = row.text("source_id"),
        sourceName = row.text("source_name"),
        assetId = id,
        originalPageUrl = row.text("original_url"),
        creatorName = row.text("creator_name"),
        creatorPageUrl = row.text("creator_url"),
        licenseType = licenseType,
        licenseUrl = (row["license_url"] as? String)?.ifBlank { licenseType.defaultUrl } ?: licenseType.defaultUrl,
        licenseVersion = row.text("license_version"),
        attributionText = row.text("attribution"),
        retrievedAtEpochMs = row.long("retrieved_at"),
        originalWidth = row.int("orig_w"),
        originalHeight = row.int("orig_h"),
        renditionWidth = row.int("rend_w"),
        renditionHeight = row.int("rend_h"),
        safetyClassification = safetyClass,
        safetyClassificationSource = row.text("safety_source"),
        sha256Checksum = row.text("sha256"),
        perceptualHash = row.text("perceptual_hash"),
        adapterVersion = row["adapter_version"]?.toString()?.takeIf { it.isNotBlank() }
            ?: ProvenanceRecord.DEFAULT_ADAPTER_VERSION,
        safetyGateVersion = row["safety_gate_version"]?.toString()?.takeIf { it.isNotBlank() } ?: "v0.1.0",
        gateLayerOutcomes = row["gate_layer_outcomes"] as? String ?: "L1:PASS;L2:PASS;L3:PASS;L4:PASS"
    )

    private fun decodeEvaluation(row: Map<String, Any?>, isFailClosedUnsafe: Boolean): ImageEvaluationResult =
        ImageEvaluationResult(
            isEligible = row.flag("is_eligible") && !isFailClosedUnsafe,
            rejectionReasonCode = row["rejection_reason_code"] as? String,
            globalLuminance = row.double("global_lum"),
            desktopZoneLuminance = row.double("desktop_lum"),
            lockClockZoneLuminance = row.double("clock_lum"),
            glareFractionInSafeZones = row.double("glare_frac"),
            contrastStdDev = row.double("contrast_std"),
            brightnessClass = row.enumOr("brightness_class", BrightnessClass.NEUTRAL),
            cropRect = decodeCrop(row),
            explanation = row.text("eval_explanation"),
            lightnessP50 = row.double("lightness_p50", -1.0),
            desktopLabelContrast = row.double("label_contrast", -1.0),
            warmth = row.double("warmth"),
            hueBucket = row.int("hue_bucket", -1)
        )

    private fun decodeCrop(row: Map<String, Any?>): CropRect = CropRect(
        left = row.int("crop_x"),
        top = row.int("crop_y"),
        width = (row["crop_w"] as? Number)?.toInt()?.takeIf { it > 0 } ?: row.int("rend_w"),
        height = (row["crop_h"] as? Number)?.toInt()?.takeIf { it > 0 } ?: row.int("rend_h"),
        focalX = row.double("focal_x", 0.5),
        focalY = row.double("focal_y", 0.5),
        usesBlurredFrameFill = row.flag("uses_blurred_fill"),
        pixelCoverageRatio = row.double("native_coverage", 1.0)
    )

    fun toContentValues(record: WallpaperRecord): ContentValues {
        val map = toColumnMap(record)
        val cv = ContentValues()
        for ((k, v) in map) {
            when (v) {
                null -> cv.putNull(k)
                is String -> cv.put(k, v)
                is Int -> cv.put(k, v)
                is Long -> cv.put(k, v)
                is Double -> cv.put(k, v)
                is Float -> cv.put(k, v.toDouble())
                is Boolean -> cv.put(k, if (v) 1 else 0)
                else -> cv.put(k, v.toString())
            }
        }
        return cv
    }

    fun fromCursor(c: Cursor): WallpaperRecord {
        val map = HashMap<String, Any?>(c.columnCount)
        for (i in 0 until c.columnCount) {
            val name = c.getColumnName(i)
            val value: Any? = when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                Cursor.FIELD_TYPE_STRING -> c.getString(i)
                else -> null
            }
            map[name] = value
        }
        return fromColumnMap(map)
    }
}

/**
 * Production SQLite store with non-destructive v1 -> v2 schema migration,
 * indexed queries, capped rejection logs, and dedicated permanent blocklist table.
 */
/**
 * Android's default handler deletes a corrupt database and starts empty, which would silently drop the permanent
 * "Reject forever" blocklist and every safety ban (Article VII §6: fail closed). This one leaves the file where it is,
 * so opening fails, automation is suspended and the problem is shown, instead of the blocklist vanishing.
 */
internal object KeepCorruptDatabaseHandler : android.database.DatabaseErrorHandler {
    override fun onCorruption(dbObj: SQLiteDatabase) {
        android.util.Log.e("SqliteLibraryStore", "database_corrupt: keeping the file, storage unavailable")
    }
}

/**
 * [databaseName] is the production name unless a test asks for its own file, so a device test can create and delete a
 * database without pulling the app's own out from under its open connection.
 */
class SqliteLibraryStore(context: Context, private val databaseName: String = DATABASE_NAME) : LibraryStore {
    private val appContext: Context = context.applicationContext

    companion object {
        const val DATABASE_NAME = "sunloom_library.db"
        const val DATABASE_VERSION = 3
        const val MAX_REJECTION_LOG_ENTRIES = 1000

        @Volatile
        private var sharedInstance: Pair<String, SqliteLibraryStore>? = null

        /** Whether anything was written since the last checkpoint; shared by every handle in the process. */
        private val writes = WriteTracker()

        /**
         * Folds the write-ahead log into the main file if, and only if, something was written. A background run that
         * wrote nothing (a Follow-OS poll with nothing to do) neither opens the database nor builds a repository.
         */
        fun checkpointSharedIfWritten() {
            val store = sharedInstance?.second ?: return
            writes.runIfWritten { store.checkpoint() }
        }

        /** Forgets the shared instance, so the next [getInstance] opens (and creates) the database afresh. */
        internal fun forgetSharedInstance() {
            synchronized(this) { sharedInstance = null }
        }

        fun getInstance(context: Context): SqliteLibraryStore {
            val appCtx = context.applicationContext
            val key = appCtx.filesDir.absolutePath
            sharedInstance?.let { (cachedKey, store) ->
                if (cachedKey == key) return store
            }
            return synchronized(this) {
                sharedInstance?.let { (cachedKey, store) ->
                    if (cachedKey == key) return@synchronized store
                }
                SqliteLibraryStore(appCtx).also { sharedInstance = key to it }
            }
        }
    }

    private val dbHelper = object : SQLiteOpenHelper(
        context.applicationContext,
        databaseName,
        null,
        DATABASE_VERSION,
        KeepCorruptDatabaseHandler
    ) {
        // Every migration only adds columns, so a build older than the database (a Play rollback, a sideloaded older
        // build) can still read it. The default would throw and leave storage permanently unavailable.
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

        override fun onCreate(db: SQLiteDatabase) {
            writes.markWritten()
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS wallpapers (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    raw_categories_tags TEXT NOT NULL DEFAULT '',
                    category TEXT NOT NULL,
                    local_path TEXT NOT NULL,
                    cropped_path TEXT NOT NULL,
                    file_size INTEGER NOT NULL,
                    is_favorite INTEGER NOT NULL,
                    is_rejected INTEGER NOT NULL,
                    is_baseline INTEGER NOT NULL,
                    applied_count INTEGER NOT NULL,
                    last_applied INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    decision_explanation TEXT NOT NULL,
                    source_id TEXT NOT NULL,
                    source_name TEXT NOT NULL,
                    original_url TEXT NOT NULL,
                    creator_name TEXT NOT NULL,
                    creator_url TEXT NOT NULL,
                    license_type TEXT NOT NULL,
                    license_url TEXT NOT NULL DEFAULT '',
                    license_version TEXT NOT NULL DEFAULT '',
                    attribution TEXT NOT NULL,
                    retrieved_at INTEGER NOT NULL,
                    orig_w INTEGER NOT NULL,
                    orig_h INTEGER NOT NULL,
                    rend_w INTEGER NOT NULL,
                    rend_h INTEGER NOT NULL,
                    safety_class TEXT NOT NULL,
                    safety_source TEXT NOT NULL,
                    sha256 TEXT NOT NULL,
                    perceptual_hash TEXT NOT NULL DEFAULT '',
                    adapter_version TEXT NOT NULL DEFAULT '1.3.0',
                    safety_gate_version TEXT NOT NULL DEFAULT '${SafetyGate.COMPOSITE_GATE_VERSION}',
                    gate_layer_outcomes TEXT NOT NULL DEFAULT 'L1:PASS;L2:PASS;L3:PASS;L4:PASS',
                    is_eligible INTEGER NOT NULL,
                    rejection_reason_code TEXT,
                    global_lum REAL NOT NULL,
                    desktop_lum REAL NOT NULL,
                    clock_lum REAL NOT NULL,
                    glare_frac REAL NOT NULL,
                    contrast_std REAL NOT NULL,
                    brightness_class TEXT NOT NULL,
                    eval_explanation TEXT NOT NULL,
                    crop_x INTEGER NOT NULL DEFAULT 0,
                    crop_y INTEGER NOT NULL DEFAULT 0,
                    crop_w INTEGER NOT NULL DEFAULT 0,
                    crop_h INTEGER NOT NULL DEFAULT 0,
                    focal_x REAL NOT NULL DEFAULT 0.5,
                    focal_y REAL NOT NULL DEFAULT 0.5,
                    uses_blurred_fill INTEGER NOT NULL DEFAULT 0,
                    native_coverage REAL NOT NULL DEFAULT 1.0,
                    lightness_p50 REAL NOT NULL DEFAULT -1.0,
                    label_contrast REAL NOT NULL DEFAULT -1.0,
                    warmth REAL NOT NULL DEFAULT 0.0,
                    hue_bucket INTEGER NOT NULL DEFAULT -1
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS rejections (
                    asset_id TEXT PRIMARY KEY,
                    source_id TEXT NOT NULL,
                    reason_code TEXT NOT NULL,
                    explanation TEXT NOT NULL,
                    timestamp_ms INTEGER NOT NULL,
                    is_permanent INTEGER NOT NULL DEFAULT 1,
                    display_signature TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent()
            )
            db.execSQL(LibraryMigrations.CREATE_BLOCKLIST.trimIndent())
            createIndexes(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            writes.markWritten()
            // SQLiteOpenHelper runs this in a transaction: if a statement throws, nothing is applied, the version
            // stays where it was, and the open fails (storage unavailable) instead of half-migrating.
            for (sql in LibraryMigrations.statements(oldVersion) { table -> columnNames(db, table) }) db.execSQL(sql)
        }

        private fun columnNames(db: SQLiteDatabase, table: String): Set<String> =
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                val nameIndex = c.getColumnIndexOrThrow("name")
                buildSet { while (c.moveToNext()) add(c.getString(nameIndex)) }
            }

        private fun createIndexes(db: SQLiteDatabase) {
            LibraryMigrations.INDEXES.forEach(db::execSQL)
        }
    }

    override fun upsertWallpaper(record: WallpaperRecord) {
        writes.markWritten()
        val db = dbHelper.writableDatabase
        db.transaction {
            val cv = WallpaperRowMapper.toContentValues(record)
            insertWithOnConflict("wallpapers", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override fun getWallpaperById(id: String): WallpaperRecord? {
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT * FROM wallpapers WHERE id = ? LIMIT 1", arrayOf(id)).use { c ->
            return if (c.moveToFirst()) WallpaperRowMapper.fromCursor(c) else null
        }
    }

    override fun getAllWallpapers(): List<WallpaperRecord> {
        val db = dbHelper.readableDatabase
        val out = ArrayList<WallpaperRecord>()
        db.rawQuery("SELECT * FROM wallpapers ORDER BY retrieved_at DESC", null).use { c ->
            while (c.moveToNext()) {
                out.add(WallpaperRowMapper.fromCursor(c))
            }
        }
        return out
    }

    override fun deleteWallpaper(id: String) {
        writes.markWritten()
        val db = dbHelper.writableDatabase
        db.delete("wallpapers", "id = ?", arrayOf(id))
    }

    override fun recordRejection(rejection: RejectionRecord) {
        writes.markWritten()
        val db = dbHelper.writableDatabase
        db.transaction {
            val cv = ContentValues().apply {
                put("asset_id", rejection.assetId)
                put("source_id", rejection.sourceId)
                put("reason_code", rejection.reasonCode)
                put("explanation", rejection.explanation)
                put("timestamp_ms", rejection.timestampEpochMs)
                put("is_permanent", if (rejection.isPermanent) 1 else 0)
                put("display_signature", rejection.displaySignature)
            }
            insertWithOnConflict("rejections", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            execSQL(
                """
                DELETE FROM rejections WHERE is_permanent = 0 AND asset_id NOT IN (
                    SELECT asset_id FROM rejections WHERE is_permanent = 0
                    ORDER BY timestamp_ms DESC LIMIT $MAX_REJECTION_LOG_ENTRIES
                )
                """.trimIndent()
            )
        }
    }

    override fun clearTransientRejections() {
        writes.markWritten()
        dbHelper.writableDatabase.execSQL("DELETE FROM rejections WHERE is_permanent = 0")
    }

    override fun removeSoftSafetyRecords(reasonCodes: Set<String>) {
        writes.markWritten()
        if (reasonCodes.isEmpty()) return
        val marks = reasonCodes.joinToString(",") { "?" }
        val args = reasonCodes.toTypedArray()
        dbHelper.writableDatabase.transaction {
            execSQL("DELETE FROM blocklist WHERE reason_code IN ($marks)", args)
            execSQL("DELETE FROM rejections WHERE reason_code IN ($marks)", args)
        }
    }

    override fun getAllRejections(): List<RejectionRecord> {
        val db = dbHelper.readableDatabase
        val out = ArrayList<RejectionRecord>()
        db.rawQuery("SELECT * FROM rejections ORDER BY timestamp_ms DESC", null).use { c ->
            while (c.moveToNext()) {
                val permIdx = c.getColumnIndex("is_permanent")
                val sigIdx = c.getColumnIndex("display_signature")
                out.add(
                    RejectionRecord(
                        assetId = c.getString(c.getColumnIndexOrThrow("asset_id")),
                        sourceId = c.getString(c.getColumnIndexOrThrow("source_id")),
                        reasonCode = c.getString(c.getColumnIndexOrThrow("reason_code")),
                        explanation = c.getString(c.getColumnIndexOrThrow("explanation")),
                        timestampEpochMs = c.getLong(c.getColumnIndexOrThrow("timestamp_ms")),
                        isPermanent = if (permIdx >= 0) c.getInt(permIdx) == 1 else true,
                        displaySignature = if (sigIdx >= 0) c.getString(sigIdx) ?: "" else ""
                    )
                )
            }
        }
        return out
    }

    override fun addBlocklistEntry(entry: BlocklistEntry) {
        writes.markWritten()
        val db = dbHelper.writableDatabase
        val key = entry.assetId.ifBlank { entry.sha256.ifBlank { entry.dHash.ifBlank { "blk_${entry.createdAtEpochMs}" } } }
        val cv = ContentValues().apply {
            put("entry_id", key)
            put("asset_id", entry.assetId)
            put("sha256", entry.sha256)
            put("perceptual_hash", entry.dHash)
            put("source_id", entry.sourceId)
            put("reason_code", entry.reason)
            put("timestamp_ms", entry.createdAtEpochMs)
        }
        db.insertWithOnConflict("blocklist", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun getAllBlocklistEntries(): List<BlocklistEntry> {
        val db = dbHelper.readableDatabase
        val out = ArrayList<BlocklistEntry>()
        db.rawQuery("SELECT * FROM blocklist ORDER BY timestamp_ms DESC", null).use { c ->
            while (c.moveToNext()) {
                val srcIdx = c.getColumnIndex("source_id")
                out.add(
                    BlocklistEntry(
                        assetId = c.getString(c.getColumnIndexOrThrow("asset_id")),
                        sha256 = c.getString(c.getColumnIndexOrThrow("sha256")),
                        dHash = c.getString(c.getColumnIndexOrThrow("perceptual_hash")),
                        sourceId = if (srcIdx >= 0) c.getString(srcIdx) ?: "" else "",
                        reason = c.getString(c.getColumnIndexOrThrow("reason_code")),
                        createdAtEpochMs = c.getLong(c.getColumnIndexOrThrow("timestamp_ms"))
                    )
                )
            }
        }
        return out
    }

    override fun wipe() {
        runCatching { dbHelper.close() }
        // Removes the database and its -wal, -shm and -journal files.
        appContext.deleteDatabase(databaseName)
        forgetSharedInstance()
    }

    override fun checkpoint() {
        runCatching {
            dbHelper.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
                c.moveToFirst()
            }
        }
    }
}
