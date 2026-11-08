package com.sunloom.app.data.repository

import com.sunloom.app.Requirement
import com.sunloom.app.domain.evaluator.ImageEvaluator
import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.CropRect
import com.sunloom.app.domain.model.ImageEvaluationResult
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.RejectionRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.model.UserPreferences
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.safety.SafetyGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Article VII §5: the pass works on the record as it is now, not as it was when the pass began. */
@Requirement("VII.5")
class RevalidatorTest {
    private fun stale(id: String) = WallpaperRecord(
        id = id, title = "t", category = SafeCategory.LANDSCAPES_NATURE,
        localFilePath = "/nonexistent/$id.jpg", croppedFilePath = "", fileSizeBytes = 10L,
        provenance = ProvenanceRecord(
            sourceId = "wikimedia_featured", sourceName = "s", assetId = id,
            originalPageUrl = "https://commons.wikimedia.org/wiki/File:x.jpg", creatorName = "c", creatorPageUrl = "",
            licenseType = LicenseType.CC0, attributionText = "", retrievedAtEpochMs = 0L, originalWidth = 100,
            originalHeight = 100, renditionWidth = 0, renditionHeight = 0,
            safetyClassification = SafetyClassification.SAFE, safetyClassificationSource = "s", sha256Checksum = "x",
            safetyGateVersion = "old"
        ),
        evaluation = ImageEvaluationResult(
            isEligible = true, rejectionReasonCode = null, globalLuminance = 0.5, desktopZoneLuminance = 0.5,
            lockClockZoneLuminance = 0.5, glareFractionInSafeZones = 0.0, contrastStdDev = 0.1,
            brightnessClass = BrightnessClass.LIGHT, cropRect = CropRect(0, 0, 100, 100, 0.5, 0.5, false, 1.0),
            explanation = "e"
        )
    )

    /** A host whose library already holds a newer version of each record than the snapshot the pass started from. */
    private class FreshHost(private val current: Map<String, WallpaperRecord?>) : RevalidationHost {
        val upserts = mutableListOf<WallpaperRecord>()
        val rejections = mutableListOf<RejectionRecord>()
        override fun upsert(record: WallpaperRecord) { upserts += record }
        override fun invalidateActive(wallpaperId: String) = Unit
        override fun recordRejection(rejection: RejectionRecord) { rejections += rejection }
        override fun blocklist(
            assetId: String?, sha256: String?, perceptualHash: String?, sourceId: String, reasonCode: String,
            timestampEpochMs: Long
        ) = Unit
        override fun <T> withCurrent(id: String, block: (WallpaperRecord) -> T): T? = current[id]?.let(block)
    }

    private fun revalidator(host: RevalidationHost) = Revalidator(SafetyGate(), ImageEvaluator(), host)
    private fun blocked() = Revalidator.Blocked(mutableSetOf(), mutableSetOf(), mutableSetOf())

    @Test
    fun `a record rejected since the pass began is not revalidated or written back`() {
        val snapshot = stale("a")
        val host = FreshHost(mapOf("a" to snapshot.copy(isRejected = true)))
        val evicted = revalidator(host).run(listOf(snapshot), display = null, UserPreferences(), blocked())
        assertEquals(0, evicted)
        assertTrue("nothing is written from the stale snapshot", host.upserts.isEmpty())
        assertTrue(host.rejections.isEmpty())
    }

    @Test
    fun `a record deleted since the pass began is skipped`() {
        val host = FreshHost(mapOf("gone" to null))
        assertEquals(0, revalidator(host).run(listOf(stale("gone")), display = null, UserPreferences(), blocked()))
        assertTrue(host.upserts.isEmpty())
    }

    @Test
    fun `the current version of the record is the one that is judged`() {
        val snapshot = stale("a")
        val nowCurrent = snapshot.copy(provenance = snapshot.provenance.copy(safetyGateVersion = SafetyGate.COMPOSITE_GATE_VERSION))
        val host = FreshHost(mapOf("a" to nowCurrent))
        assertEquals("already on the current version by now", 0, revalidator(host).run(listOf(snapshot), null, UserPreferences(), blocked()))
        assertTrue(host.upserts.isEmpty())
    }

    @Test
    fun `a stale record whose file is missing is evicted through the host`() {
        val snapshot = stale("m")
        val host = FreshHost(mapOf("m" to snapshot))
        assertEquals(1, revalidator(host).run(listOf(snapshot), null, UserPreferences(), blocked()))
        assertEquals("MISSING_LOCAL_PAYLOAD", host.rejections.single().reasonCode)
    }
}
