package com.sunloom.app.domain.renderer

import com.sunloom.app.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article IV §2/§4 and XII §2: a picture is decoded no smaller than the part of it that will be shown (so a display
 * is never fed an upscaled crop) and no larger than that needs (so memory stays low on 4K and 5K screens).
 */
@Requirement("IV.2")
class DecodeSamplingTest {
    private val renderer = CropRenderer()

    private fun target(outW: Int, outH: Int, focalX: Double = 0.5, focalY: Double = 0.5) =
        DecodeTarget(outW.toDouble() / outH, focalX, focalY, outW, outH)

    private fun sampleSize(srcW: Int, srcH: Int, t: DecodeTarget) =
        DecodeSampling.sampleSize(srcW, srcH, t, renderer, legacyMaxW = t.outputWidth, legacyMaxH = t.outputHeight)

    private fun decodedCrop(srcW: Int, srcH: Int, t: DecodeTarget, s: Int): Pair<Int, Int> {
        val b = renderer.computeAspectCropBounds(srcW, srcH, t.focalX, t.focalY, t.aspect)
        return b.width / s to b.height / s
    }

    // --- the old rule, kept for pictures that are not cropped ---

    @Test
    fun `the legacy rule samples until the whole picture fits twice the display`() {
        assertEquals(1, DecodeSampling.legacySampleSize(6000, 4000, 3840, 2160))
        assertEquals(2, DecodeSampling.legacySampleSize(12000, 8000, 3840, 2160))
        assertEquals("never below the 2560x1600 floor", 1, DecodeSampling.legacySampleSize(2560, 1600, 800, 600))
    }

    @Test
    fun `without a crop target the legacy rule is used`() {
        assertEquals(
            DecodeSampling.legacySampleSize(12000, 8000, 3840, 2160),
            DecodeSampling.sampleSize(12000, 8000, null, renderer, legacyMaxW = 3840, legacyMaxH = 2160)
        )
    }

    // --- the bug: the legacy rule undersamples a tall picture ---

    @Test
    fun `a tall portrait is no longer decoded below the width a 16 by 10 display needs`() {
        val t = target(2560, 1600)
        // Legacy: 4000x6000 does not fit 5120x3200 until it is halved, leaving a 2000 px wide crop for a 2560 px screen.
        val legacy = DecodeSampling.legacySampleSize(4000, 6000, 2560, 1600)
        assertTrue("the old rule undersamples here", decodedCrop(4000, 6000, t, legacy).first < 2560)

        val s = sampleSize(4000, 6000, t)

        assertEquals(1, s)
        assertTrue(decodedCrop(4000, 6000, t, s).first >= 2560)
    }

    // --- the rule itself ---

    @Test
    fun `the decoded crop is never smaller than the output, and could not be halved again`() {
        val sources = listOf(1600 to 1000, 3000 to 2000, 4000 to 6000, 6000 to 4000, 8000 to 3000, 12000 to 8000, 20000 to 10000, 5000 to 5000)
        val outputs = listOf(1280 to 800, 2560 to 1600, 3840 to 2160, 5120 to 2880, 5120 to 1440, 1200 to 1920)
        val focals = listOf(0.5 to 0.5, 0.1 to 0.9, 0.9 to 0.1)
        for ((sw, sh) in sources) for ((ow, oh) in outputs) for ((fx, fy) in focals) {
            val t = target(ow, oh, fx, fy)
            val s = sampleSize(sw, sh, t)
            val (cw, ch) = decodedCrop(sw, sh, t, s)
            assertTrue("$sw x $sh -> $ow x $oh: s=$s must be a power of two", s >= 1 && (s and (s - 1)) == 0)
            if (s > 1) {
                assertTrue("$sw x $sh -> $ow x $oh: crop $cw x $ch below output at s=$s", cw >= ow && ch >= oh)
            }
            val (hw, hh) = decodedCrop(sw, sh, t, s * 2)
            assertTrue("$sw x $sh -> $ow x $oh: s=$s could have been ${s * 2}", hw < ow || hh < oh)
        }
    }

    @Test
    fun `a picture smaller than the output is decoded whole`() {
        assertEquals(1, sampleSize(1600, 1000, target(3840, 2160)))
    }

    @Test
    fun `a huge picture on a small display is sampled right down`() {
        val s = sampleSize(20000, 10000, target(1280, 800))

        assertEquals(8, s)
        assertTrue(decodedCrop(20000, 10000, target(1280, 800), s).first >= 1280)
    }
}
