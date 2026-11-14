package com.nanote.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanote.core.common.AppLog
import com.nanote.core.data.DocumentRepository
import com.nanote.core.data.UserPreferencesRepository
import com.nanote.core.model.LibraryFilter
import com.nanote.core.model.NotebookDefaults
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel behind every library perspective (all / notebooks / imported /
 * favorites / recent). The browsing state lives in [browser]; notebook
 * defaults come from user preferences.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repo: DocumentRepository,
    private val prefs: UserPreferencesRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "LibraryViewModel"
    }

    private val filterToken: String? = savedStateHandle["filter"]

    /** Locks the perspective for parameterized routes (notebooks/favorites/recent). */
    private val lockedFilter: LibraryFilter? =
        LibraryFilter.fromToken(filterToken).takeIf { it != LibraryFilter.ALL }

    val browser = DocumentBrowser(viewModelScope, repo, prefs, lockedFilter)

    /** Defaults applied when the "New notebook" form opens. */
    val notebookDefaults: StateFlow<NotebookDefaults> =
        prefs.userPreferences
            .map { it.notebookDefaults }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotebookDefaults())

    fun createFolder(name: String, parentId: String? = null, onCreated: (String) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repo.createFolder(name, parentId) }
                .onSuccess { onCreated(it) }
                .onFailure { AppLog.w(TAG, "createFolder failed: ${it.message}") }
        }
    }
}

/** Observe notebook defaults concisely from any feature. */
fun UserPreferencesRepository.notebookDefaultsStateIn(
    scope: CoroutineScope
): StateFlow<NotebookDefaults> = userPreferences
    .map { it.notebookDefaults }
    .stateIn(scope, SharingStarted.WhileSubscribed(5_000), NotebookDefaults())
