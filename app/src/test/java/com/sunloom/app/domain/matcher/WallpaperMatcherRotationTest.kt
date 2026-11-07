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
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article XI §2: rotation must be varied, not a fixed cycle. These tests reproduce the reported
 * "same 2-3 wallpapers in the same order" symptom and pin the shuffle, recency window,
 * NEUTRAL-before-repeat and source-interleave behaviour.
 */
@Requirement("XI.2")
class WallpaperMatcherRotationTest {

    private var verifierCalls = 0

    private fun matcher(seed: Int = 1) = WallpaperMatcher(
        fileExistsVerifier = { record ->
            verifierCalls++
            record.localFilePath.isNotBlank() && record.fileSizeBytes > 0L
        },
        random = Random(seed)
    )

    private fun record(
        id: String,
        brightness: BrightnessClass,
        sourceId: String = "wikimedia_featured",
        isFavorite: Boolean = false,
        appliedCount: Int = 0,
        lastApplied: Long = 0L,
        category: SafeCategory = SafeCategory.LANDSCAPES_NATURE,
        dHash: String = "",
        hue: Int = -1
    ): WallpaperRecord = WallpaperRecord(
        id = id,
        title = "Sample $id",
        category = category,
        localFilePath = "/tmp/$id.jpg",
        croppedFilePath = "/tmp/${id}_crop.jpg",
        fileSizeBytes = 2048L,
        isFavorite = isFavorite,
        appliedCount = appliedCount,
        lastAppliedEpochMs = lastApplied,
        provenance = ProvenanceRecord(
            sourceId = sourceId,
            sourceName = sourceId,
            assetId = id,
            originalPageUrl = "https://example.org/$id",
            creatorName = "Creator $id",
            creatorPageUrl = "https://example.org",
            licenseType = LicenseType.CC0,
            attributionText = "CC0",
            retrievedAtEpochMs = 1762280000000L,
            originalWidth = 3840,
            originalHeight = 2400,
            renditionWidth = 2560,
            renditionHeight = 1600,
            safetyClassification = SafetyClassification.SAFE,
            safetyClassificationSource = "Verified",
            sha256Checksum = "abcd1234",
            perceptualHash = dHash
        ),
        evaluation = ImageEvaluationResult(
            isEligible = true,
            rejectionReasonCode = null,
            globalLuminance = 0.5,
            desktopZoneLuminance = 0.5,
            lockClockZoneLuminance = 0.5,
            glareFractionInSafeZones = 0.0,
            contrastStdDev = 0.1,
            brightnessClass = brightness,
            cropRect = CropRect(0, 0, 2560, 1600, 0.5, 0.5, false, 1.0),
            explanation = "Sample",
            hueBucket = hue
        )
    )

    /** Simulates repeated "Next" presses the way the app does: record the apply, then ask again. */
    private fun press(
        matcher: WallpaperMatcher,
        pool: List<WallpaperRecord>,
        target: BrightnessClass,
        presses: Int
    ): List<String> {
        var library = pool
        var current: String? = null
        var previous: String? = null
        var clock = 1_000L
        val shown = ArrayList<String>()
        repeat(presses) {
            val next = matcher.selectNextWallpaper(
                candidates = library,
                targetBrightness = target,
                preferences = UserPreferences(),
                currentWallpaperId = current,
                recentlyUsedIds = setOfNotNull(current, previous)
            )
            assertNotNull("pool unexpectedly exhausted", next)
            shown += next!!.id
            library = library.map {
                if (it.id == next.id) it.copy(appliedCount = it.appliedCount + 1, lastAppliedEpochMs = clock++) else it
            }
            previous = current
            current = next.id
        }
        return shown
    }

    @Test
    fun `two light plus two neutral does not ping-pong between the light pair`() {
        val pool = listOf(
            record("A", BrightnessClass.LIGHT),
            record("B", BrightnessClass.LIGHT),
            record("N1", BrightnessClass.NEUTRAL),
            record("N2", BrightnessClass.NEUTRAL)
        )
        for (seed in 1..10) {
            val shown = press(matcher(seed), pool, BrightnessClass.LIGHT, presses = 8)
            assertTrue("neutral images must get a turn, got $shown", shown.any { it.startsWith("N") })
            val pingPong = shown.windowed(4).any { it[0] == it[2] && it[1] == it[3] }
            assertFalse("A,B,A,B pattern in $shown (seed $seed)", pingPong)
            assertTrue("every image appears in $shown", shown.toSet().size == 4)
        }
    }

    @Test
    fun `every image is shown once before any repeats`() {
        val pool = (1..10).map { record("L$it", BrightnessClass.LIGHT) }
        val shown = press(matcher(7), pool, BrightnessClass.LIGHT, presses = 10)
        assertEquals(10, shown.toSet().size)
    }

    @Test
    fun `order differs between seeds`() {
        val pool = (1..10).map { record("L$it", BrightnessClass.LIGHT) }
        val orders = (1..6).map { press(matcher(it), pool, BrightnessClass.LIGHT, presses = 10) }.toSet()
        assertTrue("expected varied orders, got ${orders.size}", orders.size > 1)
    }

