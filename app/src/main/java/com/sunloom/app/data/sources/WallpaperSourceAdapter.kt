package com.sunloom.app.data.sources

import com.sunloom.app.BuildConfig
import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.SafeCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** The source answered, but not with the JSON this adapter expects (an HTML error page, a changed API). */
class SourceResponseException(message: String) : IllegalStateException(message)

/** Parses a source's JSON body, or fails the request when it is not the JSON object this adapter expects. */
internal fun parseSourceObject(sourceName: String, jsonText: String): SimpleJson.JsonObj =
    SimpleJson.parseObject(jsonText) ?: throw SourceResponseException("$sourceName returned a non-JSON response")

class RateLimitException(
    val retryAfterSeconds: Long,
    message: String
) : IllegalStateException(message)

class SourceBudgetExceededException(
    val sourceId: String,
    val maxRequests: Int
) : IllegalStateException("Source '$sourceId' reached its per-sync budget of $maxRequests HTTP requests")

/**
 * Abstraction for HTTPS GET requests so SourceAdapters are 100% JVM-testable via constructor DI
 * while connecting to live HTTPS endpoints on Dispatchers.IO in production.
 */
interface HttpClient {
    suspend fun getText(url: String): String
    suspend fun getBytes(url: String, maxBytes: Int = 35 * 1024 * 1024): ByteArray

    /** A client whose redirects may only reach [sourceId]'s own hosts (Article VI §7); fakes may stay unscoped. */
    fun scopedTo(sourceId: String): HttpClient = this
}

/**
 * Shared non-throwing metadata fetch helper that preserves coroutine cancellation
 * and HTTP 429 rate-limit propagation while handling per-sync budget exhaustion and
 * transient network/parse errors uniformly across all online source adapters.
 */
