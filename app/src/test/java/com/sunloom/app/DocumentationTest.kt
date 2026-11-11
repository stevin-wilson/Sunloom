package com.sunloom.app

import com.sunloom.app.data.sources.SourceRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Someone who finds Sunloom on GitHub reads the documents before the code. They must exist, link to each other without
 * dead ends, and not disagree with the app about what it contacts or asks for (Constitution Article XVI).
 */
@Requirement("XVI.1")
class DocumentationTest {
    private val root: File = File("..").canonicalFile

    private fun text(path: String): String = File(root, path).also { assertTrue("$path is missing", it.isFile) }.readText()

    private val required = listOf(
        "README.md", "LICENSE", "NOTICE", "CHANGELOG.md", "CONTRIBUTING.md", "SECURITY.md",
        "docs/USER_GUIDE.md", "docs/ARCHITECTURE.md", "docs/RELEASING.md", "docs/PRIVACY_POLICY.md",
        ".github/ISSUE_TEMPLATE/bug_report.yml", ".github/ISSUE_TEMPLATE/feature_request.yml",
        ".github/ISSUE_TEMPLATE/config.yml", ".github/pull_request_template.md"
    )

    @Test
    fun `the documents a visitor expects are all there`() {
        val missing = required.filterNot { File(root, it).isFile }
        assertTrue("missing: $missing", missing.isEmpty())
    }

    @Test
    fun `links between documents resolve`() {
        val link = Regex("\\]\\(((?!https?://|mailto:|#)[^)\\s#]+)(#[^)]*)?\\)")
        val broken = listOf("README.md", "CONTRIBUTING.md", "SECURITY.md", "docs/USER_GUIDE.md", "docs/ARCHITECTURE.md", "docs/RELEASING.md")
            .flatMap { doc ->
                val dir = File(root, doc).parentFile
                link.findAll(text(doc)).map { it.groupValues[1] }.filterNot { File(dir, it).exists() }.map { "$doc -> $it" }.toList()
            }
        assertTrue("dead links: $broken", broken.isEmpty())
    }

    @Test
    fun `the privacy policy names every host Sunloom may contact`() {
        val policy = text("docs/PRIVACY_POLICY.md")
        val hosts = SourceRegistry.admittedSources().flatMap { it.allowedHosts } + "api.github.com"
        val missing = hosts.filterNot { policy.contains("`$it`") }
        assertTrue("hosts missing from the privacy policy: $missing", missing.isEmpty())
    }

    @Test
    fun `the privacy policy and README explain every permission the manifest asks for`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val permissions = Regex("uses-permission android:name=\"android.permission.([A-Z_]+)\"").findAll(manifest).map { it.groupValues[1] }.toList()
        assertTrue(permissions.isNotEmpty())
        val readme = text("README.md")
        val missing = permissions.filterNot { readme.contains(it) }
        assertTrue("permissions the README does not explain: $missing", missing.isEmpty())
    }

    @Test
    fun `the policy is written for the GitHub release, not a store listing`() {
        val policy = text("docs/PRIVACY_POLICY.md")
        assertFalse("still tells the maintainer to fill in the Play Console", policy.contains("Play Console"))
        assertFalse(policy.contains("Replace the contact line"))
        assertTrue(policy.contains("https://github.com/stevin-wilson/Sunloom/issues"))
    }

    @Test
    fun `the changelog has a section for the next release and the release workflow can find it`() {
        val changelog = text("CHANGELOG.md")
        assertTrue(changelog.contains("## [Unreleased]"))
        assertTrue(changelog.contains("Keep a Changelog"))
    }

    @Test
    fun `the README points to the guides and the licence`() {
        val readme = text("README.md")
        listOf("docs/USER_GUIDE.md", "docs/PRIVACY_POLICY.md", "CONTRIBUTING.md", "LICENSE").forEach {
            assertTrue("README does not link $it", readme.contains(it))
        }
        assertTrue(readme.contains("SHA256SUMS"))
        assertTrue(readme.contains("GPL-3.0"))
    }

    @Test
    fun `the issue form asks for diagnostics`() {
        assertTrue(text(".github/ISSUE_TEMPLATE/bug_report.yml").contains("Copy diagnostics"))
    }
}
