package com.sunloom.app.domain.safety

import com.sunloom.app.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Article VII §4 L3: the size limits are read from the file header before decoding. A format the parser cannot size
 * would skip them (and the downsampling that follows), so every WebP flavour and every JPEG frame type must report.
 */
@Requirement("VII.4")
class HeaderBoundsTest {

    private fun riff(chunk: String, payload: ByteArray): ByteArray {
        val size = 4 + 8 + payload.size
        return "RIFF".toByteArray() + byteArrayOf(size.toByte(), (size shr 8).toByte(), 0, 0) +
            "WEBP".toByteArray() + chunk.toByteArray() +
            byteArrayOf(payload.size.toByte(), (payload.size shr 8).toByte(), 0, 0) + payload
    }

    @Test
    fun `lossy WebP (VP8) reports its dimensions`() {
        val w = 3000
        val h = 2000
        val payload = byteArrayOf(0x10, 0, 0, 0x9D.toByte(), 0x01, 0x2A,
            (w and 0xFF).toByte(), (w shr 8).toByte(), (h and 0xFF).toByte(), (h shr 8).toByte(), 0, 0)
        val bounds = SafetyGate.inspectImageHeaderBounds(riff("VP8 ", payload))
        assertNotNull(bounds)
        assertEquals(w, bounds!!.width)
        assertEquals(h, bounds.height)
    }

    @Test
    fun `lossless WebP (VP8L) reports its dimensions`() {
        val w = 16383
        val h = 12000
        val bits = (w - 1) or ((h - 1) shl 14)
        val payload = byteArrayOf(0x2F, bits.toByte(), (bits shr 8).toByte(), (bits shr 16).toByte(), (bits shr 24).toByte(), 0, 0, 0, 0, 0, 0)
        val bounds = SafetyGate.inspectImageHeaderBounds(riff("VP8L", payload))
        assertNotNull(bounds)
        assertEquals(w, bounds!!.width)
        assertEquals(h, bounds.height)
    }

    @Test
    fun `JPEG frame types other than baseline and progressive report dimensions`() {
        for (marker in listOf(0xC3, 0xC5, 0xC9, 0xCA, 0xCF)) {
            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), marker.toByte(), 0, 11, 8,
                (4000 shr 8).toByte(), (4000 and 0xFF).toByte(), (6000 shr 8).toByte(), (6000 and 0xFF).toByte(), 3, 0, 0, 0)
            val bounds = SafetyGate.inspectImageHeaderBounds(jpeg)
            assertNotNull("SOF marker 0x${marker.toString(16)}", bounds)
            assertEquals(6000, bounds!!.width)
            assertEquals(4000, bounds.height)
        }
    }

    @Test
    fun `huffman and arithmetic table markers are not mistaken for a frame header`() {
        // 0xC4 (DHT), 0xC8 (JPG) and 0xCC (DAC) sit inside the 0xC0..0xCF range but carry no dimensions.
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC4.toByte(), 0, 6, 0, 0, 0, 0,
            0xFF.toByte(), 0xC0.toByte(), 0, 11, 8, 0, 100, 0, 200.toByte(), 3, 0, 0, 0)
        val bounds = SafetyGate.inspectImageHeaderBounds(jpeg)
        assertEquals(200, bounds?.width)
        assertEquals(100, bounds?.height)
    }
}
