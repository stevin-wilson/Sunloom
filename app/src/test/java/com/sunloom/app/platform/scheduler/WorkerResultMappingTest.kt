package com.sunloom.app.platform.scheduler

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import com.sunloom.app.data.repository.LibraryStoreUnavailableException
import com.sunloom.app.domain.model.WallpaperStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class WorkerResultMappingTest {

    private fun change(attempt: Int, cancelled: BooleanArray = booleanArrayOf(false), exec: suspend () -> WallpaperStatus?) =
        runBlocking { mapChangeWorkResult(attempt, exec) { cancelled[0] = true } }

    private fun refresh(attempt: Int, cancelled: BooleanArray = booleanArrayOf(false), exec: suspend () -> Int) =
        runBlocking { mapRefreshWorkResult(attempt, exec) { cancelled[0] = true } }

    @Test
    fun change_successAndNullStatusMapToSuccess() {
        assertEquals(Result.success(), change(0) { WallpaperStatus.APPLIED_SUCCESSFULLY })
        assertEquals(Result.success(), change(0) { null })
        assertEquals(Result.success(), change(0) { WallpaperStatus.UNSUPPORTED_BY_SYSTEM })
    }

    @Test
    fun change_temporaryFailureRetriesUntilThirdAttempt() {
        assertEquals(Result.retry(), change(2) { WallpaperStatus.FAILED_TEMPORARILY })
        assertEquals(Result.success(), change(3) { WallpaperStatus.FAILED_TEMPORARILY })
    }

    @Test
    fun change_unexpectedThrowableRetriesThenFails() {
        assertEquals(Result.retry(), change(0) { throw IllegalStateException("boom") })
        assertEquals(Result.failure(), change(3) { throw IllegalStateException("boom") })
    }

    @Test
    fun change_storeUnavailableRetriesFirstThenSuspendsAutomation() {
        // A store that will not open can be a full disk for a minute: retry before giving up (Article VII §6).
        val early = booleanArrayOf(false)
        assertEquals(Result.retry(), change(0, early) { throw LibraryStoreUnavailableException("down") })
        assertFalse("a first failure must not cancel the schedule", early[0])

        val cancelled = booleanArrayOf(false)
        val result = change(3, cancelled) { throw LibraryStoreUnavailableException("down") }
        assertEquals(Result.failure(), result)
        assertTrue(cancelled[0])
    }

    @Test
    fun change_cancellationPropagates() {
        try {
            change(0) { throw CancellationException("cancelled") }
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun refresh_successMapsToSuccess() {
        assertEquals(Result.success(), refresh(0) { 5 })
    }

    @Test
    fun refresh_unexpectedThrowableRetriesOnceThenSucceeds() {
        assertEquals(Result.retry(), refresh(1) { throw RuntimeException("net") })
        assertEquals(Result.success(), refresh(2) { throw RuntimeException("net") })
    }

    @Test
    fun refresh_storeUnavailableRetriesFirstThenSuspendsAutomation() {
        val early = booleanArrayOf(false)
        assertEquals(Result.retry(), refresh(0, early) { throw LibraryStoreUnavailableException("down") })
        assertFalse(early[0])

        val cancelled = booleanArrayOf(false)
        val result = refresh(3, cancelled) { throw LibraryStoreUnavailableException("down") }
        assertEquals(Result.failure(), result)
        assertTrue(cancelled[0])
    }

    @Test
    fun refresh_cancellationPropagates() {
        val cancelled = booleanArrayOf(false)
        try {
            refresh(0, cancelled) { throw CancellationException("cancelled") }
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertFalse(cancelled[0])
    }

    @Test
    fun workers_doWorkReturnsSuccessWhenAutoChangeDisabled() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val changeResult = runBlocking { TestListenableWorkerBuilder<ChangeWorker>(context).build().doWork() }
        val refreshResult = runBlocking { TestListenableWorkerBuilder<RefreshWorker>(context).build().doWork() }
        assertEquals(Result.success(), changeResult)
        assertEquals(Result.success(), refreshResult)
    }

    @Test
    fun change_outOfMemoryIsRetriedAndRearmedLikeAnyOtherFailure() {
        var rearmed = 0
        fun run(attempt: Int) =
            runBlocking { mapChangeWorkResult(attempt, { throw OutOfMemoryError("bitmap") }, onGiveUp = { rearmed++ }) { } }

        assertEquals(Result.retry(), run(0))
        assertEquals(0, rearmed)
        assertEquals(Result.failure(), run(3))
        assertEquals("the schedule is re-armed so automation does not silently stop", 1, rearmed)
    }

    @Test
    fun refresh_outOfMemoryDoesNotEscapeTheWorker() {
        assertEquals(Result.retry(), refresh(0) { throw OutOfMemoryError("download") })
        assertEquals(Result.success(), refresh(2) { throw OutOfMemoryError("download") })
    }

    @Test
    fun change_rearmsScheduleOnlyWhenRetriesAreExhausted() {
        var rearmed = 0
        fun run(attempt: Int, exec: suspend () -> WallpaperStatus?) =
            runBlocking { mapChangeWorkResult(attempt, exec, onGiveUp = { rearmed++ }) { } }

        run(1) { WallpaperStatus.FAILED_TEMPORARILY }
        run(0) { throw IllegalStateException("boom") }
        assertEquals(0, rearmed)

        run(3) { WallpaperStatus.FAILED_TEMPORARILY }
        assertEquals(1, rearmed)
        run(3) { throw IllegalStateException("boom") }
        assertEquals(2, rearmed)
    }

    private fun watch(opens: suspend () -> Boolean, recovered: BooleanArray) =
        runBlocking { mapStoreWatchResult(opens) { recovered[0] = true } }

    @Test
    fun storeWatch_resumesAutomationOnceTheStoreOpens() {
        val recovered = booleanArrayOf(false)
        assertEquals(Result.success(), watch({ true }, recovered))
        assertTrue(recovered[0])
    }

    @Test
    fun storeWatch_staysQuietWhileTheStoreIsStillDown() {
        val recovered = booleanArrayOf(false)
        assertEquals(Result.success(), watch({ throw LibraryStoreUnavailableException("down") }, recovered))
        assertFalse(recovered[0])
        assertEquals(Result.success(), watch({ throw IllegalStateException("boom") }, recovered))
        assertFalse(recovered[0])
        assertEquals(Result.success(), watch({ false }, recovered))
        assertFalse(recovered[0])
    }

    @Test
    fun storeWatch_cancellationPropagates() {
        try {
            watch({ throw CancellationException("cancelled") }, booleanArrayOf(false))
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
    }
}
