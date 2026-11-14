package com.nanote.app.di

import android.content.Context
import com.nanote.core.data.DataStoreUserPreferencesRepository
import com.nanote.core.data.DocumentRepository
import com.nanote.core.data.DocumentRepositoryImpl
import com.nanote.core.data.SettingsStore
import com.nanote.core.data.UserPreferencesRepository
import com.nanote.core.database.AppDatabase
import com.nanote.core.database.FolderDao
import com.nanote.core.database.NoteDao
import com.nanote.core.database.RecentActivityDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Data-layer bindings: Room database + DAOs, the document repository,
 * the DataStore preferences repository and the legacy editor settings store.
 * Everything document-related is a singleton because it is shared across
 * the whole app (library, folders, editor, export).
 */
@InstallIn(SingletonComponent::class)
@Module
object DataModule {

    @Provides
    @Singleton
    fun db(@ApplicationContext c: Context): AppDatabase = AppDatabase.build(c)

    @Provides
    fun noteDao(db: AppDatabase): NoteDao = db.noteDao()

    @Provides
    fun folderDao(db: AppDatabase): FolderDao = db.folderDao()

    @Provides
    fun recentActivityDao(db: AppDatabase): RecentActivityDao = db.recentActivityDao()

    @Provides
    @Singleton
    fun documentRepository(impl: DocumentRepositoryImpl): DocumentRepository = impl

    @Provides
    @Singleton
    fun userPreferencesRepository(@ApplicationContext c: Context): UserPreferencesRepository =
        DataStoreUserPreferencesRepository.fromContext(c)

    @Provides
    @Singleton
    fun settingsStore(@ApplicationContext c: Context): SettingsStore = SettingsStore(c)
}
