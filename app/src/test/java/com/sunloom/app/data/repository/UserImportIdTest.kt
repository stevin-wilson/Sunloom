package com.sunloom.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Imports through Share, drag-and-drop or several picked files can arrive in the same millisecond; an id built from the
 * clock would make the second replace the first. Ids must be unique by construction.
 */
class UserImportIdTest {
    @Test
    fun `ids are unique even when created in the same instant`() {
        val ids = List(10_000) { UserImportPipeline.newImportId() }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `an id keeps the local prefix and is a UUID`() {
        val id = UserImportPipeline.newImportId()

        assertTrue(id.startsWith("local-"))
        java.util.UUID.fromString(id.removePrefix("local-")) // throws if it is not a UUID
    }
}
