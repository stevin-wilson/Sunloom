package com.sunloom.app.data.repository

import com.sunloom.app.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article XVIII §4 / X §1: a background run checkpoints the database only if it wrote something. A Follow-OS poll that
 * finds nothing to do must not open the database, or build a repository, just to fold an empty log.
 */
@Requirement("XVIII.4")
class WriteTrackerTest {
    @Test
    fun `nothing written means nothing to do`() {
        val tracker = WriteTracker()
        var ran = 0

        assertFalse(tracker.runIfWritten { ran++ })

        assertEquals(0, ran)
    }

    @Test
    fun `a write makes the next call run once`() {
        val tracker = WriteTracker()
        var ran = 0
        tracker.markWritten()

        assertTrue(tracker.runIfWritten { ran++ })
        assertFalse("and only once, until something is written again", tracker.runIfWritten { ran++ })

        assertEquals(1, ran)
    }

    @Test
    fun `a write that arrives after the check is not lost`() {
        val tracker = WriteTracker()
        tracker.markWritten()

        tracker.runIfWritten { tracker.markWritten() } // a write during the work itself

        var ran = 0
        assertTrue(tracker.runIfWritten { ran++ })
        assertEquals(1, ran)
    }
}
