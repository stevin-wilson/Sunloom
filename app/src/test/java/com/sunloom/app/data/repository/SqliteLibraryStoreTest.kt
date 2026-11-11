package com.sunloom.app.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.NativeSqliteAssumption
import com.sunloom.app.Requirement
import org.junit.Assert.assertEquals
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

    // --- Article XIV: upgrades from older databases, against real SQLite ---

    private fun dbPath() = context.getDatabasePath(SqliteLibraryStore.DATABASE_NAME).also { it.parentFile?.mkdirs() }.path

    /** A version-1 database: the original columns only, no blocklist, one row in each table. */
    private fun createVersion1Database(withWallpapersTable: Boolean = true) {
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbPath(), null).use { db ->
            if (withWallpapersTable) {
                db.execSQL(
                    """CREATE TABLE wallpapers (id TEXT PRIMARY KEY, title TEXT NOT NULL, category TEXT NOT NULL,
                    local_path TEXT NOT NULL, cropped_path TEXT NOT NULL, file_size INTEGER NOT NULL,
                    is_favorite INTEGER NOT NULL, is_rejected INTEGER NOT NULL, is_baseline INTEGER NOT NULL,
                    applied_count INTEGER NOT NULL, last_applied INTEGER NOT NULL, status TEXT NOT NULL,
                    decision_explanation TEXT NOT NULL, source_id TEXT NOT NULL, source_name TEXT NOT NULL,
                    original_url TEXT NOT NULL, creator_name TEXT NOT NULL, creator_url TEXT NOT NULL,
                    license_type TEXT NOT NULL, attribution TEXT NOT NULL, retrieved_at INTEGER NOT NULL,
                    orig_w INTEGER NOT NULL, orig_h INTEGER NOT NULL, rend_w INTEGER NOT NULL, rend_h INTEGER NOT NULL,
                    safety_class TEXT NOT NULL, safety_source TEXT NOT NULL, sha256 TEXT NOT NULL,
                    is_eligible INTEGER NOT NULL, global_lum REAL NOT NULL, desktop_lum REAL NOT NULL,
                    clock_lum REAL NOT NULL, glare_frac REAL NOT NULL, contrast_std REAL NOT NULL,
                    brightness_class TEXT NOT NULL, eval_explanation TEXT NOT NULL)""".trimIndent()
                )
                db.execSQL(
                    """INSERT INTO wallpapers VALUES ('old-1','Old','LANDSCAPES_NATURE','/x','/y',10,1,0,0,2,3,'ok','e',
                    'artic_cc0','Art Institute','u','c','cu','CC0','a',4,100,100,100,100,'SAFE','s','abc',1,0.5,0.5,0.5,0.0,0.1,
                    'LIGHT','x')""".trimIndent()
                )
            }
            db.execSQL("CREATE TABLE rejections (asset_id TEXT PRIMARY KEY, source_id TEXT NOT NULL, reason_code TEXT NOT NULL, explanation TEXT NOT NULL, timestamp_ms INTEGER NOT NULL)")
            db.execSQL("INSERT INTO rejections VALUES ('bad-1','s','R','why',5)")
            db.version = 1
        }
    }

    private fun columns(db: android.database.sqlite.SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }

    @Test
    fun `a version 1 database upgrades to the current schema and keeps its rows`() {
        createVersion1Database()

        SqliteLibraryStore(context).getAllBlocklistEntries()

        android.database.sqlite.SQLiteDatabase.openDatabase(dbPath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(SqliteLibraryStore.DATABASE_VERSION, db.version)
            assertTrue(columns(db, "wallpapers").containsAll(listOf("description", "crop_x", "native_coverage", "lightness_p50", "hue_bucket")))
            assertTrue(columns(db, "rejections").containsAll(listOf("is_permanent", "display_signature")))
            assertTrue(columns(db, "blocklist").isNotEmpty())
            db.rawQuery("SELECT title, lightness_p50, hue_bucket FROM wallpapers WHERE id='old-1'", null).use { c ->
                assertTrue("the old row survives", c.moveToFirst())
                assertEquals("Old", c.getString(0))
                assertEquals("unmeasured features default to -1", -1.0, c.getDouble(1), 0.0)
                assertEquals(-1, c.getInt(2))
            }
            db.rawQuery("SELECT is_permanent FROM rejections WHERE asset_id='bad-1'", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("old rejections stay permanent", 1, c.getInt(0))
            }
        }
    }

    @Test
    fun `a database an older build lowered to version 2 upgrades again without error`() {
        SqliteLibraryStore(context).getAllBlocklistEntries() // creates the current schema, v3 columns included
        android.database.sqlite.SQLiteDatabase.openDatabase(dbPath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
            .use { it.version = 2 } // what an older build's onDowngrade leaves behind

        SqliteLibraryStore(context).getAllBlocklistEntries() // must not fail with "duplicate column"

        android.database.sqlite.SQLiteDatabase.openDatabase(dbPath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
            .use { assertEquals(SqliteLibraryStore.DATABASE_VERSION, it.version) }
    }

    @Test
    fun `a failing upgrade is reported and leaves the version and the data alone`() {
        createVersion1Database(withWallpapersTable = false) // the ALTER on wallpapers has no table to change

        try {
            SqliteLibraryStore(context).getAllBlocklistEntries()
            fail("a migration that cannot run must not be swallowed")
        } catch (_: Exception) {
            // expected: the repository maps this to LibraryStoreUnavailableException
        }

        android.database.sqlite.SQLiteDatabase.openDatabase(dbPath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals("the version does not move over a failed migration", 1, db.version)
            db.rawQuery("SELECT COUNT(*) FROM rejections", null).use { c ->
                c.moveToFirst()
                assertEquals("nothing was lost", 1, c.getInt(0))
            }
        }
    }
}
