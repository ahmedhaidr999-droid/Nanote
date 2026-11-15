package com.nanote.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
 *
 * The v1 database is created through the same SupportSQLiteOpenHelper
 * machinery Room uses (like MigrationTestHelper, but inline) to avoid any
 * raw-SQLiteDatabase interop quirks.
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

    /** Creates a v1.0.0-shaped database file through the framework factory. */
    private fun createV1Database(name: String) {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
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
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_folderId ON notes(folderId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_updatedAt ON notes(updatedAt)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS folders (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL)"""
                )
                db.execSQL("INSERT INTO notes VALUES ('n1', 'Old note', 'NOTE', 0, NULL, 10, 20, 3)")
                db.execSQL("INSERT INTO notes VALUES ('n2', 'Old PDF', 'PDF', 1, NULL, 11, 21, 9)")
                db.execSQL("INSERT INTO folders VALUES ('f1', 'Docs', 0)")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(callback)
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        // Accessing the database triggers onCreate; no query needed.
        @Suppress("UNUSED_EXPRESSION")
        helper.writableDatabase
        helper.close()
    }

    @Test
    fun migrate1To2_preservesRowsAndAddsColumns() = runBlocking {
        val dbName = "migration-test.db"
        context.getDatabasePath(dbName).parentFile?.mkdirs()
        context.getDatabasePath(dbName).delete()
        createV1Database(dbName)

        // --- open with Room; the migration runs and the schema is validated.
        db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
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

    // ------------------------------------------------------------------
    // v2 → v3 (Part 3)
    // ------------------------------------------------------------------

    /** Creates a v2 (Part 1) shaped database file through the framework factory. */
    private fun createV2Database(name: String) {
        val callback = object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS notes (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        favorite INTEGER NOT NULL,
                        folderId TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        pageCount INTEGER NOT NULL,
                        coverColor TEXT NOT NULL DEFAULT 'BLUE',
                        icon TEXT NOT NULL DEFAULT 'NOTEBOOK',
                        pageTemplate TEXT NOT NULL DEFAULT 'LINED',
                        pageOrientation TEXT NOT NULL DEFAULT 'PORTRAIT',
                        pageSize TEXT NOT NULL DEFAULT 'A4',
                        lastOpenedAt INTEGER NOT NULL DEFAULT 0,
                        sortOrder INTEGER NOT NULL DEFAULT 0)"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_folderId ON notes(folderId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_updatedAt ON notes(updatedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_lastOpenedAt ON notes(lastOpenedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_sortOrder ON notes(sortOrder)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS folders (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        parentId TEXT,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0)"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_folders_parentId ON folders(parentId)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS recent_activity (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        documentId TEXT NOT NULL,
                        activityAction TEXT NOT NULL,
                        occurredAt INTEGER NOT NULL,
                        FOREIGN KEY(documentId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE CASCADE)"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recent_activity_documentId ON recent_activity(documentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recent_activity_occurredAt ON recent_activity(occurredAt)")
                db.execSQL("INSERT INTO notes VALUES ('p1', 'Lecture 1', 'PDF', 1, NULL, 100, 200, 12, 'SLATE', 'NOTES', 'PLAIN', 'PORTRAIT', 'A4', 150, 0)")
                db.execSQL("INSERT INTO notes VALUES ('p2', 'Journal', 'NOTE', 0, NULL, 101, 201, 4, 'BLUE', 'JOURNAL', 'LINED', 'PORTRAIT', 'A5', 151, 1)")
                db.execSQL("INSERT INTO folders VALUES ('f9', 'Uni', 0, NULL, 5, 6)")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(callback)
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        @Suppress("UNUSED_EXPRESSION")
        helper.writableDatabase
        helper.close()
    }

    @Test
    fun migrate2To3_preservesRowsAndAddsTables() = runBlocking {
        val dbName = "migration-test-v3.db"
        context.getDatabasePath(dbName).parentFile?.mkdirs()
        context.getDatabasePath(dbName).delete()
        createV2Database(dbName)

        db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        // --- existing rows survive with sensible defaults for new columns.
        val p1 = db.noteDao().getById("p1")
        assertNotNull(p1)
        assertEquals("Lecture 1", p1!!.title)
        assertEquals(0L, p1.fileSizeBytes)
        assertNull(p1.sourceFileName)

        // --- tags accept assignments and cascade.
        db.tagDao().upsert(TagEntity(name = "study", color = 0xFF2563EB, createdAt = 1))
        db.tagDao().setNoteTags("p1", listOf("study"))
        assertEquals(listOf("study"), db.tagDao().tagsForNote("p1"))
        assertEquals(listOf("p1"), db.tagDao().notesWithTag("study"))
        db.noteDao().deleteByIds(setOf("p1"))
        assertEquals(0, db.tagDao().notesWithTag("study").size)

        // --- the PDF text index accepts rows and is searchable.
        db.pdfTextDao().insertAll(
            listOf(
                PdfTextPageEntity(id = 0, documentId = "p2", pageIndex = 0, pdfPage = -1, text = "hello world"),
                PdfTextPageEntity(id = 0, documentId = "p2", pageIndex = 1, pdfPage = -1, text = "second page")
            )
        )
        assertEquals(2, db.pdfTextDao().countForDoc("p2"))
        assertEquals(1, db.pdfTextDao().search("second", 10).size)

        // --- bookmarks + reading positions round-trip.
        db.bookmarkDao().upsert(
            PdfBookmarkEntity(id = "b1", documentId = "p2", pageIndex = 1, pdfPage = 1, title = "Ch 2", createdAt = 9)
        )
        assertEquals(1, db.bookmarkDao().getForDoc("p2").size)
        db.readingPositionDao().upsert(
            ReadingPositionEntity(documentId = "p2", pageIndex = 1, zoomScale = 1.5f, savedAt = 10)
        )
        assertEquals(1.5f, db.readingPositionDao().get("p2")!!.zoomScale)

        // --- search history round-trips.
        db.searchHistoryDao().upsert(SearchHistoryEntity(query = "lecture", searchedAt = 11))
        assertEquals(1, db.searchHistoryDao().getRecent(5).size)
    }
}
