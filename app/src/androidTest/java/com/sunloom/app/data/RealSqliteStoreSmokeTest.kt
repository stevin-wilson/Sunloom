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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(SqliteLibraryStore.DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(SqliteLibraryStore.DATABASE_NAME)
    }

    @Test
    fun blocklistSurvivesReopeningTheDatabase() {
        val entry = BlocklistEntry("asset-1", "sha-1", "dhash-1", "test", "smoke", 1L)
        SqliteLibraryStore(context).addBlocklistEntry(entry)

        val reopened = SqliteLibraryStore(context)
        assertEquals(listOf(entry), reopened.getAllBlocklistEntries())
    }

    @Test
    fun checkpointFoldsTheWriteAheadLogIntoTheMainFile() {
        val store = SqliteLibraryStore(context)
        store.addBlocklistEntry(BlocklistEntry("asset-wal", "sha-wal", "dhash-wal", "test", "smoke", 2L))

        store.checkpoint()

        // Auto Backup copies the .db file but not the -wal, so the write must now live in the main file.
        val wal = java.io.File(context.getDatabasePath(SqliteLibraryStore.DATABASE_NAME).path + "-wal")
        assertTrue("the -wal file must be empty after a TRUNCATE checkpoint", !wal.exists() || wal.length() == 0L)
    }

    @Test
    fun databaseIsCreatedAtTheCurrentSchemaVersionAndCheckpointsCleanly() {
        val store = SqliteLibraryStore(context)
        store.getAllBlocklistEntries()
        store.checkpoint()
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath(SqliteLibraryStore.DATABASE_NAME).path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        db.use { assertEquals(SqliteLibraryStore.DATABASE_VERSION, it.version) }
        assertTrue(store.getAllWallpapers().isEmpty())
    }
}
