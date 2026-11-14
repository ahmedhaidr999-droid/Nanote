package com.nanote.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MIGRATION_1_2: the v1.0.0 schema (notes + flat folders) must upgrade to
 * the Part 1 schema (notebook presentation columns, nested folders,
 * recent_activity) without losing data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun migrate1To2_preservesRowsAndAddsColumns() = runBlocking {
        val dbFile = context.getDatabasePath("migration-test.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        // --- v1.0.0 schema, written by hand exactly as Room v1 generated it.
        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqlite.execSQL(
            """CREATE TABLE IF NOT EXISTS notes (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                kind TEXT NOT NULL,
                favorite INTEGER NOT NULL,
                folderId TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                pageCount INTEGER NOT NULL)"""
        )
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS index_notes_folderId ON notes(folderId)")
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS index_notes_updatedAt ON notes(updatedAt)")
        sqlite.execSQL(
            """CREATE TABLE IF NOT EXISTS folders (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                sortOrder INTEGER NOT NULL)"""
        )
        sqlite.execSQL("INSERT INTO notes VALUES ('n1', 'Old note', 'NOTE', 0, NULL, 10, 20, 3)")
        sqlite.execSQL("INSERT INTO notes VALUES ('n2', 'Old PDF', 'PDF', 1, NULL, 11, 21, 9)")
        sqlite.execSQL("INSERT INTO folders VALUES ('f1', 'Docs', 0)")
        sqlite.close()

        // --- open with Room; the migration runs and the schema is validated.
        db = Room.databaseBuilder(context, AppDatabase::class.java, "migration-test.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        val n1 = db.noteDao().getById("n1")
        assertNotNull(n1)
        assertEquals("Old note", n1!!.title)
        assertEquals("BLUE", n1.coverColor)
        assertEquals("NOTEBOOK", n1.icon)
        assertEquals("LINED", n1.pageTemplate)
        assertEquals("PORTRAIT", n1.pageOrientation)
        assertEquals("A4", n1.pageSize)
        assertEquals(0L, n1.lastOpenedAt)
        assertEquals(0, n1.sortOrder)

        val n2 = db.noteDao().getById("n2")
        assertEquals(true, n2!!.favorite)

        val f1 = db.folderDao().getById("f1")
        assertNotNull(f1)
        assertEquals("Docs", f1!!.name)
        assertNull(f1.parentId)

        // --- the new activity table accepts rows and cascades on delete.
        db.recentActivityDao().insert(
            RecentActivityEntity(id = 0, documentId = "n1", action = "OPENED", occurredAt = 99)
        )
        assertEquals(1, db.recentActivityDao().observeRecent(10).first().size)

        db.noteDao().deleteByIds(setOf("n1"))
        assertEquals(0, db.recentActivityDao().observeRecent(10).first().size)
    }
}
