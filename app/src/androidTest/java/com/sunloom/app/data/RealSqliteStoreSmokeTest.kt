package com.sunloom.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.domain.model.BlocklistEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The production SQLite store on the device's own SQLite (not Robolectric's): it opens, keeps what is written
 * across a reopen, and checkpoints. Every JVM test replaces this store with an in-memory fake.
 */
@RunWith(AndroidJUnit4::class)
class RealSqliteStoreSmokeTest {
    private lateinit var context: Context

    // These tests use a database of their own: deleting the app's real one would pull it out from under the connection
    // the running app (and any other test in this process) holds, which SQLite reports as "database moved".
    private companion object {
        const val TEST_DB = "sunloom_library_smoke_test.db"
    }

    private fun store() = SqliteLibraryStore(context, TEST_DB)
    private fun testDbPath() = context.getDatabasePath(TEST_DB).path

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DB)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun blocklistSurvivesReopeningTheDatabase() {
        val entry = BlocklistEntry("asset-1", "sha-1", "dhash-1", "test", "smoke", 1L)
        store().addBlocklistEntry(entry)

        val reopened = store()
        assertEquals(listOf(entry), reopened.getAllBlocklistEntries())
    }

    @Test
    fun checkpointFoldsTheWriteAheadLogIntoTheMainFile() {
        val store = store()
        store.addBlocklistEntry(BlocklistEntry("asset-wal", "sha-wal", "dhash-wal", "test", "smoke", 2L))

        store.checkpoint()

        // Auto Backup copies the .db file but not the -wal, so the write must now live in the main file.
        val wal = java.io.File(context.getDatabasePath(SqliteLibraryStore.DATABASE_NAME).path + "-wal")
        assertTrue("the -wal file must be empty after a TRUNCATE checkpoint", !wal.exists() || wal.length() == 0L)
    }

    @Test
    fun databaseIsCreatedAtTheCurrentSchemaVersionAndCheckpointsCleanly() {
        val store = store()
        store.getAllBlocklistEntries()
        store.checkpoint()
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            testDbPath(),
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        db.use { assertEquals(SqliteLibraryStore.DATABASE_VERSION, it.version) }
        assertTrue(store.getAllWallpapers().isEmpty())
    }

    @Test
    fun aVersion1DatabaseUpgradesOnTheDevicesSqlite() {
        val path = context.getDatabasePath(TEST_DB).also { it.parentFile?.mkdirs() }.path
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            // The columns version 1 really had and the upgrade's indexes need (retrieved_at, source_id, timestamp_ms);
            // the columns it adds (description, crop_x, ...) are what is being exercised.
            db.execSQL("CREATE TABLE wallpapers (id TEXT PRIMARY KEY, source_id TEXT NOT NULL DEFAULT '', retrieved_at INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE rejections (asset_id TEXT PRIMARY KEY, timestamp_ms INTEGER NOT NULL DEFAULT 0)")
            db.version = 1
        }

        store().getAllBlocklistEntries()

        android.database.sqlite.SQLiteDatabase.openDatabase(path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(SqliteLibraryStore.DATABASE_VERSION, db.version)
            val wallpaperColumns = db.rawQuery("PRAGMA table_info(wallpapers)", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(1)) }
            }
            assertTrue(wallpaperColumns.containsAll(listOf("description", "crop_x", "lightness_p50", "hue_bucket")))
        }
    }

    @Test
    fun aBackgroundRunCheckpointsOnlyWhenItWrote() {
        val store = SqliteLibraryStore.getInstance(context)
        store.getAllBlocklistEntries() // opens (and, on a fresh database, creates) it
        SqliteLibraryStore.checkpointSharedIfWritten() // settles whatever opening wrote
        store.addBlocklistEntry(BlocklistEntry("asset-run", "sha-run", "dhash-run", "test", "smoke", 3L))
        val wal = java.io.File(context.getDatabasePath(SqliteLibraryStore.DATABASE_NAME).path + "-wal")

        SqliteLibraryStore.checkpointSharedIfWritten()

        assertTrue("the write reached the main file", !wal.exists() || wal.length() == 0L)
    }
}
