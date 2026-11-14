package com.nanote.app.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * App database: the note/folder index. Document content itself is stored as
 * .nanote JSON files on disk (see NotesRepositoryImpl); Room only powers the
 * library lists, search and favorites.
 */
@Database(
    entities = [NoteEntity::class, FolderEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun folderDao(): FolderDao
}
