package com.sunloom.app.update

import com.sunloom.app.Requirement
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Article XVIII §1: the one request the GitHub build can make is short, to an allowed host only, sends nothing but a
 * User-Agent, follows no redirect, and reads a bounded answer.
 */
@Requirement("XVIII.1")
class GithubFetchTest {
    private class FakeConnection(
        url: URL,
        private val status: Int = 200,
        private val body: InputStream = ByteArrayInputStream("{}".toByteArray())
    ) : HttpURLConnection(url) {
        val headers = mutableMapOf<String, String>()
        var disconnected = false
        override fun getResponseCode() = status
        override fun getInputStream(): InputStream = body
        override fun setRequestProperty(key: String, value: String) { headers[key] = value }
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() {}
    }

    private fun fetch(url: String, make: (URL) -> FakeConnection): String = runBlocking {
        fetchFromGithub(url) { make(it) }
    }

    private val goodUrl = GithubUpdateChecker.LATEST_RELEASE_URL

    @Test
    fun `an answer is returned as text`() {
        assertEquals("""{"tag_name":"v2.1.0"}""", fetch(goodUrl) { FakeConnection(it, body = ByteArrayInputStream("""{"tag_name":"v2.1.0"}""".toByteArray())) })
    }

    @Test
    fun `it sends the user agent and asks for no redirect, and always lets go of the connection`() {
        lateinit var seen: FakeConnection

        fetch(goodUrl) { FakeConnection(it).also { c -> seen = c } }

        assertTrue(seen.headers["User-Agent"]!!.startsWith("Sunloom/"))
        assertEquals("application/vnd.github+json", seen.headers["Accept"])
        assertFalse(seen.instanceFollowRedirects)
        assertTrue(seen.disconnected)
        assertEquals("only those two headers are sent", setOf("User-Agent", "Accept"), seen.headers.keys)
    }

    @Test
    fun `any answer but OK is a failure`() {
        for (code in listOf(301, 403, 404, 500)) {
            try {
                fetch(goodUrl) { FakeConnection(it, status = code) }
                fail("HTTP $code must not count as an answer")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("$code"))
            }
        }
    }

    @Test
    fun `a host that is not an update host is never contacted`() {
        var contacted = false
        try {
            fetch("https://evil.example/releases/latest") { contacted = true; FakeConnection(it) }
            fail("only GitHub's own hosts are allowed")
        } catch (_: IOException) {
        }
        assertFalse(contacted)
        try {
            fetch("http://api.github.com/repos/x/y/releases/latest") { contacted = true; FakeConnection(it) }
            fail("and only over https")
        } catch (_: IOException) {
        }
        assertFalse(contacted)
    }

    @Test
    fun `an answer that is too large is refused instead of read to the end`() {
        val huge = object : InputStream() {
            var served = 0
            override fun read(): Int { served++; return 'x'.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int { served += len; java.util.Arrays.fill(b, off, off + len, 'x'.code.toByte()); return len }
        }
        try {
            fetch(goodUrl) { FakeConnection(it, body = huge) }
            fail("an unbounded answer must be cut off")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("too large"))
        }
        assertTrue("it stopped reading soon after the limit (${huge.served} bytes)", huge.served < 1_000_000)
    }
}
