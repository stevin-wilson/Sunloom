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
}
