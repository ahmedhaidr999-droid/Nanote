package com.nanote.app.core.data

import com.nanote.app.core.data.repo.NotesRepository
import com.nanote.app.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * Builds [AutosaveManager]s bound to the application scope so final saves
 * survive editor ViewModel destruction (zero data loss on screen exit).
 */
@Singleton
class AutosaveManagerFactory @Inject constructor(
    private val repo: NotesRepository,
    @ApplicationScope private val appScope: CoroutineScope
) {
    fun create(): AutosaveManager = AutosaveManager(appScope, repo)
}
