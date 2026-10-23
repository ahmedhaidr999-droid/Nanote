package com.nanote.di

import javax.inject.Qualifier

/** Qualifiers for the distinct File roots of the app (all under filesDir/cacheDir). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NotebooksRoot

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ThumbsDir

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ExportsDir
