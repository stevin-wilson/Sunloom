package com.sunloom.app.data.repository

import com.sunloom.app.domain.model.WallpaperRecord
import com.sunloom.app.domain.safety.SafetyDenylist
import com.sunloom.app.domain.safety.SafetyGate

/**
 * Article X §3 (v0.14): keeps the reserve varied while it is being filled.
 *
 * - **Creator cap:** at most [MAX_UNSEEN_PER_CREATOR] never-shown images per creator, so one painter or
 *   photographer cannot crowd out everything else a source returns.
 * - **Near-duplicate guard:** a picture whose perceptual hash is close to one already in the library is not
 *   added again, even under a different id (the same artwork on two museums, re-uploads, crops).
 *
 * Institutional creators ([EXEMPT_SOURCES], blank or placeholder names) are not capped.
 */
internal class LibraryDiversityGuard(usableRows: List<WallpaperRecord>) {

    private val unseenByCreator = HashMap<String, Int>()
    private val hashes = ArrayList<String>()

    init {
        for (row in usableRows) {
            note(row.provenance.sourceId, row.provenance.creatorName, row.provenance.perceptualHash, unseen = row.appliedCount == 0)
        }
    }

    fun creatorCapReached(sourceId: String, creatorName: String): Boolean {
        val key = creatorKey(sourceId, creatorName) ?: return false
        return (unseenByCreator[key] ?: 0) >= MAX_UNSEEN_PER_CREATOR
    }

    fun isNearDuplicate(dHash: String): Boolean =
        SafetyGate.isInformativeDHash(dHash) && hashes.any { SafetyGate.isNearDuplicateDHash(dHash, it) }

    fun noteAdded(sourceId: String, creatorName: String, dHash: String) =
        note(sourceId, creatorName, dHash, unseen = true)

    private fun note(sourceId: String, creatorName: String, dHash: String, unseen: Boolean) {
        if (SafetyGate.isInformativeDHash(dHash)) hashes += dHash
        if (!unseen) return
        creatorKey(sourceId, creatorName)?.let { unseenByCreator.merge(it, 1, Int::plus) }
    }

    private fun creatorKey(sourceId: String, creatorName: String): String? {
        if (sourceId in EXEMPT_SOURCES) return null
        val key = SafetyDenylist.normalizeRaw(creatorName)
        // Adapters use a generic credit line when a work has no individual creator (e.g. "Archival / Public
        // Domain (Wikimedia Commons)"); that is a label for many authors, not one creator to cap.
        return key.takeUnless {
            it.isEmpty() || it in PLACEHOLDERS || GENERIC_CREDIT_FRAGMENTS.any { fragment -> fragment in it }
        }
    }

    companion object {
        const val MAX_UNSEEN_PER_CREATOR = 3
        private val EXEMPT_SOURCES = setOf("seed_reserve", "user_local", "nasa_library")
        private val GENERIC_CREDIT_FRAGMENTS = listOf("public domain", "wikimedia commons", "archival")
        private val PLACEHOLDERS = setOf("unknown", "unknown author", "anonymous", "unknown artist", "n a", "none")
    }
}
