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
        "README.md", "LICENSE", "NOTICE", "CHANGELOG.md", "CONTRIBUTING.md", "SECURITY.md", "CLAUDE.md", "AGENTS.md",
        "docs/USER_GUIDE.md", "docs/ARCHITECTURE.md", "docs/RELEASING.md", "docs/PRIVACY_POLICY.md",
        ".github/ISSUE_TEMPLATE/bug_report.yml", ".github/ISSUE_TEMPLATE/feature_request.yml",
        ".github/ISSUE_TEMPLATE/config.yml", ".github/ISSUE_TEMPLATE/image_report.yml", ".github/pull_request_template.md"
    )

    @Test
    fun `the documents a visitor expects are all there`() {
        val missing = required.filterNot { File(root, it).isFile }
        assertTrue("missing: $missing", missing.isEmpty())
    }

    @Test
    fun `links between documents resolve`() {
        val link = Regex("\\]\\(((?!https?://|mailto:|#)[^)\\s#]+)(#[^)]*)?\\)")
        val broken = listOf("README.md", "CONTRIBUTING.md", "SECURITY.md", "CLAUDE.md", "AGENTS.md", "docs/USER_GUIDE.md", "docs/ARCHITECTURE.md", "docs/RELEASING.md")
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
        // WorkManager adds these two when the manifests are merged; the installed app has them, so the README says so.
        val missing = (permissions + listOf("WAKE_LOCK", "FOREGROUND_SERVICE")).filterNot { readme.contains(it) }
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
    fun `the agent guide points to the rules, maps every article, and names the fast domain task the build defines`() {
        val guide = text("CLAUDE.md")
        listOf("CONTRIBUTING.md", "CONSTITUTION.md").forEach { assertTrue("CLAUDE.md does not link $it", guide.contains("]($it)")) }
        assertTrue("AGENTS.md must point to CLAUDE.md", text("AGENTS.md").contains("CLAUDE.md"))
        val articles = Regex("(?m)^## Article ([IVX]+):").findAll(text("CONSTITUTION.md")).map { it.groupValues[1] }.toList()
        assertTrue(articles.isNotEmpty())
        val unmapped = articles.filterNot { Regex("Article $it\\b").containsMatchIn(guide) }
        assertTrue("articles missing from the map in CLAUDE.md: $unmapped", unmapped.isEmpty())
        // The commands in the guide must exist: the task is registered in the build and the contributor guide knows it too.
        assertTrue("CLAUDE.md does not name testDomain", guide.contains("testDomain"))
        assertTrue("CONTRIBUTING.md does not name testDomain", text("CONTRIBUTING.md").contains("testDomain"))
        assertTrue("the build does not register testDomain", File("build.gradle.kts").readText().contains("\"testDomain\""))
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
    fun `the signing certificate fingerprint is the same in every document that quotes it`() {
        val shape = Regex("(?:[0-9A-F]{2}:){31}[0-9A-F]{2}")
        val found = listOf("README.md", "CHANGELOG.md", "docs/RELEASING.md")
            .associateWith { doc -> shape.findAll(text(doc)).map { it.value }.toSet() }
        assertTrue("README.md must quote the SHA-256 fingerprint of the signing certificate", found.getValue("README.md").size == 1)
        assertTrue(
            "CHANGELOG.md must quote the same fingerprint in the section for the release it belongs to",
            found.getValue("CHANGELOG.md").containsAll(found.getValue("README.md"))
        )
        found.forEach { (doc, values) ->
            assertTrue("$doc quotes a different fingerprint: $values", values.all { it in found.getValue("README.md") })
        }
    }

    @Test
    fun `the issue form asks for diagnostics`() {
        assertTrue(text(".github/ISSUE_TEMPLATE/bug_report.yml").contains("Copy diagnostics"))
    }

    @Test
    fun `the image report form is the one the app opens and asks for a reason without the picture`() {
        val form = text(".github/ISSUE_TEMPLATE/image_report.yml")
        // The app opens it by file name and fills the field with this id (ReportImage.issueUrl).
        assertTrue(form.contains("id: details"))
        assertTrue("the reason is required", Regex("id: reason[\\s\\S]*?required: true").containsMatchIn(form))
        assertTrue("it labels the issue for triage", form.contains("image-report"))
        assertTrue("it asks people not to post the picture", form.contains("do not attach") || form.contains("Please do not attach"))
    }

    @Test
    fun `the privacy policy and the user guide describe reporting an image`() {
        val policy = text("docs/PRIVACY_POLICY.md")
        assertTrue(policy.contains("Report this image"))
        assertTrue("it says the issue is public", policy.contains("public"))
        assertTrue(text("docs/USER_GUIDE.md").contains("Report this image"))
    }
}
