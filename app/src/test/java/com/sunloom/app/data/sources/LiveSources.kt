package com.sunloom.app.data.sources

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

/** The online adapters the live checks call, built on one [client]. Both opt-in live tests use this so they cannot drift apart. */
internal object LiveSources {
    fun adapters(client: HttpClient = DefaultHttpsClient.shared): List<WallpaperSourceAdapter> = listOf(
        WikimediaFeaturedSourceAdapter(client),
        NasaLibrarySourceAdapter(client),
        ArtInstituteCc0SourceAdapter(client),
        MetMuseumCc0SourceAdapter(client),
        ClevelandMuseumCc0SourceAdapter(client),
        SmkOpenSourceAdapter(client)
    )
}

/**
 * Passes every request through and remembers what went wrong, because the adapters swallow most transport errors and
 * return an empty list: without this a timeout and a source that really has nothing look the same.
 */
internal class RecordingHttpClient(private val delegate: HttpClient) : HttpClient {
    private val requests = AtomicInteger(0)
    private val lastError = AtomicReference<Throwable?>(null)

    val requestCount: Int get() = requests.get()

    /** The last transport error seen (not a rate limit, which adapters rethrow), or null. */
    val httpError: Throwable? get() = lastError.get()

    private suspend fun <T> recording(block: suspend () -> T): T {
        count()
        try {
            return block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (rateLimit: RateLimitException) {
            throw rateLimit
        } catch (e: Exception) {
            note(e)
            throw e
        }
    }

    override suspend fun getText(url: String): String = recording { delegate.getText(url) }

    override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = recording { delegate.getBytes(url, maxBytes) }

    /** The scoped copy the budgeted client really calls reports into this one, so the check sees its requests and errors. */
    override fun scopedTo(sourceId: String): HttpClient = RecordingHttpClient(delegate.scopedTo(sourceId)).also { it.parent = this }

    private var parent: RecordingHttpClient? = null

    private fun count() {
        requests.incrementAndGet()
        parent?.count()
    }

    private fun note(error: Throwable) {
        lastError.set(error)
        parent?.note(error)
    }
}
