package com.nanote.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * App database: the notebook/folder/activity index. Document content itself
 * is stored as .nanote JSON files on disk (see the storage module); Room
 * powers the library lists, search, favorites, folder tree and activity feed.
 */
@Database(
    entities = [
        NoteEntity::class,
        FolderEntity::class,
        RecentActivityEntity::class,
        TagEntity::class,
        NoteTagEntity::class,
        PdfTextPageEntity::class,
        PdfBookmarkEntity::class,
        ReadingPositionEntity::class,
        SearchHistoryEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun folderDao(): FolderDao
    abstract fun recentActivityDao(): RecentActivityDao
    abstract fun tagDao(): TagDao
    abstract fun pdfTextDao(): PdfTextDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun readingPositionDao(): ReadingPositionDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        const val NAME = "nanote.db"

        /**
         * v1 → v2 (Part 1 foundation):
         * - notes: notebook presentation columns + lastOpenedAt + sortOrder
         * - folders: nested folders (parentId) + timestamps
         * - new recent_activity table (cascade on document delete)
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN coverColor TEXT NOT NULL DEFAULT 'BLUE'")
                db.execSQL("ALTER TABLE notes ADD COLUMN icon TEXT NOT NULL DEFAULT 'NOTEBOOK'")
                db.execSQL("ALTER TABLE notes ADD COLUMN pageTemplate TEXT NOT NULL DEFAULT 'LINED'")
                db.execSQL("ALTER TABLE notes ADD COLUMN pageOrientation TEXT NOT NULL DEFAULT 'PORTRAIT'")
                db.execSQL("ALTER TABLE notes ADD COLUMN pageSize TEXT NOT NULL DEFAULT 'A4'")
                db.execSQL("ALTER TABLE notes ADD COLUMN lastOpenedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_lastOpenedAt ON notes(lastOpenedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_sortOrder ON notes(sortOrder)")
                db.execSQL("ALTER TABLE folders ADD COLUMN parentId TEXT")
                db.execSQL("ALTER TABLE folders ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_folders_parentId ON folders(parentId)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS recent_activity (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        documentId TEXT NOT NULL,
                        activityAction TEXT NOT NULL,
                        occurredAt INTEGER NOT NULL,
                        FOREIGN KEY(documentId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recent_activity_documentId ON recent_activity(documentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recent_activity_occurredAt ON recent_activity(occurredAt)")
            }
        }

        /**
         * v2 → v3 (Part 3 — PDF engine, local storage, search & export):
         * - notes: imported-file metadata (fileSizeBytes, sourceFileName)
         * - new tags + note_tags tables (document tagging)
         * - new pdf_text_pages table (local full-text search index)
         * - new pdf_bookmarks table
         * - new reading_positions table
         * - new search_history table
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN fileSizeBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN sourceFileName TEXT DEFAULT NULL")

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS tags (
                        name TEXT NOT NULL PRIMARY KEY,
                        color INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS note_tags (
                        noteId TEXT NOT NULL,
                        tagName TEXT NOT NULL,
                        PRIMARY KEY(noteId, tagName),
                        FOREIGN KEY(noteId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(tagName) REFERENCES tags(name) ON UPDATE NO ACTION ON DELETE CASCADE
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_note_tags_noteId ON note_tags(noteId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_note_tags_tagName ON note_tags(tagName)")

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS pdf_text_pages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        documentId TEXT NOT NULL,
                        pageIndex INTEGER NOT NULL,
                        pdfPage INTEGER NOT NULL,
                        text TEXT NOT NULL,
                        FOREIGN KEY(documentId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pdf_text_pages_documentId ON pdf_text_pages(documentId)")

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS pdf_bookmarks (
                        id TEXT NOT NULL PRIMARY KEY,
                        documentId TEXT NOT NULL,
                        pageIndex INTEGER NOT NULL,
                        pdfPage INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(documentId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pdf_bookmarks_documentId ON pdf_bookmarks(documentId)")

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS reading_positions (
                        documentId TEXT NOT NULL PRIMARY KEY,
                        pageIndex INTEGER NOT NULL,
                        zoomScale REAL NOT NULL,
                        savedAt INTEGER NOT NULL,
                        FOREIGN KEY(documentId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )"""
                )

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS search_history (
                        query TEXT NOT NULL PRIMARY KEY,
                        searchedAt INTEGER NOT NULL
                    )"""
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
