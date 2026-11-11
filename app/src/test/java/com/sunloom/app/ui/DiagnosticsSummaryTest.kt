package com.sunloom.app.ui

import com.sunloom.app.Requirement
import com.sunloom.app.domain.matcher.SourceRejectionGroup
import com.sunloom.app.domain.matcher.SourceStockRow
import com.sunloom.app.domain.model.SourceDiagnostics
import com.sunloom.app.domain.model.SourceHealthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4

/** Article X §4: Diagnostics words what each source holds and how its last sync went. */
@Requirement("X.4")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class DiagnosticsSummaryTest {
    private val r get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources


    @Test
    fun `a source with images shows the split by brightness and the unseen count`() {
        val row = SourceStockRow("wikimedia_featured", "Wikimedia", light = 9, dark = 3, balanced = 2, unseen = 10, isFallback = false)
        assertEquals("Wikimedia: 14 images (9 Light, 3 Dark, 2 Balanced), 10 unseen", sourceStockLine(row, r))
    }

    @Test
    fun `a source that holds nothing says so plainly`() {
        val row = SourceStockRow("cma_cc0", "Cleveland Museum of Art", 0, 0, 0, 0, isFallback = false)
        assertEquals("Cleveland Museum of Art: nothing in the reserve", sourceStockLine(row, r))
    }

    @Test
    fun `fallbacks are labelled as not counting towards the reserve target`() {
        val row = SourceStockRow("seed_reserve", "Offline seeds", light = 4, dark = 2, balanced = 0, unseen = 6, isFallback = true)
        assertTrue(sourceStockLine(row, r).endsWith("fallback, not counted towards the reserve target"))
    }

    @Test
    fun `the sync line combines health, last sync and last error and is absent when there is nothing to say`() {
        assertNull(sourceSyncLine(null, r))
        assertNull(sourceSyncLine(SourceDiagnostics("a", "A", isAdmitted = true, isEnabled = true), r))
        val diag = SourceDiagnostics(
            "smk_open", "SMK", isAdmitted = true, isEnabled = true,
            healthState = SourceHealthState.DEGRADED,
            lastSyncSummary = "fetched 8, added 0, rejected 8", lastError = "HTTP 429"
        )
        assertEquals("${SourceHealthState.DEGRADED.label} • Last sync: fetched 8, added 0, rejected 8 • HTTP 429", sourceSyncLine(diag, r))
    }

    @Test
    fun `a rejection group reads as source, reason and count`() {
        assertEquals(
            "cma_cc0: COMPOSITION_CROPS_TOO_MUCH × 12",
            rejectionGroupLine(SourceRejectionGroup("cma_cc0", "COMPOSITION_CROPS_TOO_MUCH", 12))
        )
    }
}
