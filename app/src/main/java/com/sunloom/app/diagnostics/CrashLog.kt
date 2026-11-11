package com.sunloom.app.diagnostics

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/** One recorded crash: the file it lives in, when it happened, and its text. */
data class CrashEntry(val fileName: String, val timestampMs: Long, val text: String)

/**
 * A small crash log kept on the device (Article VIII §2: no crash-reporting service, nothing leaves the device).
 * Each uncaught exception is written to its own file with the build and the stack trace; only the latest
 * [maxEntries] are kept. The person can copy them into a bug report; no code here sends them anywhere.
 *
 * Recording must never make a crash worse, so it swallows its own failures.
 */
class CrashLog(
    private val dir: File,
    private val appInfo: () -> AppInfo,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxChars: Int = DEFAULT_MAX_CHARS
) {
    fun record(thread: Thread, error: Throwable) {
        runCatching {
            dir.mkdirs()
            val now = clock()
            val info = appInfo()
            val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            val header = "Sunloom ${info.buildLabel} | Android API ${info.sdkInt} | ${info.deviceModel}\n" +
                "Time: ${Instant.ofEpochMilli(now)}\nThread: ${thread.name}\n\n"
            val text = (header + stack).let { if (it.length > maxChars) it.take(maxChars) + "\n…[cut]" else it }
            var file = File(dir, "crash-$now.txt")
            var n = 1
            while (file.exists()) file = File(dir, "crash-$now-${n++}.txt")
            file.writeText(text)
            prune()
        }
    }

    /** Recorded crashes, newest first. */
    fun entries(): List<CrashEntry> = runCatching {
        dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }.orEmpty()
            .map { CrashEntry(it.name, timestampOf(it), it.readText()) }
            .sortedByDescending { it.timestampMs }
    }.getOrDefault(emptyList())

    fun clear() {
        runCatching { dir.listFiles().orEmpty().forEach { it.delete() } }
    }

    /** An uncaught-exception handler that records the crash, then hands over to [previous] so the system still reports it. */
    fun handler(previous: Thread.UncaughtExceptionHandler?): Thread.UncaughtExceptionHandler =
        Thread.UncaughtExceptionHandler { thread, error ->
            record(thread, error)
            previous?.uncaughtException(thread, error)
        }

    private fun prune() {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }.orEmpty().sortedByDescending { timestampOf(it) }
        files.drop(maxEntries).forEach { it.delete() }
    }

    private fun timestampOf(file: File): Long =
        file.name.removePrefix("crash-").removeSuffix(".txt").substringBefore('-').toLongOrNull() ?: file.lastModified()

    companion object {
        const val DEFAULT_MAX_ENTRIES = 5
        const val DEFAULT_MAX_CHARS = 8000

        /** The log of this app; `noBackupFilesDir` keeps it out of Auto Backup. */
        fun forApp(context: Context) = CrashLog(File(context.applicationContext.noBackupFilesDir, "crashes"), { AppInfo.current() })

        /** Starts recording uncaught exceptions on every thread. Cheap: it only installs a handler. */
        fun install(context: Context) {
            val log = forApp(context)
            Thread.setDefaultUncaughtExceptionHandler(log.handler(Thread.getDefaultUncaughtExceptionHandler()))
        }
    }
}
