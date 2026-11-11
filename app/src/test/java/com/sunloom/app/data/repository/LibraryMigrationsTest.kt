package com.sunloom.app.data.repository

import com.sunloom.app.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article XIV: schema upgrades are non-destructive and honest. A column is added only when it is missing, so a real
 * failure (a missing table, a full disk) is never swallowed, and a database an older build wrote to after a downgrade
 * (version lowered, columns still there) upgrades again without error.
 */
@Requirement("XIV.1")
class LibraryMigrationsTest {

    private val v1Wallpapers = setOf(
        "id", "title", "category", "local_path", "cropped_path", "file_size", "is_favorite", "is_rejected", "is_baseline",
        "applied_count", "last_applied", "status", "decision_explanation", "source_id", "source_name", "original_url",
        "creator_name", "creator_url", "license_type", "attribution", "retrieved_at", "orig_w", "orig_h", "rend_w",
        "rend_h", "safety_class", "safety_source", "sha256", "is_eligible", "global_lum", "desktop_lum", "clock_lum",
        "glare_frac", "contrast_std", "brightness_class", "eval_explanation"
    )
    private val v1Rejections = setOf("asset_id", "source_id", "reason_code", "explanation", "timestamp_ms")

    private val v3FeatureColumns = setOf("lightness_p50", "label_contrast", "warmth", "hue_bucket")

    private fun plan(old: Int, wallpapers: Set<String>, rejections: Set<String>): List<String> =
        LibraryMigrations.statements(old) { table ->
            when (table) {
                "wallpapers" -> wallpapers
                "rejections" -> rejections
                else -> emptySet()
            }
        }

    private fun alters(statements: List<String>) = statements.filter { it.startsWith("ALTER TABLE") }

    @Test
    fun `from version 1 every missing column is added and the blocklist is created`() {
        val statements = plan(1, v1Wallpapers, v1Rejections)

        val wallpaperAlters = alters(statements).filter { it.contains("TABLE wallpapers") }
        val rejectionAlters = alters(statements).filter { it.contains("TABLE rejections") }
        assertEquals("v2 adds 17 wallpaper columns and v3 adds 4", 21, wallpaperAlters.size)
        assertEquals(2, rejectionAlters.size)
        assertTrue(statements.any { it.contains("CREATE TABLE IF NOT EXISTS blocklist") })
        assertTrue(statements.any { it.contains("idx_blocklist_sha256") })
        assertTrue(wallpaperAlters.any { it.contains("lightness_p50 REAL NOT NULL DEFAULT -1.0") })
        assertTrue(wallpaperAlters.any { it.contains("gate_layer_outcomes TEXT NOT NULL DEFAULT 'L1:PASS;L2:PASS;L3:PASS;L4:PASS'") })
    }

    @Test
    fun `from version 2 only the feature columns are added`() {
        val v2Wallpapers = v1Wallpapers + setOf(
            "description", "raw_categories_tags", "license_url", "license_version", "perceptual_hash", "adapter_version",
            "safety_gate_version", "gate_layer_outcomes", "rejection_reason_code", "crop_x", "crop_y", "crop_w", "crop_h",
            "focal_x", "focal_y", "uses_blurred_fill", "native_coverage"
        )
        val statements = plan(2, v2Wallpapers, v1Rejections + setOf("is_permanent", "display_signature"))

        assertEquals(4, alters(statements).size)
        assertTrue(alters(statements).all { stmt -> v3FeatureColumns.any { stmt.contains(it) } })
        assertFalse("the blocklist already exists from version 2", statements.any { it.contains("CREATE TABLE") })
    }

    @Test
    fun `a column that already exists is never added again`() {
        // An older build opened a version-3 database, lowered its version to 2, and left the v3 columns in place.
        val everything = v1Wallpapers + v3FeatureColumns + setOf(
            "description", "raw_categories_tags", "license_url", "license_version", "perceptual_hash", "adapter_version",
            "safety_gate_version", "gate_layer_outcomes", "rejection_reason_code", "crop_x", "crop_y", "crop_w", "crop_h",
            "focal_x", "focal_y", "uses_blurred_fill", "native_coverage"
        )
        val statements = plan(2, everything, v1Rejections + setOf("is_permanent", "display_signature"))

        assertTrue("nothing to add, so nothing can fail with 'duplicate column'", alters(statements).isEmpty())
    }

    @Test
    fun `column names are compared without regard to case`() {
        val upper = v1Wallpapers.map { it.uppercase() }.toSet()
        val statements = plan(1, upper + v3FeatureColumns.map { it.uppercase() }, v1Rejections)
        assertTrue(alters(statements).none { it.contains("lightness_p50") })
    }

    @Test
    fun `a current database needs nothing`() {
        assertEquals(emptyList<String>(), plan(3, emptySet(), emptySet()))
    }

    @Test
    fun `a missing table still produces the add so the failure surfaces`() {
        // No columns known for the table: the ALTER is emitted and the database rejects it ("no such table"),
        // instead of being skipped or swallowed. The open then fails and storage is reported unavailable.
        val statements = plan(1, emptySet(), emptySet())
        assertTrue(alters(statements).isNotEmpty())
    }
}
