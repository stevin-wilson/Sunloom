package com.sunloom.app.data.repository

import com.sunloom.app.domain.model.BlocklistEntry

/**
 * What has been refused for good (Article VII §4 L5): by asset id, by checksum and by perceptual hash. An id counts when
 * it has a blocklist entry or a permanent rejection; transient rejections never block.
 */
internal class Blocklist(private val store: LibraryStore) {

    fun add(
        assetId: String?,
        sha256: String?,
        perceptualHash: String?,
        sourceId: String,
        reasonCode: String,
        timestampEpochMs: Long = System.currentTimeMillis()
    ) {
        store.addBlocklistEntry(
            BlocklistEntry(
                assetId = assetId?.trim().orEmpty(),
                sha256 = sha256?.trim().orEmpty(),
                dHash = perceptualHash?.trim().orEmpty(),
                sourceId = sourceId,
                reason = reasonCode,
                createdAtEpochMs = timestampEpochMs
            )
        )
    }

    fun assetIds(): Set<String> {
        val fromBlocklist = store.getAllBlocklistEntries()
            .map { it.assetId }
            .filter { it.isNotBlank() }
            .toSet()
        val fromPermanentRejections = store.getAllRejections()
            .filter { it.isPermanent }
            .map { it.assetId }
            .filter { it.isNotBlank() }
            .toSet()
        return fromBlocklist + fromPermanentRejections
    }

    fun sha256s(): Set<String> = store.getAllBlocklistEntries().map { it.sha256 }.filter { it.isNotBlank() }.toSet()

    fun perceptualHashes(): Set<String> = store.getAllBlocklistEntries().map { it.dHash }.filter { it.isNotBlank() }.toSet()
}
