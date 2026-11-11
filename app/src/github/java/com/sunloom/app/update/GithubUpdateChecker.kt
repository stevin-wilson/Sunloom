package com.sunloom.app.update

import com.sunloom.app.data.sources.DefaultHttpsClient
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A release number as the project writes it: `v2.0.0`, or `v2.0.0-rc.1` for a candidate. */
internal object ReleaseVersion {
    private val SHAPE = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-rc\.(\d+))?$""")

    private data class Parsed(val major: Int, val minor: Int, val patch: Int, val candidate: Int?) : Comparable<Parsed> {
        // A candidate comes before its own release: rc.N < final.
        override fun compareTo(other: Parsed): Int = compareValuesBy(
            this, other, { it.major }, { it.minor }, { it.patch }, { it.candidate ?: Int.MAX_VALUE }
        )
    }

    private fun parse(text: String): Parsed? = SHAPE.matchEntire(text.trim())?.destructured?.let { (a, b, c, rc) ->
        runCatching { Parsed(a.toInt(), b.toInt(), c.toInt(), rc.takeIf { it.isNotEmpty() }?.toInt()) }.getOrNull()
    }

    /** True only when both are real versions and [latestTag] is later. Anything unreadable is not an update. */
    fun isNewer(current: String, latestTag: String): Boolean {
        val installed = parse(current) ?: return false
        val latest = parse(latestTag) ?: return false
        return latest > installed
    }

    fun isVersion(text: String): Boolean = parse(text) != null
}

/** What GitHub's "latest release" answer says, once checked. */
internal data class LatestRelease(val version: String, val pageUrl: String?, val draft: Boolean, val prerelease: Boolean)

internal object GithubReleases {
    /** Reads a release; null when the text is not one (an error page, a message object, a tag that is not a version). */
    fun parse(json: String): LatestRelease? = runCatching {
        val obj = JSONObject(json)
        val tag = obj.optString("tag_name", "")
        if (!ReleaseVersion.isVersion(tag)) return null
        LatestRelease(
            version = tag.removePrefix("v"),
            pageUrl = obj.optString("html_url", "").ifBlank { null },
            draft = obj.optBoolean("draft", false),
            prerelease = obj.optBoolean("prerelease", false)
        )
    }.getOrNull()
}

/**
 * Asks GitHub for the project's latest release (Article XVIII §1). Only on request, only the hosts in [UpdateHosts], and
 * the answer is only ever shown as a link: nothing is downloaded or installed. [fetch] returns the response text.
 */
internal class GithubUpdateChecker(
    private val fetch: suspend (String) -> String = ::fetchFromGithub
) : UpdateCheckFeature {

    override suspend fun check(currentVersion: String): UpdateCheckResult = try {
        val latest = GithubReleases.parse(fetch(LATEST_RELEASE_URL))
        when {
            latest == null -> UpdateCheckResult.Failed
            latest.draft || latest.prerelease -> UpdateCheckResult.UpToDate(currentVersion)
            ReleaseVersion.isNewer(currentVersion, latest.version) ->
                UpdateCheckResult.Available(latest.version, latest.pageUrl?.takeIf(UpdateHosts::isAllowed) ?: RELEASES_PAGE_URL)
            else -> UpdateCheckResult.UpToDate(currentVersion)
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        UpdateCheckResult.Failed
    }

    companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/stevin-wilson/Sunloom/releases/latest"
        const val RELEASES_PAGE_URL = "https://github.com/stevin-wilson/Sunloom/releases/latest"
    }
}

/** One short GET to an allowed host: no redirects, small timeouts, a size limit, and nothing but the User-Agent sent. */
private suspend fun fetchFromGithub(url: String): String = withContext(Dispatchers.IO) {
    if (!UpdateHosts.isAllowed(url)) throw IOException("Not an update host: $url")
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("User-Agent", DefaultHttpsClient.USER_AGENT)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
        connection.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                out.write(chunk, 0, read)
                if (out.size() > MAX_RESPONSE_BYTES) throw IOException("Response too large")
            }
            out.toString(Charsets.UTF_8.name())
        }
    } finally {
        connection.disconnect()
    }
}

private const val MAX_RESPONSE_BYTES = 256 * 1024
