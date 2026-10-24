package com.nanote.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanote.data.files.SettingsStore
import com.nanote.data.repo.NotebookRepository
import com.nanote.domain.model.Folder
import com.nanote.domain.model.Notebook
import com.nanote.domain.model.SortOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.combine
import com.nanote.data.db.TagEntity
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Library state: current folder + sort + view mode + search + multi-select,
 * real first-page thumbnails, pinned favorites and the recent strip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: NotebookRepository,
    private val recent: SettingsStore
) : ViewModel() {

    // Nav arg is a genuine nullable String now (null = library root);
    // no "null" string sentinel decoding anymore (see Routes.LIBRARY_ARGS).
    val folderId: String? = savedStateHandle.get<String>("folderId")

    private val _sort = MutableStateFlow(SortOrder.MODIFIED)
    val sort: StateFlow<SortOrder> = _sort.asStateFlow()

    private val _viewModeGrid = MutableStateFlow(true)
    val viewModeGrid: StateFlow<Boolean> = _viewModeGrid.asStateFlow()

    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    val folders: StateFlow<List<Folder>> = repo.observeFolders(folderId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val breadcrumbs: StateFlow<List<Folder>> = repo.observeBreadcrumb(folderId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notebooks: StateFlow<List<Notebook>> =
        combine(_sort, _search) { sort, q -> sort to q }
            .flatMapLatest { (sort, q) ->
                if (q.isBlank()) repo.observeNotebooks(folderId, sort)
                else repo.searchNotebooksByTitle(q)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pinned: StateFlow<List<Notebook>> = repo.observePinned()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Quick-switch strip: most recently used notebooks, newest first. */
    val recents: StateFlow<List<Notebook>> = recent.recentIds
        .flatMapLatest { ids ->
            if (ids.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyList())
            else repo.observeNotebooksByIds(ids)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --------------------------- tags (M5) ---------------------------

    val tags = repo.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _tagFilter = MutableStateFlow<String?>(null)
    val tagFilter: StateFlow<String?> = _tagFilter.asStateFlow()

    val taggedNotebooks: StateFlow<List<Notebook>> = _tagFilter
        .flatMapLatest { tagId ->
            if (tagId == null) kotlinx.coroutines.flow.flowOf(emptyList())
            else repo.observeNotebooksWithTag(tagId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setTagFilter(tagId: String?) {
        _tagFilter.value = tagId
    }

    fun addTagToNotebook(notebookId: String, name: String) = viewModelScope.launch {
        repo.addTagToNotebook(notebookId, name)
    }

    // --------------------------- multi-select ---------------------------

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    fun toggleSelect(id: String) {
        val cur = _selectedIds.value
        _selectedIds.value = if (id in cur) cur - id else cur + id
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    // --------------------------- actions ---------------------------

    fun setSort(s: SortOrder) {
        _sort.value = s
    }

    fun setViewMode(grid: Boolean) {
        _viewModeGrid.value = grid
    }

    fun setSearch(q: String) {
        _search.value = q
    }

    fun createNotebookAndOpen(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val count = notebooks.value.size
            val id = repo.createNotebook("Note ${count + 1}", folderId)
            recent.touchNotebook(id)
            onCreated(id)
        }
    }

    fun createFolder(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val id = repo.createFolder("Folder ${folders.value.size + 1}", folderId)
            onCreated(id)
        }
    }

    // ------------------- v1.2 folder long-press: rename / delete

    fun renameFolder(id: String, name: String) = viewModelScope.launch {
        repo.renameFolder(id, name)
    }

    /** Soft-deletes the folder tree; restorable from Trash for 30 days. */
    fun trashFolder(id: String) = viewModelScope.launch {
        repo.trashFolders(listOf(id))
    }

    // ------------------------------------------------------- imports (M4)

    fun importPdf(bytes: ByteArray, displayName: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val result = repo.importPdfAsNotebook(
                bytes,
                displayName.ifBlank { "PDF" },
                { f -> com.nanote.ink.render.PdfPageProvider(f) },
                folderId
            )
            onDone(result.getOrNull())
        }
    }

    fun importImages(images: List<ByteArray>, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val result = repo.importImagesAsNotebook(
                images,
                "Images " + java.text.DateFormat.getDateInstance().format(java.util.Date()),
                folderId
            )
            onDone(result.getOrNull())
        }
    }

    fun importArchive(bytes: ByteArray, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val result = repo.importNotebookArchive(bytes.inputStream(), folderId)
            onDone(result.getOrNull())
        }
    }

    fun exportNotebookTo(notebookId: String, out: java.io.OutputStream, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val r = repo.exportNotebookArchive(notebookId, out)
            onDone(r.isSuccess)
        }
    }

    fun renameNotebook(id: String, title: String) = viewModelScope.launch {
        repo.renameNotebook(id, title)
    }

    fun duplicateNotebook(id: String) = viewModelScope.launch {
        repo.duplicateNotebook(id)
    }

    fun togglePin(id: String) = viewModelScope.launch {
        val nb = notebooks.value.firstOrNull { it.id == id } ?: pinned.value.firstOrNull { it.id == id } ?: return@launch
        repo.setPinned(id, !nb.pinned)
    }

    fun trashSelected() = viewModelScope.launch {
        val ids = _selectedIds.value.toList()
        if (ids.isNotEmpty()) {
            repo.trashNotebooks(ids)
            _selectedIds.value = emptySet()
        }
    }

    fun trashOne(id: String) = viewModelScope.launch {
        repo.trashNotebooks(listOf(id))
        _selectedIds.value = _selectedIds.value - id
    }
}
