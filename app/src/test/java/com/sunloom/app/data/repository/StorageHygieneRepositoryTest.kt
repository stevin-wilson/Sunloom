package com.sunloom.app.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Storage hygiene runs at most once a day, however often the app opens or a worker runs (Article X §1). */
@Requirement("X.3")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class StorageHygieneRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("sunloom_preferences", Context.MODE_PRIVATE).edit().remove("last_storage_hygiene_ms").commit()
    }

    @Test
    fun `hygiene runs once, then waits a day`() {
        val repo = LocalLibraryRepository(context)
        val day = 24L * 60 * 60 * 1000
        val t0 = 1_800_000_000_000L

        assertNotNull("the first call runs", repo.runStorageHygieneIfDue(t0))
        assertNull("a second call the same hour does nothing", repo.runStorageHygieneIfDue(t0 + 60 * 60 * 1000))
        assertNull("still within the day", repo.runStorageHygieneIfDue(t0 + day - 1))
        assertEquals(StorageHygieneReport(orphanFilesRemoved = 0, staleRowsRemoved = 0), repo.runStorageHygieneIfDue(t0 + day))
    }
}
