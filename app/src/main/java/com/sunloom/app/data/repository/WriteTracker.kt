package com.sunloom.app.data.repository

import java.util.concurrent.atomic.AtomicBoolean

/** Remembers whether anything has been written since the last time [runIfWritten] ran. */
internal class WriteTracker {
    private val written = AtomicBoolean(false)

    fun markWritten() {
        written.set(true)
    }

    /** Runs [block] only if something was written since the last run; the flag is cleared first, so a write during [block] is kept. */
    fun runIfWritten(block: () -> Unit): Boolean {
        if (!written.getAndSet(false)) return false
        block()
        return true
    }
}
