package com.nanote.feature.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanote.core.common.AppLog
import com.nanote.core.data.DocumentRepository
import com.nanote.core.data.UserPreferencesRepository
import com.nanote.core.model.ActivityAction
import com.nanote.core.model.CreateNotebookRequest
import com.nanote.core.model.DocumentMeta
import com.nanote.core.model.DocumentSorter
import com.nanote.core.model.Folder
import com.nanote.core.model.FolderTreeNode
import com.nanote.core.model.LibraryFilter
import com.nanote.core.model.NotebookDefaults
import com.nanote.core.model.RecentActivityEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Home dashboard state: search across documents and folders, recent
 * documents, favorites, notebooks preview, root folders and the activity
 * feed — plus quick actions (create notebook / folder, imports).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: DocumentRepository,
    prefs: UserPreferencesRepository
) : ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"
        private const val QUERY_DEBOUNCE_MS = 200L
    }

    val query = MutableStateFlow("")

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** Null while the search box is empty. */
    val searchResults: StateFlow<List<DocumentMeta>?> = query
        .debounce(QUERY_DEBOUNCE_MS)
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(null)
            else repo.observeDocuments(LibraryFilter.ALL, q)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val searchFolders: StateFlow<List<Folder>> = query
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList())
            else repo.observeFolders().map { folders ->
                folders.filter { it.name.contains(q, ignoreCase = true) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recent: StateFlow<List<DocumentMeta>> = repo.observeRecent(10)
        .map { DocumentSorter.byLastActivity(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favorites: StateFlow<List<DocumentMeta>> = repo.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val notebooks: StateFlow<List<DocumentMeta>> = repo.observeDocuments(LibraryFilter.NOTEBOOKS)
        .map { DocumentSorter.sort(it, com.nanote.core.model.SortOption.RECENTLY_MODIFIED) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val rootFolders: StateFlow<List<Folder>> = repo.observeFolders()
        .map { folders ->
            folders.filter { it.parentId == null }
                .sortedWith(compareBy({ it.sortOrder }, { it.name }))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activity: StateFlow<List<RecentActivityEntry>> = repo.observeRecentActivity(8)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folderTree: StateFlow<List<FolderTreeNode>> = repo.observeFolderTree()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val notebookDefaults: StateFlow<NotebookDefaults> = prefs.userPreferences
        .map { it.notebookDefaults }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotebookDefaults())

    fun setQuery(value: String) {
        query.value = value
    }

    // ------------------------------------------------------------- actions

    fun createNotebook(request: CreateNotebookRequest, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val id = runCatching { repo.createNotebook(request) }
                .onFailure { AppLog.e(TAG, "createNotebook failed: ${it.message}", it) }
                .getOrNull()
            if (id != null) onCreated(id)
        }
    }

    fun createFolder(name: String, onCreated: (String) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repo.createFolder(name, null) }
                .onSuccess { onCreated(it) }
                .onFailure { AppLog.w(TAG, "createFolder failed: ${it.message}") }
        }
    }

    fun importPdf(uri: Uri, title: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            _importing.value = true
            val id = runCatching { repo.importPdf(uri, title) }
                .onFailure { AppLog.e(TAG, "importPdf failed: ${it.message}", it) }
                .getOrNull()
            _importing.value = false
            onResult(id)
        }
    }

    fun importNanote(uri: Uri, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            _importing.value = true
            val id = runCatching { repo.importNanote(uri) }
                .onFailure { AppLog.e(TAG, "importNanote failed: ${it.message}", it) }
                .getOrNull()
            _importing.value = false
            onResult(id)
        }
    }

    fun toggleFavorite(meta: DocumentMeta) {
        viewModelScope.launch {
            runCatching { repo.setFavorite(meta.id, !meta.favorite) }
        }
    }

    fun rename(id: String, title: String) {
        viewModelScope.launch {
            runCatching { repo.renameDocument(id, title) }
        }
    }

    fun duplicate(id: String) {
        viewModelScope.launch {
            runCatching { repo.duplicateDocument(id) }
        }
    }

    fun deleteIds(ids: Set<String>) {
        viewModelScope.launch {
            runCatching { repo.deleteDocuments(ids) }
        }
    }

    fun moveIds(ids: Set<String>, folderId: String?) {
        viewModelScope.launch {
            runCatching { repo.moveDocuments(ids, folderId) }
        }
    }

    fun logActivity(id: String, action: ActivityAction) {
        viewModelScope.launch {
            runCatching { repo.logActivity(id, action) }
        }
    }
}
