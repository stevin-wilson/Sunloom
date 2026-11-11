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

    /** True for a release candidate such as `2.0.0-rc.1`; false for a release and for anything that is not a version. */
    fun isCandidate(text: String): Boolean = parse(text)?.candidate != null

    /** The highest of [versions] by version number (not by order of appearance), or null when there are none. */
    fun highest(versions: List<String>): String? = versions.mapNotNull { v -> parse(v)?.let { v to it } }.maxByOrNull { it.second }?.first

    fun isVersion(text: String): Boolean = parse(text) != null
}

/** One release as GitHub lists it. */
internal data class LatestRelease(val version: String, val pageUrl: String?, val draft: Boolean, val prerelease: Boolean)

internal object GithubReleases {
    private fun release(obj: JSONObject): LatestRelease? {
        val tag = obj.optString("tag_name", "")
        if (!ReleaseVersion.isVersion(tag)) return null
        return LatestRelease(
            version = tag.removePrefix("v"),
            pageUrl = obj.optString("html_url", "").ifBlank { null },
            draft = obj.optBoolean("draft", false),
            prerelease = obj.optBoolean("prerelease", false)
        )
    }

    /**
     * Reads GitHub's list of releases. Null when the text is not a list (an error page, a message object, one release on
     * its own); entries that are not releases, or whose tag is not a version, are left out.
     */
    fun parseList(json: String): List<LatestRelease>? = runCatching {
        val array = org.json.JSONArray(json)
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::release) }
    }.getOrNull()
}

/**
 * Asks GitHub for the project's published releases (Article XVIII §1) and offers the highest one that is newer than the
 * installed version. A release build is only ever offered releases; a release-candidate build is offered the next
 * candidate or the final release. Only on request, only the hosts in [UpdateHosts], and the answer is only ever shown as a
 * link: nothing is downloaded or installed. [fetch] returns the response text.
 */
internal class GithubUpdateChecker(
    private val fetch: suspend (String) -> String = { url -> fetchFromGithub(url) }
) : UpdateCheckFeature {

    override suspend fun check(currentVersion: String): UpdateCheckResult = try {
        val releases = GithubReleases.parseList(fetch(RELEASES_URL))
        if (releases == null) {
            UpdateCheckResult.Failed
        } else {
            val offered = releases.filter { !it.draft && (!it.prerelease || ReleaseVersion.isCandidate(currentVersion)) }
            val best = ReleaseVersion.highest(offered.map { it.version })?.let { v -> offered.first { it.version == v } }
            if (best != null && ReleaseVersion.isNewer(currentVersion, best.version)) {
                UpdateCheckResult.Available(best.version, best.pageUrl?.takeIf(UpdateHosts::isAllowed) ?: RELEASES_PAGE_URL)
            } else {
                UpdateCheckResult.UpToDate(currentVersion)
            }
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        UpdateCheckResult.Failed
    }

    companion object {
        const val RELEASES_URL = "https://api.github.com/repos/stevin-wilson/Sunloom/releases?per_page=10"
        const val RELEASES_PAGE_URL = "https://github.com/stevin-wilson/Sunloom/releases"
    }
}

/**
 * One short GET to an allowed host: no redirects, small timeouts, a size limit, and nothing but the User-Agent (and the
 * Accept header GitHub asks for) sent. [open] makes the connection; tests replace it.
 */
internal suspend fun fetchFromGithub(
    url: String,
    open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
): String = withContext(Dispatchers.IO) {
    if (!UpdateHosts.isAllowed(url)) throw IOException("Not an update host: $url")
    val connection = open(URL(url))
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