suspend inline fun HttpClient.fetchTextOrNull(
    url: String,
    onBudgetExceeded: () -> Unit = {}
): String? {
    return try {
        getText(url)
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (rateLimit: RateLimitException) {
        throw rateLimit
    } catch (_: SourceBudgetExceededException) {
        onBudgetExceeded()
        null
    } catch (_: Exception) {
        null
    }
}

object SourceAdapterUtils {
    fun extractHost(url: String): String =
        runCatching { URL(url).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")

    fun isSupportedRasterExtension(url: String): Boolean {
        val pathLower = url.substringBefore('?').lowercase(Locale.ROOT)
        return pathLower.endsWith(".jpg") ||
            pathLower.endsWith(".jpeg") ||
            pathLower.endsWith(".png") ||
            pathLower.endsWith(".webp")
    }

    fun isSupportedHttpsRasterUrl(url: String, sourceId: String? = null): Boolean {
        if (!url.startsWith("https://")) return false
        if (sourceId != null && !SourceRegistry.isHostAllowed(extractHost(url), sourceId)) return false
        return isSupportedRasterExtension(url)
    }
}

/**
 * Article VI §7 & Article X §4:
 * Enforces per-source request ceilings, source-scoped host allowlists, and sequential pacing
 * across both metadata queries and binary asset downloads in a sync run.
 */
class BudgetedSourceHttpClient(
    private val delegate: HttpClient,
    private val sourceId: String,
    private val maxRequests: Int,
    private val minSpacingMs: Long = 150L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val sleeper: suspend (Long) -> Unit = { delay(it) }
) : HttpClient {

    private val scopedDelegate: HttpClient = delegate.scopedTo(sourceId)

    var requestCount: Int = 0
        private set

    /** Transport/HTTP failures from the delegate (not budget or rate-limit signals), for sync reporting. */
    var httpErrorCount: Int = 0
        private set
    var lastHttpError: Throwable? = null
        private set
    var successfulRequestCount: Int = 0
        private set

    /** Bytes received so far in this sync (images and pages), for the background's daily data budget. */
    var bytesDownloaded: Long = 0L
        private set

    private var lastRequestEpochMs: Long = 0L

    private suspend fun <T> acquireAndRun(url: String, block: suspend () -> T): T {
        DefaultHttpsClient.validateHttpsAndHost(url, sourceId)
        if (requestCount >= maxRequests) {
            throw SourceBudgetExceededException(sourceId, maxRequests)
        }
        if (requestCount > 0 && minSpacingMs > 0L) {
            val elapsed = clockMs() - lastRequestEpochMs
            if (elapsed in 0 until minSpacingMs) {
                sleeper(minSpacingMs - elapsed)
            }
        }
        requestCount++
        lastRequestEpochMs = clockMs()
        return block()
    }

    private suspend fun <T> tracked(block: suspend () -> T): T = try {
        block().also { successfulRequestCount++ }
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (rateLimit: RateLimitException) {
        throw rateLimit
    } catch (err: Exception) {
        httpErrorCount++
        lastHttpError = err
        throw err
    }

    override suspend fun getText(url: String): String = acquireAndRun(url) {
        tracked { scopedDelegate.getText(url) }.also { bytesDownloaded += it.encodeToByteArray().size }
    }

    override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = acquireAndRun(url) {
        tracked { scopedDelegate.getBytes(url, maxBytes) }.also { bytesDownloaded += it.size }
    }
}

class DefaultHttpsClient(
    private val connectionFactory: (URL) -> HttpURLConnection = { u -> u.openConnection() as HttpURLConnection }
) : HttpClient {

    private data class CachedTextResponse(
        val etag: String?,
        val lastModified: String?,
        val body: String
    )

    /** Forgets the saved responses (called when the system asks the app to give memory back). */
    fun clearCaches() {
        synchronized(conditionalTextCache) { conditionalTextCache.clear() }
    }

    internal fun cachedResponseCount(): Int = synchronized(conditionalTextCache) { conditionalTextCache.size }

    private val conditionalTextCache = object : LinkedHashMap<String, CachedTextResponse>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedTextResponse>?): Boolean {
            return size > MAX_CONDITIONAL_CACHE_ENTRIES
        }
    }

    override suspend fun getText(url: String): String = fetchText(url, null)

    override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = fetchBytes(url, maxBytes, null)

    override fun scopedTo(sourceId: String): HttpClient = object : HttpClient {
        override suspend fun getText(url: String): String = fetchText(url, sourceId)
        override suspend fun getBytes(url: String, maxBytes: Int): ByteArray = fetchBytes(url, maxBytes, sourceId)
        override fun scopedTo(sourceId: String): HttpClient = this@DefaultHttpsClient.scopedTo(sourceId)
    }

    private suspend fun fetchText(url: String, scopeSourceId: String?): String = withContext(Dispatchers.IO) {
        val cached = synchronized(conditionalTextCache) { conditionalTextCache[url] }
        val deadline = System.nanoTime() + REQUEST_DEADLINE_NANOS
        executeFollowingValidatedRedirects(
            initialUrl = url,
            scopeSourceId = scopeSourceId,
            acceptHeader = "application/json",
            ifNoneMatch = cached?.etag,
            ifModifiedSince = cached?.lastModified
        ) { conn, statusCode ->
            if (statusCode == HttpURLConnection.HTTP_NOT_MODIFIED && cached != null) {
                return@executeFollowingValidatedRedirects cached.body
            }
            val contentLength = conn.contentLengthLong
            require(contentLength <= MAX_TEXT_BYTES) { "JSON payload Content-Length ($contentLength) exceeds limit ($MAX_TEXT_BYTES)" }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use {
                readTextBounded(it, MAX_TEXT_BYTES, deadlineNanos = deadline)
            }
            val etag = conn.getHeaderField("ETag")?.trim()?.takeIf { it.isNotEmpty() }
            val lastMod = conn.getHeaderField("Last-Modified")?.trim()?.takeIf { it.isNotEmpty() }
            // Only small bodies are worth retaining; 32 large JSON pages could pin tens of MB.
            if ((etag != null || lastMod != null) && text.length <= MAX_CACHED_TEXT_CHARS) {
                synchronized(conditionalTextCache) {
                    conditionalTextCache[url] = CachedTextResponse(etag, lastMod, text)
                }
            }
            text
        }
    }

    /**
     * The blocking download runs on a worker so that cancelling the caller tears the connection down at once: a read
     * stalled on a dead network would otherwise keep running until its timeout, holding the sync (and its memory).
     */
    private suspend fun fetchBytes(url: String, maxBytes: Int, scopeSourceId: String?): ByteArray = coroutineScope {
        val active = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>(null)
        val worker = async(Dispatchers.IO) {
            try {
                downloadBytes(url, maxBytes, scopeSourceId, active) { ensureActive() }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                // Tearing the connection down makes the blocked read fail ("Socket closed"). That failure is the cancellation
                // working, not a download error: a real exception would otherwise replace the cancellation.
                if (!isActive) throw CancellationException("Download cancelled").apply { initCause(failure) }
                throw failure
            }
        }
        try {
            worker.await()
        } catch (cancel: CancellationException) {
            active.get()?.disconnect()
            throw cancel
        }
    }

    private fun downloadBytes(
        url: String,
        maxBytes: Int,
        scopeSourceId: String?,
        active: java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>,
        stopIfCancelled: () -> Unit
    ): ByteArray {
        val deadline = System.nanoTime() + REQUEST_DEADLINE_NANOS
        return executeFollowingValidatedRedirects(
            initialUrl = url,
            scopeSourceId = scopeSourceId,
            acceptHeader = "image/jpeg,image/png,image/webp,*/*;q=0.8",
            track = { active.set(it) }
        ) { conn, _ ->
            val contentLength = conn.contentLengthLong
            require(contentLength <= maxBytes) { "Asset Content-Length ($contentLength bytes) exceeds maximum ($maxBytes bytes)" }
            val contentType = conn.contentType?.lowercase(Locale.ROOT).orEmpty()
            if (contentType.startsWith("text/html") || contentType.startsWith("application/xhtml")) {
                error("Unexpected HTML Content-Type '$contentType' when downloading image asset")
            }
            BufferedInputStream(conn.inputStream).use { input ->
                val buffer = DownloadBuffer(contentLength, maxBytes)
                val chunk = ByteArray(16_384)
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    stopIfCancelled()
                    if (System.nanoTime() > deadline) throw java.io.IOException("Download exceeded its deadline")
                    buffer.append(chunk, 0, read)
                }
                buffer.toByteArray()
            }
        }
    }

    private fun <T> executeFollowingValidatedRedirects(
        initialUrl: String,
        scopeSourceId: String? = null,
        acceptHeader: String,
        ifNoneMatch: String? = null,
        ifModifiedSince: String? = null,
        track: (HttpURLConnection) -> Unit = {},
        reader: (HttpURLConnection, Int) -> T
    ): T {
        var currentUrl = initialUrl
        for (hop in 0..MAX_REDIRECT_HOPS) {
            validateHttpsAndHost(currentUrl, scopeSourceId)
            val conn = connectionFactory(URL(currentUrl)).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", acceptHeader)
                if (hop == 0) {
                    if (!ifNoneMatch.isNullOrBlank()) setRequestProperty("If-None-Match", ifNoneMatch)
                    if (!ifModifiedSince.isNullOrBlank()) setRequestProperty("If-Modified-Since", ifModifiedSince)
                }
            }
            track(conn)
            try {
                val code = conn.responseCode
                if (code in REDIRECT_STATUS_CODES) {
                    if (hop == MAX_REDIRECT_HOPS) {
                        error("Exceeded maximum redirect hops ($MAX_REDIRECT_HOPS) for $initialUrl")
                    }
                    val location = conn.getHeaderField("Location")?.trim().orEmpty()
                    if (location.isEmpty()) {
                        error("HTTP $code redirect missing Location header from $currentUrl")
                    }
                    val resolvedUrl = URL(URL(currentUrl), location).toString()
                    validateHttpsAndHost(resolvedUrl, scopeSourceId)
                    currentUrl = resolvedUrl
                    continue
                }
                if (code == 429) {
                    val retryAfterSec = parseRetryAfterSeconds(conn.getHeaderField("Retry-After"))
                    throw RateLimitException(
                        retryAfterSeconds = retryAfterSec,
                        message = "HTTP 429 rate-limited from $currentUrl (Retry-After: ${retryAfterSec}s)"
                    )
                }
                if (code == HttpURLConnection.HTTP_NOT_MODIFIED && (!ifNoneMatch.isNullOrBlank() || !ifModifiedSince.isNullOrBlank())) {
                    return reader(conn, code)
                }
                if (code !in 200..299) {
                    error("HTTP $code from $currentUrl")
                }
                return reader(conn, code)
            } finally {
                conn.disconnect()
            }
        }
        error("Redirect loop terminated unexpectedly for $initialUrl")
    }

    companion object {
        const val MAX_REDIRECT_HOPS = 3
        const val MAX_TEXT_BYTES = 5L * 1024L * 1024L
        private const val MAX_CACHED_TEXT_CHARS = 512 * 1024

        /** Process-wide client so the conditional-GET cache is shared by all adapters and workers. */
        val shared: DefaultHttpsClient by lazy { DefaultHttpsClient() }

        /** A whole request, including a slow body, may not take longer than this (read timeouts are per read). */
        private const val REQUEST_DEADLINE_NANOS = 90_000_000_000L

        internal fun readTextBounded(
            reader: java.io.Reader,
            maxChars: Long,
            deadlineNanos: Long = Long.MAX_VALUE,
            nanoClock: () -> Long = System::nanoTime
        ): String {
            val out = StringBuilder()
            val buf = CharArray(8 * 1024)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                if (nanoClock() > deadlineNanos) throw java.io.IOException("Download exceeded its deadline")
                out.append(buf, 0, n)
                require(out.length <= maxChars) { "JSON payload exceeds limit ($maxChars)" }
            }
            return out.toString()
        }
        private const val MAX_CONDITIONAL_CACHE_ENTRIES = 32
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)

        /**
         * Article VI §7: the app and its version, with the project page as the way to reach us (no mailbox needed).
         */
        val USER_AGENT: String =
            "Sunloom/${BuildConfig.VERSION_NAME} (+https://github.com/stevin-wilson/Sunloom)"

        fun parseRetryAfterSeconds(rawHeader: String?, nowEpochMs: Long = System.currentTimeMillis()): Long {
            val trimmed = rawHeader?.trim().orEmpty()
            if (trimmed.isEmpty()) return 60L
            trimmed.toLongOrNull()?.let { delta ->
                return delta.coerceIn(1L, 86_400L)
            }
            val parsedEpochMs = runCatching {
                java.time.ZonedDateTime.parse(trimmed, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull() ?: return 60L
            val diffSec = ((parsedEpochMs - nowEpochMs + 999L) / 1000L)
            return diffSec.coerceIn(1L, 86_400L)
        }

        fun validateHttpsAndHost(url: String, sourceId: String? = null) {
            require(url.startsWith("https://")) {
                "Only HTTPS transport is permitted (Article VI §4f & VIII): $url"
            }
            val parsed = URL(url)
            val host = parsed.host?.lowercase(Locale.ROOT).orEmpty()
            require(SourceRegistry.isHostAllowed(host, sourceId)) {
                "Host '$host' is not in Schedule A admitted source hosts (Article VI §7)"
            }
        }
    }
}

/**
 * Article VI §3 & §4: Replaceable Source Adapter interface.
 */
interface WallpaperSourceAdapter {
    val sourceId: String
    val sourceName: String
    val licenseSummary: String
    val isBuiltInOffline: Boolean
        get() = false

    fun withHttpClient(client: HttpClient): WallpaperSourceAdapter = this

    suspend fun fetchCandidates(
        enabledCategories: Set<SafeCategory>,
        limit: Int = 8,
        offset: Int = 0,
        targetWidthPx: Int = 2560
    ): List<CandidateAsset>
}
