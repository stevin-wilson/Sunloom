package com.sunloom.app.domain.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Pins [SafetyGate.detectImageFormat] and [SafetyGate.inspectImageHeaderBounds] over valid headers, every truncation of
 * them and every single-byte change to them, so reorganising the parser cannot change what it accepts or reads. The
 * expected text is recorded in `src/test/resources/golden/image_header_bounds.txt`; delete that file and run once to
 * record it again after an intended change.
 */
class ImageHeaderBoundsGoldenTest {

    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    private fun le24(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte())
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun pad(b: ByteArray, to: Int) = if (b.size >= to) b else b + ByteArray(to - b.size)

    private fun png(w: Int, h: Int) =
        bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + be32(13) + ascii("IHDR") + be32(w) + be32(h) + ByteArray(9)

    private fun jpegSof(marker: Int, w: Int, h: Int, before: ByteArray = ByteArray(0)) =
        bytes(0xFF, 0xD8) + before + bytes(0xFF, marker, 0x00, 0x11, 0x08, h shr 8, h and 0xFF, w shr 8, w and 0xFF) + ByteArray(12)

    private val dht = bytes(0xFF, 0xC4, 0x00, 0x06, 1, 2, 3, 4)
    private val app0 = bytes(0xFF, 0xE0, 0x00, 0x04, 9, 9)
    private val fillBytes = bytes(0xFF, 0xFF, 0xFF)
    private val rst = bytes(0xFF, 0xD3)

    private fun riff(chunk: ByteArray) = ascii("RIFF") + le16(chunk.size + 4) + byteArrayOf(0, 0) + ascii("WEBP") + chunk

    private fun vp8Lossy(w: Int, h: Int) =
        riff(ascii("VP8 ") + ByteArray(4) + bytes(0, 0, 0, 0x9D, 0x01, 0x2A) + le16(w) + le16(h) + ByteArray(8))

    private fun vp8Lossless(w: Int, h: Int): ByteArray {
        val bits = (w - 1) or ((h - 1) shl 14)
        return riff(ascii("VP8L") + ByteArray(4) + bytes(0x2F) + le16(bits and 0xFFFF) + le16(bits ushr 16) + ByteArray(8))
    }

    private fun vp8x(w: Int, h: Int) =
        riff(ascii("VP8X") + ByteArray(4) + ByteArray(4) + le24(w - 1) + le24(h - 1) + ByteArray(8))

    private val samples: Map<String, ByteArray> = linkedMapOf(
        "png-ok" to png(1920, 1080),
        "png-zero-w" to png(0, 10),
        "png-huge" to png(70000, 70000),
        "jpeg-sof0" to jpegSof(0xC0, 3840, 2160),
        "jpeg-sof2" to jpegSof(0xC2, 1024, 768),
        "jpeg-after-app0-dht" to jpegSof(0xC0, 640, 480, app0 + dht),
        "jpeg-fill-bytes" to jpegSof(0xC0, 800, 600, fillBytes + app0),
        "jpeg-rst-marker" to jpegSof(0xC0, 500, 400, rst + app0),
        "jpeg-dht-only" to bytes(0xFF, 0xD8) + dht + dht + dht + ByteArray(8),
        "jpeg-short-segment" to bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x01) + ByteArray(12),
        "jpeg-zero-size" to jpegSof(0xC0, 0, 0),
        "webp-lossy" to vp8Lossy(2048, 1536),
        "webp-lossless" to vp8Lossless(1000, 700),
        "webp-extended" to vp8x(4096, 2304),
        "webp-extended-zero" to vp8x(1, 1),
        "not-an-image" to ByteArray(40) { (it * 7).toByte() }
    )

    private fun describe(b: ByteArray): String {
        val format = SafetyGate.detectImageFormat(b)
        val bounds = SafetyGate.inspectImageHeaderBounds(b)
        return "$format ${bounds?.let { "${it.width}x${it.height} ${it.outMimeType}" } ?: "-"}"
    }

    private fun render(): String = buildString {
        for ((name, b) in samples) {
            append("$name len=${b.size} -> ${describe(b)}\n")
            for (len in 0 until b.size) append("$name trunc=$len -> ${describe(b.copyOf(len))}\n")
            for (i in 0 until minOf(b.size, 40)) {
                for (v in intArrayOf(0x00, 0xFF, 0x7F)) {
                    val m = b.copyOf()
                    m[i] = v.toByte()
                    append("$name set[$i]=$v -> ${describe(m)}\n")
                }
            }
        }
    }

    @Test
    fun `image header parsing is unchanged for valid, truncated and mutated headers`() {
        val golden = File("src/test/resources/golden/image_header_bounds.txt")
        val actual = render()
        if (!golden.exists()) {
            golden.parentFile.mkdirs()
            golden.writeText(actual)
            fail("Recorded ${golden.path}; run the test again.")
        }
        assertEquals(golden.readText().lines(), actual.lines())
    }
}
