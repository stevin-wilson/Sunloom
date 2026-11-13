package com.sunloom.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Pins how [WallpaperRowMapper.fromColumnMap] reads a stored row, including every row with one column missing, one
 * column of the wrong type, or an unknown enum name: the fail-closed decoding (a damaged row must never turn into a
 * usable wallpaper) is the point. The expected text is recorded in `src/test/resources/golden/wallpaper_row_mapper.txt`;
 * delete that file and run once to record it again after an intended change.
 */
class WallpaperRowMapperGoldenTest {

    private val richRow: Map<String, Any?> = linkedMapOf(
        "id" to "wiki-42", "title" to "Mount Fuji", "description" to "A calm morning",
        "raw_categories_tags" to "landscape\u001Fmountain\u001F\u001Fsnow", "category" to "LANDSCAPES_NATURE",
        "local_path" to "/data/w/wiki-42.jpg", "cropped_path" to "/data/w/wiki-42.jpg", "file_size" to 123456L,
        "is_favorite" to 1, "is_rejected" to 0, "is_baseline" to 0, "applied_count" to 3, "last_applied" to 1762280000000L,
        "status" to "PREPARED", "decision_explanation" to "ok",
        "source_id" to "wikimedia_featured", "source_name" to "Wikimedia", "original_url" to "https://example.org/x",
        "creator_name" to "Someone", "creator_url" to "https://example.org/u", "license_type" to "CC_BY_SA",
        "license_url" to "", "license_version" to "4.0", "attribution" to "Someone, CC BY-SA", "retrieved_at" to 1762280000001L,
        "orig_w" to 6000, "orig_h" to 4000, "rend_w" to 3840, "rend_h" to 2400, "safety_class" to "SAFE",
        "safety_source" to "curated", "sha256" to "abc", "perceptual_hash" to "0123", "adapter_version" to "a-2",
        "safety_gate_version" to "v0.6.0", "gate_layer_outcomes" to "L1:PASS;L2:PASS", "is_eligible" to 1,
        "rejection_reason_code" to null, "global_lum" to 0.4, "desktop_lum" to 0.3, "clock_lum" to 0.35,
        "glare_frac" to 0.01, "contrast_std" to 0.2, "brightness_class" to "DARK", "eval_explanation" to "dark",
        "lightness_p50" to 30.0, "label_contrast" to 7.5, "warmth" to -0.2, "hue_bucket" to 4,
        "crop_x" to 10, "crop_y" to 20, "crop_w" to 3000, "crop_h" to 2000, "focal_x" to 0.3, "focal_y" to 0.6,
        "uses_blurred_fill" to 1, "native_coverage" to 0.9
    )

    private fun show(row: Map<String, Any?>) = WallpaperRowMapper.fromColumnMap(row).toString()

    private fun render(): String = buildString {
        append("rich -> ${show(richRow)}\n")
        append("empty -> ${show(emptyMap())}\n")
        append("roundtrip -> ${show(WallpaperRowMapper.toColumnMap(WallpaperRowMapper.fromColumnMap(richRow)))}\n")
        for (key in richRow.keys) {
            append("missing[$key] -> ${show(richRow - key)}\n")
            append("wrongtype[$key] -> ${show(richRow + (key to true))}\n")
            append("string[$key] -> ${show(richRow + (key to "zzz"))}\n")
            append("number[$key] -> ${show(richRow + (key to 7))}\n")
            append("blank[$key] -> ${show(richRow + (key to ""))}\n")
        }
        for ((key, values) in mapOf(
            "status" to listOf("REJECTED_SAFETY_OR_QUALITY", "DISABLED_BY_USER", "APPLIED_SUCCESSFULLY"),
            "license_type" to listOf("UNKNOWN_OR_RESTRICTED", "CC0", "PUBLIC_DOMAIN", "USER_OWNED"),
            "safety_class" to listOf("UNCERTAIN", "MISSING", "CONFLICTING", "NSFW_OR_UNSAFE", "SAFE"),
            "brightness_class" to listOf("LIGHT", "NEUTRAL"),
            "is_rejected" to listOf(1, 0, 2),
            "is_eligible" to listOf(0, 1, 5),
            "crop_w" to listOf(0, -4),
            "adapter_version" to listOf("   ")
        )) for (v in values) append("set[$key]=$v -> ${show(richRow + (key to v))}\n")
    }

    @Test
    fun `fromColumnMap output is unchanged for complete, damaged and unknown rows`() {
        val golden = File("src/test/resources/golden/wallpaper_row_mapper.txt")
        val actual = render()
        if (!golden.exists()) {
            golden.parentFile.mkdirs()
            golden.writeText(actual)
            fail("Recorded ${golden.path}; run the test again.")
        }
        assertEquals(golden.readText().lines(), actual.lines())
    }
}
