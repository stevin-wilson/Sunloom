package com.sunloom.app.data.repository

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.RejectionRecord
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Article VII §4 L5: what the person or the gate has refused for good is remembered by id, checksum and perceptual hash. */
@Requirement("VII.4")
class BlocklistTest {
    private val store = InMemoryLibraryStore(File(System.getProperty("java.io.tmpdir"), "blocklist_test_${System.nanoTime()}"))
    private val blocklist = Blocklist(store)

    @Test
    fun `an entry is remembered by id, checksum and perceptual hash`() {
        blocklist.add(assetId = "a", sha256 = "sha-a", perceptualHash = "hash-a", sourceId = "s", reasonCode = "USER_REJECTED")
        assertEquals(setOf("a"), blocklist.assetIds())
        assertEquals(setOf("sha-a"), blocklist.sha256s())
        assertEquals(setOf("hash-a"), blocklist.perceptualHashes())
    }

    @Test
    fun `blank and missing parts are not remembered, and padding is trimmed`() {
        blocklist.add(assetId = "  b  ", sha256 = null, perceptualHash = "   ", sourceId = "s", reasonCode = "X")
        assertEquals(setOf("b"), blocklist.assetIds())
        assertEquals(emptySet<String>(), blocklist.sha256s())
        assertEquals(emptySet<String>(), blocklist.perceptualHashes())
    }

    @Test
    fun `an id refused through a permanent rejection counts, a transient one does not`() {
        store.recordRejection(RejectionRecord("perm", "s", "R", "e", 1L, isPermanent = true))
        store.recordRejection(RejectionRecord("temp", "s", "R", "e", 1L, isPermanent = false, displaySignature = "sig"))
        assertEquals(setOf("perm"), blocklist.assetIds())
    }

    @Test
    fun `ids from entries and from permanent rejections are combined`() {
        blocklist.add(assetId = "x", sha256 = null, perceptualHash = null, sourceId = "s", reasonCode = "R")
        store.recordRejection(RejectionRecord("y", "s", "R", "e", 1L, isPermanent = true))
        assertEquals(setOf("x", "y"), blocklist.assetIds())
    }
}