    @Test
    fun `never immediately repeats the current wallpaper when alternatives exist`() {
        val pool = (1..3).map { record("L$it", BrightnessClass.LIGHT) }
        val shown = press(matcher(3), pool, BrightnessClass.LIGHT, presses = 30)
        assertTrue(shown.zipWithNext().none { (a, b) -> a == b })
    }

    @Test
    fun `an unseen newcomer beats heavily shown images`() {
        val worn = (1..5).map { record("W$it", BrightnessClass.LIGHT, appliedCount = 9, lastApplied = 100L + it) }
        val pool = worn + record("NEW", BrightnessClass.LIGHT)
        for (seed in 1..10) {
            val next = matcher(seed).selectNextWallpaper(pool, BrightnessClass.LIGHT, UserPreferences())
            assertEquals("NEW", next!!.id)
        }
    }

    @Test
    fun `least recently applied half is preferred once everything has been seen`() {
        val pool = (1..6).map { record("L$it", BrightnessClass.LIGHT, appliedCount = 1, lastApplied = it * 100L) }
        for (seed in 1..20) {
            val next = matcher(seed).selectNextWallpaper(pool, BrightnessClass.LIGHT, UserPreferences())
            assertTrue("picked recent ${next!!.id}", next.id in setOf("L1", "L2", "L3"))
        }
    }

    @Test
    fun `source interleave is a hard preference over the active source`() {
        val current = record("cma-1", BrightnessClass.LIGHT, sourceId = "cma_cc0")
        val pool = listOf(
            current,
            record("cma-2", BrightnessClass.LIGHT, sourceId = "cma_cc0"),
            record("cma-3", BrightnessClass.LIGHT, sourceId = "cma_cc0"),
            record("wiki-1", BrightnessClass.LIGHT, sourceId = "wikimedia_featured")
        )
        val prefs = UserPreferences(enabledSources = setOf("cma_cc0", "wikimedia_featured"))
        for (seed in 1..20) {
            val next = matcher(seed).selectNextWallpaper(
                pool, BrightnessClass.LIGHT, prefs,
                currentWallpaperId = "cma-1", recentlyUsedIds = setOf("cma-1")
            )
            assertEquals("wiki-1", next!!.id)
        }
    }

    @Test
    fun `favourites are preferred gently but never exclusively`() {
        val pool = listOf(
            record("fav", BrightnessClass.LIGHT, isFavorite = true),
            record("plain", BrightnessClass.LIGHT)
        )
        val rng = matcher(42)
        val picks = (1..600).map {
            rng.selectNextWallpaper(pool, BrightnessClass.LIGHT, UserPreferences())!!.id
        }
        val favShare = picks.count { it == "fav" } / picks.size.toDouble()
        assertTrue("favourite share $favShare should exceed an even split", favShare > 0.55)
        assertTrue("non-favourites must still appear (share $favShare)", favShare < 0.85)
    }

    @Test
    fun `deterministic selection is stable across calls`() {
        val pool = (1..8).map { record("L$it", BrightnessClass.LIGHT) }
        val m = matcher(5)
        val first = m.selectNextWallpaper(pool, BrightnessClass.LIGHT, UserPreferences(), deterministic = true)
        repeat(10) {
            assertEquals(
                first!!.id,
                m.selectNextWallpaper(pool, BrightnessClass.LIGHT, UserPreferences(), deterministic = true)!!.id
            )
        }
    }

    @Test
    fun `ranking verifies file existence once per record regardless of result count`() {
        val pool = (1..6).map { record("L$it", BrightnessClass.LIGHT) }
        verifierCalls = 0
        val ranked = matcher(2).rankCandidatesForSelection(
            candidates = pool,
            targetBrightness = BrightnessClass.LIGHT,
            preferences = UserPreferences(),
            maxResults = 3
        )
        assertEquals(3, ranked.size)
        assertEquals(3, ranked.map { it.id }.toSet().size)
        assertEquals(6, verifierCalls)
    }

    @Test
    fun `recent window covers the last applied images even when callers pass only current and previous`() {
        // 6 images; L1..L4 were applied most recently, L5/L6 were applied long ago.
        val pool = listOf(
            record("L1", BrightnessClass.LIGHT, appliedCount = 1, lastApplied = 600),
            record("L2", BrightnessClass.LIGHT, appliedCount = 1, lastApplied = 500),
            record("L3", BrightnessClass.LIGHT, appliedCount = 1, lastApplied = 400),
            record("L4", BrightnessClass.LIGHT, appliedCount = 1, lastApplied = 300),
            record("L5", BrightnessClass.LIGHT, appliedCount = 1, lastApplied = 20),
            record("L6", BrightnessClass.LIGHT, appliedCount = 1, lastApplied = 10)
        )
        for (seed in 1..20) {
            val next = matcher(seed).selectNextWallpaper(
                pool, BrightnessClass.LIGHT, UserPreferences(),
                currentWallpaperId = "L1", recentlyUsedIds = setOf("L1", "L2")
            )
            assertTrue("picked ${next!!.id}", next.id in setOf("L5", "L6"))
        }
    }

