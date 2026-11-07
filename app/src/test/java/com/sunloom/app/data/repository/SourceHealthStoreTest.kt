package com.sunloom.app.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.data.sources.RateLimitException
import com.sunloom.app.domain.model.SourceHealthState
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Per-source health, backoff and fetch cursor (Article VI §7, X §4). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
@Requirement("X.4")
class SourceHealthStoreTest {

    private lateinit var store: SourceHealthStore
    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("source_health_store_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = SourceHealthStore(prefs, kotlin.random.Random(7))
    }

    @Test
    fun `three failures in a row start a backoff and a success clears it`() {
        val now = 1_000_000L
        store.recordFailure("met_cc0", IllegalStateException("HTTP 500"), now)
        store.recordFailure("met_cc0", IllegalStateException("HTTP 500"), now)
        assertFalse(store.isInBackoff("met_cc0", now))
        store.recordFailure("met_cc0", IllegalStateException("HTTP 500"), now)
        assertTrue(store.isInBackoff("met_cc0", now + 1))

        store.recordSuccess("met_cc0", now)
        assertFalse(store.isInBackoff("met_cc0", now + 1))
    }

    @Test
    fun `a rate limit backs off at least fifteen minutes whatever the server asked`() {
        val now = 5_000L
        store.recordFailure("nasa_library", RateLimitException(retryAfterSeconds = 30, message = "429"), now)
        assertTrue(store.isInBackoff("nasa_library", now + 14 * 60_000L))
        assertFalse(store.isInBackoff("nasa_library", now + 16 * 60_000L))
    }

    @Test
    fun `a cancellation is never recorded as a source failure`() {
        store.recordFailure("smk_open", CancellationException("Job was cancelled"), 1L)
        store.recordFailure("smk_open", IllegalStateException("wrapped", CancellationException("x")), 1L)
        assertEquals(0, store.failureCount("smk_open"))
    }

    @Test
    fun `stored cancellation errors from older versions are healed`() {
        store.recordFailure("smk_open", IllegalStateException("boom"), 1L)
        // an older build recorded this text as if the server had failed
        prefs.edit().putString("source_last_error_smk_open", "Job was cancelled").putInt("source_fail_count_smk_open", 4).commit()
        store.healCancelledDiagnostics(listOf("smk_open"))
        assertEquals(0, store.failureCount("smk_open"))
    }

    @Test
    fun `health state follows failures and backoff`() {
        assertEquals(SourceHealthState.HEALTHY, store.stateFor("a", admitted = true, now = 0L))
        repeat(3) { store.recordFailure("a", IllegalStateException("x"), 0L) }
        assertEquals(SourceHealthState.DEGRADED, store.stateFor("a", admitted = true, now = 0L))
        assertEquals(SourceHealthState.REVOKED, store.stateFor("a", admitted = false, now = 0L))
    }

    @Test
    fun `the cursor starts at a random point, then advances by a step and keeps its own value per source`() {
        val first = store.fetchCursor("wikimedia_featured")
        assertEquals("stable until advanced", first, store.fetchCursor("wikimedia_featured"))
        store.advanceCursor("wikimedia_featured", first)
        assertEquals(first + 6, store.fetchCursor("wikimedia_featured"))
        assertTrue(store.fetchCursor("smk_open") in 0 until 600)
    }

    @Test
    fun `a page that keeps failing is skipped after three failures`() {
        val cursor = store.fetchCursor("cma_cc0")
        repeat(2) { store.recordFailure("cma_cc0", IllegalStateException("x"), 0L) }
        store.moveOnIfStuck("cma_cc0", cursor)
        assertEquals(cursor, store.fetchCursor("cma_cc0"))
        store.recordFailure("cma_cc0", IllegalStateException("x"), 0L)
        store.moveOnIfStuck("cma_cc0", cursor)
        assertEquals(cursor + 6, store.fetchCursor("cma_cc0"))
    }

    @Test
    fun `the sync summary is stored per source for Diagnostics`() {
        store.recordSyncSummary("artic_cc0", fetched = 8, alreadyHad = 2, added = 3, httpErrors = 1)
        assertEquals(
            "fetched 8, added 3, already had 2, rejected or skipped 3, HTTP errors 1",
            store.syncSummary("artic_cc0")
        )
    }
}
