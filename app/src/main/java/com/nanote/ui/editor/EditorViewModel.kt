package com.nanote.ui.editor

import android.graphics.Color
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanote.data.files.PageStore
import com.nanote.data.files.SettingsStore
import com.nanote.data.repo.NotebookRepository
import com.nanote.di.IoDispatcher
import com.nanote.di.ThumbsDir
import com.nanote.domain.model.Page
import com.nanote.ink.engine.AddStrokes
import com.nanote.ink.engine.PAGE_SIZES_PT
import com.nanote.core.StylusRegistry
import com.nanote.data.files.ToolbarPosition
import com.nanote.domain.model.ImageObj
import com.nanote.domain.model.LinkObj
import com.nanote.domain.model.LinkTarget
import com.nanote.domain.model.PageObject
import com.nanote.domain.model.TextBox
import com.nanote.domain.model.identityMatrix
import com.nanote.ink.engine.AddObjects
import com.nanote.ink.engine.EditorTool
import com.nanote.ink.engine.History
import com.nanote.ink.engine.PageDoc
import com.nanote.ink.engine.PenType
import com.nanote.ink.engine.Stroke
import com.nanote.ink.engine.ToolSpec
import com.nanote.ink.engine.ViewTransform
import com.nanote.ink.render.InkCanvasView
import com.nanote.ink.render.InkTileCache
import com.nanote.ink.render.PageSnapshot
import com.nanote.ink.render.ThumbRenderer
import com.nanote.ui.library.ThumbLoader
import android.graphics.Bitmap
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * One ViewModel per editor screen (spec 4). Owns the PageDoc + History +
 * ViewTransform + TileCache of the edited page (they survive config changes
 * because they live here, not in the View) and drives autosave.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: NotebookRepository,
    private val pageStore: PageStore,
    private val thumbs: ThumbRenderer,
    private val recent: SettingsStore,
    private val thumbLoader: ThumbLoader,
    @ThumbsDir private val thumbsDir: java.io.File,
    @com.nanote.di.ExportsDir private val exportsDir: java.io.File,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    @IoDispatcher private val io: CoroutineDispatcher
) : ViewModel() {

    val notebookId: String = savedStateHandle.get<String>("notebookId").orEmpty()
    private val requestedPageId: String = savedStateHandle.get<String>("pageId") ?: "first"

    val title: StateFlow<String> = repo.observeNotebook(notebookId)
        .map { it?.title ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val pagesFlow: StateFlow<List<Page>> = repo.observePages(notebookId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pageIndex = MutableStateFlow(0)

    private val _tool = MutableStateFlow(ToolSpec())
    val tool: StateFlow<ToolSpec> = _tool.asStateFlow()

    private val _fingersDraw = MutableStateFlow(true)
    val fingersDraw: StateFlow<Boolean> = _fingersDraw.asStateFlow()

    /** v1.1: full palm rejection — pen-only input (mirrored in Settings). */
    private val _stylusOnly = MutableStateFlow(false)
    val stylusOnly: StateFlow<Boolean> = _stylusOnly.asStateFlow()

    /** v1.1: where the tool palette sits (top/bottom/left/right). */
    val toolbarPosition: StateFlow<ToolbarPosition> = recent.toolbarPosition
        .stateIn(viewModelScope, SharingStarted.Eagerly, ToolbarPosition.TOP)

    /** v1.1: barrel-button action consumed by the input router. */
    @Volatile private var barrelAction: com.nanote.ink.engine.StylusBarrelAction =
        com.nanote.ink.engine.StylusBarrelAction.ERASE

    init {
        // mirror persisted input settings into the live editor state
        viewModelScope.launch {
            recent.fingersDraw.collect { _fingersDraw.value = it }
        }
        viewModelScope.launch {
            recent.stylusOnly.collect { _stylusOnly.value = it }
        }
        viewModelScope.launch {
            recent.stylusButton.collect { barrelAction = it }
        }
        // one-shot: known stylus tablet -> enable palm rejection by default
        viewModelScope.launch {
            if (!recent.stylusAutoCfgDone()) {
                recent.setStylusAutoCfgDone()
                val pen = StylusRegistry.detectForThisDevice()
                if (pen != null && pen.palmRejection) {
                    recent.setStylusOnly(true)
                    recent.setFingersDraw(false)
                }
            }
        }
    }

    val undoDepth = MutableStateFlow(0)
    val redoDepth = MutableStateFlow(0)

    /** View invalidation signal after undo/redo/page switches. */
    val refreshTick = MutableStateFlow(0L)

    @Volatile var surfaceArgb: Int = Color.LTGRAY

    // ------------------------------------------------------------ session

    var session: PageSession? = null
        private set

    /** Editing state of one page; implements the canvas Host. */
    inner class PageSession(
        val page: Page,
        override val doc: PageDoc,
        override val history: History,
        override val transform: ViewTransform,
        override val tileCache: InkTileCache
    ) : InkCanvasView.Host {

        override val hiddenStrokeIds: MutableSet<Int> = HashSet()
        override val hiddenObjectIds: MutableSet<String> = HashSet()

        /** PDF provider opened lazily on the tile render thread (thread-confined). */
        var pdfProvider: com.nanote.ink.render.PdfPageProvider? = null

        override fun tool(): ToolSpec = _tool.value
        override fun fingersDraw(): Boolean = _fingersDraw.value
        override fun surfaceColor(): Int = surfaceArgb

        override fun stylusOnly(): Boolean = _stylusOnly.value

        override fun stylusBarrelAction(): com.nanote.ink.engine.StylusBarrelAction = barrelAction

        override fun onBarrelCommand(action: com.nanote.ink.engine.StylusBarrelAction) {
            when (action) {
                com.nanote.ink.engine.StylusBarrelAction.UNDO -> undo()
                com.nanote.ink.engine.StylusBarrelAction.REDO -> redo()
                else -> Unit
            }
        }

        override fun onContentCommitted() {
            markDirty()
        }

        override fun onViewportOrContentMayHaveChanged() {
            // live invalidation happens inside the view
        }

        override fun onTapForText(pageX: Float, pageY: Float) {
            this@EditorViewModel.textComposerX = pageX
            this@EditorViewModel.textComposerY = pageY
            showTextComposer.value = true
        }

        override fun onTapForImage(pageX: Float, pageY: Float) {
            this@EditorViewModel.imageTargetX = pageX
            this@EditorViewModel.imageTargetY = pageY
            imagePickerRequest.value++
        }

        override fun onLinkTap(link: LinkObj) {
            when (val target = link.target) {
                is LinkTarget.PageTarget -> {
                    openPageByPageId(target.pageId)
                }
                is LinkTarget.UrlTarget -> {
                    _urlOpenEvent.value = target.url
                }
            }
        }
    }

    fun openInitialPage() {
        if (session != null) return
        viewModelScope.launch {
            recent.touchNotebook(notebookId)
            val list = repo.pagesOf(notebookId)
            val idx = list.indexOfFirst { it.id == requestedPageId }.takeIf { it >= 0 } ?: 0
            openPageAt(idx.coerceIn(0, list.size - 1))
        }
    }

    fun openPageAt(index: Int) {
        viewModelScope.launch {
            val list = repo.pagesOf(notebookId)
            if (list.isEmpty()) return@launch
            val clamped = index.coerceIn(0, list.size - 1)
            val previous = session
            if (previous != null && previous.page.id == list[clamped].id) {
                pageIndex.value = clamped
                return@launch
            }
            flushSave(previous)
            session?.tileCache?.close()
            val page = list[clamped]
            pageIndex.value = clamped
            val loaded = withContext(io) { pageStore.load(notebookId, page.id) }
            val doc = PageDoc(page.widthPt, page.heightPt, loaded.strokes)
            doc.objects.addAll(loaded.objects)
            val s = PageSession(
                page = page,
                doc = doc,
                history = History(),
                transform = ViewTransform(),
                tileCache = InkTileCache(page.widthPt, page.heightPt, page.paper, page.bgArgb).apply {
                    snapshotProvider = { PageSnapshot(doc.strokesSnapshot(), doc.objectsSnapshot(), doc.revision) }
                }
            )
            session = s

            // PDF background: lazily open the provider on the tile thread
            if (page.pdfPageIndex != null) {
                val assetName = repo.pdfAssetName(notebookId)
                if (assetName != null) {
                    val parts = assetName.split('.')
                    val pdfFile = pageStore.assetFile(notebookId, parts[0], parts.getOrNull(1) ?: "pdf")
                    s.tileCache.pdfPageIndex = page.pdfPageIndex
                    s.tileCache.pdfTileDraw = { idx, ts, l, t, out ->
                        val provider = s.pdfProvider ?: com.nanote.ink.render.PdfPageProvider(pdfFile)
                            .also { s.pdfProvider = it }
                        provider.renderTile(idx, ts, l, t, out)
                    }
                }
            }
            refreshTick.value++
        }
    }

    fun addPageAndOpen() {
        viewModelScope.launch {
            repo.addPage(notebookId)
            openPageAt(pagesFlow.value.size)
        }
    }

    fun duplicateCurrentPage() {
        viewModelScope.launch {
            val s = session ?: return@launch
            flushSave(s)
            repo.duplicatePage(s.page.id)
            openPageAt(pageIndex.value + 1)
        }
    }

    fun deleteCurrentPage() {
        viewModelScope.launch {
            val s = session ?: return@launch
            val idx = pageIndex.value
            val ok = repo.deletePage(notebookId, s.page.id)
            if (ok) {
                openPageAt((idx - 1).coerceAtLeast(0))
            }
        }
    }

    /** Moves the current page one slot left (delta -1) or right (+1). */
    fun moveCurrentPage(delta: Int) {
        viewModelScope.launch {
            val s = session ?: return@launch
            val list = repo.pagesOf(notebookId)
            val idx = list.indexOfFirst { it.id == s.page.id }
            if (idx < 0) return@launch
            val to = (idx + delta).coerceIn(0, list.size - 1)
            if (to == idx) return@launch
            val ids = list.map { it.id }.toMutableList()
            ids.removeAt(idx)
            ids.add(to, s.page.id)
            repo.reorderPages(notebookId, ids)
            openPageAt(to)
        }
    }

    fun setPagePaper(paper: String, bgArgb: Int) {
        viewModelScope.launch {
            val s = session ?: return@launch
            flushSave(s)
            repo.setPagePaper(s.page.id, paper, bgArgb)
            openPageAt(pageIndex.value)
        }
    }

    fun resizeCurrentPage(size: String) {
        viewModelScope.launch {
            val s = session ?: return@launch
            flushSave(s)
            val (w, h) = PAGE_SIZES_PT.getValue(size)
            repo.resizePage(s.page.id, w, h)
            openPageAt(pageIndex.value)
        }
    }

    // ------------------------- page thumbnails (sidebar) -------------------------

    private val _pageThumbs = MutableStateFlow<Map<String, Bitmap?>>(emptyMap())
    val pageThumbs: StateFlow<Map<String, Bitmap?>> = _pageThumbs.asStateFlow()

    private val thumbRequests = HashSet<String>()

    fun requestPageThumb(pageId: String) {
        if (!thumbRequests.add(pageId)) return
        viewModelScope.launch {
            val bmp = withContext(io) {
                try {
                    thumbLoader.load(pageId, java.io.File(thumbsDir, "$pageId.webp"))
                } catch (e: Exception) {
                    null
                }
            }
            _pageThumbs.value = _pageThumbs.value + (pageId to bmp)
        }
    }

    fun setShapeKind(kind: com.nanote.ink.engine.ShapeKind) {
        _tool.value = _tool.value.copy(shapeKind = kind, tool = EditorTool.SHAPE)
    }

    fun selectTool(tool: EditorTool) {
        _tool.value = ToolSpec.withTool(_tool.value, tool)
    }

    // ------------------------------------------------- text / image / links

    var textComposerX = 0f
        private set
    var textComposerY = 0f
        private set
    val showTextComposer = MutableStateFlow(false)
    var composerLinkTarget: String = ""

    var imageTargetX = 0f
        private set
    var imageTargetY = 0f
        private set
    val imagePickerRequest = MutableStateFlow(0)
    private val _urlOpenEvent = MutableStateFlow<String?>(null)
    val urlOpenEvent: StateFlow<String?> = _urlOpenEvent.asStateFlow()

    fun dismissTextComposer() {
        showTextComposer.value = false
        composerLinkTarget = ""
    }

    fun consumeUrlEvent() {
        _urlOpenEvent.value = null
    }

    fun openPageByPageId(pageId: String) {
        viewModelScope.launch {
            val list = repo.pagesOf(notebookId)
            val idx = list.indexOfFirst { it.id == pageId }
            if (idx >= 0) openPageAt(idx)
        }
    }

    fun commitText(text: String, fontPt: Float, colorArgb: Int, linkTarget: String) {
        val s = session ?: return
        showTextComposer.value = false
        if (text.isBlank() && linkTarget.isBlank()) return
        val id = java.util.UUID.randomUUID().toString()
        val maxZ = (s.doc.objectsSnapshot().maxOfOrNull { it.z } ?: -1) + 1
        val obj: PageObject = TextBox(
            id = id,
            text = text,
            m = identityMatrix().toMutableList().also { it[2] = textComposerX; it[5] = textComposerY },
            z = maxZ,
            colorArgb = colorArgb,
            fontPt = fontPt
        )
        s.history.push(AddObjects(listOf(obj)), s.doc)
        // optional link anchored at the text position
        val target = linkTarget.trim()
        if (target.isNotEmpty()) {
            val linkId = java.util.UUID.randomUUID().toString()
            val linkObj: PageObject = LinkObj(
                id = linkId,
                target = if (target.toIntOrNull() != null && target.all { it.isDigit() }) {
                    val pagesNow = pagesFlow.value
                    val idx2 = target.toIntOrNull()?.minus(1) ?: 0
                    LinkTarget.PageTarget(pagesNow.getOrNull(idx2.coerceIn(0, pagesNow.size - 1))?.id ?: "")
                } else {
                    LinkTarget.UrlTarget(target)
                },
                anchorX = textComposerX,
                anchorY = textComposerY + fontPt * 1.2f,
                z = maxZ + 1,
                label = target
            )
            s.history.push(AddObjects(listOf(linkObj)), s.doc)
        }
        markDirty()
    }

    /** Called by the screen after the photo picker returns a stream. */
    fun addImageAtTarget(bytes: ByteArray, ext: String) {
        val s = session ?: return
        viewModelScope.launch {
            val assetId = java.util.UUID.randomUUID().toString()
            withContext(io) {
                pageStore.saveAsset(notebookId, assetId, bytes, ext)
            }
            // decode bounds
            val bounds = withContext(io) {
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                (opts.outWidth to opts.outHeight)
            }
            val (iw, ih) = bounds
            if (iw <= 0 || ih <= 0) return@launch
            val pageW = s.page.widthPt
            val targetW = pageW * 0.6f
            val scalePt = targetW / iw
            val wPt = iw * scalePt
            val hPt = ih * scalePt
            val maxZ = (s.doc.objectsSnapshot().maxOfOrNull { it.z } ?: -1) + 1
            val obj: PageObject = ImageObj(
                id = java.util.UUID.randomUUID().toString(),
                assetId = assetId,
                m = identityMatrix().toMutableList().also {
                    it[2] = (imageTargetX - wPt / 2f)
                    it[5] = (imageTargetY - hPt / 2f)
                },
                z = maxZ,
                widthPt = wPt,
                heightPt = hPt
            )
            s.history.push(AddObjects(listOf(obj)), s.doc)
            markDirty()
        }
    }

    fun assetFileFor(assetId: String, ext: String): java.io.File =
        pageStore.assetFile(notebookId, assetId, ext)

    // ------------------------------------------------------ selection ops

    val clipboard = ArrayList<Stroke>()

    fun copySelection() {
        val v = canvasView ?: return
        clipboard.clear()
        clipboard.addAll(v.lassoSelection())
    }

    fun pasteClipboard() {
        val s = session ?: return
        val v = canvasView ?: return
        if (clipboard.isEmpty()) return
        val copies = clipboard.map {
            it.transformed(
                com.nanote.ink.engine.Mat3().also { m -> m.postTranslate(12f, 12f) },
                s.doc.nextStrokeId()
            )
        }
        s.history.push(com.nanote.ink.engine.AddStrokes(copies), s.doc)
        v.clearSelectionOnly()
        markDirty()
    }

    fun deleteSelection() {
        canvasView?.deleteSelection()
        markDirty()
    }

    fun recolorSelection(argb: Int) {
        canvasView?.recolorSelection(argb)
        markDirty()
    }

    fun finishSelection() {
        canvasView?.commitLasso()
    }

    fun hasSelection(): Boolean = canvasView?.hasSelection() == true

    /** The single canvas view; not recreated on rotation (configChanges in
     *  the manifest handles size changes in place), so a plain field is safe
     *  and outlives nothing. Lint's StaticFieldLeak does not apply here. */
    @android.annotation.SuppressLint("StaticFieldLeak")
    var canvasView: InkCanvasView? = null

    // ------------------------------------------------------------- exports (M4)

    private fun pdfBitmapProviderForExport(): ((Int, Int) -> android.graphics.Bitmap?)? {
        val assetName = kotlinx.coroutines.runBlocking { repo.pdfAssetName(notebookId) } ?: return null
        val parts = assetName.split('.')
        val f = pageStore.assetFile(notebookId, parts[0], parts.getOrNull(1) ?: "pdf")
        if (!f.exists()) return null
        var provider: com.nanote.ink.render.PdfPageProvider? = null
        return { idx, w ->
            val p = provider ?: com.nanote.ink.render.PdfPageProvider(f).also { provider = it }
            try {
                p.renderFull(idx, w)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun imageLoaderForExport(): (String) -> android.graphics.Bitmap? = { assetId ->
        null // images are drawn from the live object painter; export loads below
    }

    private fun exporter(): com.nanote.export.PageExporter {
        val assetsDir = pageStore.assetsDir(notebookId)
        return com.nanote.export.PageExporter(
            pdfBitmap = pdfBitmapProviderForExport(),
            imageLoader = { assetId ->
                val f = assetsDir.listFiles()?.firstOrNull { it.nameWithoutExtension == assetId }
                if (f != null && f.exists()) {
                    try {
                        android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                    } catch (e: Exception) {
                        null
                    }
                } else null
            }
        )
    }

    /** Writes the whole notebook as a PDF into the SAF uri. */
    fun exportPdfTo(uri: android.net.Uri?) {
        val u = uri ?: return
        viewModelScope.launch {
            flushSave(session)
            withContext(io) {
                try {
                    val pages = repo.pagesOf(notebookId)
                    val snaps = repo.snapshotsFor(notebookId)
                    appContext.contentResolver.openOutputStream(u)?.use { out ->
                        exporter().exportPdf(pages, snaps, out)
                    }
                } catch (e: Exception) {
                    // SAF write failures surface silently; the user can retry
                }
            }
        }
    }

    fun exportPagePngTo(uri: android.net.Uri?, dpi: Int = 150) {
        val u = uri ?: return
        val s = session ?: return
        viewModelScope.launch {
            flushSave(s)
            withContext(io) {
                try {
                    val snap = PageSnapshot(s.doc.strokesSnapshot(), s.doc.objectsSnapshot(), 0)
                    appContext.contentResolver.openOutputStream(u)?.use { out ->
                        exporter().exportImage(s.page, snap, dpi, asJpeg = false, out)
                    }
                } catch (e: Exception) {
                }
            }
        }
    }

    fun exportNanoteTo(uri: android.net.Uri?) {
        val u = uri ?: return
        viewModelScope.launch {
            flushSave(session)
            withContext(io) {
                try {
                    appContext.contentResolver.openOutputStream(u)?.use { out ->
                        repo.exportNotebookArchive(notebookId, out)
                    }
                } catch (e: Exception) {
                }
            }
        }
    }

    /** Writes the current page PNG into exports/ for sharing; returns the file. */
    suspend fun writeSharedPng(): java.io.File? {
        val s = session ?: return null
        flushSave(s)?.join()
        return withContext(io) {
            try {
                val snap = PageSnapshot(s.doc.strokesSnapshot(), s.doc.objectsSnapshot(), 0)
                val name = "nanote-share-${System.currentTimeMillis()}.png"
                val f = java.io.File(exportsDir, name)
                f.outputStream().use { out ->
                    exporter().exportImage(s.page, snap, 150, asJpeg = false, out)
                }
                f
            } catch (e: Exception) {
                null
            }
        }
    }

    fun selectPen(pen: PenType) {
        _tool.value = ToolSpec.withPen(_tool.value, pen)
    }

    fun selectColor(argb: Int) {
        _tool.value = ToolSpec.withColor(_tool.value, argb)
    }

    fun selectWidth(w: Float) {
        _tool.value = ToolSpec.withWidth(_tool.value, w)
    }

    fun setEraserWholeStroke(whole: Boolean) {
        _tool.value = _tool.value.copy(eraserWholeStroke = whole)
    }

    fun setEraserRadius(r: Float) {
        _tool.value = _tool.value.copy(eraserRadiusPt = r)
    }

    fun toggleFingersDraw() {
        // persists; the settings collector mirrors it into the live state
        viewModelScope.launch { recent.setFingersDraw(!_fingersDraw.value) }
    }

    /** v1.1: top-bar pen-only toggle — persists across launches. */
    fun toggleStylusOnly() {
        viewModelScope.launch { recent.setStylusOnly(!_stylusOnly.value) }
    }

    // ----------------------------------------------------------- undo/redo

    fun undo() {
        val s = session ?: return
        s.history.undo(s.doc)
        syncUndoFlags()
        markDirty()
        refreshTick.value++
    }

    fun redo() {
        val s = session ?: return
        s.history.redo(s.doc)
        syncUndoFlags()
        markDirty()
        refreshTick.value++
    }

    private fun syncUndoFlags() {
        val s = session ?: return
        undoDepth.value = s.history.depth
        redoDepth.value = if (s.history.canRedo) 1 else 0
    }

    // ------------------------------------------------------------ autosave

    private var dirtyTick = 0L
    private var autosaveJob: Job? = null

    private fun markDirty() {
        syncUndoFlags()
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(1500) // debounce 1.5 s after the last change (spec 5)
            flushSave(session)
        }
    }

    /** Saves the given session (or the current one) to disk + thumbnail. */
    fun flushSave(target: PageSession? = session): Job? {
        val s = target ?: return null
        autosaveJob?.cancel()
        return viewModelScope.launch {
            val snapshot = PageSnapshot(s.doc.strokesSnapshot(), s.doc.objectsSnapshot(), s.doc.revision)
            withContext(io) {
                try {
                    pageStore.save(notebookId, s.page.id, snapshot.strokes, snapshot.objects)
                    repo.reindexPageText(
                        s.page.id, notebookId,
                        snapshot.objects.filterIsInstance<com.nanote.domain.model.TextBox>().map { it.text }
                    )
                    thumbs.render(s.page.id, s.page.widthPt, s.page.heightPt, s.page.paper, s.page.bgArgb, snapshot)
                } catch (e: Exception) {
                    // Saving must never crash the editor; the .bak keeps the last good state.
                }
            }
        }
    }

    /** Full flush before leaving (also called from ON_STOP). */
    fun saveNowAndClose() {
        flushSave(session)
    }

    override fun onCleared() {
        saveNowAndClose()
        session?.tileCache?.close()
        super.onCleared()
    }
}
