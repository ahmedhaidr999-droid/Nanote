package com.nanote.core.common

import javax.inject.Qualifier

/**
 * Marks the application-lifetime [kotlinx.coroutines.CoroutineScope] provided
 * by DI. Work launched in this scope outlives ViewModels (e.g. final saves
 * when the editor screen is destroyed).
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD)
annotation class ApplicationScope
