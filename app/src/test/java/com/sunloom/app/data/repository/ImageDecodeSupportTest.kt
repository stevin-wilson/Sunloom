package com.sunloom.app.data.repository

import android.app.Application
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Article VII §4 L3/L4: a picture is never decoded at full size, even when its header could not be parsed. */
@Requirement("VII.4")
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
}