    @Test
    fun `consecutive wallpapers rarely share a category when alternatives exist`() {
        val pool = (1..6).map { record("A$it", BrightnessClass.LIGHT, category = SafeCategory.LANDSCAPES_NATURE) } +
            (1..6).map { record("B$it", BrightnessClass.LIGHT, category = SafeCategory.SPACE_ASTRONOMY) }
        val byId = pool.associateBy { it.id }
        var samePairs = 0
        var pairs = 0
        for (seed in 1..30) {
            val shown = press(matcher(seed), pool, BrightnessClass.LIGHT, presses = 12)
            shown.zipWithNext().forEach { (a, b) ->
                pairs++
                if (byId.getValue(a).category == byId.getValue(b).category) samePairs++
            }
        }
        val share = samePairs / pairs.toDouble()
        assertTrue("same-category share $share should be well below a coin flip", share < 0.4)
    }

    @Test
    fun `a picture that looks like the last one shown is deprioritised`() {
        val shownHash = "5a3c96e1b2d47f08"
        val pool = listOf(
            record("shown", BrightnessClass.LIGHT, appliedCount = 1, lastApplied = 900, dHash = shownHash),
            record("lookalike", BrightnessClass.LIGHT, dHash = "5a3c96e1b2d47f0b"),
            record("different", BrightnessClass.LIGHT, dHash = "a5c3691e4d2b80f7")
        )
        val rng = matcher(11)
        val picks = (1..400).map {
            rng.selectNextWallpaper(pool, BrightnessClass.LIGHT, UserPreferences(), currentWallpaperId = "shown", recentlyUsedIds = setOf("shown"))!!.id
        }
        val different = picks.count { it == "different" } / picks.size.toDouble()
        assertTrue("different share $different", different > 0.7)
        assertTrue("lookalike must still be possible", picks.contains("lookalike"))
    }

    @Test
    fun `taste from kept and disliked images shifts weights gently and never excludes`() {
        val pool = listOf(
            record("loved", BrightnessClass.LIGHT, category = SafeCategory.SCENIC_FINE_ART),
            record("disliked", BrightnessClass.LIGHT, category = SafeCategory.ABSTRACT_PATTERNS)
        )
        val taste = TasteProfile(
            categoryMultiplier = mapOf(SafeCategory.SCENIC_FINE_ART to 1.6, SafeCategory.ABSTRACT_PATTERNS to 0.6)
        )
        val rng = matcher(3)
        val picks = (1..800).map {
            rng.selectNextWallpaper(pool, BrightnessClass.LIGHT, UserPreferences(), taste = taste)!!.id
        }
        val loved = picks.count { it == "loved" } / picks.size.toDouble()
        assertTrue("loved share $loved", loved in 0.62..0.80)
        assertTrue(picks.contains("disliked"))
    }

    @Test
    fun `the same dominant hue as the last wallpaper is mildly deprioritised`() {
        val pool = listOf(
            record("shown", BrightnessClass.LIGHT, appliedCount = 1, lastApplied = 900, hue = 7),
            record("blue-again", BrightnessClass.LIGHT, hue = 7),
            record("warm", BrightnessClass.LIGHT, hue = 1)
        )
        val rng = matcher(21)
        val picks = (1..600).map {
            rng.selectNextWallpaper(pool, BrightnessClass.LIGHT, UserPreferences(), currentWallpaperId = "shown", recentlyUsedIds = setOf("shown"))!!.id
        }
        val warm = picks.count { it == "warm" } / picks.size.toDouble()
        assertTrue("warm share $warm", warm in 0.50..0.75)
    }

    @Test
    fun `ranked fallbacks are distinct even when the whole pool is recent`() {
        val pool = listOf(
            record("A", BrightnessClass.LIGHT, appliedCount = 1, lastApplied = 300),
            record("B", BrightnessClass.LIGHT, appliedCount = 1, lastApplied = 200),
            record("C", BrightnessClass.LIGHT, appliedCount = 1, lastApplied = 100)
        )
        for (seed in 1..20) {
            val ranked = matcher(seed).rankCandidatesForSelection(
                pool, BrightnessClass.LIGHT, UserPreferences(),
                currentWallpaperId = "A", recentlyUsedIds = setOf("A", "B"), maxResults = 3
            )
            assertEquals("duplicates in ${ranked.map { it.id }}", ranked.size, ranked.map { it.id }.toSet().size)
            assertTrue(ranked.isNotEmpty())
        }
        val two = listOf(record("A", BrightnessClass.LIGHT, lastApplied = 2, appliedCount = 1), record("B", BrightnessClass.LIGHT, lastApplied = 1, appliedCount = 1))
        val ranked = matcher(1).rankCandidatesForSelection(two, BrightnessClass.LIGHT, UserPreferences(), currentWallpaperId = "A", recentlyUsedIds = setOf("A", "B"), maxResults = 3)
        assertEquals(ranked.map { it.id }.distinct(), ranked.map { it.id })
    }
}
