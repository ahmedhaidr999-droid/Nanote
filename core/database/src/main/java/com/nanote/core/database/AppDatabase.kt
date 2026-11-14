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
    entities = [NoteEntity::class, FolderEntity::class, RecentActivityEntity::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun folderDao(): FolderDao
    abstract fun recentActivityDao(): RecentActivityDao

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
                        action TEXT NOT NULL,
                        occurredAt INTEGER NOT NULL,
                        FOREIGN KEY(documentId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recent_activity_documentId ON recent_activity(documentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recent_activity_occurredAt ON recent_activity(occurredAt)")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
