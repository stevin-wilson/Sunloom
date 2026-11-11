package com.sunloom.app.platform.scheduler

import com.sunloom.app.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reset erases everything; cancelling the queue does not stop an attempt that is already running, which holds its own
 * repository and could write after the wipe. Reset therefore waits - for a bounded time - until nothing is running.
 */
@Requirement("XII.3")
class WorkIdleTest {
    private var now = 0L
    private var sleeps = mutableListOf<Long>()
    private val sleep: (Long) -> Unit = { ms -> sleeps += ms; now += ms }

    @Test
    fun `nothing running means no waiting`() {
        assertTrue(WorkIdle.awaitIdle(isBusy = { false }, timeoutMs = 5_000, nowMs = { now }, sleep = sleep))
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `it waits while work is running and returns once it has stopped`() {
        var busyChecks = 3

        val idle = WorkIdle.awaitIdle(isBusy = { busyChecks-- > 0 }, timeoutMs = 5_000, pollMs = 50, nowMs = { now }, sleep = sleep)

        assertTrue(idle)
        assertEquals(listOf(50L, 50L, 50L), sleeps)
    }

    @Test
    fun `work that never stops is given up on at the timeout instead of hanging`() {
        val idle = WorkIdle.awaitIdle(isBusy = { true }, timeoutMs = 1_000, pollMs = 100, nowMs = { now }, sleep = sleep)

        assertFalse(idle)
        assertTrue("it stopped waiting at about the timeout, not far past it", now in 1_000L..1_100L)
    }

    @Test
    fun `a failure to ask is treated as nothing running`() {
        assertTrue(WorkIdle.awaitIdle(isBusy = { throw IllegalStateException("WorkManager unavailable") }, timeoutMs = 1_000, nowMs = { now }, sleep = sleep))
    }
}
