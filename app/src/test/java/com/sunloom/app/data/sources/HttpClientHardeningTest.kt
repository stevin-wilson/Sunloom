package com.sunloom.app.data.sources

import com.sunloom.app.Requirement
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Article VI §7: redirects stay on the source's own hosts, and one request cannot be held open indefinitely. */
@Requirement("VI.7")
class HttpClientHardeningTest {

    private fun redirecting(location: String, requested: MutableList<String>) = DefaultHttpsClient { url ->
        requested += url.toString()
        object : HttpURLConnection(url) {
            override fun getResponseCode(): Int = if (url.host == "collectionapi.metmuseum.org") 302 else 200
            override fun getHeaderField(name: String): String? = if (name == "Location") location else null
            override fun getContentLengthLong(): Long = 2L
            override fun getInputStream(): InputStream = "{}".byteInputStream()
            override fun disconnect() {}
            override fun usingProxy(): Boolean = false
            override fun connect() {}
        }
    }

    @Test
    fun `a redirect to another source's host is refused for a scoped client`() = runBlocking {
        val requested = mutableListOf<String>()
        val client = redirecting("https://upload.wikimedia.org/x.json", requested).scopedTo("met_museum_cc0")
        try {
            client.getText("https://collectionapi.metmuseum.org/public/collection/v1.1/search")
            fail("a Met request must not be redirected to a Wikimedia host")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("upload.wikimedia.org"))
        }
        assertEquals("the other host is never contacted", listOf("https://collectionapi.metmuseum.org/public/collection/v1.1/search"), requested)
    }

    @Test
    fun `a redirect within the source's own hosts is followed`() = runBlocking {
        val requested = mutableListOf<String>()
        val client = redirecting("https://www.metmuseum.org/x.json", requested).scopedTo("met_museum_cc0")
        assertEquals("{}", client.getText("https://collectionapi.metmuseum.org/a"))
        assertEquals(2, requested.size)
    }

    @Test
    fun `a body that keeps dripping past the deadline is abandoned`() {
        var now = 0L
        val drip = object : java.io.Reader() {
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                now += 10_000_000_000L // each read takes 10 s
                cbuf[off] = 'x'
                return 1
            }
            override fun close() {}
        }
        try {
            DefaultHttpsClient.readTextBounded(drip, 1_000_000L, deadlineNanos = 25_000_000_000L, nanoClock = { now })
            fail("a slow-drip body must hit the deadline")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("deadline"))
        }
    }

    @Test
    fun `URL host check helper agrees with the registry`() {
        assertTrue(SourceRegistry.isHostAllowed(URL("https://www.metmuseum.org/x").host, "met_museum_cc0"))
    }
}
