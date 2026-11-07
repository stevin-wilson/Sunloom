package com.sunloom.app.data.repository

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.data.sources.HttpClient
import com.sunloom.app.data.sources.WallpaperSourceAdapter
import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article X §3 & XI §3: an image downloaded too small for a newly docked display is paused, not lost. When the
 * source later offers a larger rendition of it, the sync fetches that, keeping the favourite flag and history.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
@Requirement("X.3")
class LocalLibraryRefetchTest {

    private lateinit var context: Context
    private val laptop = DisplayProfile(0, "Laptop", 2560, 1600, densityDpi = 220, isPrimary = true)
    private val ultrawide = DisplayProfile(1, "Ultrawide", 3440, 1440, densityDpi = 160, isPrimary = false)

    private var serveLarge = false
    private var downloads = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /** The same picture at two sizes: the mosaic depends only on [seed], so both renditions share one dHash. */
    private fun jpeg(seed: String, width: Int, height: Int): ByteArray {
        val rng = java.util.Random(seed.hashCode().toLong())
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val cells = IntArray(9 * 8) { 20 + rng.nextInt(215) }
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val v = cells[(y * 8 / height) * 9 + (x * 9 / width)]
            pixels[y * width + x] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray().also { bmp.recycle() }
    }

    private val client = object : HttpClient {
        override suspend fun getText(url: String): String = "{}"
        override suspend fun getBytes(url: String, maxBytes: Int): ByteArray {
            downloads++
            return if (serveLarge) jpeg("pic", 3500, 1800) else jpeg("pic", 1700, 1100)
        }
    }

    private class Adapter(var advertisedWidth: Int, var advertisedHeight: Int) : WallpaperSourceAdapter {
        override val sourceId = "artic_cc0"
        override val sourceName = "artic_cc0"
        override val licenseSummary = "CC0"
        override fun withHttpClient(client: HttpClient): WallpaperSourceAdapter = this
        override suspend fun fetchCandidates(
            enabledCategories: Set<SafeCategory>,
            limit: Int,
            offset: Int,
            targetWidthPx: Int
        ) = listOf(
            CandidateAsset(
                id = "pic-1",
                title = "Mountain Valley Clouds",
                description = "Landscape",
                category = SafeCategory.SCENIC_FINE_ART,
                rawCategoriesAndTags = listOf("landscape", "mountains"),
                downloadUrl = "https://www.artic.edu/iiif/2/pic/full/1686,/0/default.jpg",
                provenance = ProvenanceRecord(
                    sourceId = "artic_cc0", sourceName = "artic_cc0", assetId = "pic-1",
                    originalPageUrl = "https://www.artic.edu/artworks/pic-1", creatorName = "Artist",
                    creatorPageUrl = "", licenseType = LicenseType.CC0, attributionText = "CC0",
                    retrievedAtEpochMs = 1762280000000L, originalWidth = advertisedWidth,
                    originalHeight = advertisedHeight, renditionWidth = 0, renditionHeight = 0,
                    safetyClassification = SafetyClassification.SAFE, safetyClassificationSource = "artic_cc0",
                    sha256Checksum = ""
                )
            )
        )
    }

    private fun repo(adapter: Adapter) =
        LocalLibraryRepository(context = context, httpClient = client, sourceAdapters = listOf(adapter), random = kotlin.random.Random(1))

    @Test
    fun `an image paused for a docked display is fetched again when the source offers a larger rendition`() =
        kotlinx.coroutines.test.runTest {
            val adapter = Adapter(1700, 1100)
            val repo = repo(adapter)
            assertEquals(1, repo.refreshOnlineCatalog(laptop, connectedDisplays = listOf(laptop)))
            repo.recordWallpaperApplied("pic-1")
            repo.setFavorite("pic-1", true)

            // Dock the ultrawide: the 1700 px picture is now too small for it and is paused.
            val docked = listOf(laptop, ultrawide)
            assertEquals(0, repo.refreshOnlineCatalog(ultrawide, connectedDisplays = docked))
            assertFalse(repo.getWallpaperById("pic-1")!!.evaluation.isEligible)

            // The source now advertises, and serves, a larger rendition of the same picture.
            adapter.advertisedWidth = 3500
            adapter.advertisedHeight = 1800
            serveLarge = true
            assertEquals(1, repo.refreshOnlineCatalog(ultrawide, connectedDisplays = docked))

            val refreshed = repo.getWallpaperById("pic-1")!!
            assertEquals(3500, refreshed.provenance.originalWidth)
            assertTrue(refreshed.evaluation.isEligible)
            assertTrue(refreshed.isFavorite)
            assertEquals(1, refreshed.appliedCount)
        }

    @Test
    fun `a paused image is not downloaded again when the source offers nothing larger`() = kotlinx.coroutines.test.runTest {
        val adapter = Adapter(1700, 1100)
        val repo = repo(adapter)
        repo.refreshOnlineCatalog(laptop, connectedDisplays = listOf(laptop))
        val docked = listOf(laptop, ultrawide)
        repo.refreshOnlineCatalog(ultrawide, connectedDisplays = docked)
        val downloadsWhilePaused = downloads

        assertEquals(0, repo.refreshOnlineCatalog(ultrawide, connectedDisplays = docked))

        assertEquals(downloadsWhilePaused, downloads)
        assertFalse(repo.getWallpaperById("pic-1")!!.evaluation.isEligible)
    }
}
