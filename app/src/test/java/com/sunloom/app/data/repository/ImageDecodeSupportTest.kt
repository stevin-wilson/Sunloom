package com.sunloom.app.data.repository

import android.app.Application
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.SafetyVerdict
import com.sunloom.app.domain.safety.SafetyFixtures
import com.sunloom.app.domain.safety.SafetyGate
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Article VII §4 L3/L4: a picture is never decoded at full size, even when its header could not be parsed. */
@Requirement("VII.4", "VII.4.m")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ImageDecodeSupportTest {

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }

    @Test
    fun `a decode without header bounds is still downsampled`() {
        val bytes = jpeg(3000, 2000)
        val decoded = decodeSampledBitmapSafely(bytes, headerW = 0, headerH = 0, maxEdgePx = 512)
        assertNotNull(decoded)
        assertTrue("decoded ${decoded!!.width}x${decoded.height}", decoded.width <= 512 && decoded.height <= 512)
    }

    @Test
    fun `a decode with header bounds is downsampled as before`() {
        val decoded = decodeSampledBitmapSafely(jpeg(3000, 2000), headerW = 3000, headerH = 2000, maxEdgePx = 512)
        assertNotNull(decoded)
        assertTrue(decoded!!.width <= 512 && decoded.height <= 512)
    }

    @Test
    fun `running out of memory is reported apart from a corrupt picture`() {
        val oom = decodeSampledOutcome(ByteArray(10), 100, 100, decoder = { _, _ -> throw OutOfMemoryError() })
        assertTrue("OOM must not look corrupt", oom is DecodeOutcome.OutOfMemory)
        val corrupt = decodeSampledOutcome(ByteArray(10), 100, 100, decoder = { _, _ -> null })
        assertTrue(corrupt is DecodeOutcome.Corrupt)
        val broken = decodeSampledOutcome(ByteArray(10), 100, 100, decoder = { _, _ -> throw IllegalArgumentException("bad") })
        assertTrue(broken is DecodeOutcome.Corrupt)
    }

    @Test
    fun `a decodable picture is returned`() {
        val outcome = decodeSampledOutcome(jpeg(800, 600), 800, 600, maxEdgePx = 512)
        assertTrue(outcome is DecodeOutcome.Decoded)
    }

    @Test
    fun `a picture that cannot be sized at all is refused, never decoded at full size`() {
        // No header bounds and the decoder's own probe reports no size either.
        val outcome = decodeSampledOutcome(ByteArray(10), 0, 0, decoder = { _, _ -> null })
        assertTrue(outcome is DecodeOutcome.Corrupt)
    }

    @Requirement("VII.4.l")
    @Test
    fun `the decoder sizes a file from its header alone, and gives nothing for bytes it cannot read`() {
        val bounds = probeImageBounds(jpeg(3000, 2000))
        assertNotNull(bounds)
        assertTrue("${bounds!!.width}x${bounds.height}", bounds.width == 3000 && bounds.height == 2000)
        assertTrue("its own type, when the decoder reports one (Robolectric's does not)", bounds.outMimeType.let { it == null || it == "image/jpeg" })
        // (Robolectric's decoder invents a size for any bytes, so "cannot size it" is stood in for by a decoder that reports none.)
        assertTrue("a decoder that cannot size it", probeImageBounds(ByteArray(5000) { 0x42 }, decoder = { _, _ -> null }) == null)
        assertTrue("a decoder that fails", probeImageBounds(jpeg(40, 40), decoder = { _, _ -> error("boom") }) == null)
    }

    @Requirement("VII.4.l")
    @Test
    fun `a size only the decoder could read is held to the same limits as any other`() {
        // The header parser could not size this file, but the decoder says it is 20,000 pixels wide: past the 16,384 limit.
        val bytes = ByteArray(SafetyGate.MIN_PAYLOAD_BYTES) { 0x42 }
        bytes[0] = 0xFF.toByte(); bytes[1] = 0xD8.toByte(); bytes[2] = 0xFF.toByte(); bytes[3] = 0xE0.toByte()
        val bounds = probeImageBounds(bytes, decoder = { _, o -> o.outWidth = 20_000; o.outHeight = 1_000; o.outMimeType = "image/jpeg"; null })
        assertNotNull(bounds)
        val verdict = SafetyGate().evaluatePayload(SafetyFixtures.candidate(), bytes, headerBounds = bounds)
        assertTrue("$verdict", verdict is SafetyVerdict.Rejected && verdict.reasonCode == "IMAGE_DIMENSIONS_OUT_OF_BOUNDS")
        // And a file nobody can size is refused rather than waved through.
        val unsized = SafetyGate().evaluatePayload(SafetyFixtures.candidate(), bytes, headerBounds = probeImageBounds(bytes, decoder = { _, _ -> null }))
        assertTrue("$unsized", unsized is SafetyVerdict.Rejected && unsized.reasonCode == "UNREADABLE_IMAGE_HEADER")
    }
}
