package com.sunloom.app.data.sources

import com.sunloom.app.Requirement
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Article VI §4: no adapter ships until its source is in the Source Register and meets the admission criteria, and
 * Article X §4's per-source request caps. The register is data, so these read it for every online source, which means a
 * source added tomorrow is held to the same rules without anyone remembering to add a test.
 */
class SourceAdmissionTest {

    private val online = SourceRegistry.admittedSources().filter { !it.isBuiltInOffline }

    private val module = File(".").canonicalFile // the Gradle module directory (app/) when run by Gradle

    @Test
    fun `there are online sources to hold to the rules`() {
        assertTrue("only ${online.size} online sources", online.size >= 6)
    }

    @Requirement("VI.4.a")
    @Test
    fun `every online source has reviewed terms, with the date and the reviewer on record`() {
        online.forEach { source ->
            assertTrue("${source.sourceId}: terms are a documented https page", source.termsUrl.startsWith("https://"))
            val date = runCatching { LocalDate.parse(source.reviewedDate) }.getOrNull()
            assertTrue("${source.sourceId}: review date `${source.reviewedDate}` is a real date", date != null)
            assertTrue("${source.sourceId}: a reviewer is named", source.reviewer.isNotBlank())
        }
    }

    @Requirement("VI.4.b")
    @Test
    fun `no source needs a key, a token or a login, and none is shipped in the app`() {
        val credentialWords = Regex(
            """api[_-]?key|apikey|x-api-key|bearer |client[_-]?secret|access[_-]?token|oauth|authorization|DEMO_KEY|[?&]key=""",
            RegexOption.IGNORE_CASE
        )
        val sources = File(module, "src/main/java/com/sunloom/app/data/sources").listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue("the adapters should be among the sources scanned", sources.any { it.name == "NasaLibrarySourceAdapter.kt" })
        val found = sources.flatMap { f -> credentialWords.findAll(withoutComments(f.readText())).map { "${f.name}: ${it.value}" }.toList() }
        assertTrue("an adapter mentions a credential: $found", found.isEmpty())
        val gradle = withoutComments(File(module, "build.gradle.kts").readText())
        val secretFields = Regex("""buildConfigField\([^)]*(KEY|TOKEN|SECRET)""", RegexOption.IGNORE_CASE).findAll(gradle).map { it.value }.toList()
        assertTrue("a secret is built into the app: $secretFields", secretFields.isEmpty())
    }

    @Requirement("VI.4.c")
    @Test
    fun `every online source states how its pictures are licensed, and none by a banned licence`() {
        val banned = Regex("""\bNC\b|\bND\b|-NC|-ND|GFDL|Unsplash|free to use""", RegexOption.IGNORE_CASE)
        online.forEach { source ->
            assertTrue("${source.sourceId}: a licence summary", source.licenseSummary.isNotBlank())
            assertFalse("${source.sourceId}: `${source.licenseSummary}` names a licence Sunloom rejects", banned.containsMatchIn(source.licenseSummary))
        }
    }

    @Requirement("VI.4.f")
    @Test
    fun `every host a source may use is a plain host name, and a plain http address is refused`() {
        online.forEach { source ->
            assertTrue("${source.sourceId} has hosts", source.allowedHosts.isNotEmpty())
            source.allowedHosts.forEach { host ->
                assertTrue("`$host` should be a bare lower-case host name", Regex("""[a-z0-9][a-z0-9.-]*\.[a-z]{2,}""").matches(host))
            }
        }
        val host = online.first().allowedHosts.first()
        val source = online.first().sourceId
        DefaultHttpsClient.validateHttpsAndHost("https://$host/x", source)
        try {
            DefaultHttpsClient.validateHttpsAndHost("http://$host/x", source)
            fail("a plain http address must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("HTTPS"))
        }
    }

    @Requirement("VI.4.f")
    @Test
    fun `no adapter writes a plain http address`() {
        val sources = File(module, "src/main/java/com/sunloom/app/data/sources").listFiles { f -> f.extension == "kt" }.orEmpty()
        val found = sources.flatMap { f -> Regex("\"http://").findAll(withoutComments(f.readText())).map { f.name }.toList() }
        assertTrue("an adapter names an http:// address: $found", found.isEmpty())
    }

    @Requirement("VI.4.g", "X.4.a")
    @Test
    fun `normal use stays at or below half of each published anonymous limit`() {
        // The most one device asks of a source in an hour: automatic top-ups (at most one every ten minutes, Article X §4)
        // of up to MAX_TOP_UP_ROUNDS rounds each, every round spending the source's whole request cap.
        val topUpsPerHour = 6
        val rounds = com.sunloom.app.ui.MainViewModel.MAX_TOP_UP_ROUNDS
        online.forEach { source ->
            val worstPerHour = topUpsPerHour * rounds * source.maxRequestsPerSync
            val limits = Regex("""([\d,]+)\s*req/(s|min|hr)""").findAll(source.publishedRateLimitNote).map {
                val n = it.groupValues[1].replace(",", "").toLong()
                when (it.groupValues[2]) { "s" -> n * 3600; "min" -> n * 60; else -> n }
            }.toList()
            if (limits.isEmpty()) {
                assertTrue(
                    "${source.sourceId} publishes no limit, so its note must say the budget is conservative: `${source.publishedRateLimitNote}`",
                    source.publishedRateLimitNote.contains("conservative", ignoreCase = true)
                )
            } else {
                assertTrue(
                    "${source.sourceId}: up to $worstPerHour requests an hour against a published ${limits.min()} an hour",
                    worstPerHour * 2 <= limits.min()
                )
            }
        }
    }

    @Requirement("VI.4.h")
    @Test
    fun `every online source documents its safety signals and its rate limit`() {
        online.forEach { source ->
            assertTrue("${source.sourceId}: safety signals", source.safetySignalsSummary.isNotBlank())
            assertTrue("${source.sourceId}: rate limit note", source.publishedRateLimitNote.isNotBlank())
        }
    }

    @Requirement("X.4.a", "X.4.b")
    @Test
    fun `each source's request cap and spacing sit inside the Constitution's limits`() {
        online.forEach { source ->
            assertTrue("${source.sourceId}: cap ${source.maxRequestsPerSync} is not within 5 to 10", source.maxRequestsPerSync in 5..10)
            assertTrue("${source.sourceId}: spacing ${source.minRequestSpacingMs} ms is not at least 150", source.minRequestSpacingMs >= 150L)
        }
        assertEquals("the outer ceiling", 10, online.maxOf { it.maxRequestsPerSync })
    }

    /** [text] without block or line comments, leaving string literals as they are. */
    private fun withoutComments(text: String): String {
        val tokens = Regex("""/\*[\s\S]*?\*/|//[^\n]*|"(?:[^"\\\n]|\\.)*"""")
        return tokens.replace(text) { if (it.value.startsWith("\"")) it.value else "" }
    }
}
