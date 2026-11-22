package com.sunloom.app.data.sources

import com.sunloom.app.domain.model.CandidateAsset
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.IsoFields
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * OPT-IN live check, run weekly by `.github/workflows/source-drift.yml` and never by the normal build:
 *
 *     SUNLOOM_LIVE_CHECK=1 ./gradlew :app:testGithubDebugUnitTest --tests '*LiveSourceContractCheck*' --rerun
 *
 * For each online source it asks one small question (one category, chosen by week so all of them are covered over a few weeks),
 * holds every candidate to [SourceContractRules], downloads one small picture and checks it with the app's own L3 pieces.
 * It uses the app's adapters, User-Agent, request caps and spacing, never retries, and pauses between sources.
 *
 * It fails only on drift: a response that is no longer the JSON the adapter expects, a candidate that breaks the contract, or a
 * picture the app could not size. Timeouts, rate limits and empty answers are reported and do not fail it.
 * The report is `app/build/reports/live-source-contract.md`.
 */
class LiveSourceContractCheck {

    @Test
    fun `the live catalogs still meet the contract the adapters rely on`() {
        assumeTrue("Set SUNLOOM_LIVE_CHECK=1 to run the live source check", System.getenv("SUNLOOM_LIVE_CHECK") == "1")

        val week = System.getenv("SUNLOOM_LIVE_CHECK_WEEK")?.toIntOrNull()
            ?: LocalDate.now(ZoneOffset.UTC).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val recording = RecordingHttpClient(DefaultHttpsClient.shared)
        val rows = ArrayList<String>()
        val verdicts = ArrayList<Verdict>()

        for ((index, adapter) in LiveSources.adapters(recording).withIndex()) {
            val registration = SourceRegistry.get(adapter.sourceId)
            val categories = registration?.servedCategories.orEmpty().sortedBy { it.name }
            if (registration == null || categories.isEmpty()) continue
            val category = categories[(week + index) % categories.size]
            val before = recording.httpError

            var failure: Throwable? = null
            var candidates: List<CandidateAsset> = emptyList()
            try {
                candidates = runBlocking { adapter.fetchCandidates(setOf(category), limit = 4, offset = 0, targetWidthPx = 1600) }
            } catch (e: Exception) {
                failure = e
            }
            val catalogHttpError = recording.httpError.takeIf { it !== before } // before the picture is fetched
            val violations = candidates.flatMap { c -> SourceContractRules.violations(c, registration).map { "${c.id}: $it" } }

            var imageProblems = emptyList<String>()
            var imageFetchError: Throwable? = null
            var imageRefusal: String? = null
            var imageNote = "none"
            val picture = candidates.firstOrNull { SourceAdapterUtils.isSupportedHttpsRasterUrl(it.downloadUrl, adapter.sourceId) }
            if (failure == null && picture != null) {
                Thread.sleep(registration.minRequestSpacingMs.coerceAtLeast(250L))
                val budgeted = BudgetedSourceHttpClient(recording, adapter.sourceId, maxRequests = 2, minSpacingMs = registration.minRequestSpacingMs)
                try {
                    val bytes = runBlocking { budgeted.getBytes(picture.downloadUrl) } // the app's own download limit
                    imageProblems = SourceContractRules.imageProblems(bytes)
                    imageNote = "${bytes.size / 1024} KB " + (SafetyGateBounds.describe(bytes))
                } catch (e: IllegalArgumentException) {
                    // The client's own checks (host and URL allowed, size within the app's download limit) refused what the adapter chose.
                    imageRefusal = e.message.orEmpty().take(160)
                    imageNote = "refused (${imageRefusal})".replace("|", "/").replace("\n", " ")
                } catch (e: Exception) {
                    imageFetchError = e
                    imageNote = "not fetched (${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)})".replace("|", "/").replace("\n", " ")
                }
            }

            val check = SourceCheck(
                sourceId = adapter.sourceId, category = category.name, candidates = candidates.size, violations = violations,
                failure = failure, httpError = catalogHttpError, imageProblems = imageProblems,
                imageFetchError = imageFetchError, imageRefusal = imageRefusal
            )
            val verdict = classify(check)
            verdicts += verdict
            val details = buildList {
                failure?.let { add("${it.javaClass.simpleName}: ${it.message.orEmpty().take(200)}") }
                addAll(violations.take(5).map { it.take(240) })
                addAll(imageProblems.map { "image: $it" })
                imageRefusal?.let { add("image refused: $it") }
            }.joinToString("; ").replace("|", "/").replace("\n", " ")
            rows += "| ${adapter.sourceId} | $verdict | ${category.name} | ${candidates.size} | $imageNote | ${details.ifEmpty { "" }} |"
            Thread.sleep(1_000)
        }

        val result = overallResult(verdicts)
        val summary = when (result) {
            "FAILED" -> "FAILED: a source has changed."
            "INCONCLUSIVE" -> "Inconclusive: every source had a network problem."
            "PARTIAL" -> "Passed, but some sources could not be checked this week (network or rate limit)."
            else -> "Passed."
        }
        val report = buildString {
            appendLine("## Live source check (week $week)")
            appendLine()
            appendLine("Result: $result")
            appendLine()
            appendLine(summary)
            appendLine()
            appendLine("| source | result | category | candidates | picture | details |")
            appendLine("|---|---|---|---|---|---|")
            rows.forEach { appendLine(it) }
            appendLine()
            appendLine("DRIFT fails the run. WARN (nothing returned, picture not fetched), RATE_LIMITED and TRANSIENT (network) do not;" +
                " only a run where every source could be judged (PASSED) closes an open issue.")
        }
        val out = File("build/reports/live-source-contract.md")
        out.parentFile.mkdirs()
        out.writeText(report)
        println("Live source contract report: ${out.absolutePath}\n$report")

        assertTrue("A live source has changed; see ${out.path}\n$report", !runFails(verdicts))
    }
}

/** A short description of a downloaded picture for the report. */
private object SafetyGateBounds {
    fun describe(bytes: ByteArray): String {
        val format = com.sunloom.app.domain.safety.SafetyGate.detectImageFormat(bytes)
        val bounds = com.sunloom.app.domain.safety.SafetyGate.inspectImageHeaderBounds(bytes)
        return if (bounds == null) format.name else "${format.name} ${bounds.width}x${bounds.height}"
    }
}
