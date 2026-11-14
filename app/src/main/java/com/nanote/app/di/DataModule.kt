package com.nanote.app.di

import android.content.Context
import androidx.room.Room
import com.nanote.app.core.data.SettingsStore
import com.nanote.app.core.data.db.AppDatabase
import com.nanote.app.core.data.db.FolderDao
import com.nanote.app.core.data.db.NoteDao
import com.nanote.app.core.data.repo.NotesRepository
import com.nanote.app.core.data.repo.NotesRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Data-layer bindings: Room database, DAOs, the notes repository and the
 * settings store. Everything document-related is a singleton because it is
 * shared across the whole app (library, editor, export service).
 */
@InstallIn(SingletonComponent::class)
@Module
object DataModule {

    @Provides
    @Singleton
    fun db(@ApplicationContext c: Context): AppDatabase =
        Room.databaseBuilder(c, AppDatabase::class.java, "nanote.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun noteDao(db: AppDatabase): NoteDao = db.noteDao()

    @Provides
    fun folderDao(db: AppDatabase): FolderDao = db.folderDao()

    @Provides
    @Singleton
    fun notesRepository(impl: NotesRepositoryImpl): NotesRepository = impl

    @Provides
    @Singleton
    fun settingsStore(@ApplicationContext c: Context): SettingsStore = SettingsStore(c)
}
