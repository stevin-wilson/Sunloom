package com.sunloom.app.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.data.sources.BundledSeedSourceAdapter
import com.sunloom.app.data.sources.SeedRenderer
import com.sunloom.app.domain.evaluator.ImageEvaluator
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.safety.SafetyGate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article IX §1 / XII §2: docking and undocking a monitor changes the display set, and rendering every offline seed again
 * each time is slow and warms the device. Renders are kept on disk for the last two sizes, so going back to a size that was
 * used a moment ago renders nothing.
 */
@Requirement("IX.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SeedRenderCacheTest {
    private lateinit var context: Context
    private lateinit var wallpaperDir: File
    private lateinit var cacheDir: File
    private val rendered = mutableListOf<String>()
    private val seedCount = BundledSeedSourceAdapter.SEED_SPECS.size

    private val laptop = DisplayProfile(0, "Built-in", 1000, 625, densityDpi = 160)
    private val monitor = DisplayProfile(1, "Monitor", 1200, 750, densityDpi = 160)
    private val small = DisplayProfile(2, "Small", 800, 500, densityDpi = 160)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        wallpaperDir = File(context.cacheDir, "seed_test_walls").apply { deleteRecursively(); mkdirs() }
        cacheDir = File(context.cacheDir, "seed_test_renders").apply { deleteRecursively() }
        rendered.clear()
        SeedReserve.clearSeedRenderCache()
    }

    private fun reserve(withCache: Boolean = true) = SeedReserve(
        wallpaperDir, SafetyGate(), ImageEvaluator(), maxRenderPixels = 640 * 400,
        renderCacheDir = if (withCache) cacheDir else null,
        render = { spec, w, h -> rendered += "${spec.id}@${w}x$h"; SeedRenderer.render(spec, w, h) }
    )

    /** Loads the seeds for [display] on top of what an earlier load left, as the repository does. */
    private fun load(reserve: SeedReserve, display: DisplayProfile, existing: List<WallpaperRecord> = emptyList()): List<WallpaperRecord> {
        val stored = linkedMapOf<String, WallpaperRecord>()
        existing.forEach { stored[it.id] = it }
        reserve.ensureLoaded(display, existing, emptySet(), emptySet(), emptySet()) { stored[it.id] = it }
        return stored.values.toList()
    }

    private fun geometryDirs() = cacheDir.listFiles { f -> f.isDirectory }.orEmpty()

    @Test
    fun `the first load renders every seed and keeps the renders`() {
        load(reserve(), laptop)

        assertEquals(seedCount, rendered.size)
        assertEquals(1, geometryDirs().size)
        assertEquals(seedCount, geometryDirs().single().listFiles()!!.size)
    }

    @Test
    fun `going back to a size used a moment ago renders nothing`() {
        val r = reserve()
        val onLaptop = load(r, laptop)
        val onMonitor = load(r, monitor, onLaptop)
        rendered.clear()
        SeedReserve.clearSeedRenderCache() // the in-memory cache cannot hold a full set at 4K; the disk cache must

        val back = load(r, laptop, onMonitor)

        assertEquals("undocking re-renders nothing", emptyList<String>(), rendered)
        assertEquals(seedCount, back.count { it.provenance.originalWidth == onLaptop.first().provenance.originalWidth })
    }

    @Test
    fun `a render read back from disk is the same picture`() {
        val first = load(reserve(), laptop).associate { it.id to it.provenance.sha256Checksum }
        SeedReserve.clearSeedRenderCache()
        rendered.clear()

        // A fresh library on the same size: everything comes from disk, and every checksum matches.
        val again = load(reserve(), laptop).associate { it.id to it.provenance.sha256Checksum }

        assertEquals(emptyList<String>(), rendered)
        assertEquals(first, again)
    }

    @Test
    fun `only the two most recent sizes are kept`() {
        val r = reserve()
        var records = load(r, laptop)
        Thread.sleep(20)
        records = load(r, monitor, records)
        Thread.sleep(20)
        load(r, small, records)

        assertEquals(2, geometryDirs().size)
        val sizes = geometryDirs().map { it.name }
        assertTrue("the newest size is kept", sizes.any { it.contains("800x500") })
        assertTrue("and the one before it", sizes.any { it.contains("1200x750") })
        assertFalse("the oldest is dropped", sizes.any { it.contains("1000x625") })
    }

    @Test
    fun `a damaged cached render is drawn again instead of trusted`() {
        load(reserve(), laptop)
        val victim = geometryDirs().single().listFiles()!!.first()
        victim.writeBytes(ByteArray(40) { 7 }) // not a PNG
        SeedReserve.clearSeedRenderCache()
        wallpaperDir.deleteRecursively(); wallpaperDir.mkdirs()
        rendered.clear()

        val records = load(reserve(), laptop)

        assertEquals("only the damaged one is drawn again", 1, rendered.size)
        assertEquals(seedCount, records.size)
    }

    @Test
    fun `without a cache folder it still works and keeps nothing`() {
        val records = load(reserve(withCache = false), laptop)

        assertEquals(seedCount, records.size)
        assertFalse(cacheDir.exists())
    }

    @Test
    fun `renders kept by an older version of the app are not reused`() {
        // A release that changes a seed or the renderer must not be shown last release's pictures.
        val stale = File(cacheDir, "old-version_1000x625_1000x625").apply { mkdirs() }
        BundledSeedSourceAdapter.SEED_SPECS.forEach { File(stale, "${it.id}.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4, 5, 6)) }

        val records = load(reserve(), laptop)

        assertEquals("every seed is drawn afresh", seedCount, rendered.size)
        assertEquals(seedCount, records.size)
    }

    @Test
    fun `kept renders are filed under the current version`() {
        load(reserve(), laptop)

        val folder = geometryDirs().single().name
        assertTrue("the folder name carries the version ($folder)", folder.startsWith(SeedReserve.renderCacheVersion() + "_"))
    }
}
