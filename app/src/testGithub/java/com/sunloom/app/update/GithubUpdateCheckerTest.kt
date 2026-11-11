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

    private val release = """{"tag_name":"v2.1.0","html_url":"https://github.com/stevin-wilson/Sunloom/releases/tag/v2.1.0","draft":false,"prerelease":false,"body":"x"}"""

    private fun check(current: String = "2.0.0", fetch: suspend (String) -> String) =
        runBlocking { GithubUpdateChecker(fetch).check(current) }

    @Test
    fun `a newer release is reported with its page`() {
        val result = check { release }

        assertEquals(UpdateCheckResult.Available("2.1.0", "https://github.com/stevin-wilson/Sunloom/releases/tag/v2.1.0"), result)
    }

    @Test
    fun `being on the latest release says so`() {
        assertEquals(UpdateCheckResult.UpToDate("2.1.0"), check(current = "2.1.0") { release })
    }

    @Test
    fun `it asks GitHub for the project's latest release, and only that`() {
        var asked: String? = null

        check { url -> asked = url; release }

        assertEquals("https://api.github.com/repos/stevin-wilson/Sunloom/releases/latest", asked)
        assertTrue(UpdateHosts.isAllowed(asked!!))
    }

    @Test
    fun `a failed request is reported as a failure, not as being up to date`() {
        assertEquals(UpdateCheckResult.Failed, check { throw java.io.IOException("HTTP 404") })
    }

    @Test
    fun `an answer that is not a release is a failure`() {
        assertEquals(UpdateCheckResult.Failed, check { "<html>rate limited</html>" })
        assertEquals(UpdateCheckResult.Failed, check { """{"message":"Not Found"}""" })
        assertEquals(UpdateCheckResult.Failed, check { """{"tag_name":"soon"}""" })
    }

    @Test
    fun `a page link that is not on GitHub is not trusted`() {
        val tampered = """{"tag_name":"v2.1.0","html_url":"https://evil.example/download.apk"}"""

        val result = check { tampered } as UpdateCheckResult.Available

        assertEquals("falls back to the project's own releases page", "https://github.com/stevin-wilson/Sunloom/releases/latest", result.pageUrl)
    }

    @Test
    fun `a draft or a pre-release is not offered`() {
        assertEquals(UpdateCheckResult.UpToDate("2.0.0"), check { release.replace("\"draft\":false", "\"draft\":true") })
        assertEquals(UpdateCheckResult.UpToDate("2.0.0"), check { release.replace("\"prerelease\":false", "\"prerelease\":true") })
    }

    @Test
    fun `parsing a release reads the version and page`() {
        val parsed = GithubReleases.parse(release)

        assertEquals("2.1.0", parsed?.version)
        assertNull(GithubReleases.parse("not json"))
    }
}
