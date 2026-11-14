package com.nanote.app.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanote.app.R
import com.nanote.app.core.data.SettingsStore
import com.nanote.app.core.data.repo.FolderMeta
import com.nanote.app.core.data.repo.NoteMeta
import com.nanote.app.core.data.repo.NotesRepository
import com.nanote.app.util.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home library screen state: search, favorites, recents, folders,
 * note CRUD, PDF/.nanote import and export-to-share.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: NotesRepository,
    private val settings: SettingsStore,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"
    }

    /** Compose-state mirror of the current search query (bound to the text field). */
    val query = mutableStateOf("")

    private val queryFlow = MutableStateFlow("")

    /** Latest DAO-driven search results, tagged with the query they belong to. */
    private val daoResults = MutableStateFlow<Pair<String, List<NoteMeta>>?>(null)

    /**
     * Visible notes: all notes when the query is blank; otherwise DAO search
     * results (or a title filter while the DAO lookup is in flight).
     */
    val notes: StateFlow<List<NoteMeta>> =
        combine(repo.observeAllNotes(), queryFlow, daoResults) { all, q, results ->
            if (q.isBlank()) {
                all
            } else {
                val cached = results
                if (cached != null && cached.first == q) {
                    cached.second
                } else {
                    all.filter { it.title.contains(q, ignoreCase = true) }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favorites: StateFlow<List<NoteMeta>> =
        repo.observeFavorites()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recent: StateFlow<List<NoteMeta>> =
        repo.observeRecent(10)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<List<FolderMeta>> =
        repo.observeFolders()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    // ------------------------------------------------------------------ query

    fun onQueryChange(value: String) {
        query.value = value
        queryFlow.value = value
        refresh()
    }

    /** Re-runs the DAO search when the query is non-blank. */
    fun refresh() {
        val q = queryFlow.value
        if (q.isBlank()) {
            daoResults.value = null
            return
        }
        viewModelScope.launch {
            runCatching { repo.searchNotes(q) }
                .onSuccess { daoResults.value = q to it }
                .onFailure { AppLog.w(TAG, "searchNotes failed: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------ create / import

    fun createNote(title: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            _importing.value = true
            val id = runCatching {
                repo.createNote(title.ifBlank { appContext.getString(R.string.untitled_note) })
            }
                .onFailure { AppLog.e(TAG, "createNote failed: ${it.message}", it) }
                .getOrNull()
            _importing.value = false
            if (id != null) onCreated(id)
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

    // ------------------------------------------------------------------ meta ops

    fun rename(id: String, title: String) {
        viewModelScope.launch {
            runCatching { repo.renameNote(id, title) }
                .onFailure { AppLog.e(TAG, "rename failed: ${it.message}", it) }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { repo.deleteNote(id) }
                .onFailure { AppLog.e(TAG, "delete failed: ${it.message}", it) }
        }
    }

    fun duplicate(id: String) {
        viewModelScope.launch {
            _importing.value = true
            runCatching { repo.duplicateNote(id) }
                .onFailure { AppLog.e(TAG, "duplicate failed: ${it.message}", it) }
            _importing.value = false
        }
    }

    fun toggleFavorite(meta: NoteMeta) {
        viewModelScope.launch {
            runCatching { repo.setFavorite(meta.id, !meta.favorite) }
                .onFailure { AppLog.e(TAG, "toggleFavorite failed: ${it.message}", it) }
        }
    }

    fun moveToFolder(id: String, folderId: String?) {
        viewModelScope.launch {
            runCatching { repo.setFolder(id, folderId) }
                .onFailure { AppLog.e(TAG, "moveToFolder failed: ${it.message}", it) }
        }
    }

    // ------------------------------------------------------------------ export

    /**
     * Exports the note into cache/exports and returns the file so the screen
     * can share it through the FileProvider ("<packageName>.fileprovider").
     */
    suspend fun exportFile(meta: NoteMeta, asPdf: Boolean): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(appContext.cacheDir, "exports").apply { mkdirs() }
            val sanitized = meta.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(60)
            val base = sanitized.ifBlank { meta.id }
            val file = File(dir, "$base.${if (asPdf) "pdf" else "nanote"}")
            FileOutputStream(file).use { out ->
                val ok = if (asPdf) repo.exportPdf(meta.id, out) else repo.exportNanote(meta.id, out)
                if (!ok) error("Repository export reported failure")
            }
            file
        }
            .onFailure { AppLog.e(TAG, "exportFile(${meta.id}, asPdf=$asPdf) failed: ${it.message}", it) }
            .getOrNull()
    }

    // ------------------------------------------------------------------ thumbnails

    /** First-page thumbnail used by the library cards. */
    suspend fun thumbnail(docId: String): Bitmap? = repo.thumbnail(docId)

    // ------------------------------------------------------------------ prewarm

    /** Pre-warms a document load in the background (fire and forget). */
    fun openDocument(id: String, onReady: (Boolean) -> Unit) {
        viewModelScope.launch {
            val opened = runCatching { repo.openDocument(id) }
                .onFailure { AppLog.e(TAG, "openDocument prewarm failed: ${it.message}", it) }
                .getOrNull()
            AppLog.d(TAG, "prewarm openDocument($id) -> ${opened != null}")
            onReady(opened != null)
        }
    }
}
