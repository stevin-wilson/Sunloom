package com.sunloom.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the release workflow must do for Article XVII §1, read from `.github/workflows/release.yml`: build the signed APK
 * for the `github` flavor and, for Google Play, the signed Android App Bundle for the `play` flavor, from one tag, with the
 * same key, and leave everything in a draft with checksums. The workflow cannot be run here, so these check what it says;
 * the real proof is the rehearsal run described in `docs/RELEASING.md`. Comments are ignored, so a note about a step that
 * was removed does not keep a rule "proved".
 */
@Requirement("XVII.1")
class ReleaseWorkflowTest {

    private val workflow = File("../.github/workflows/release.yml")

    /** The workflow with comment lines and trailing comments removed. */
    private val code: String by lazy {
        assertTrue("release.yml not found from ${File(".").absolutePath}", workflow.isFile)
        workflow.readLines().map { it.substringBefore(" #") }.filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
    }

    @Test
    fun `one signed run builds the github APK and the play bundle`() {
        assertTrue(":app:assembleGithubRelease" in code)
        assertTrue(":app:bundlePlayRelease" in code)
        // Both come from the same Gradle call, so both get the same tag-derived version and the same signing credentials.
        val buildCall = code.lines().filter { ":app:bundlePlayRelease" in it || ":app:assembleGithubRelease" in it }
        assertTrue("the APK and the bundle are built in separate calls: $buildCall", buildCall.size == 1)
    }

    @Test
    fun `the bundle is signed with the same key as the APK`() {
        // An .aab is a jar: jarsigner must verify it, and its certificate must be the one the APK carries.
        assertTrue("the bundle's signature is not verified", "jarsigner -verify" in code)
        assertTrue("the bundle's certificate is not compared with the APK's", "keytool -printcert -jarfile" in code)
    }

    @Test
    fun `the bundle is named, checksummed and attached to the draft`() {
        assertTrue("Sunloom-\${versionName}-play.aab" in code)
        // The checksum file lists every file attached, so what a person downloads can be checked.
        assertTrue("the bundle is not in SHA256SUMS", Regex("""sha256sum[^\n]*-play\.aab""").containsMatchIn(code))
        assertTrue("the bundle is not attached to the release", Regex("""gh release create[^\n]*-play\.aab""").containsMatchIn(code))
    }

    @Test
    fun `the play mapping is kept for reading crash traces`() {
        assertTrue("outputs/mapping/playRelease/mapping.prt" in code)
    }

    @Test
    fun `the release stays a draft and nothing is sent to Google Play`() {
        assertTrue("--draft" in code)
        // The first upload is made by hand in Play Console (docs/RELEASING.md); no Play credentials belong in this workflow.
        val playUpload = listOf("androidpublisher", "upload-google-play", "r0adkll", "publishPlay", "SERVICE_ACCOUNT", "fastlane")
        val found = playUpload.filter { it.lowercase() in code.lowercase() }
        assertFalse("the workflow talks to Google Play: $found", found.isNotEmpty())
    }
}
