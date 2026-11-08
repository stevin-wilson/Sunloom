package com.sunloom.app.data.repository

import com.sunloom.app.domain.model.RejectionRecord

/**
 * Builds the two kinds of [RejectionRecord] (Article VII §4). Permanent rejections (safety, licence, the user's
 * own choice) block an image on every display. Display-scoped ones (size, quality, near-duplicates) block it only
 * for the display set it was judged on, so they carry that set's signature; without one they would never match.
 */
internal object Rejections {

    fun permanent(
        assetId: String,
        sourceId: String,
        reasonCode: String,
        explanation: String,
        nowMs: Long = System.currentTimeMillis()
    ): RejectionRecord = RejectionRecord(
        assetId = assetId,
        sourceId = sourceId,
        reasonCode = reasonCode,
        explanation = explanation,
        timestampEpochMs = nowMs,
        isPermanent = true
    )

    fun displayScoped(
        assetId: String,
        sourceId: String,
        reasonCode: String,
        explanation: String,
        displaySignature: String,
        nowMs: Long = System.currentTimeMillis()
    ): RejectionRecord {
        require(displaySignature.isNotBlank()) { "A display-scoped rejection needs the display signature it applies to" }
        return RejectionRecord(
            assetId = assetId,
            sourceId = sourceId,
            reasonCode = reasonCode,
            explanation = explanation,
            timestampEpochMs = nowMs,
            isPermanent = false,
            displaySignature = displaySignature
        )
    }

    /**
     * A picture that was skipped by a fault of ours (a full disk, a store error), not by a judgement about it. It is
     * listed in Diagnostics, but has no display signature, so it never matches the current display set and never
     * blocks the picture: the next sync tries it again.
     */
    fun transient(
        assetId: String,
        sourceId: String,
        reasonCode: String,
        explanation: String,
        nowMs: Long = System.currentTimeMillis()
    ): RejectionRecord = RejectionRecord(
        assetId = assetId,
        sourceId = sourceId,
        reasonCode = reasonCode,
        explanation = explanation,
        timestampEpochMs = nowMs,
        isPermanent = false
    )
}
