package com.nanote.feature.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanote.core.data.AutosaveManager
import com.nanote.core.data.AutosaveManagerFactory
import com.nanote.core.data.SettingsStore
import com.nanote.core.data.DocumentRepository
import com.nanote.core.data.OpenedDocument
import com.nanote.core.data.SaveState
import com.nanote.core.pdf.PageBitmapCache
import com.nanote.core.common.AppLog
import com.nanote.core.model.ExportFormat
import com.nanote.core.model.ExportOptions
import com.nanote.core.model.PdfBookmark
import com.nanote.core.model.PdfExportMode
import com.nanote.core.model.PdfFileInfo
import com.nanote.core.model.PdfPageOpResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editor screen ViewModel — owns pane controllers (1 or 2 for split workspace),
 * per-document autosave, import/export, page operations, bookmarks, reading
 * positions and PDF engine wiring.
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
        private const val POSITION_SAVE_DEBOUNCE_MS = 900L
    }

    val docId: String = savedState.get<String>("docId") ?: ""

    private val _noteTitle = MutableStateFlow("")
    val noteTitle: StateFlow<String> = _noteTitle

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast: SharedFlow<String> = _toast

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting

    /** Autosave lifecycle shown by the save-status chip. */
    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState

    /** PDF bookmarks of the main document. */
    private val _bookmarks = MutableStateFlow<List<PdfBookmark>>(emptyList())
    val bookmarks: StateFlow<List<PdfBookmark>> = _bookmarks

    /** File metadata for the PDF details sheet (null for plain notes). */
    private val _pdfInfo = MutableStateFlow<PdfFileInfo?>(null)
    val pdfInfo: StateFlow<PdfFileInfo?> = _pdfInfo

    /** Result message of the last page operation (transient). */
    private val _pageOpRunning = MutableStateFlow(false)
    val pageOpRunning: StateFlow<Boolean> = _pageOpRunning

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
    private var pendingPositionSave: Job? = null

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
            val pane = newPane(docId, opened)
            _mainPane.value = pane

            // Bookmarks + PDF info + reading position restore.
            observeBookmarks()
            if (opened.pdfFile != null) {
                viewModelScope.launch {
                    _pdfInfo.value = runCatching { repo.pdfFileInfo(docId) }.getOrNull()
                }
            }
            restoreReadingPosition(pane)

            if (opened.model.partiallyRecovered) {
                _toast.tryEmit("corrupted_partial")
            }
            watchReadingPosition(pane)
        }
    }

    private fun attachAutosave(id: String, model: com.nanote.core.model.DocumentModel) {
        if (autosaves.containsKey(id)) return
        val manager = autosaveFactory.create()
        manager.attach(id, model)
        autosaves[id] = manager
        if (id == docId) {
            viewModelScope.launch { manager.state.collect { _saveState.value = it } }
        }
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

    // ---------------- reading position ----------------

    private suspend fun restoreReadingPosition(pane: PaneController) {
        try {
            val pos = repo.readingPosition(docId) ?: return
            if (pos.pageIndex in 0 until pane.pageCount) {
                pane.goToPage(pos.pageIndex)
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "restore position failed: ${t.message}")
        }
    }

    /** Debounced persistence of the reading position (page + zoom). */
    private fun watchReadingPosition(pane: PaneController) {
        viewModelScope.launch {
            pane.currentPage.collect { page ->
                pendingPositionSave?.cancel()
                pendingPositionSave = viewModelScope.launch {
                    delay(POSITION_SAVE_DEBOUNCE_MS)
                    try {
                        repo.saveReadingPosition(docId, page, pane.currentZoomScale())
                    } catch (t: Throwable) {
                        AppLog.w(TAG, "save position failed: ${t.message}")
                    }
                }
            }
        }
    }

    // ---------------- bookmarks ----------------

    private fun observeBookmarks() {
        viewModelScope.launch {
            repo.observeBookmarks(docId).collect { _bookmarks.value = it }
        }
    }

    fun toggleBookmarkOnCurrentPage() {
        val pane = _mainPane.value ?: return
        val page = pane.currentPage.value
        viewModelScope.launch {
            try {
                val added = repo.toggleBookmark(
                    docId, page, pane.pdfPageOf(page),
                    title = _noteTitle.value.ifBlank { "Page ${page + 1}" }
                )
                _toast.tryEmit(if (added) "bookmark_added" else "bookmark_removed")
            } catch (t: Throwable) {
                AppLog.w(TAG, "toggle bookmark failed: ${t.message}")
            }
        }
    }

    fun deleteBookmark(bookmarkId: String) {
        viewModelScope.launch {
            try {
                repo.deleteBookmark(bookmarkId)
            } catch (t: Throwable) {
                AppLog.w(TAG, "delete bookmark failed: ${t.message}")
            }
        }
    }

    fun isCurrentPageBookmarked(): Boolean {
        val page = _mainPane.value?.currentPage?.value ?: -1
        return _bookmarks.value.any { it.pageIndex == page }
    }

    // ---------------- page operations (PDF engine) ----------------

    fun deletePdfPages(pageIndexes: Set<Int>) {
        runPageOp { repo.deletePdfPages(docId, pageIndexes) }
    }

    fun duplicatePdfPage(pageIndex: Int) {
        runPageOp { repo.duplicatePdfPage(docId, pageIndex) }
    }

    fun rotatePdfPage(pageIndex: Int) {
        runPageOp { repo.rotatePdfPage(docId, pageIndex) }
    }

    fun movePdfPage(fromIndex: Int, toIndex: Int) {
        runPageOp { repo.movePdfPage(docId, fromIndex, toIndex) }
    }

    fun insertPdfPagesFrom(uri: Uri, insertAt: Int) {
        runPageOp { repo.insertPdfPagesFrom(docId, uri, insertAt) }
    }

    fun extractPdfPages(pageIndexes: List<Int>) {
        viewModelScope.launch {
            _pageOpRunning.value = true
            try {
                val title = (_noteTitle.value.ifBlank { "Pages" }) + " — extract"
                val newId = repo.extractPdfPagesToDocument(docId, pageIndexes, title)
                if (newId != null) _toast.tryEmit("pages_extracted") else _toast.tryEmit("page_op_failed")
            } catch (t: Throwable) {
                AppLog.e(TAG, "extract failed", t)
                _toast.tryEmit("page_op_failed")
            } finally {
                _pageOpRunning.value = false
            }
        }
    }

    private fun runPageOp(op: suspend () -> PdfPageOpResult) {
        viewModelScope.launch {
            _pageOpRunning.value = true
            val result = try {
                op()
            } catch (t: Throwable) {
                AppLog.e(TAG, "page op failed", t)
                PdfPageOpResult(false, 0, t.message)
            }
            _pageOpRunning.value = false
            if (result.ok) {
                _toast.tryEmit("page_op_done")
                reloadMainDocument()
            } else {
                _toast.tryEmit("page_op_failed")
            }
        }
    }

    /** Re-opens the document after a page operation changed it on disk. */
    private fun reloadMainDocument() {
        viewModelScope.launch {
            val oldPane = _mainPane.value
            autosaves[docId]?.detach()
            oldPane?.close()
            openedDocs.remove(docId)
            val opened = try {
                repo.openDocument(docId)
            } catch (t: Throwable) {
                AppLog.e(TAG, "reload failed", t); null
            } ?: return@launch
            openedDocs[docId] = opened
            attachAutosave(docId, opened.model)
            val pane = newPane(docId, opened)
            _mainPane.value = pane
            if (opened.pdfFile != null) {
                _pdfInfo.value = runCatching { repo.pdfFileInfo(docId) }.getOrNull()
            }
            watchReadingPosition(pane)
        }
    }

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

    // ---------------- export & sharing ----------------

    /** Legacy two-format export (kept for existing callers). */
    fun exportDocument(uri: Uri, asPdf: Boolean, onDone: (Boolean) -> Unit) {
        val options = if (asPdf) {
            ExportOptions(format = ExportFormat.PDF, pdfMode = PdfExportMode.FLATTENED)
        } else {
            ExportOptions(format = ExportFormat.NANOTE)
        }
        exportWithOptions(uri, options, onDone)
    }

    /** Universal export with full Part 3 options, written to a SAF target. */
    fun exportWithOptions(uri: Uri, options: ExportOptions, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            _exporting.value = true
            val ok = try {
                appContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    repo.exportDocument(docId, options, out)
                } ?: false
            } catch (t: Throwable) {
                AppLog.e(TAG, "export failed", t); false
            }
            _exporting.value = false
            if (ok) flushNow()
            onDone(ok)
        }
    }

    /** Renders the export into a cache file (used by share + print). */
    private suspend fun exportToCache(options: ExportOptions, extension: String): File? =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(appContext.cacheDir, "share").apply { mkdirs() }
                // clean older shares
                dir.listFiles()?.forEach { if (it.lastModified() < System.currentTimeMillis() - 86_400_000L) it.delete() }
                val file = File(dir, "nanote-export-${System.currentTimeMillis()}.$extension")
                val ok = FileOutputStream(file).use { out ->
                    repo.exportDocument(docId, options, out)
                }
                if (ok) file else null
            } catch (t: Throwable) {
                AppLog.e(TAG, "exportToCache failed", t)
                null
            }
        }

    /** Exports and hands the file to the Android Sharesheet. */
    fun shareExport(options: ExportOptions) {
        viewModelScope.launch {
            _exporting.value = true
            val file = exportToCache(options, extensionOf(options))
            _exporting.value = false
            if (file == null) {
                _toast.tryEmit("export_failed")
                return@launch
            }
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    appContext, appContext.packageName + ".fileprovider", file
                )
                val mime = when (options.format) {
                    ExportFormat.PDF -> "application/pdf"
                    ExportFormat.PNG -> "image/png"
                    ExportFormat.JPEG -> "image/jpeg"
                    ExportFormat.NANOTE -> "application/octet-stream"
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                appContext.startActivity(
                    Intent.createChooser(send, _noteTitle.value.ifBlank { "Nanote" })
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t: Throwable) {
                AppLog.e(TAG, "share failed", t)
                _toast.tryEmit("export_failed")
            }
        }
    }

    /** Flattens to PDF in the cache and opens the Android Print Framework. */
    fun printDocument() {
        viewModelScope.launch {
            _exporting.value = true
            val file = exportToCache(
                ExportOptions(format = ExportFormat.PDF, pdfMode = PdfExportMode.FLATTENED),
                "pdf"
            )
            _exporting.value = false
            if (file == null) {
                _toast.tryEmit("export_failed")
                return@launch
            }
            try {
                val printManager = appContext.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val jobName = _noteTitle.value.ifBlank { "Nanote document" }
                printManager.print(jobName, FilePrintAdapter(file, jobName), PrintAttributes.Builder().build())
            } catch (t: Throwable) {
                AppLog.e(TAG, "print failed", t)
                _toast.tryEmit("export_failed")
            }
        }
    }

    private fun extensionOf(options: ExportOptions): String = when (options.format) {
        ExportFormat.PDF -> "pdf"
        ExportFormat.PNG -> "png"
        ExportFormat.JPEG -> "jpg"
        ExportFormat.NANOTE -> "nanote"
    }

    /** Page thumbnail loader for the page panel (cached, IO-rendered). */
    suspend fun pageThumbnail(pageIndex: Int, maxW: Int = 120): android.graphics.Bitmap? =
        repo.renderPageBitmap(docId, pageIndex, maxW)

    private fun flushDoc(id: String) {
        viewModelScope.launch {
            autosaves[id]?.flush()
        }
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

/**
 * PrintDocumentAdapter that streams an already-exported PDF file into the
 * print framework (the documented file-copy pattern).
 */
private class FilePrintAdapter(
    private val file: File,
    private val jobName: String
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(file.name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback
    ) {
        try {
            file.inputStream().use { input ->
                FileOutputStream(destination.fileDescriptor).use { output ->
                    input.copyTo(output)
                }
            }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (t: Throwable) {
            callback.onWriteFailed(t.message)
        }
    }
}
