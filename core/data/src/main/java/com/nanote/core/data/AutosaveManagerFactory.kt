package com.nanote.core.data

import com.nanote.core.data.DocumentRepository
import com.nanote.core.common.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * Builds [AutosaveManager]s bound to the application scope so final saves
 * survive editor ViewModel destruction (zero data loss on screen exit).
 */
@Singleton
class AutosaveManagerFactory @Inject constructor(
    private val repo: DocumentRepository,
    @ApplicationScope private val appScope: CoroutineScope
) {
    fun create(): AutosaveManager = AutosaveManager(appScope, repo)
}
