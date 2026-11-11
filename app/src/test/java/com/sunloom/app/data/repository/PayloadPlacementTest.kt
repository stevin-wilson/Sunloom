package com.sunloom.app.data.repository

import com.sunloom.app.Requirement
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * An accepted picture moves from quarantine into the cache by renaming the staged file. If the rename cannot be done
 * (two volumes), the file is copied from disk, not from a payload kept in memory for the whole ingest (Article X §3).
 */
@Requirement("X.3")
class PayloadPlacementTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a staged file is moved into place by renaming`() {
        val staged = folder.newFile("staged.tmp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val dest = File(folder.root, "final.jpg")

        CandidateIngestor.placeStagedFile(staged, dest)

        assertArrayEquals(byteArrayOf(1, 2, 3), dest.readBytes())
        assertFalse(staged.exists())
    }

    @Test
    fun `when renaming fails the staged file is copied from disk`() {
        val staged = folder.newFile("staged.tmp").apply { writeBytes(ByteArray(2048) { (it % 7).toByte() }) }
        val dest = File(folder.root, "final.jpg")

        CandidateIngestor.placeStagedFile(staged, dest, rename = { _, _ -> false })

        assertArrayEquals(ByteArray(2048) { (it % 7).toByte() }, dest.readBytes())
    }
}
