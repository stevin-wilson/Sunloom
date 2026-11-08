package com.sunloom.app.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.NativeSqliteAssumption
import com.sunloom.app.Requirement
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Article VII §6 / XIV: the production SQLite store, which every other test replaces with an in-memory fake. Runs
 * where Robolectric's native SQLite exists (CI); it skips on the aarch64 dev box.
 */
@Requirement("VII.6")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SqliteLibraryStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        NativeSqliteAssumption.assumeAvailable()
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `a corrupt database is kept, not deleted, and opening it fails closed`() {
        val dbFile = context.getDatabasePath(SqliteLibraryStore.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.writeBytes(ByteArray(4096) { 0x5A })
        try {
            SqliteLibraryStore(context).getAllBlocklistEntries()
            fail("a corrupt database must not open as an empty one")
        } catch (_: Exception) {
            // expected: the repository maps this to LibraryStoreUnavailableException
        }
        assertTrue("the corrupt file (and the blocklist in it) must survive", dbFile.exists() && dbFile.length() > 0L)
    }

    @Test
    fun `a database written by a newer version still opens`() {
        val first = SqliteLibraryStore(context)
        first.getAllBlocklistEntries()
        val dbFile = context.getDatabasePath(SqliteLibraryStore.DATABASE_NAME)
        android.database.sqlite.SQLiteDatabase.openDatabase(dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
            .use { it.version = SqliteLibraryStore.DATABASE_VERSION + 1 }
        SqliteLibraryStore(context).getAllBlocklistEntries()
    }
}
