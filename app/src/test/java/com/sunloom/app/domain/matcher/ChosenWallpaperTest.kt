package com.sunloom.app.domain.matcher

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.ImageEvaluationResult
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.model.WallpaperStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Article III §1 (v0.59): a person may choose one picture for the daytime and one for the evening. A chosen picture is
 * used when it can be shown at all, whatever its size or brightness class says, and is left alone when it cannot.
 */
@Requirement("III.1")
class ChosenWallpaperTest {
    private fun record(
        id: String,
        eligible: Boolean = true,
        rejected: Boolean = false,
        baseline: Boolean = false,
        status: WallpaperStatus = WallpaperStatus.PREPARED,
        path: String = "/tmp/$id.jpg"
    ) = WallpaperRecord(
        id = id, title = id, category = SafeCategory.SCENIC_FINE_ART,
        localFilePath = path, croppedFilePath = path, fileSizeBytes = if (path.isBlank()) 0L else 100L,
        provenance = ProvenanceRecord(
            sourceId = "met_museum_cc0", sourceName = "m", assetId = id, originalPageUrl = "https://e.org",
            creatorName = "c", creatorPageUrl = "", licenseType = LicenseType.CC0, attributionText = "CC0",
            retrievedAtEpochMs = 1L, originalWidth = 400, originalHeight = 300, renditionWidth = 400,
            renditionHeight = 300, safetyClassification = SafetyClassification.SAFE,
            safetyClassificationSource = "t", sha256Checksum = "x"
        ),
        evaluation = ImageEvaluationResult(
            eligible, if (eligible) null else "RESOLUTION_BELOW_DISPLAY_TARGET", 0.4, 0.3, 0.3, 0.0, 0.1,
            BrightnessClass.LIGHT, CropRect(0, 0, 400, 300, 0.5, 0.5, false, 1.0), "t"
        ),
        isRejected = rejected, isBaselineRestore = baseline, status = status
    )

    private fun resolve(
        prefs: UserPreferences,
        target: BrightnessClass,
        records: List<WallpaperRecord>,
        existing: (String) -> Boolean = { true }
    ): WallpaperRecord? =
        ChosenWallpaper.resolve(prefs, target, lookup = { id -> records.firstOrNull { it.id == id } }, fileExists = existing)

    private val day = UserPreferences(daytimePickId = "d")
    private val evening = UserPreferences(eveningPickId = "e")

    @Test
    fun `the daytime picture is used when the target is light`() {
        val d = record("d")
        assertEquals(d, resolve(day, BrightnessClass.LIGHT, listOf(d)))
    }

    @Test
    fun `the evening picture is used when the target is dark`() {
        val e = record("e")
        assertEquals(e, resolve(evening, BrightnessClass.DARK, listOf(e)))
    }

    @Test
    fun `each side uses only its own slot`() {
        val prefs = UserPreferences(daytimePickId = "d", eveningPickId = "e")
        val records = listOf(record("d"), record("e"))
        assertEquals("d", resolve(prefs, BrightnessClass.LIGHT, records)?.id)
        assertEquals("e", resolve(prefs, BrightnessClass.DARK, records)?.id)
        assertNull(resolve(day, BrightnessClass.DARK, records))
        assertNull(resolve(evening, BrightnessClass.LIGHT, records))
    }

    @Test
    fun `a balanced target has no slot`() {
        val prefs = UserPreferences(daytimePickId = "d", eveningPickId = "e")
        assertNull(resolve(prefs, BrightnessClass.NEUTRAL, listOf(record("d"), record("e"))))
    }

    @Test
    fun `an empty slot means automatic`() {
        assertNull(resolve(UserPreferences(), BrightnessClass.LIGHT, listOf(record("d"))))
        assertNull(resolve(UserPreferences(daytimePickId = ""), BrightnessClass.LIGHT, listOf(record("d"))))
    }

    @Test
    fun `a picture too small for the displays is still used`() {
        val small = record("d", eligible = false)
        assertEquals(small, resolve(day, BrightnessClass.LIGHT, listOf(small)))
    }

    @Test
    fun `a picture of the other brightness class is still used`() {
        // The record says LIGHT; it is chosen for the evening anyway.
        val bright = record("e")
        assertEquals(bright, resolve(evening, BrightnessClass.DARK, listOf(bright)))
    }

    @Test
    fun `a picture that is gone from the library is not usable`() {
        assertNull(resolve(day, BrightnessClass.LIGHT, emptyList()))
    }

    @Test
    fun `a rejected picture is not usable`() {
        assertNull(resolve(day, BrightnessClass.LIGHT, listOf(record("d", rejected = true))))
    }

    @Test
    fun `the baseline restore is not usable`() {
        assertNull(resolve(day, BrightnessClass.LIGHT, listOf(record("d", baseline = true))))
    }

    @Test
    fun `a picture switched off by the person is not usable`() {
        assertNull(resolve(day, BrightnessClass.LIGHT, listOf(record("d", status = WallpaperStatus.DISABLED_BY_USER))))
    }

    @Test
    fun `a picture whose file is gone is not usable`() {
        assertNull(resolve(day, BrightnessClass.LIGHT, listOf(record("d", path = ""))))
        assertNull(resolve(day, BrightnessClass.LIGHT, listOf(record("d")), existing = { false }))
    }

    @Test
    fun `the file check is made on the stored path`() {
        val checked = mutableListOf<String>()
        resolve(day, BrightnessClass.LIGHT, listOf(record("d", path = "/x/d.png"))) { checked += it; true }
        assertEquals(listOf("/x/d.png"), checked)
    }
}
