package com.sunloom.app.domain.safety

import com.sunloom.app.Property
import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.ImageFormat
import com.sunloom.app.domain.model.SafetyVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article VII §4 L3: the picture's size is read from its header, before anything is decoded, from bytes nobody trusts.
 * The headers here are built from the format specifications (PNG IHDR, the JPEG segment structure, the three WebP
 * flavours), not from the parser, so these check the parser against the formats rather than against itself:
 * a well-formed header gives exactly its size, a cut or damaged one never gives a wrong size and never crashes, and what
 * the gate lets through is exactly what the limits allow, for sizes far past the limits too.
 */
@Requirement("VII.4", "VII.4.l")
class HeaderBoundsPropertyTest {

    private val gate = SafetyGate()

    // --- Building headers from the format specifications ---

    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun be16(v: Int) = bytes(v ushr 8, v)
    private fun be32(v: Long) = bytes((v ushr 24).toInt(), (v ushr 16).toInt(), (v ushr 8).toInt(), v.toInt())
    private fun le16(v: Int) = bytes(v, v ushr 8)
    private fun le24(v: Int) = bytes(v, v ushr 8, v ushr 16)
    private fun le32(v: Int) = bytes(v, v ushr 8, v ushr 16, v ushr 24)

    private fun Property.Case.noise(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    /** A size, leaning on the values where a parser slips: 1, 2, the format's largest, one under, and the L3 limit. */
    private fun Property.Case.dimension(max: Long): Long = when (int(0, 3)) {
        0 -> oneOf(listOf(1L, 2L, max, max - 1, 16_384L, 16_385L, 10_000L)).coerceIn(1L, max)
        else -> random.nextLong(1L, max + 1)
    }

    /** A header with the size it says it has. */
    private class Header(val bytes: ByteArray, val width: Long, val height: Long, val format: ImageFormat, val kind: String) {
        override fun toString() = "$kind ${width}x$height (${bytes.size} bytes)"
    }

    private fun png(case: Property.Case, w: Long, h: Long): Header {
        // Signature, then the IHDR chunk: length 13, "IHDR", width, height (four-byte big-endian), five more fields, CRC.
        val body = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + be32(13) + ascii("IHDR") + be32(w) + be32(h) +
            bytes(8, 2, 0, 0, 0) + case.noise(4) + case.noise(case.int(0, 40))
        return Header(body, w, h, ImageFormat.PNG, "PNG")
    }

    private fun jpeg(case: Property.Case, w: Long, h: Long): Header {
        val out = java.io.ByteArrayOutputStream()
        out.write(bytes(0xFF, 0xD8))
        repeat(case.int(0, 4)) {
            if (case.boolean()) out.write(bytes(0xFF, 0xFF)) // fill bytes may come before any marker
            when (case.int(0, 3)) {
                0 -> segment(out, case.int(0xE0, 0xEF), thumbnailLike(case)) // APPn, which may hold a smaller picture of its own
                1 -> segment(out, 0xDB, case.noise(case.int(0, 64))) // quantisation tables
                2 -> segment(out, 0xC4, case.noise(case.int(0, 64))) // Huffman tables: in the SOF range, but not a frame
                else -> segment(out, 0xFE, case.noise(case.int(0, 30))) // comment
            }
        }
        val frame = case.oneOf((0xC0..0xCF).filter { it != 0xC4 && it != 0xC8 && it != 0xCC })
        val components = case.int(1, 4)
        segment(out, frame, bytes(8) + be16(h.toInt()) + be16(w.toInt()) + bytes(components) + case.noise(3 * components))
        out.write(case.noise(case.int(0, 30)))
        return Header(out.toByteArray(), w, h, ImageFormat.JPEG, "JPEG frame 0x${frame.toString(16)}")
    }

    /** Segment data that sometimes contains a whole smaller picture's frame header, as an EXIF thumbnail does. */
    private fun thumbnailLike(case: Property.Case): ByteArray {
        val data = case.noise(case.int(0, 40))
        return if (case.boolean()) data + bytes(0xFF, 0xD8, 0xFF, 0xC0, 0, 11, 8, 0, 90, 0, 120, 3) + case.noise(5) else data
    }

    private fun segment(out: java.io.ByteArrayOutputStream, marker: Int, data: ByteArray) {
        out.write(bytes(0xFF, marker))
        out.write(be16(data.size + 2))
        out.write(data)
    }

    private fun riff(chunks: ByteArray): ByteArray = ascii("RIFF") + le32(chunks.size + 4) + ascii("WEBP") + chunks

    private fun webp(case: Property.Case, flavour: Int, w: Long, h: Long): Header = when (flavour) {
        0 -> { // lossy: frame tag (a key frame), start code, then 14-bit sizes with a two-bit scale on top, which is not size
            val payload = bytes(case.int(0, 127) shl 1, case.int(0, 255), case.int(0, 255)) + bytes(0x9D, 0x01, 0x2A) +
                le16(w.toInt() or (case.int(0, 3) shl 14)) + le16(h.toInt() or (case.int(0, 3) shl 14)) + case.noise(case.int(0, 20))
            Header(riff(ascii("VP8 ") + le32(payload.size) + payload), w, h, ImageFormat.WEBP, "WebP lossy")
        }
        1 -> { // lossless: signature 0x2F, then 14 bits of width-1, 14 bits of height-1, an alpha bit, three version bits
            val packed = ((w - 1) or ((h - 1) shl 14) or (case.int(0, 1).toLong() shl 28)).toInt()
            val payload = bytes(0x2F) + le32(packed) + case.noise(case.int(0, 20))
            Header(riff(ascii("VP8L") + le32(payload.size) + payload), w, h, ImageFormat.WEBP, "WebP lossless")
        }
        else -> { // extended: flags, three reserved bytes, then the canvas as 24-bit width-1 and height-1
            val payload = bytes(case.int(0, 255), 0, 0, 0) + le24((w - 1).toInt()) + le24((h - 1).toInt())
            Header(riff(ascii("VP8X") + le32(payload.size) + payload + case.noise(case.int(0, 20))), w, h, ImageFormat.WEBP, "WebP extended")
        }
    }

    /**
     * A random well-formed header followed by some picture data. The sizes run to everything the format can say, not just
     * to what is plausible. A real picture is never just its header (the gate wants 4 KB at least), and the WebP reader
     * looks 30 bytes in, so the header is padded to that.
     */
    private fun header(case: Property.Case): Header = withData(case, rawHeader(case))

    private fun withData(case: Property.Case, h: Header) =
        if (h.bytes.size >= 30) h else Header(h.bytes + case.noise(30 - h.bytes.size), h.width, h.height, h.format, h.kind)

    private fun rawHeader(case: Property.Case): Header = when (case.int(0, 4)) {
        0 -> png(case, case.dimension(Int.MAX_VALUE.toLong()), case.dimension(Int.MAX_VALUE.toLong())) // PNG sizes go to 2^31-1
        1 -> jpeg(case, case.dimension(65_535), case.dimension(65_535))
        2 -> webp(case, 0, case.dimension(16_383), case.dimension(16_383))
        3 -> webp(case, 1, case.dimension(16_384), case.dimension(16_384))
        else -> webp(case, 2, case.dimension(1L shl 24), case.dimension(1L shl 24))
    }

    // --- Properties ---

    @Test
    fun `a well-formed header gives exactly its size and its own type`() {
        Property.forAll("header size is read exactly") { case ->
            val h = header(case)
            case.input = h.toString()
            val bounds = SafetyGate.inspectImageHeaderBounds(h.bytes)
            assertNotNull("a well-formed header must be readable", bounds)
            assertEquals("width", h.width, bounds!!.width.toLong())
            assertEquals("height", h.height, bounds.height.toLong())
            assertEquals("type", h.format.mimeType, bounds.outMimeType)
            assertEquals("the type the bytes announce", h.format, SafetyGate.detectImageFormat(h.bytes))
        }
    }

    @Test
    fun `a PNG side too big for a signed integer reads as the largest number, and a zero side is unreadable`() {
        Property.forAll("PNG sides past 2^31") { case ->
            val big = case.random.nextLong(1L shl 31, 0xFFFF_FFFFL + 1)
            val other = case.dimension(Int.MAX_VALUE.toLong())
            val (w, h) = if (case.boolean()) big to other else other to big
            val header = withData(case, png(case, w, h))
            case.input = header.toString()
            val bounds = SafetyGate.inspectImageHeaderBounds(header.bytes)
            assertNotNull("a side of $big is past every limit, so it is reported for the limits to refuse", bounds)
            assertTrue("the oversized side is reported as the largest number", maxOf(bounds!!.width, bounds.height) == Int.MAX_VALUE)
            assertNull("a zero side is no picture", SafetyGate.inspectImageHeaderBounds(withData(case, png(case, 0L, other)).bytes))
            assertNull("a zero side is no picture", SafetyGate.inspectImageHeaderBounds(withData(case, png(case, other, 0L)).bytes))
        }
    }

    @Test
    fun `cutting a header short never gives a wrong size`() {
        Property.forAll("a prefix of a header is unreadable or right", cases = 150) { case ->
            val h = header(case)
            case.input = h.toString()
            for (length in 0..h.bytes.size) {
                val bounds = SafetyGate.inspectImageHeaderBounds(h.bytes.copyOf(length))
                if (bounds != null) {
                    assertEquals("cut at $length: width", h.width, bounds.width.toLong())
                    assertEquals("cut at $length: height", h.height, bounds.height.toLong())
                }
            }
        }
    }

    @Test
    fun `damaged or random bytes never crash the reader, and any size it gives is a real one`() {
        Property.forAll("damaged headers") { case ->
            val h = header(case)
            val damaged = h.bytes.copyOf()
            val damage = case.int(0, 3)
            repeat(case.int(1, 6)) { if (damaged.isNotEmpty()) damaged[case.int(0, damaged.size - 1)] = case.int(0, 255).toByte() }
            val tested = when (damage) {
                0 -> damaged // a few bytes changed
                1 -> damaged.copyOf(case.int(0, damaged.size)) // changed, then cut
                2 -> case.noise(case.int(0, 200)) // nothing but noise
                else -> h.bytes.copyOf(12) + case.noise(case.int(0, 100)) // a real signature, then noise
            }
            case.input = "${h}, damage kind $damage, ${tested.size} bytes: ${tested.take(32).joinToString(" ") { "%02x".format(it) }}"
            val bounds = SafetyGate.inspectImageHeaderBounds(tested)
            if (bounds != null) {
                assertTrue("width ${bounds.width} must be positive", bounds.width >= 1)
                assertTrue("height ${bounds.height} must be positive", bounds.height >= 1)
                assertEquals("the type must be the one the bytes announce", SafetyGate.detectImageFormat(tested).mimeType, bounds.outMimeType)
            }
        }
    }

    @Test
    fun `bytes that do not announce a picture never get a size`() {
        Property.forAll("not a picture, no size") { case ->
            val body = case.noise(case.int(0, 300))
            if (body.isNotEmpty()) body[0] = 0 // no JPEG, PNG or RIFF signature starts with a zero byte
            case.input = "${body.size} bytes starting ${body.take(8).joinToString(" ") { "%02x".format(it) }}"
            assertEquals(ImageFormat.UNSUPPORTED, SafetyGate.detectImageFormat(body))
            assertNull(SafetyGate.inspectImageHeaderBounds(body))
        }
    }

    // --- What the gate does with those sizes (Article VII §4 L3: <= 16,384 per side, <= 100 MP) ---

    private fun payload(h: Header): ByteArray = h.bytes + ByteArray(maxOf(0, SafetyGate.MIN_PAYLOAD_BYTES - h.bytes.size))

    @Test
    fun `the gate lets a picture through exactly when its header is inside the limits`() {
        Property.forAll("L3 limits, whatever the declared size") { case ->
            val h = header(case)
            case.input = h.toString()
            val inside = h.width <= 16_384 && h.height <= 16_384 && h.width * h.height <= 100_000_000L
            val verdict = gate.evaluatePayload(SafetyFixtures.candidate(), payload(h))
            if (inside) {
                assertTrue("inside the limits but refused: $verdict", verdict is SafetyVerdict.Approved)
            } else {
                assertTrue("outside the limits but approved", verdict is SafetyVerdict.Rejected)
                assertEquals("IMAGE_DIMENSIONS_OUT_OF_BOUNDS", (verdict as SafetyVerdict.Rejected).reasonCode)
                assertEquals("L3", verdict.failedLayer)
            }
        }
    }

    @Test
    fun `a PNG that declares a side above the limit is never approved, even when the number does not fit a signed integer`() {
        Property.forAll("an oversized PNG is refused however large the number") { case ->
            // Any four-byte pattern is a possible width, including those with the top bit set (2^31 and up).
            val big = case.random.nextLong(16_385L, 0xFFFF_FFFFL + 1)
            val small = case.dimension(16_384)
            val (w, h) = if (case.boolean()) big to small else small to big
            val p = png(case, w, h)
            case.input = p.toString()
            val verdict = gate.evaluatePayload(SafetyFixtures.candidate(), payload(p))
            assertTrue("a PNG declaring ${w}x$h must not be approved, was $verdict", verdict is SafetyVerdict.Rejected)
        }
    }
}
