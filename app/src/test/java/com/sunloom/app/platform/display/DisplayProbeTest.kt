package com.sunloom.app.platform.display

import android.app.Application
import android.content.Context
import android.view.Display
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.domain.model.DisplayProfile
import com.sunloom.app.domain.model.DisplayQualityTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class DisplayProbeTest {

    @Test
    fun `when every display is asleep they all stay connected`() {
        // Screen timeout / monitor sleep must not look like an undock (Article IV §6).
        val asleep = listOf(
            DisplayProbe.Listed(0, flags = 0, state = Display.STATE_OFF),
            DisplayProbe.Listed(1, flags = 0, state = Display.STATE_OFF)
        )
        assertEquals(asleep, DisplayProbe.selectConnected(asleep))
    }

    @Test
    fun `a sleeping display is left out while another display is awake`() {
        // Lid closed on a docked laptop: the dark built-in panel is not planned for.
        val builtInOff = DisplayProbe.Listed(0, flags = 0, state = Display.STATE_OFF)
        val external = DisplayProbe.Listed(2, flags = 0, state = Display.STATE_ON)
        assertEquals(listOf(external), DisplayProbe.selectConnected(listOf(builtInOff, external)))
    }

    @Test
    fun `private displays never count as connected`() {
        val private = DisplayProbe.Listed(5, flags = Display.FLAG_PRIVATE, state = Display.STATE_ON)
        val normal = DisplayProbe.Listed(0, flags = 0, state = Display.STATE_ON)
        assertEquals(listOf(normal), DisplayProbe.selectConnected(listOf(private, normal)))
        assertTrue(DisplayProbe.selectConnected(listOf(private)).isEmpty())
    }

    @Test
    fun `resolvePhysicalPpi accepts calibrated EDID PPI across standard mid and high tiers`() {
        // 34" UWQHD 3440x1440 (~109.7 PPI) -> STANDARD_100
        val uwqhdPpi = DisplayProbe.resolvePhysicalPpi(3440, 1440, xdpi = 109.5f, ydpi = 109.8f)
        assertEquals(109.65, requireNotNull(uwqhdPpi), 0.2)
        val uwqhd = DisplayProfile(1, "UWQHD", 3440, 1440, densityDpi = 160, physicalPpi = uwqhdPpi)
        assertEquals(DisplayQualityTier.STANDARD_100, uwqhd.qualityTier)

        // 27" 4K UHD 3840x2160 (~163.2 PPI) -> MID_80 even if logical densityDpi is 160!
        val uhd27Ppi = DisplayProbe.resolvePhysicalPpi(3840, 2160, xdpi = 163.2f, ydpi = 163.2f)
        assertEquals(163.2, requireNotNull(uhd27Ppi), 0.2)
        val uhd27 = DisplayProfile(2, "27-inch 4K", 3840, 2160, densityDpi = 160, physicalPpi = uhd27Ppi)
        assertEquals(DisplayQualityTier.MID_80, uhd27.qualityTier)

        // 27" 5K Studio 5120x2880 (~217.6 PPI) -> HIGH_65
        val studio5kPpi = DisplayProbe.resolvePhysicalPpi(5120, 2880, xdpi = 217.6f, ydpi = 217.6f)
        assertEquals(217.6, requireNotNull(studio5kPpi), 0.2)
        val studio5k = DisplayProfile(3, "5K Studio", 5120, 2880, densityDpi = 240, physicalPpi = studio5kPpi)
        assertEquals(DisplayQualityTier.HIGH_65, studio5k.qualityTier)
    }

    @Test
    fun `resolvePhysicalPpi rejects VESA 72 and 96 placeholders and implausible EDID values`() {
        // VESA 72 and 96 defaults must be rejected so Retina displays fall back to densityDpi
        assertNull(DisplayProbe.resolvePhysicalPpi(2560, 1600, xdpi = 72.0f, ydpi = 72.0f))
        assertNull(DisplayProbe.resolvePhysicalPpi(3840, 2160, xdpi = 96.0f, ydpi = 96.0f))
        assertNull(DisplayProbe.resolvePhysicalPpi(2560, 1600, xdpi = 95.5f, ydpi = 96.2f))

        // Out of [60..600] range or severely non-square garbage EDID
        assertNull(DisplayProbe.resolvePhysicalPpi(3840, 2160, xdpi = 24.0f, ydpi = 24.0f))
        assertNull(DisplayProbe.resolvePhysicalPpi(3840, 2160, xdpi = 850.0f, ydpi = 850.0f))
        assertNull(DisplayProbe.resolvePhysicalPpi(3840, 2160, xdpi = 110.0f, ydpi = 220.0f))

        // Retina laptop with rejected 96/96 placeholder falls back cleanly to densityDpi=240 -> HIGH_65
        val retinaFallback = DisplayProfile(
            displayId = 0,
            name = "GoogleBook Retina",
            widthPx = 2560,
            heightPx = 1600,
            densityDpi = 240,
            physicalPpi = DisplayProbe.resolvePhysicalPpi(2560, 1600, 96f, 96f)
        )
        assertEquals(DisplayQualityTier.HIGH_65, retinaFallback.qualityTier)
    }

    @Test
    fun `an auto-rotating display is measured in its natural orientation whatever its live rotation`() {
        // Laptop-native 2560x1600 panel, auto-rotate on, currently turned into portrait (Article IV §6).
        val upright = DisplayProbe.resolveModeDimensions(
            modeWidth = 2560, modeHeight = 1600, rotation = Surface.ROTATION_0,
            fallbackMetricsWidth = 2560, fallbackMetricsHeight = 1600, useNaturalOrientation = true
        )
        val turned = DisplayProbe.resolveModeDimensions(
            modeWidth = 2560, modeHeight = 1600, rotation = Surface.ROTATION_90,
            fallbackMetricsWidth = 1600, fallbackMetricsHeight = 2560, useNaturalOrientation = true
        )
        assertEquals(2560 to 1600, upright)
        assertEquals(upright, turned)
    }

    @Test
    fun `a portrait-native panel keeps its portrait natural orientation when auto-rotated`() {
        val heldInLandscape = DisplayProbe.resolveModeDimensions(
            modeWidth = 1600, modeHeight = 2560, rotation = Surface.ROTATION_90,
            fallbackMetricsWidth = 2560, fallbackMetricsHeight = 1600, useNaturalOrientation = true
        )
        assertEquals(1600 to 2560, heldInLandscape)
    }

    @Test
    fun `natural orientation falls back to live metrics when the physical mode is unknown`() {
        val noMode = DisplayProbe.resolveModeDimensions(
            modeWidth = 0, modeHeight = 0, rotation = Surface.ROTATION_90,
            fallbackMetricsWidth = 1600, fallbackMetricsHeight = 2560, useNaturalOrientation = true
        )
        assertEquals(1600 to 2560, noMode)
    }

    @Test
    fun `scaledDpi gives the same density whether the panel is upright or turned`() {
        val upright = DisplayProbe.scaledDpi(2560, 1600, 2560, 1600, xdpi = 227f, ydpi = 226f)
        val turned = DisplayProbe.scaledDpi(2560, 1600, 1600, 2560, xdpi = 226f, ydpi = 227f)
        assertEquals(upright.first, turned.first, 0.01f)
        assertEquals(upright.second, turned.second, 0.01f)
    }

    @Test
    fun `scaledDpi does not treat a square panel as turned`() {
        val (x, y) = DisplayProbe.scaledDpi(1600, 1600, 1920, 1080, xdpi = 100f, ydpi = 200f)
        // Axes stay as measured (not swapped): x is out of the scaling range, y scales by 1600/1080.
        assertEquals(100f, x, 0.01f)
        assertEquals(200f * 1600f / 1080f, y, 0.01f)
    }

    @Test
    fun `a dozing display is still visible and stays connected alongside an awake one`() {
        val dozing = DisplayProbe.Listed(0, flags = 0, state = Display.STATE_DOZE)
        val awake = DisplayProbe.Listed(2, flags = 0, state = Display.STATE_ON)
        assertEquals(listOf(dozing, awake), DisplayProbe.selectConnected(listOf(dozing, awake)))
    }

    @Test
    fun `scaledDpi scales up when the physical mode is larger than the scaled metrics`() {
        val (x, y) = DisplayProbe.scaledDpi(3840, 2160, 1920, 1080, xdpi = 80f, ydpi = 80f)
        assertEquals(160f, x, 0.01f)
        assertEquals(160f, y, 0.01f)
    }

    @Test
    fun `resolveModeDimensions honors physical mode resolution and live rotation`() {
        // Landscape monitor rotated 90 degrees into portrait
        val portraitRotated = DisplayProbe.resolveModeDimensions(
            modeWidth = 3840,
            modeHeight = 2160,
            rotation = Surface.ROTATION_90,
            fallbackMetricsWidth = 1440,
            fallbackMetricsHeight = 2560
        )
        assertEquals(2160 to 3840, portraitRotated)

        // Portrait-native tablet panel held in landscape (metrics report landscape 2560x1600)
        val landscapeTablet = DisplayProbe.resolveModeDimensions(
            modeWidth = 1600,
            modeHeight = 2560,
            rotation = Surface.ROTATION_90,
            fallbackMetricsWidth = 2560,
            fallbackMetricsHeight = 1600
        )
        assertEquals(2560 to 1600, landscapeTablet)
    }

    @Test
    fun `DisplayHistoryStore caps remembered displays at 6 and supports forgetting entries`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DisplayHistoryStore(context)
        store.clearAll()

        val sevenDisplays = (1..7).map { i ->
            DisplayProfile(
                displayId = i,
                name = "Monitor $i",
                widthPx = 1920 + i * 160,
                heightPx = 1080 + i * 90,
                densityDpi = 160,
                physicalPpi = 110.0 + i
            )
        }
        sevenDisplays.forEachIndexed { idx, d ->
            store.recordConnectedDisplays(listOf(d), nowEpochMs = 1_000L + idx * 100L)
        }

        val activeNow = listOf(sevenDisplays.last())
        val history = store.getRememberedDisplays(activeNow)
        assertEquals("Remembered display list must cap at 6 entries", 6, history.size)
        assertFalse("Oldest display (Monitor 1) must be evicted first", history.any { it.name == "Monitor 1" })
        assertTrue(history.first { it.name == "Monitor 7" }.isCurrentlyConnected)
        assertFalse(history.first { it.name == "Monitor 6" }.isCurrentlyConnected)

        val keyToForget = history.first { it.name == "Monitor 6" }.key
        store.forgetDisplay(keyToForget)
        val afterForget = store.getRememberedDisplays(activeNow)
        assertEquals(5, afterForget.size)
        assertFalse(afterForget.any { it.name == "Monitor 6" })
    }

    // --- the remembered-displays file is written when something changed, not on every look ---

    private fun history(): DisplayHistoryStore {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("sunloom_display_history", Context.MODE_PRIVATE).edit().clear().commit()
        return DisplayHistoryStore(ctx)
    }

    private val laptop = DisplayProfile(0, "Built-in", 2560, 1600, densityDpi = 220)
    private val monitor = DisplayProfile(1, "Monitor", 3840, 2160, densityDpi = 160)

    private fun lastSeen(store: DisplayHistoryStore, display: DisplayProfile) =
        store.getRememberedDisplays(listOf(display)).single { it.name == display.name }.lastSeenEpochMs

    @Test
    fun `looking again at the same display straight away does not rewrite the history`() {
        val store = history()
        store.recordConnectedDisplays(listOf(laptop), nowEpochMs = 1_000_000L)

        store.recordConnectedDisplays(listOf(laptop), nowEpochMs = 1_000_000L + 60_000L)

        assertEquals("still the first look: nothing was written", 1_000_000L, lastSeen(store, laptop))
    }

    @Test
    fun `the last seen time is refreshed once it is a while old`() {
        val store = history()
        store.recordConnectedDisplays(listOf(laptop), nowEpochMs = 1_000_000L)

        store.recordConnectedDisplays(listOf(laptop), nowEpochMs = 1_000_000L + DisplayHistoryStore.REFRESH_AFTER_MS + 1)

        assertEquals(1_000_000L + DisplayHistoryStore.REFRESH_AFTER_MS + 1, lastSeen(store, laptop))
    }

    @Test
    fun `a newly connected display is recorded at once`() {
        val store = history()
        store.recordConnectedDisplays(listOf(laptop), nowEpochMs = 1_000_000L)

        store.recordConnectedDisplays(listOf(laptop, monitor), nowEpochMs = 1_000_000L + 1_000L)

        assertEquals(2, store.getRememberedDisplays(listOf(laptop, monitor)).size)
        assertEquals(1_000_000L + 1_000L + 1, lastSeen(store, monitor))
    }

    @Test
    fun `a display whose details changed is recorded at once`() {
        val store = history()
        store.recordConnectedDisplays(listOf(laptop), nowEpochMs = 1_000_000L)
        val rescaled = laptop.copy(densityDpi = 240)

        store.recordConnectedDisplays(listOf(rescaled), nowEpochMs = 1_000_000L + 1_000L)

        assertEquals(240, store.getRememberedDisplays(listOf(rescaled)).single { it.name == "Built-in" }.densityDpi)
    }
}
