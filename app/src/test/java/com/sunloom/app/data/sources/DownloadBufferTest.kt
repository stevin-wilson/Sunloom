package com.sunloom.app.data.sources

import com.sunloom.app.Requirement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/**
 * Article X §3 / XII §2: a download is held once, not twice. When the server says how long the file is, the bytes land in
 * one array of that size and are handed over as it is; a plain growing stream followed by a copy held the payload twice
 * (up to 70 MB for a 35 MB picture).
 */
@Requirement("X.3")
class DownloadBufferTest {
    private fun fill(buffer: DownloadBuffer, bytes: ByteArray, chunk: Int = 7) {
        var offset = 0
        while (offset < bytes.size) {
            val n = minOf(chunk, bytes.size - offset)
            buffer.append(bytes, offset, n)
            offset += n
        }
    }

    @Test
    fun `when the announced length is right the array is handed over without a copy`() {
        val payload = ByteArray(100) { it.toByte() }
        val buffer = DownloadBuffer(expectedLength = 100, maxBytes = 1000)
        val first = buffer.backingArray

        fill(buffer, payload)

        val result = buffer.toByteArray()
        assertSame("no second copy of the payload", first, result)
        assertArrayEquals(payload, result)
    }

    @Test
    fun `a shorter body gets an array of exactly its size`() {
        val payload = ByteArray(40) { (it * 3).toByte() }
        val buffer = DownloadBuffer(expectedLength = 100, maxBytes = 1000)

        fill(buffer, payload)

        val result = buffer.toByteArray()
        assertEquals(40, result.size)
        assertArrayEquals(payload, result)
    }

    @Test
    fun `a longer body than announced grows and keeps every byte`() {
        val payload = ByteArray(5000) { (it % 251).toByte() }
        val buffer = DownloadBuffer(expectedLength = 100, maxBytes = 10_000)

        fill(buffer, payload, chunk = 333)

        assertArrayEquals(payload, buffer.toByteArray())
    }

    @Test
    fun `an unknown length starts small and still works`() {
        val payload = ByteArray(200_000) { (it % 13).toByte() }
        val buffer = DownloadBuffer(expectedLength = -1, maxBytes = 1_000_000)

        fill(buffer, payload, chunk = 16_384)

        assertArrayEquals(payload, buffer.toByteArray())
    }

    @Test
    fun `the size limit is enforced as bytes arrive`() {
        val buffer = DownloadBuffer(expectedLength = -1, maxBytes = 50)

        try {
            fill(buffer, ByteArray(80))
            fail("a body over the limit must be refused")
        } catch (e: IllegalArgumentException) {
            assertEquals(true, e.message!!.contains("50"))
        }
    }

    @Test
    fun `an announced length over the limit never allocates that much`() {
        val buffer = DownloadBuffer(expectedLength = 900_000_000L, maxBytes = 1000)

        assertEquals(true, buffer.backingArray.size <= 1000)
        assertNotSame(ByteArray(0), buffer.backingArray)
    }
}
