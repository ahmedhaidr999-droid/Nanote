package com.nanote.di

import android.content.Context
import androidx.room.Room
import com.nanote.data.db.AppDatabase
import com.nanote.data.db.FolderDao
import com.nanote.data.db.NotebookDao
import com.nanote.data.db.PageDao
import com.nanote.data.db.PageTextDao
import com.nanote.data.db.TagDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "nanote.db")
            // Schema v1. Any future schema change MUST ship with a Migration.
            .build()

    @Provides
    fun provideFolderDao(db: AppDatabase): FolderDao = db.folderDao()

    @Provides
    fun provideNotebookDao(db: AppDatabase): NotebookDao = db.notebookDao()

    @Provides
    fun providePageDao(db: AppDatabase): PageDao = db.pageDao()

    @Provides
    fun provideTagDao(db: AppDatabase): TagDao = db.tagDao()

    @Provides
    fun providePageTextDao(db: AppDatabase): PageTextDao = db.pageTextDao()
}
