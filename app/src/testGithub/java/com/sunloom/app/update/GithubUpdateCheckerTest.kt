package com.sunloom.app.update

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article XVIII §1: the GitHub build can ask GitHub whether a newer release exists - only when the person taps "Check for
 * updates", only to hosts in [UpdateHosts], and it only ever shows a link; it never downloads or installs anything.
 */
@Requirement("XVIII.1")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class GithubUpdateCheckerTest {

    // --- comparing versions ---

    @Test
    fun `a later release is newer`() {
        assertTrue(ReleaseVersion.isNewer(current = "1.7.0", latestTag = "v2.0.0"))
        assertTrue(ReleaseVersion.isNewer("2.0.0", "v2.0.1"))
        assertTrue(ReleaseVersion.isNewer("2.0.9", "v2.1.0"))
    }

    @Test
    fun `the same or an earlier release is not newer`() {
        assertFalse(ReleaseVersion.isNewer("2.0.0", "v2.0.0"))
        assertFalse(ReleaseVersion.isNewer("2.0.1", "v2.0.0"))
        assertFalse(ReleaseVersion.isNewer("10.0.0", "v9.9.9"))
    }

    @Test
    fun `a release candidate is older than its release and newer than the one before`() {
        assertTrue("2.0.0 follows 2.0.0-rc.1", ReleaseVersion.isNewer("2.0.0-rc.1", "v2.0.0"))
        assertFalse("a candidate is not an update to the release", ReleaseVersion.isNewer("2.0.0", "v2.0.0-rc.2"))
        assertTrue(ReleaseVersion.isNewer("2.0.0-rc.1", "v2.0.0-rc.2"))
        assertTrue(ReleaseVersion.isNewer("1.9.0", "v2.0.0-rc.1"))
    }

    @Test
    fun `numbers are compared as numbers, not as text`() {
        assertTrue(ReleaseVersion.isNewer("2.9.0", "v2.10.0"))
        assertTrue(ReleaseVersion.isNewer("2.0.0-rc.2", "v2.0.0-rc.10"))
    }

    @Test
    fun `something that is not a version is never newer`() {
        assertFalse(ReleaseVersion.isNewer("1.7.0", "nightly"))
        assertFalse(ReleaseVersion.isNewer("1.7.0", ""))
        assertFalse(ReleaseVersion.isNewer("local-build", "v2.0.0"))
    }

    // --- reading GitHub's answer ---

    private fun entry(tag: String, draft: Boolean = false, prerelease: Boolean = false, url: String? = null) =
        """{"tag_name":"$tag","html_url":"${url ?: "https://github.com/stevin-wilson/Sunloom/releases/tag/$tag"}",""" +
            """"draft":$draft,"prerelease":$prerelease,"body":"x"}"""

    private fun list(vararg entries: String) = entries.joinToString(prefix = "[", postfix = "]", separator = ",")

    private fun check(current: String = "2.0.0", fetch: suspend (String) -> String) =
        runBlocking { GithubUpdateChecker(fetch).check(current) }

    private val page = "https://github.com/stevin-wilson/Sunloom/releases/tag"

    @Test
    fun `a newer release is reported with its page`() {
        val result = check { list(entry("v2.1.0"), entry("v2.0.0")) }

        assertEquals(UpdateCheckResult.Available("2.1.0", "$page/v2.1.0"), result)
    }

    @Test
    fun `being on the newest release says so`() {
        assertEquals(UpdateCheckResult.UpToDate("2.1.0"), check(current = "2.1.0") { list(entry("v2.1.0"), entry("v2.0.0")) })
    }

    @Test
    fun `it asks GitHub for the project's releases, and only that`() {
        var asked: String? = null

        check { url -> asked = url; list(entry("v2.1.0")) }

        assertEquals("https://api.github.com/repos/stevin-wilson/Sunloom/releases?per_page=10", asked)
        assertTrue(UpdateHosts.isAllowed(asked!!))
    }

    @Test
    fun `a failed request is reported as a failure, not as being up to date`() {
        assertEquals(UpdateCheckResult.Failed, check { throw java.io.IOException("HTTP 404") })
    }

    @Test
    fun `an answer that is not a list of releases is a failure`() {
        assertEquals(UpdateCheckResult.Failed, check { "<html>rate limited</html>" })
        assertEquals(UpdateCheckResult.Failed, check { """{"message":"Not Found"}""" })
        assertEquals(UpdateCheckResult.Failed, check { entry("v2.1.0") }) // one release, not a list
    }

    @Test
    fun `no releases yet means nothing newer, not a failure`() {
        assertEquals(UpdateCheckResult.UpToDate("2.0.0"), check { "[]" })
    }

    @Test
    fun `entries that are not versions are skipped`() {
        assertEquals(UpdateCheckResult.Available("2.1.0", "$page/v2.1.0"), check { list(entry("nightly"), entry("v2.1.0")) })
        assertEquals(UpdateCheckResult.UpToDate("2.0.0"), check { list(entry("nightly"), "42", "null") })
    }

    @Test
    fun `a page link that is not on GitHub is not trusted`() {
        val result = check { list(entry("v2.1.0", url = "https://evil.example/download.apk")) } as UpdateCheckResult.Available

        assertEquals("falls back to the project's own releases page", "https://github.com/stevin-wilson/Sunloom/releases", result.pageUrl)
    }

    @Test
    fun `a draft is never offered`() {
        assertEquals(UpdateCheckResult.UpToDate("2.0.0"), check { list(entry("v2.1.0", draft = true)) })
    }

    @Test
    fun `a release build is not offered a release candidate`() {
        assertEquals(UpdateCheckResult.UpToDate("2.0.0"), check { list(entry("v2.1.0-rc.1", prerelease = true)) })
        assertEquals(
            "it still sees the release behind the candidate",
            UpdateCheckResult.Available("2.0.1", "$page/v2.0.1"),
            check { list(entry("v2.1.0-rc.1", prerelease = true), entry("v2.0.1"), entry("v2.0.0")) }
        )
    }

    @Test
    fun `a release candidate build is offered the next candidate and the final release`() {
        assertEquals(
            UpdateCheckResult.Available("2.0.0-rc.2", "$page/v2.0.0-rc.2"),
            check(current = "2.0.0-rc.1") { list(entry("v2.0.0-rc.2", prerelease = true), entry("v2.0.0-rc.1", prerelease = true)) }
        )
        assertEquals(
            UpdateCheckResult.Available("2.0.0", "$page/v2.0.0"),
            check(current = "2.0.0-rc.2") { list(entry("v2.0.0"), entry("v2.0.0-rc.2", prerelease = true)) }
        )
        assertEquals(
            UpdateCheckResult.UpToDate("2.0.0-rc.1"),
            check(current = "2.0.0-rc.1") { list(entry("v2.0.0-rc.1", prerelease = true)) }
        )
    }

    @Test
    fun `the highest version wins whatever order GitHub lists them in`() {
        val result = check(current = "1.0.0") { list(entry("v2.0.0"), entry("v2.10.0"), entry("v2.9.0")) }

        assertEquals(UpdateCheckResult.Available("2.10.0", "$page/v2.10.0"), result)
    }

    @Test
    fun `parsing reads each release's version, page, draft and pre-release flags`() {
        val parsed = GithubReleases.parseList(list(entry("v2.1.0"), entry("v2.2.0-rc.1", prerelease = true), entry("junk")))

        assertEquals(listOf("2.1.0", "2.2.0-rc.1"), parsed?.map { it.version })
        assertEquals(listOf(false, true), parsed?.map { it.prerelease })
        assertNull(GithubReleases.parseList("not json"))
        assertNull(GithubReleases.parseList("{}"))
    }

    @Test
    fun `only a release candidate counts as a candidate`() {
        assertTrue(ReleaseVersion.isCandidate("2.0.0-rc.1"))
        assertFalse(ReleaseVersion.isCandidate("2.0.0"))
        assertFalse(ReleaseVersion.isCandidate("local-build"))
    }
}
