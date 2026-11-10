package com.sunloom.app.data.repository

/**
 * The library database's upgrade steps, as plain data so they can be tested without a database (Article XIV).
 *
 * Every step only adds: a column, the blocklist table, an index. A column is added only when it is missing, which
 * covers one legitimate case without hiding anything else: an older build that opened a newer database lowers its
 * version but leaves the newer columns in place, so the next upgrade finds them already there. Any other failure
 * (no such table, a full disk) is left to throw; the open then fails and storage is reported unavailable instead of
 * the version moving on over a half-migrated schema.
 */
internal object LibraryMigrations {
    /** Added in version 2. */
    private val wallpaperColumnsV2 = listOf(
        "description TEXT NOT NULL DEFAULT ''",
        "raw_categories_tags TEXT NOT NULL DEFAULT ''",
        "license_url TEXT NOT NULL DEFAULT ''",
        "license_version TEXT NOT NULL DEFAULT ''",
        "perceptual_hash TEXT NOT NULL DEFAULT ''",
        "adapter_version TEXT NOT NULL DEFAULT '1.0.0'",
        "safety_gate_version TEXT NOT NULL DEFAULT 'v0.1.0'",
        "gate_layer_outcomes TEXT NOT NULL DEFAULT 'L1:PASS;L2:PASS;L3:PASS;L4:PASS'",
        "rejection_reason_code TEXT",
        "crop_x INTEGER NOT NULL DEFAULT 0",
        "crop_y INTEGER NOT NULL DEFAULT 0",
        "crop_w INTEGER NOT NULL DEFAULT 0",
        "crop_h INTEGER NOT NULL DEFAULT 0",
        "focal_x REAL NOT NULL DEFAULT 0.5",
        "focal_y REAL NOT NULL DEFAULT 0.5",
        "uses_blurred_fill INTEGER NOT NULL DEFAULT 0",
        "native_coverage REAL NOT NULL DEFAULT 1.0"
    )

    private val rejectionColumnsV2 = listOf(
        "is_permanent INTEGER NOT NULL DEFAULT 1",
        "display_signature TEXT NOT NULL DEFAULT ''"
    )

    /** Added in version 3: per-image features for variety (lightness, label contrast, warmth, hue); unknown on old rows. */
    private val wallpaperColumnsV3 = listOf(
        "lightness_p50 REAL NOT NULL DEFAULT -1.0",
        "label_contrast REAL NOT NULL DEFAULT -1.0",
        "warmth REAL NOT NULL DEFAULT 0.0",
        "hue_bucket INTEGER NOT NULL DEFAULT -1"
    )

    const val CREATE_BLOCKLIST = """
        CREATE TABLE IF NOT EXISTS blocklist (
            entry_id TEXT PRIMARY KEY,
            asset_id TEXT NOT NULL DEFAULT '',
            sha256 TEXT NOT NULL DEFAULT '',
            perceptual_hash TEXT NOT NULL DEFAULT '',
            source_id TEXT NOT NULL DEFAULT '',
            reason_code TEXT NOT NULL,
            timestamp_ms INTEGER NOT NULL
        )"""

    val INDEXES = listOf(
        "CREATE INDEX IF NOT EXISTS idx_wallpapers_retrieved_at ON wallpapers(retrieved_at DESC)",
        "CREATE INDEX IF NOT EXISTS idx_wallpapers_source_id ON wallpapers(source_id)",
        "CREATE INDEX IF NOT EXISTS idx_rejections_timestamp ON rejections(timestamp_ms DESC)",
        "CREATE INDEX IF NOT EXISTS idx_blocklist_sha256 ON blocklist(sha256)",
        "CREATE INDEX IF NOT EXISTS idx_blocklist_phash ON blocklist(perceptual_hash)",
        "CREATE INDEX IF NOT EXISTS idx_blocklist_asset ON blocklist(asset_id)"
    )

    /**
     * The statements that bring a database at [oldVersion] up to the current schema. [columnsOf] returns a table's
     * existing column names (empty when the table does not exist); only missing columns produce an `ALTER`.
     */
    fun statements(oldVersion: Int, columnsOf: (table: String) -> Set<String>): List<String> {
        val out = mutableListOf<String>()
        fun addMissing(table: String, specs: List<String>) {
            val existing = columnsOf(table).map { it.lowercase() }.toSet()
            for (spec in specs) {
                if (spec.substringBefore(' ').lowercase() !in existing) out += "ALTER TABLE $table ADD COLUMN $spec"
            }
        }
        if (oldVersion < 2) {
            addMissing("wallpapers", wallpaperColumnsV2)
            addMissing("rejections", rejectionColumnsV2)
            out += CREATE_BLOCKLIST.trimIndent().trim()
        }
        if (oldVersion < 3) addMissing("wallpapers", wallpaperColumnsV3)
        if (oldVersion < 3) out += INDEXES
        return out
    }
}
