package com.nanote.feature.editor

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanote.core.data.AutosaveManager
import com.nanote.core.data.AutosaveManagerFactory
import com.nanote.core.data.SettingsStore
import com.nanote.core.data.DocumentRepository
import com.nanote.core.data.OpenedDocument
import com.nanote.core.pdf.PageBitmapCache
import com.nanote.core.common.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Editor screen ViewModel — owns pane controllers (1 or 2 for split workspace),
 * per-document autosave, import/export and AI wiring.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val repo: DocumentRepository,
    val settings: SettingsStore,
    private val autosaveFactory: AutosaveManagerFactory,
    @ApplicationContext private val appContext: Context,
    private val pageCache: PageBitmapCache
) : ViewModel() {

    companion object {
        private const val TAG = "EditorViewModel"
        const val KEY_MAIN = "main"
        const val KEY_SECOND = "second"
    }

    val docId: String = savedState.get<String>("docId") ?: ""

    private val _noteTitle = MutableStateFlow("")
    val noteTitle: StateFlow<String> = _noteTitle

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast: SharedFlow<String> = _toast

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting

    // split workspace
    private val _splitEnabled = MutableStateFlow(false)
    val splitEnabled: StateFlow<Boolean> = _splitEnabled

    private val _mainPane = MutableStateFlow<PaneController?>(null)
    val mainPane: StateFlow<PaneController?> = _mainPane

    private val _secondPane = MutableStateFlow<PaneController?>(null)
    val secondPane: StateFlow<PaneController?> = _secondPane

    private val openedDocs = HashMap<String, OpenedDocument>()
    private val autosaves = HashMap<String, AutosaveManager>()
    private var secondDocId: String? = null

    val isPdf: Boolean get() = _mainPane.value?.isPdf == true

    init {
        viewModelScope.launch {
            val opened = try {
                repo.openDocument(docId)
            } catch (t: Throwable) {
                AppLog.e(TAG, "open failed", t); null
            }
            if (opened == null) {
                _toast.tryEmit("cannot_open_file")
                return@launch
            }
            openedDocs[docId] = opened
            _noteTitle.value = opened.meta.title
            attachAutosave(docId, opened.model)
            val density = appContext.resources.displayMetrics.density
            val pane = newPane(docId, opened)
            _mainPane.value = pane
            if (opened.model.partiallyRecovered) {
                _toast.tryEmit("corrupted_partial")
            }
        }
    }

    private fun attachAutosave(id: String, model: com.nanote.core.model.DocumentModel) {
        if (autosaves.containsKey(id)) return
        val manager = autosaveFactory.create()
        manager.attach(id, model)
        autosaves[id] = manager
    }

    private fun newPane(id: String, opened: OpenedDocument): PaneController =
        PaneController(
            docId = id,
            context = appContext,
            doc = opened.model,
            settings = settings,
            externalScope = viewModelScope,
            density = appContext.resources.displayMetrics.density,
            pdfFile = opened.pdfFile,
            imageResolver = { rel -> repo.resolveImage(id, rel).takeIf { it.exists() } },
            pageCache = pageCache
        )

    // ---------------- split workspace ----------------

    fun toggleSplit() {
        if (_splitEnabled.value) {
            closeSplit()
        } else {
            _splitEnabled.value = true
            if (_secondPane.value == null) {
                // open a fresh blank note as the second pane by default
                viewModelScope.launch {
                    val id = repo.createNote("")
                    openSecondDoc(id)
                }
            }
        }
    }

    fun openSecondDoc(id: String) {
        viewModelScope.launch {
            val opened = try {
                repo.openDocument(id)
            } catch (t: Throwable) { null }
            if (opened == null) {
                _toast.tryEmit("cannot_open_file")
                return@launch
            }
            openedDocs[id] = opened
            attachAutosave(id, opened.model)
            secondDocId = id
            _secondPane.value?.close()
            _secondPane.value = newPane(id, opened)
            _splitEnabled.value = true
        }
    }

    private fun closeSplit() {
        _splitEnabled.value = false
        _secondPane.value?.let { pane ->
            viewModelScope.launch { flushDoc(pane.docId) }
            pane.close()
        }
        _secondPane.value = null
        secondDocId = null
    }

    fun setSecondDocId(id: String) = openSecondDoc(id)

    // ---------------- media ----------------

    fun insertImage(uri: Uri, paneKey: String) {
        val pane = pane(paneKey) ?: return
        viewModelScope.launch {
            val file = repo.importImageIntoDoc(pane.docId, uri)
            if (file != null) {
                pane.insertImage(file)
                _toast.tryEmit("image_inserted")
            }
        }
    }

    fun cameraTargetFile(paneKey: String): File? {
        val pane = pane(paneKey) ?: return null
        return repo.cameraFile(pane.docId)
    }

    fun insertCameraImage(file: File, paneKey: String) {
        val pane = pane(paneKey) ?: return
        pane.insertImage(file)
    }

    fun importPdfToOtherPane(uri: Uri) {
        viewModelScope.launch {
            _exporting.value = true
            val title = queryTitle(uri) ?: "PDF"
            val id = try {
                repo.importPdf(uri, title)
            } catch (t: Throwable) {
                AppLog.e(TAG, "pdf import failed", t); null
            }
            _exporting.value = false
            if (id != null) openSecondDoc(id)
            else _toast.tryEmit("import_failed")
        }
    }

    private fun queryTitle(uri: Uri): String? = try {
        val cursor = appContext.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && it.moveToFirst()) it.getString(idx) else null
        }
    } catch (t: Throwable) { null }

    // ---------------- export ----------------

    fun exportDocument(uri: Uri, asPdf: Boolean, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            _exporting.value = true
            val ok = try {
                appContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    if (asPdf) repo.exportPdf(docId, out) else repo.exportNanote(docId, out)
                } ?: false
            } catch (t: Throwable) {
                AppLog.e(TAG, "export failed", t); false
            }
            _exporting.value = false
            if (ok) flushNow()
            onDone(ok)
        }
    }

    private suspend fun flushDoc(id: String) {
        autosaves[id]?.flush()
    }

    suspend fun flushNow() {
        for ((id, m) in autosaves) {
            try { m.flush() } catch (t: Throwable) { AppLog.e(TAG, "flush $id failed", t) }
        }
    }

    fun pane(key: String): PaneController? = when (key) {
        KEY_MAIN -> _mainPane.value
        KEY_SECOND -> _secondPane.value
        else -> null
    }

    fun sendToast(message: String) {
        _toast.tryEmit(message)
    }

    override fun onCleared() {
        // Final saves run on the application scope — they survive this cancellation.
        for ((_, m) in autosaves) m.detach()
        _mainPane.value?.close()
        _secondPane.value?.close()
        super.onCleared()
    }
}
