package com.sunloom.app.platform

import com.sunloom.app.Requirement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article V §2: the wallpaper on screen and the one recorded as active must agree. The app and the background
 * worker both apply, so an apply and the record of it are one step that two callers cannot interleave.
 */
@Requirement("V.2")
class ApplyLockTest {
    @Test
    fun `two appliers never run their apply-and-record step at the same time`() {
        val inside = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(8)
        repeat(8) {
            pool.execute {
                ApplyLock.run {
                    val now = inside.incrementAndGet()
                    peak.accumulateAndGet(now, ::maxOf)
                    Thread.sleep(5)
                    inside.decrementAndGet()
                }
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(1, peak.get())
    }

    @Test
    fun `it returns the value of the block and can be re-entered by the same thread`() {
        assertEquals(7, ApplyLock.run { ApplyLock.run { 7 } })
    }
}
