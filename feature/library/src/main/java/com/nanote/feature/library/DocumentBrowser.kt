package com.nanote.feature.library

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.nanote.core.common.AppLog
import com.nanote.core.data.DocumentRepository
import com.nanote.core.data.UserPreferencesRepository
import com.nanote.core.model.ActivityAction
import com.nanote.core.model.CreateNotebookRequest
import com.nanote.core.model.DocumentMeta
import com.nanote.core.model.DocumentSorter
import com.nanote.core.model.LibraryFilter
import com.nanote.core.model.NotebookSettings
import com.nanote.core.model.SortOption
import com.nanote.core.model.ViewMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Reusable browsing state for any document collection: filter, search,
 * sort, view mode, multi-selection and every management action.
 * Shared by the Library screen and the Folders screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentBrowser(
    private val scope: CoroutineScope,
    private val repo: DocumentRepository,
    private val prefs: UserPreferencesRepository,
    /** Locks the perspective (e.g. FAVORITES or InFolder); null = user-switchable. */
    private val fixedFilter: LibraryFilter? = null
) {

    companion object {
        private const val TAG = "DocumentBrowser"
    }

    val filter: MutableStateFlow<LibraryFilter> =
        MutableStateFlow(fixedFilter ?: LibraryFilter.ALL)

    val query = MutableStateFlow("")

    val sortOption = MutableStateFlow(SortOption.RECENTLY_MODIFIED)

    val viewMode = MutableStateFlow(ViewMode.GRID)

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    /** Snackbar message (string resource) consumed by the host screen. */
    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()

    val documents: StateFlow<List<DocumentMeta>> =
        combine(filter, query) { f, q -> f to q }
            .flatMapLatest { (f, q) -> repo.observeDocuments(f, q) }
            .combine(sortOption) { docs, sort -> DocumentSorter.sort(docs, sort) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Flattened folder tree for the "Move to folder" picker. */
    val folderTree: StateFlow<List<com.nanote.core.model.FolderTreeNode>> =
        repo.observeFolderTree()
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isFilterLocked: Boolean get() = fixedFilter != null

    init {
        scope.launch {
            runCatching { prefs.userPreferences.first() }.onSuccess { p ->
                sortOption.value = p.defaultSort
                viewMode.value = p.defaultViewMode
            }
        }
    }

    // ------------------------------------------------------------------ state

    fun setFilter(value: LibraryFilter) {
        if (fixedFilter == null) {
            filter.value = value
            clearSelection()
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setSort(option: SortOption) {
        sortOption.value = option
        clearSelection()
    }

    fun setViewMode(mode: ViewMode) {
        viewMode.value = mode
    }

    // ------------------------------------------------------------------ selection

    fun toggleSelection(id: String) {
        _selection.value = _selection.value.let {
            if (id in it) it - id else it + id
        }
    }

    fun selectAll() {
        _selection.value = documents.value.map { it.id }.toSet()
    }

    fun clearSelection() {
        if (_selection.value.isNotEmpty()) _selection.value = emptySet()
    }

    val selectionMode: Boolean get() = _selection.value.isNotEmpty()

    // ------------------------------------------------------------------ actions

    fun toggleFavorite(meta: DocumentMeta) {
        scope.launch {
            runCatching { repo.setFavorite(meta.id, !meta.favorite) }
                .onFailure { AppLog.w(TAG, "setFavorite failed: ${it.message}") }
        }
    }

    fun setFavoritesForSelection(favorite: Boolean) {
        val ids = _selection.value
        if (ids.isEmpty()) return
        scope.launch {
            runCatching { repo.setFavorites(ids, favorite) }
                .onFailure { AppLog.w(TAG, "setFavorites failed: ${it.message}") }
        }
        clearSelection()
    }

    fun rename(id: String, title: String) {
        scope.launch {
            runCatching { repo.renameDocument(id, title) }
                .onFailure { AppLog.w(TAG, "rename failed: ${it.message}") }
        }
    }

    fun duplicate(id: String) {
        scope.launch {
            runCatching { repo.duplicateDocument(id) }
                .onFailure { AppLog.w(TAG, "duplicate failed: ${it.message}") }
        }
    }

    fun deleteIds(ids: Set<String>, onDone: () -> Unit = {}) {
        if (ids.isEmpty()) return
        scope.launch {
            runCatching { repo.deleteDocuments(ids) }
                .onSuccess { _message.value = R.string.deleted_items }
                .onFailure { AppLog.w(TAG, "delete failed: ${it.message}") }
            if (_selection.value.isNotEmpty() && _selection.value.intersect(ids.toSet()).isNotEmpty()) {
                clearSelection()
            }
            onDone()
        }
    }

    fun deleteSelection(onDone: () -> Unit = {}) = deleteIds(_selection.value, onDone)

    fun moveSelectionTo(folderId: String?) {
        val ids = _selection.value
        if (ids.isEmpty()) return
        scope.launch {
            runCatching { repo.moveDocuments(ids, folderId) }
                .onSuccess { _message.value = R.string.moved_items }
                .onFailure { AppLog.w(TAG, "move failed: ${it.message}") }
            clearSelection()
        }
    }

    fun updateNotebookSettings(id: String, settings: NotebookSettings) {
        scope.launch {
            runCatching { repo.updateNotebookSettings(id, settings) }
                .onFailure { AppLog.w(TAG, "updateNotebookSettings failed: ${it.message}") }
        }
    }

    /** Custom order helpers (persisted through sortOrder). */
    fun moveCustomOrder(id: String, delta: Int) {
        val current = documents.value
        if (sortOption.value != SortOption.CUSTOM || current.size < 2) return
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = (index + delta).coerceIn(0, current.lastIndex)
        if (target == index) return
        val reordered = current.toMutableList().apply {
            val item = removeAt(index)
            add(target, item)
        }
        sortOption.value = SortOption.CUSTOM
        scope.launch {
            runCatching { repo.setCustomOrder(reordered.map { it.id }) }
                .onFailure { AppLog.w(TAG, "setCustomOrder failed: ${it.message}") }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ------------------------------------------------------------------ import / create

    fun createNotebook(request: CreateNotebookRequest, onCreated: (String) -> Unit) {
        scope.launch {
            val id = runCatching { repo.createNotebook(request) }
                .onFailure { AppLog.e(TAG, "createNotebook failed: ${it.message}", it) }
                .getOrNull()
            if (id != null) onCreated(id)
        }
    }

    fun importPdf(uri: Uri, title: String, onResult: (String?) -> Unit) {
        scope.launch {
            val id = runCatching { repo.importPdf(uri, title) }
                .onFailure { AppLog.e(TAG, "importPdf failed: ${it.message}", it) }
                .getOrNull()
            onResult(id)
        }
    }

    fun importNanote(uri: Uri, onResult: (String?) -> Unit) {
        scope.launch {
            val id = runCatching { repo.importNanote(uri) }
                .onFailure { AppLog.e(TAG, "importNanote failed: ${it.message}", it) }
                .getOrNull()
            onResult(id)
        }
    }

    fun logActivity(id: String, action: ActivityAction) {
        scope.launch {
            runCatching { repo.logActivity(id, action) }
        }
    }
}
