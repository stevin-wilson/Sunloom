package com.sunloom.app.data.sources

import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.SafetyVerdict
import com.sunloom.app.domain.safety.SafetyGate
import java.net.URI

/**
 * What a live catalog has to keep giving the app (Article VI §4): a stable id and a public page, an https image on a host the
 * source may use, a licence and creator the gate's L1 accepts, and a picture the L3 checks can size. These are pure, so they are
 * tested against made-up candidates in every build; [LiveSourceContractCheck] applies them to what the real catalogs return.
 */
internal object SourceContractRules {

    /** Why [candidate] no longer meets the contract; empty when it does. Safety (L2) is about content, not drift, so it is not asked here. */
    fun violations(candidate: CandidateAsset, registration: SourceRegistration?): List<String> {
        val found = mutableListOf<String>()
        if (registration == null) return listOf("the source is not in the register")
        if (candidate.id.isBlank()) found += "no id"
        if (candidate.provenance.assetId.isBlank()) found += "no asset id"
        val page = runCatching { URI(candidate.provenance.originalPageUrl) }.getOrNull()
        if (page == null || page.scheme != "https" || page.host.isNullOrEmpty() || page.path.isNullOrEmpty() || page.path.length < 2) {
            found += "no https public page (`${candidate.provenance.originalPageUrl.take(120)}`)"
        }
        if (!SourceAdapterUtils.isSupportedHttpsRasterUrl(candidate.downloadUrl, registration.sourceId)) {
            found += "the image URL is not an https picture on one of the source's hosts (`${candidate.downloadUrl.take(120)}`)"
        }
        if (candidate.provenance.originalWidth < 0 || candidate.provenance.originalHeight < 0) {
            found += "a negative size (${candidate.provenance.originalWidth}x${candidate.provenance.originalHeight})"
        }
        // L1 is the licence and creator check (Article VI §5 and §8): a failure there is the source's data changing.
        val verdict = SafetyGate().evaluateMetadata(candidate)
        if (verdict is SafetyVerdict.Rejected && verdict.failedLayer == "L1") found += "L1 ${verdict.reasonCode}: ${verdict.explanation.take(160)}"
        return found
    }

    /** Why a downloaded picture could not be checked by the app's own L3 pieces; empty when it can. */
    fun imageProblems(bytes: ByteArray): List<String> {
        val found = mutableListOf<String>()
        if (bytes.size < SafetyGate.MIN_PAYLOAD_BYTES) found += "only ${bytes.size} bytes"
        val format = SafetyGate.detectImageFormat(bytes)
        if (format == com.sunloom.app.domain.model.ImageFormat.UNSUPPORTED) {
            return found + "not a JPEG, PNG or WebP picture"
        }
        val bounds = SafetyGate.inspectImageHeaderBounds(bytes)
        if (bounds == null) return found + "the ${format.name} header states no size the app's parser can read"
        if (SafetyGate.exceedsDecodeBounds(bounds)) found += "${bounds.width}x${bounds.height} is past the L3 decode limits"
        if (bounds.outMimeType != null && bounds.outMimeType != format.mimeType) found += "header type ${bounds.outMimeType} is not ${format.mimeType}"
        return found
    }
}

/** How one source fared in one live run. */
internal enum class Verdict { OK, WARN, RATE_LIMITED, TRANSIENT, DRIFT }

/** What one source's check saw. [failure] is what the adapter threw; [httpError] is the last transport error the client recorded. */
internal class SourceCheck(
    val sourceId: String,
    val category: String,
    val candidates: Int,
    val violations: List<String>,
    val failure: Throwable? = null,
    val httpError: Throwable? = null,
    val imageProblems: List<String> = emptyList(),
    /** The picture could not be fetched at all (a timeout, say): not a verdict on the picture. */
    val imageFetchError: Throwable? = null,
    /** The client refused the picture the adapter chose (its host or URL is not allowed, or it is over the app's own download limit). */
    val imageRefusal: String? = null
)

/** Only a source that answered with something the app can no longer use is drift; a bad day on the network is not. */
internal fun classify(check: SourceCheck): Verdict {
    val failure = check.failure
    return when {
        failure is SourceResponseException -> Verdict.DRIFT
        failure is RateLimitException || check.imageFetchError is RateLimitException -> Verdict.RATE_LIMITED
        failure != null -> Verdict.TRANSIENT
        check.violations.isNotEmpty() || check.imageProblems.isNotEmpty() || check.imageRefusal != null -> Verdict.DRIFT
        check.candidates == 0 -> if (check.httpError != null) Verdict.TRANSIENT else Verdict.WARN
        check.imageFetchError != null -> Verdict.WARN
        else -> Verdict.OK
    }
}

/** The run fails on drift only. When every source had a network problem it is inconclusive, not red. */
internal fun runFails(verdicts: List<Verdict>): Boolean = Verdict.DRIFT in verdicts
internal fun runInconclusive(verdicts: List<Verdict>): Boolean = verdicts.isNotEmpty() && verdicts.all { it == Verdict.TRANSIENT }

/**
 * The line `Result: ...` of the report, which the workflow's issue script acts on. Only PASSED means every source gave an answer
 * that could be judged, so only PASSED may close an open issue; PARTIAL (some source could not be checked) and INCONCLUSIVE leave
 * it as it is.
 */
internal fun overallResult(verdicts: List<Verdict>): String = when {
    runFails(verdicts) -> "FAILED"
    runInconclusive(verdicts) -> "INCONCLUSIVE"
    verdicts.isEmpty() || verdicts.any { it == Verdict.TRANSIENT || it == Verdict.RATE_LIMITED } -> "PARTIAL"
    else -> "PASSED"
}
