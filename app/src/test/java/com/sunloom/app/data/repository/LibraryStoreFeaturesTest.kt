package com.sunloom.app.data.repository

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.ImageEvaluationResult
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.WallpaperRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The per-image features used for variety (lightness, label contrast, warmth, hue) survive storage and upgrades. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LibraryStoreFeaturesTest {

    private fun record(id: String, evaluation: ImageEvaluationResult? = null) = WallpaperRecord(
        id = id, title = id, category = SafeCategory.SCENIC_FINE_ART,
        localFilePath = "/tmp/$id.jpg", croppedFilePath = "/tmp/$id.jpg", fileSizeBytes = 100L,
        provenance = ProvenanceRecord(
            sourceId = "met_museum_cc0", sourceName = "m", assetId = id, originalPageUrl = "https://e.org",
            creatorName = "c", creatorPageUrl = "", licenseType = LicenseType.CC0, attributionText = "CC0",
            retrievedAtEpochMs = 1L, originalWidth = 4000, originalHeight = 2500, renditionWidth = 2560,
            renditionHeight = 1600, safetyClassification = SafetyClassification.SAFE,
            safetyClassificationSource = "t", sha256Checksum = "x"
        ),
        evaluation = evaluation ?: ImageEvaluationResult(
            true, null, 0.4, 0.3, 0.3, 0.0, 0.1, BrightnessClass.NEUTRAL,
            CropRect(0, 0, 2560, 1600, 0.5, 0.5, false, 1.0), "t",
            lightnessP50 = 61.5, desktopLabelContrast = 5.25, warmth = 0.42, hueBucket = 2
        )
    )

    @Test
    fun `features round trip through the column mapper`() {
        val back = WallpaperRowMapper.fromColumnMap(WallpaperRowMapper.toColumnMap(record("a"))).evaluation
        assertEquals(61.5, back.lightnessP50, 1e-9)
        assertEquals(5.25, back.desktopLabelContrast, 1e-9)
        assertEquals(0.42, back.warmth, 1e-9)
        assertEquals(2, back.hueBucket)
    }

    @Test
    fun `rows written before the features existed read back as unknown`() {
        val legacy = WallpaperRowMapper.toColumnMap(record("old")) - setOf("lightness_p50", "label_contrast", "warmth", "hue_bucket")
        val back = WallpaperRowMapper.fromColumnMap(legacy).evaluation
        assertEquals(-1.0, back.lightnessP50, 1e-9)
        assertEquals(-1.0, back.desktopLabelContrast, 1e-9)
        assertEquals(0.0, back.warmth, 1e-9)
        assertEquals(-1, back.hueBucket)
    }

    @Test
    fun `the rejection log keeps enough history that retryable skips are not forgotten between syncs`() {
        assertEquals(true, SqliteLibraryStore.MAX_REJECTION_LOG_ENTRIES >= 1000)
    }

    @Test
    fun `pruning the rejection log never drops permanent rejections`() {
        val store = InMemoryLibraryStore()
        store.recordRejection(com.sunloom.app.domain.model.RejectionRecord("keep-me", "s", "USER_REJECTED", "x", 1L, true))
        repeat(SqliteLibraryStore.MAX_REJECTION_LOG_ENTRIES + 50) {
            store.recordRejection(com.sunloom.app.domain.model.RejectionRecord("soft-$it", "s", "NEAR_DUPLICATE_OF_LIBRARY", "x", 10L + it, false, "sig"))
        }
        val ids = store.getAllRejections().map { it.assetId }
        assertEquals(true, "keep-me" in ids)
        assertEquals(SqliteLibraryStore.MAX_REJECTION_LOG_ENTRIES, store.getAllRejections().count { !it.isPermanent })
    }
}
