package com.nanote.di

import android.content.Context
import com.nanote.data.files.PageStore
import com.nanote.ink.render.ThumbRenderer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FilesModule {

    @Provides
    @Singleton
    @NotebooksRoot
    fun provideNotebooksRoot(@ApplicationContext context: Context): File =
        File(context.filesDir, "notebooks")

    @Provides
    @Singleton
    @ThumbsDir
    fun provideThumbsDir(@ApplicationContext context: Context): File =
        File(context.filesDir, "thumbs").apply { mkdirs() }

    @Provides
    @Singleton
    @ExportsDir
    fun provideExportsDir(@ApplicationContext context: Context): File =
        File(context.cacheDir, "exports").apply { mkdirs() }

    @Provides
    @Singleton
    fun providePageStore(@NotebooksRoot notebooksRoot: File): PageStore = PageStore(notebooksRoot)

    @Provides
    @Singleton
    fun provideThumbRenderer(@ThumbsDir thumbsDir: File): ThumbRenderer = ThumbRenderer(thumbsDir)
}
