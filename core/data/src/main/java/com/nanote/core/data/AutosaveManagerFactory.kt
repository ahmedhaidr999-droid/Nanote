package com.nanote.core.data

import com.nanote.core.common.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * Builds [AutosaveManager]s bound to the application scope so final saves
 * survive editor ViewModel destruction (zero data loss on screen exit).
 * Autosave preferences (enabled + debounce) come from the settings store
 * and are read per manager instance.
 */
@Singleton
class AutosaveManagerFactory @Inject constructor(
    private val repo: DocumentRepository,
    private val settings: SettingsStore,
    @ApplicationScope private val appScope: CoroutineScope
) {
    fun create(): AutosaveManager = AutosaveManager(
        externalScope = appScope,
        repo = repo,
        debounceMs = settings.autosaveDebounceSeconds.value * 1000L,
        autosaveEnabled = settings.autosaveEnabled.value
    )
}
