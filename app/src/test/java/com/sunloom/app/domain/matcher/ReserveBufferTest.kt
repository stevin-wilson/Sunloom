package com.sunloom.app.domain.matcher

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.ImageEvaluationResult
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.WallpaperRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Article X §3 (v0.14): the reserve is topped up towards a target of unseen images for each mode, not only when nearly empty. */
@Requirement("X.3")
class ReserveBufferTest {

    private var n = 0
    private fun row(
        brightness: BrightnessClass,
        source: String = "wikimedia_featured",
        applied: Int = 0,
        size: Long = 1_000_000L,
        category: SafeCategory = SafeCategory.LANDSCAPES_NATURE,
        rejected: Boolean = false,
        eligible: Boolean = true
    ) = WallpaperRecord(
        id = "r${n++}", title = "t", category = category,
        localFilePath = if (size > 0) "/tmp/x.jpg" else "", croppedFilePath = "", fileSizeBytes = size,
        appliedCount = applied, isRejected = rejected,
        provenance = ProvenanceRecord(
            sourceId = source, sourceName = source, assetId = "a", originalPageUrl = "https://e.org",
            creatorName = "c", creatorPageUrl = "", licenseType = LicenseType.CC0, attributionText = "CC0",
            retrievedAtEpochMs = 1L, originalWidth = 4000, originalHeight = 2500, renditionWidth = 2560,
            renditionHeight = 1600, safetyClassification = SafetyClassification.SAFE,
            safetyClassificationSource = "t", sha256Checksum = "x"
        ),
        evaluation = ImageEvaluationResult(
            eligible, null, 0.5, 0.5, 0.5, 0.0, 0.1, brightness,
            CropRect(0, 0, 2560, 1600, 0.5, 0.5, false, 1.0), "t"
        )
    )

    private val all = SafeCategory.entries.toSet()

    @Test
    fun `neutral images count towards both modes and only usable unseen online images count`() {
        val rows = listOf(
            row(BrightnessClass.LIGHT), row(BrightnessClass.LIGHT), row(BrightnessClass.NEUTRAL), row(BrightnessClass.DARK),
            row(BrightnessClass.LIGHT, applied = 3),            // seen
            row(BrightnessClass.LIGHT, source = "seed_reserve"), // offline seed
            row(BrightnessClass.LIGHT, source = "user_local"),   // the user's own photo
            row(BrightnessClass.LIGHT, size = 0),                // no file
            row(BrightnessClass.LIGHT, rejected = true),
            row(BrightnessClass.LIGHT, eligible = false)
        )
        assertEquals(3, ReserveBuffer.unseenFor(rows, BrightnessClass.LIGHT, all))
        assertEquals(2, ReserveBuffer.unseenFor(rows, BrightnessClass.DARK, all))
    }

    @Test
    fun `categories the user turned off do not count as stock`() {
        val rows = listOf(row(BrightnessClass.LIGHT, category = SafeCategory.SPACE_ASTRONOMY), row(BrightnessClass.LIGHT))
        assertEquals(1, ReserveBuffer.unseenFor(rows, BrightnessClass.LIGHT, setOf(SafeCategory.LANDSCAPES_NATURE)))
    }

    @Test
    fun `a top up is needed until both modes reach the target`() {
        val target = ReserveBuffer.TARGET_UNSEEN_PER_MODE
        val lightOnly = List(target) { row(BrightnessClass.LIGHT) }
        assertTrue("dark side is empty", ReserveBuffer.needsTopUp(lightOnly, all, bytesUsed = 1L, maxBytes = Long.MAX_VALUE))

        val both = lightOnly + List(target) { row(BrightnessClass.DARK) }
        assertFalse(ReserveBuffer.needsTopUp(both, all, bytesUsed = 1L, maxBytes = Long.MAX_VALUE))

        val oneShort = List(target - 1) { row(BrightnessClass.NEUTRAL) }
        assertTrue(ReserveBuffer.needsTopUp(oneShort, all, bytesUsed = 1L, maxBytes = Long.MAX_VALUE))
        assertFalse(ReserveBuffer.needsTopUp(oneShort + row(BrightnessClass.NEUTRAL), all, bytesUsed = 1L, maxBytes = Long.MAX_VALUE))
    }

    @Test
    fun `no top up when the cache is nearly full because new images would only evict old ones`() {
        assertFalse(ReserveBuffer.needsTopUp(emptyList(), all, bytesUsed = 950, maxBytes = 1000))
        assertTrue(ReserveBuffer.needsTopUp(emptyList(), all, bytesUsed = 850, maxBytes = 1000))
    }

    @Test
    fun `the target is a real buffer and not the former three`() {
        assertTrue(ReserveBuffer.TARGET_UNSEEN_PER_MODE >= 10)
    }

    @Test
    fun `fetching stops once enough unseen images are waiting even if one mode stays short`() {
        // Dark images are rare: plenty of unseen Light, no Dark. Without a ceiling this would fetch until the cache is full.
        val plenty = List(ReserveBuffer.MAX_UNSEEN_STOCK) { row(BrightnessClass.LIGHT) }
        assertFalse(ReserveBuffer.needsTopUp(plenty, all, bytesUsed = 1L, maxBytes = Long.MAX_VALUE))
        val almost = List(ReserveBuffer.MAX_UNSEEN_STOCK - 1) { row(BrightnessClass.LIGHT) }
        assertTrue(ReserveBuffer.needsTopUp(almost, all, bytesUsed = 1L, maxBytes = Long.MAX_VALUE))
    }

    @Test
    fun `images that were already shown do not block a top up`() {
        // Everything fetched so far has been shown: the user needs fresh images, however many are stored.
        val allSeen = List(300) { row(BrightnessClass.LIGHT, applied = 2) }
        assertTrue(ReserveBuffer.needsTopUp(allSeen, all, bytesUsed = 1L, maxBytes = Long.MAX_VALUE))
    }

    @Test
    fun `a side held by a chosen picture does not ask for a top up`() {
        val target = ReserveBuffer.TARGET_UNSEEN_PER_MODE
        val lightOnly = List(target) { row(BrightnessClass.LIGHT) }
        fun top(held: Set<BrightnessClass>) =
            ReserveBuffer.needsTopUp(lightOnly, all, bytesUsed = 1L, maxBytes = Long.MAX_VALUE, heldSides = held)

        assertTrue("dark side empty and not held", top(emptySet()))
        assertFalse("the evening is chosen, so the dark side never rotates", top(setOf(BrightnessClass.DARK)))
        assertTrue("holding the already-full side changes nothing", top(setOf(BrightnessClass.LIGHT)))
    }

    @Test
    fun `with both sides held no top up is needed`() {
        assertFalse(
            ReserveBuffer.needsTopUp(
                emptyList(), all, bytesUsed = 1L, maxBytes = Long.MAX_VALUE,
                heldSides = setOf(BrightnessClass.LIGHT, BrightnessClass.DARK)
            )
        )
    }
}
