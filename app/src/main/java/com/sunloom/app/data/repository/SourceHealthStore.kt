package com.sunloom.app.data.repository

import android.content.SharedPreferences
import androidx.core.content.edit
import com.sunloom.app.data.sources.RateLimitException
import com.sunloom.app.data.sources.SourceRegistry
import com.sunloom.app.domain.model.SourceHealthState
import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * What the app remembers about each online source between syncs (Article VI §7, X §4): consecutive failures and
 * the backoff they earn, the last error and sync summary shown in Diagnostics, and the fetch cursor that walks the
 * source's catalog. Split out of the repository so the rules can be tested without a library or a network.
 */
class SourceHealthStore(
    private val prefs: SharedPreferences,
    private val random: kotlin.random.Random = kotlin.random.Random.Default
) {

    // --- failures and backoff ---

    fun failureCount(sourceId: String): Int = prefs.getInt(failKey(sourceId), 0)

    fun backoffUntil(sourceId: String): Long = prefs.getLong(backoffKey(sourceId), 0L)

    fun lastError(sourceId: String): String = prefs.getString(errorKey(sourceId), "") ?: ""

    fun syncSummary(sourceId: String): String = prefs.getString(syncKey(sourceId), "") ?: ""

    fun isInBackoff(sourceId: String, nowEpochMs: Long): Boolean = backoffUntil(sourceId) > nowEpochMs

    fun recordSuccess(sourceId: String, nowEpochMs: Long = System.currentTimeMillis()) {
        prefs.edit {
            clearFailureState(sourceId)
            putLong(successKey(sourceId), nowEpochMs)
        }
    }

    fun recordFailure(sourceId: String, error: Throwable?, nowEpochMs: Long = System.currentTimeMillis()) {
        // A cancelled coroutine is the app stopping or the user leaving, not the source failing.
        if (isCancellation(error) || isCancellationMessage(error?.message.orEmpty())) return
        val fails = failureCount(sourceId) + 1
        val backoffMs = when {
            error is RateLimitException -> maxOf(error.retryAfterSeconds * 1000L, 15L * 60L * 1000L)
            fails >= DEGRADED_FAILURES -> minOf(60L * 60L * 1000L, 5L * 60L * 1000L * (1L shl (fails - DEGRADED_FAILURES).coerceAtMost(4)))
            else -> 0L
        }
        val backoffUntil = if (backoffMs > 0L) nowEpochMs + backoffMs else 0L
        val errorMsg = when (error) {
            is RateLimitException -> "HTTP 429 rate limit (Retry-After: ${error.retryAfterSeconds}s)"
            null -> "Connection error"
            else -> error.message?.take(120) ?: error.javaClass.simpleName
        }
        prefs.edit {
            putInt(failKey(sourceId), fails)
            putLong(backoffKey(sourceId), backoffUntil)
            putString(errorKey(sourceId), errorMsg)
        }
    }

    /**
     * Older versions stored coroutine cancellations ("Job was cancelled") as if the source had failed. Clear those
     * entries so a healthy source is not shown as degraded or left in backoff.
     */
    fun healCancelledDiagnostics(sourceIds: Collection<String>) {
        var editor: SharedPreferences.Editor? = null
        for (sid in sourceIds) {
            if (isCancellationMessage(lastError(sid))) {
                val ed = editor ?: prefs.edit().also { editor = it }
                ed.clearFailureState(sid)
            }
        }
        editor?.apply()
    }

    /** Re-enabling a source clears its failure state unless the server rate-limited it. */
    fun clearTransientFailureOnReenable(sourceId: String) {
        val lastErr = lastError(sourceId)
        if (!lastErr.contains("429") && !lastErr.contains("rate limit", ignoreCase = true)) {
            prefs.edit { clearFailureState(sourceId) }
        }
    }

    fun stateFor(sourceId: String, admitted: Boolean, now: Long): SourceHealthState {
        val fails = failureCount(sourceId)
        return when {
            !admitted -> SourceHealthState.REVOKED
            fails >= SourceRegistry.AUTO_DISABLE_FAILURE_THRESHOLD -> SourceHealthState.AUTO_DISABLED
            isInBackoff(sourceId, now) || fails >= DEGRADED_FAILURES -> SourceHealthState.DEGRADED
            else -> SourceHealthState.HEALTHY
        }
    }

    private fun SharedPreferences.Editor.clearFailureState(sourceId: String) {
        putInt(failKey(sourceId), 0)
        putLong(backoffKey(sourceId), 0L)
        putString(errorKey(sourceId), "")
    }

    // --- fetch cursor ---

    /**
     * Each source walks its own catalog: the cursor starts at a random point on first use and only moves
     * once the source has answered (or has used up its request budget, so a page that always exhausts the
     * budget cannot pin the cursor); a dead network never skips content that was not seen (Article X §4).
     */
    fun fetchCursor(sourceId: String): Int {
        val key = cursorKey(sourceId)
        val stored = prefs.getInt(key, -1)
        if (stored >= 0) return stored
        val start = random.nextInt(CURSOR_RANDOM_START)
        prefs.edit { putInt(key, start) }
        return start
    }

    fun advanceCursor(sourceId: String, from: Int) {
        prefs.edit { putInt(cursorKey(sourceId), (from + CURSOR_STEP) % CURSOR_WRAP) }
    }

    /** A page that fails again and again must not pin the cursor: after three failures in a row, skip it. */
    fun moveOnIfStuck(sourceId: String, cursor: Int) {
        if (failureCount(sourceId) >= STUCK_CURSOR_FAILURES) advanceCursor(sourceId, cursor)
    }

    // --- sync summary ---

    /** Persists a one-line account of the latest sync for a source so Diagnostics can show it. */
    fun recordSyncSummary(
        sourceId: String,
        fetched: Int,
        alreadyHad: Int,
        added: Int,
        httpErrors: Int,
        failure: Throwable? = null
    ) {
        val rejected = (fetched - alreadyHad - added).coerceAtLeast(0)
        val text = buildString {
            append("fetched $fetched, added $added, already had $alreadyHad, rejected or skipped $rejected, HTTP errors $httpErrors")
            if (failure != null) append(" (${failure.message?.take(80) ?: failure.javaClass.simpleName})")
        }
        prefs.edit { putString(syncKey(sourceId), text) }
    }

    companion object {
        // The preference keys, in one place. Persisted data uses exactly these names, so they must not change.
        private fun failKey(id: String) = "source_fail_count_$id"
        private fun backoffKey(id: String) = "source_backoff_until_$id"
        private fun errorKey(id: String) = "source_last_error_$id"
        private fun successKey(id: String) = "source_last_success_$id"
        private fun syncKey(id: String) = "source_last_sync_$id"
        private fun cursorKey(id: String) = "fetch_cursor_$id"

        /** Consecutive failures after which a source is shown as degraded and starts to back off. */
        private const val DEGRADED_FAILURES = 3
        private const val CURSOR_STEP = 6
        private const val STUCK_CURSOR_FAILURES = 3
        private const val CURSOR_WRAP = 1_000_000

        /** First fetch from a source starts somewhere in this range, so installs do not all crawl the same pages. */
        private const val CURSOR_RANDOM_START = 600

        /** True when [error] or anything in its cause chain is a coroutine cancellation. */
        fun isCancellation(error: Throwable?): Boolean {
            var cur = error
            var depth = 0
            while (cur != null && depth < 8) {
                if (cur is CancellationException) return true
                cur = cur.cause
                depth++
            }
            return false
        }

        private fun isCancellationMessage(message: String): Boolean {
            if (message.isBlank()) return false
            val lower = message.lowercase(Locale.ROOT)
            return lower.contains("job was cancelled") ||
                lower.contains("job was canceled") ||
                lower.contains("coroutine was cancelled") ||
                lower.contains("coroutine was canceled") ||
                lower.contains("cancellationscope") ||
                lower.contains("left the composition")
        }
    }
}
