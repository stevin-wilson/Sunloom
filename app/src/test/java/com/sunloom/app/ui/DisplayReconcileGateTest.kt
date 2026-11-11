package com.sunloom.app.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import com.sunloom.app.data.repository.InMemoryLibraryStore
import com.sunloom.app.data.repository.LibraryStore
import com.sunloom.app.data.repository.LocalLibraryRepository
import com.sunloom.app.domain.model.WallpaperRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article IV §6 / XII §2: the app is told about display changes on every window resize, focus change and start. When the
 * display set is the one the library was already reconciled for, that must cost next to nothing.
 */
@Requirement("IV.6")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class DisplayReconcileGateTest {

    // --- the rule ---

    @Test
    fun `a display set is reconciled once, and again only when it changes`() {
        val gate = DisplayReconcileGate()

        assertTrue("nothing reconciled yet", gate.needsReconcile("A"))
        gate.markReconciled("A")
        assertFalse(gate.needsReconcile("A"))
        assertTrue(gate.needsReconcile("B"))
        gate.markReconciled("B")
        assertTrue("back to the first set: a different one from the last", gate.needsReconcile("A"))
    }

    @Test
    fun `invalidating forgets what was reconciled`() {
        val gate = DisplayReconcileGate()
        gate.markReconciled("A")

        gate.invalidate()

        assertTrue(gate.needsReconcile("A"))
    }

    // --- through the real gateway ---

    private class CountingStore(private val base: LibraryStore) : LibraryStore by base {
        var reads = 0
        var writes = 0
        override fun getAllWallpapers(): List<WallpaperRecord> { reads++; return base.getAllWallpapers() }
        override fun getWallpaperById(id: String): WallpaperRecord? { reads++; return base.getWallpaperById(id) }
        override fun upsertWallpaper(record: WallpaperRecord) { writes++; base.upsertWallpaper(record) }
    }

    @Test
    fun `a display change that changes nothing reads little and writes nothing`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = CountingStore(InMemoryLibraryStore())
        val repo = LocalLibraryRepository(context = context, sourceAdapters = emptyList(), store = store)

        // A gateway that has reconciled this display set...
        val settled = AndroidSunloomGateway(context, repository = repo)
        settled.loadInitialSnapshot()
        store.reads = 0; store.writes = 0
        settled.onDisplaysChanged()
        val settledReads = store.reads
        val settledWrites = store.writes

        // ...against one that has not, on the same library.
        val fresh = AndroidSunloomGateway(context, repository = repo)
        store.reads = 0
        fresh.onDisplaysChanged()
        val freshReads = store.reads

        assertEquals("nothing is written", 0, settledWrites)
        assertTrue("it reads far less than a full pass ($settledReads vs $freshReads)", settledReads < freshReads)
    }
}
