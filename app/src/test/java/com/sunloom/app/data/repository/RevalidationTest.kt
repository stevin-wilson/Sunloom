package com.sunloom.app.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.safety.SafetyGate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article VII §§5–6 / Schedule B: when the gate version changes, stored pictures are re-judged from their stored
 * metadata and local bytes. What fails is evicted (and, when the picture itself is at fault, refused for good); what
 * could not be judged for a reason that is not the picture's fault is evicted without a permanent ban.
 */
@Requirement("VII.5")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class RevalidationTest {
    private lateinit var repository: LocalLibraryRepository
    private val display = DisplayProfile(0, "GoogleBook Built-in Display", 2560, 1600)
    private val staleVersion = "v0.1.0"

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        repository = LocalLibraryRepository(context)
    }

    private fun seed(): WallpaperRecord = repository.ensureSeedReserveLoaded(display).first()

    /** A fetched-looking record pointing at [file], marked as judged under an old gate version. */
    private fun stale(id: String, file: File, version: String = staleVersion, base: WallpaperRecord): WallpaperRecord {
        // `base` is required on purpose: loading the seed reserve ends with a revalidation pass of its own, so build
        // every stale record from one seed and store them together.
        return base.copy(
            id = id,
            title = "Glacier Horizon Sunrise",
            rawCategoriesAndTags = listOf("landscape", "glacier"),
            localFilePath = file.absolutePath,
            croppedFilePath = file.absolutePath,
            fileSizeBytes = 5000L,
            provenance = base.provenance.copy(
                sourceId = "wikimedia_featured",
                assetId = id,
                originalPageUrl = "https://commons.wikimedia.org/wiki/File:Glacier.jpg",
                safetyGateVersion = version
            )
        )
    }

    @Test
    fun `a picture already judged under the current gate version is not looked at again`() {
        val seeds = repository.ensureSeedReserveLoaded(display)
        val before = seeds.associate { it.id to it.decisionExplanation }
        assertEquals(0, repository.revalidateGateVersionIfNeeded(display))
        repository.getAllWallpapers().forEach { assertFalse(it.id, it.isRejected) }
        seeds.forEach { assertEquals(before[it.id], repository.getWallpaperById(it.id)!!.decisionExplanation) }
    }

    @Test
    fun `a stale seed that still passes is stamped with the current gate version and kept`() {
        val base = seed()
        repository.upsertWallpaper(base.copy(provenance = base.provenance.copy(safetyGateVersion = staleVersion)))
        assertEquals(0, repository.revalidateGateVersionIfNeeded(display))
        val after = repository.getWallpaperById(base.id)!!
        assertFalse(after.isRejected)
        assertEquals(SafetyGate.COMPOSITE_GATE_VERSION, after.provenance.safetyGateVersion)
        assertFalse(after.provenance.gateLayerOutcomes.contains("FAIL"))
    }

    @Test
    fun `rejected and baseline records are skipped even under an old gate version`() {
        val base = seed()
        val rejected = base.copy(id = "rej", isRejected = true, provenance = base.provenance.copy(safetyGateVersion = staleVersion))
        val baseline = base.copy(id = "base", isBaselineRestore = true, provenance = base.provenance.copy(safetyGateVersion = staleVersion))
        repository.upsertWallpaper(rejected)
        repository.upsertWallpaper(baseline)
        assertEquals(0, repository.revalidateGateVersionIfNeeded(display))
        assertEquals(staleVersion, repository.getWallpaperById("rej")!!.provenance.safetyGateVersion)
        assertEquals(staleVersion, repository.getWallpaperById("base")!!.provenance.safetyGateVersion)
    }

    @Test
    fun `a missing image file is evicted without a permanent ban, because the picture is not at fault`() {
        val gone = File(repository.wallpaperDir, "gone.jpg") // never written
        repository.upsertWallpaper(stale("missing-file", gone, base = seed()))

        assertEquals(1, repository.revalidateGateVersionIfNeeded(display))

        val evicted = repository.getWallpaperById("missing-file")!!
        assertTrue(evicted.isRejected)
        assertTrue(evicted.provenance.gateLayerOutcomes.contains("L3:FAIL"))
        val rejection = repository.getAllRejections().single { it.assetId == "missing-file" }
        assertEquals("MISSING_LOCAL_PAYLOAD", rejection.reasonCode)
        assertFalse("not permanent", rejection.isPermanent)
        assertFalse("not blocklisted", "missing-file" in repository.getPermanentBlockedAssetIds())
    }

    @Test
    fun `bytes that are not a picture are evicted and refused for good`() {
        val junk = File(repository.wallpaperDir, "junk.jpg").apply { writeBytes(ByteArray(5000) { 0x55 }) }
        repository.upsertWallpaper(stale("junk-bytes", junk, base = seed()))

        assertEquals(1, repository.revalidateGateVersionIfNeeded(display))

        val evicted = repository.getWallpaperById("junk-bytes")!!
        assertTrue(evicted.isRejected)
        assertTrue("the file is deleted", !junk.exists())
        assertTrue("refused for good", "junk-bytes" in repository.getPermanentBlockedAssetIds())
        assertNotNull(repository.getAllRejections().firstOrNull { it.assetId == "junk-bytes" && it.isPermanent })
        assertTrue("the checksum of what it was is blocklisted too", repository.getPermanentBlockedSha256s().isNotEmpty())
    }

    @Test
    fun `an eviction clears the active wallpaper id without claiming a replacement was applied`() {
        val junk = File(repository.wallpaperDir, "junk-active.jpg").apply { writeBytes(ByteArray(5000) { 0x55 }) }
        repository.upsertWallpaper(stale("junk-active", junk, base = seed()))
        repository.recordWallpaperApplied("junk-active")

        repository.revalidateGateVersionIfNeeded(display)

        assertEquals(null, repository.getCurrentWallpaperId())
    }

    @Test
    fun `every stale picture that fails is evicted in one pass, and a second pass finds nothing to do`() {
        val a = File(repository.wallpaperDir, "a.jpg").apply { writeBytes(ByteArray(5000) { 0x55 }) }
        val b = File(repository.wallpaperDir, "b.jpg").apply { writeBytes(ByteArray(5000) { 0x56 }) }
        val base = seed()
        repository.upsertWallpaper(stale("first", a, base = base))
        repository.upsertWallpaper(stale("second", b, base = base))

        assertEquals(2, repository.revalidateGateVersionIfNeeded(display))
        assertTrue(repository.getWallpaperById("first")!!.isRejected)
        assertTrue(repository.getWallpaperById("second")!!.isRejected)
        assertEquals(0, repository.revalidateGateVersionIfNeeded(display))
    }

    @Test
    fun `a picture that exists but cannot be read is left for the next pass, not evicted`() {
        val locked = File(repository.wallpaperDir, "locked.jpg").apply { writeBytes(ByteArray(5000) { 0x55 }) }
        org.junit.Assume.assumeTrue("needs a file this user cannot read", locked.setReadable(false) && !locked.canRead())
        repository.upsertWallpaper(stale("unreadable", locked, base = seed()))

        assertEquals(0, repository.revalidateGateVersionIfNeeded(display))

        val after = repository.getWallpaperById("unreadable")!!
        assertFalse("a read error is not the picture's fault", after.isRejected)
        assertEquals(staleVersion, after.provenance.safetyGateVersion)
        assertTrue(locked.exists())
    }
}
