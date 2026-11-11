package com.sunloom.app.diagnostics

import com.sunloom.app.Requirement
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Article VIII §2: no crash-reporting service. A crash is written to a small file on the device so the person can
 * choose to share it; nothing leaves the device on its own.
 */
@Requirement("VIII.2")
class CrashLogTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var dir: File
    private var now = 1_800_000_000_000L
    private val info = AppInfo("1.7.0", 13, "github", 34, "Test Book")

    @Before
    fun setUp() {
        dir = File(folder.root, "crashes")
    }

    private fun log(max: Int = 5, maxChars: Int = 8000) = CrashLog(dir, { info }, { now++ }, max, maxChars)

    @Test
    fun `a recorded crash says what, where and in which build`() {
        val log = log()

        log.record(Thread.currentThread(), IllegalStateException("boom"))

        val entry = log.entries().single()
        assertTrue(entry.text.contains("IllegalStateException: boom"))
        assertTrue("it has a stack", entry.text.contains("at com.sunloom.app.diagnostics.CrashLogTest"))
        assertTrue(entry.text.contains("1.7.0 (13) github"))
        assertTrue(entry.text.contains("Android API 34"))
        assertTrue(entry.text.contains("Thread: ${Thread.currentThread().name}"))
    }

    @Test
    fun `only the latest few crashes are kept, newest first`() {
        val log = log(max = 3)

        for (n in 1..5) log.record(Thread.currentThread(), RuntimeException("crash $n"))

        val texts = log.entries().map { e -> Regex("crash \\d").find(e.text)!!.value }
        assertEquals(listOf("crash 5", "crash 4", "crash 3"), texts)
        assertEquals(3, dir.listFiles()!!.size)
    }

    @Test
    fun `a long stack is cut to a bounded size`() {
        val deep = RuntimeException("x".repeat(50_000))

        val log = log(maxChars = 2000)
        log.record(Thread.currentThread(), deep)

        assertTrue(log.entries().single().text.length <= 2000 + 50)
    }

    @Test
    fun `recording never throws, even when the folder cannot be written`() {
        File(folder.root, "crashes").writeText("a file where the folder should be")

        log().record(Thread.currentThread(), RuntimeException("boom")) // must not throw

        assertTrue(log().entries().isEmpty())
    }

    @Test
    fun `clear removes every entry`() {
        val log = log()
        log.record(Thread.currentThread(), RuntimeException("a"))
        log.clear()
        assertTrue(log.entries().isEmpty())
    }

    @Test
    fun `the handler records the crash and then lets the previous handler run`() {
        val log = log()
        var previousSaw: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { _, e -> previousSaw = e }
        val error = RuntimeException("fatal")

        log.handler(previous).uncaughtException(Thread.currentThread(), error)

        assertEquals(1, log.entries().size)
        assertSame("the system still gets to report the crash", error, previousSaw)
    }

    @Test
    fun `the handler still hands over when recording fails`() {
        File(folder.root, "crashes").writeText("blocked")
        var handedOver = false

        log().handler { _, _ -> handedOver = true }.uncaughtException(Thread.currentThread(), RuntimeException("fatal"))

        assertTrue(handedOver)
        assertFalse(log().entries().isNotEmpty())
    }
}
