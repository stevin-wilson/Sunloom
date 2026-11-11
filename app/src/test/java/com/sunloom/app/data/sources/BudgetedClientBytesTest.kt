package com.sunloom.app.data.sources

import com.sunloom.app.Requirement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Article X §2/§4: a sync knows how much it downloaded, so the background can keep to a daily byte budget. */
@Requirement("X.4")
class BudgetedClientBytesTest {
    private fun client(failing: Boolean = false) = BudgetedSourceHttpClient(
        delegate = object : HttpClient {
            override suspend fun getText(url: String): String {
                if (failing) throw java.io.IOException("down")
                return "abcd"
            }
            override suspend fun getBytes(url: String, maxBytes: Int): ByteArray {
                if (failing) throw java.io.IOException("down")
                return ByteArray(1000)
            }
            override fun scopedTo(sourceId: String): HttpClient = this
        },
        sourceId = "met_museum_cc0",
        maxRequests = 10,
        minSpacingMs = 0L
    )

    @Test
    fun `it adds up the images and the pages it fetched`() = runBlocking {
        val c = client()

        c.getBytes("https://images.metmuseum.org/a.jpg")
        c.getBytes("https://images.metmuseum.org/b.jpg")
        c.getText("https://collectionapi.metmuseum.org/public/collection/v1.1/search")

        assertEquals(2004L, c.bytesDownloaded)
    }

    @Test
    fun `a failed request adds nothing`() = runBlocking {
        val c = client(failing = true)

        runCatching { c.getBytes("https://images.metmuseum.org/a.jpg") }

        assertEquals(0L, c.bytesDownloaded)
    }
}
