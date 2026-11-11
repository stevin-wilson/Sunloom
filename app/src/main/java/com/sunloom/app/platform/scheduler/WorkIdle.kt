package com.sunloom.app.platform.scheduler

/** Waits, for a bounded time, until no background work is running. */
internal object WorkIdle {
    /**
     * True once [isBusy] reports false, false if [timeoutMs] passes first. Asking can fail (WorkManager may not be there),
     * and then nothing is known to be running, so that counts as idle.
     */
    fun awaitIdle(
        isBusy: () -> Boolean,
        timeoutMs: Long,
        pollMs: Long = 50L,
        nowMs: () -> Long = System::currentTimeMillis,
        sleep: (Long) -> Unit = Thread::sleep
    ): Boolean {
        val deadline = nowMs() + timeoutMs
        while (runCatching(isBusy).getOrDefault(false)) {
            if (nowMs() >= deadline) return false
            sleep(pollMs)
        }
        return true
    }
}
