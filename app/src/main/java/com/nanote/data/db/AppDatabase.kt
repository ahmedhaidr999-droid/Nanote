package com.nanote.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FolderEntity::class,
        NotebookEntity::class,
        PageEntity::class,
        TagEntity::class,
        NotebookTagEntity::class,
        PageTextEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun notebookDao(): NotebookDao
    abstract fun pageDao(): PageDao
    abstract fun tagDao(): TagDao
    abstract fun pageTextDao(): PageTextDao

    companion object {
        /**
         * v1 → v2 (Nanote 2.0.0): adds `pages.infinite` for the auto-growing
         * infinite canvas (spec 3). A plain ALTER TABLE — all existing rows
         * keep their data and default to bounded pages, so every notebook
         * from v1.4.0 and earlier opens unchanged.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE pages ADD COLUMN infinite INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}
