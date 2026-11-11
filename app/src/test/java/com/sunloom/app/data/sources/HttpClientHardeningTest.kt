package com.sunloom.app.data.sources

import com.sunloom.app.Requirement
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.launch
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

    // --- downloads: complete, and stoppable ---

    private fun imageConnection(url: URL, body: ByteArray, onDisconnect: () -> Unit = {}, stream: () -> InputStream = { body.inputStream() }) =
        object : HttpURLConnection(url) {
            override fun getResponseCode(): Int = 200
            override fun getContentLengthLong(): Long = body.size.toLong()
            override fun getContentType(): String = "image/jpeg"
            override fun getInputStream(): InputStream = stream()
            override fun disconnect() = onDisconnect()
            override fun usingProxy(): Boolean = false
            override fun connect() {}
        }

    @Test
    fun `a download comes back whole`() = runBlocking {
        val body = ByteArray(70_000) { (it % 199).toByte() }
        val client = DefaultHttpsClient { url -> imageConnection(url, body) }

        val got = client.getBytes("https://collectionapi.metmuseum.org/x.jpg", 1_000_000)

        assertEquals(body.toList(), got.toList())
    }

    @Test
    fun `cancelling a download that is waiting on the network disconnects it at once`() {
        val readStarted = java.util.concurrent.CountDownLatch(1)
        val disconnected = java.util.concurrent.CountDownLatch(1)
        val stalled = object : InputStream() {
            override fun read(): Int = read(ByteArray(1), 0, 1)
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                readStarted.countDown()
                // A stalled network read: it returns only when the connection is torn down.
                if (!disconnected.await(30, java.util.concurrent.TimeUnit.SECONDS)) return -1
                throw java.io.IOException("Socket closed")
            }
        }
        val client = DefaultHttpsClient { url ->
            imageConnection(url, ByteArray(10), onDisconnect = { disconnected.countDown() }, stream = { stalled })
        }

        runBlocking {
            val job = launch(kotlinx.coroutines.Dispatchers.Default) {
                client.getBytes("https://collectionapi.metmuseum.org/x.jpg", 1_000_000)
            }
            assertTrue("the read started", readStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))

            job.cancel()
            val finished = kotlinx.coroutines.withTimeoutOrNull(5_000) { job.join(); true }

            assertEquals("cancelling must not wait for the network", true, finished)
            assertEquals("the connection was torn down", 0L, disconnected.count)
        }
    }

    @Test
    fun `clearing the caches forgets saved responses`() = runBlocking {
        val client = DefaultHttpsClient { url ->
            object : HttpURLConnection(url) {
                override fun getResponseCode(): Int = 200
                override fun getHeaderField(name: String): String? = if (name == "ETag") "\"v1\"" else null
                override fun getContentLengthLong(): Long = 2L
                override fun getInputStream(): InputStream = "{}".byteInputStream()
                override fun disconnect() {}
                override fun usingProxy(): Boolean = false
                override fun connect() {}
            }
        }
        client.getText("https://collectionapi.metmuseum.org/a")
        assertEquals("a response with an ETag is kept for conditional requests", 1, client.cachedResponseCount())

        client.clearCaches()

        assertEquals(0, client.cachedResponseCount())
    }
}
