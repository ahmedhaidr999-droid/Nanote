package com.nanote.feature.folders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanote.core.common.AppLog
import com.nanote.core.data.DocumentRepository
import com.nanote.core.data.UserPreferencesRepository
import com.nanote.core.model.Folder
import com.nanote.core.model.LibraryFilter
import com.nanote.feature.library.DocumentBrowser
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Folder browser ViewModel: current folder, breadcrumb path, subfolders
 * (nested), the folder's documents (via a locked [DocumentBrowser]) and
 * all folder management operations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FoldersViewModel @Inject constructor(
    private val repo: DocumentRepository,
    prefs: UserPreferencesRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "FoldersViewModel"
        /** Route sentinel for the root view. */
        const val ROOT = "root"
    }

    /** null = root. */
    val folderId: String? = (savedStateHandle["folderId"] as String?)
        ?.takeIf { it != ROOT }

    private val scope = viewModelScope

    /** Documents inside this folder (selection + actions included). Root shows loose documents only. */
    val browser = DocumentBrowser(
        scope = scope,
        repo = repo,
        prefs = prefs,
        fixedFilter = folderId?.let { LibraryFilter.InFolder(it) } ?: LibraryFilter.ROOT
    )

    /** The current folder itself (null at root). */
    val currentFolder: StateFlow<Folder?> = folderId
        ?.let { id ->
            repo.observeFolders()
                .flatMapLatest { folders -> flowOf(folders.firstOrNull { it.id == id }) }
                .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)
        }
        ?: stateFlowOf(null)

    /** Ancestor chain root-first (empty at root). */
    val path: StateFlow<List<Folder>> = folderId
        ?.let { id -> repo.observeFolderPath(id).stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList()) }
        ?: stateFlowOf(emptyList())

    /** Subfolders of the current folder (root level when folderId == null). */
    val subfolders: StateFlow<List<Folder>> = repo.observeFolders()
        .flatMapLatest { folders ->
            flowOf(
                folders.filter { it.parentId == folderId }
                    .sortedWith(compareBy({ it.sortOrder }, { it.name }))
            )
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whole tree for the move-folder picker. */
    val folderTree = repo.observeFolderTree()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ------------------------------------------------------------- folder ops

    fun createFolder(name: String, parentId: String? = folderId) {
        viewModelScope.launch {
            runCatching { repo.createFolder(name, parentId) }
                .onFailure { AppLog.w(TAG, "createFolder failed: ${it.message}") }
        }
    }

    fun renameFolder(id: String, name: String) {
        viewModelScope.launch {
            runCatching { repo.renameFolder(id, name) }
                .onFailure { AppLog.w(TAG, "renameFolder failed: ${it.message}") }
        }
    }

    /** Deletes the folder; its contents move up to the parent. */
    fun deleteFolder(id: String) {
        viewModelScope.launch {
            runCatching { repo.deleteFolder(id) }
                .onFailure { AppLog.w(TAG, "deleteFolder failed: ${it.message}") }
        }
    }

    /** Moves a folder; cycle moves are rejected by the repository. */
    fun moveFolder(id: String, newParentId: String?) {
        viewModelScope.launch {
            runCatching { repo.moveFolder(id, newParentId) }
                .onFailure { AppLog.w(TAG, "moveFolder failed: ${it.message}") }
        }
    }

    /** Ids of the folder itself + all descendants (to disable in the picker). */
    fun subtreeOf(id: String, onResult: (Set<String>) -> Unit) {
        viewModelScope.launch {
            val out = mutableSetOf(id)
            fun collectChildren(node: com.nanote.core.model.FolderTreeNode) {
                for (child in node.children) {
                    out += child.id
                    collectChildren(child)
                }
            }
            fun find(nodes: List<com.nanote.core.model.FolderTreeNode>): Boolean {
                for (node in nodes) {
                    if (node.id == id) {
                        out += node.id
                        collectChildren(node)
                        return true
                    }
                    if (find(node.children)) return true
                }
                return false
            }
            find(folderTree.value)
            onResult(out)
        }
    }
}

private fun <T> stateFlowOf(value: T): StateFlow<T> =
    kotlinx.coroutines.flow.MutableStateFlow(value)
