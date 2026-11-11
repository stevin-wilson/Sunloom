package com.sunloom.app.ui

import com.sunloom.app.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article XII §1: setup is a short walk, not a long form - a few steps, each with one decision group, a visible sense of
 * how far along it is, and a way back.
 */
@Requirement("XII.1")
class SetupFlowTest {
    @Test
    fun `setup has four steps in a fixed order`() {
        assertEquals(listOf(SetupStep.WELCOME, SetupStep.SCHEDULE, SetupStep.CONTENT, SetupStep.FINISH), SetupStep.entries)
    }

    @Test
    fun `next walks forward and stops at the end`() {
        assertEquals(SetupStep.SCHEDULE, SetupStep.WELCOME.next())
        assertEquals(SetupStep.CONTENT, SetupStep.SCHEDULE.next())
        assertEquals(SetupStep.FINISH, SetupStep.CONTENT.next())
        assertNull(SetupStep.FINISH.next())
    }

    @Test
    fun `back walks backward and stops at the start`() {
        assertEquals(SetupStep.CONTENT, SetupStep.FINISH.back())
        assertNull(SetupStep.WELCOME.back())
    }

    @Test
    fun `the number and the progress rise with each step, ending full`() {
        assertEquals(listOf(1, 2, 3, 4), SetupStep.entries.map { it.number })
        assertEquals(0.25f, SetupStep.WELCOME.progress, 0.0001f)
        assertEquals(1.0f, SetupStep.FINISH.progress, 0.0001f)
        assertTrue(SetupStep.entries.zipWithNext().all { (a, b) -> a.progress < b.progress })
    }

    @Test
    fun `only the last step finishes setup`() {
        assertTrue(SetupStep.FINISH.isLast)
        assertFalse(SetupStep.CONTENT.isLast)
        assertTrue(SetupStep.WELCOME.isFirst)
    }
}
