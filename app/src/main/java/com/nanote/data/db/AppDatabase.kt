package com.nanote.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        FolderEntity::class,
        NotebookEntity::class,
        PageEntity::class,
        TagEntity::class,
        NotebookTagEntity::class,
        PageTextEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun notebookDao(): NotebookDao
    abstract fun pageDao(): PageDao
    abstract fun tagDao(): TagDao
    abstract fun pageTextDao(): PageTextDao
}
