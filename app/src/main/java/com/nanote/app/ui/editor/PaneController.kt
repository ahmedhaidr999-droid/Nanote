package com.nanote.app.ui.editor

import android.content.Context
import android.graphics.PointF
import com.nanote.app.core.canvas.CanvasState
import com.nanote.app.core.canvas.CanvasView
import com.nanote.app.core.canvas.ElementRenderer
import com.nanote.app.core.canvas.ToolController
import com.nanote.app.core.data.SettingsStore
import com.nanote.app.core.model.DocumentModel
import com.nanote.app.core.model.ImageElement
import com.nanote.app.core.model.TextElement
import com.nanote.app.core.model.ToolConfig
import com.nanote.app.core.model.ToolType
import com.nanote.app.core.pdf.PageBitmapCache
import com.nanote.app.core.pdf.PageRender
import com.nanote.app.core.pdf.PageRenderManager
import com.nanote.app.core.pdf.PdfDocumentHandle
import com.nanote.app.core.pdf.PdfTextExtractor
import com.nanote.app.util.AppLog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Anchor + payload for the floating text-selection context menu. */
data class TextMenuState(
    val anchorScreenX: Float,
    val anchorScreenY: Float,
    val text: String,
    val wordCount: Int
)

data class InsertTextRequest(
    val page: Int,
    val worldX: Float,
    val worldY: Float,
    val existing: TextElement?,
    val sticky: Boolean
)

/**
 * One editor pane = document + canvas state + tool controller + render pipeline.
 * Bridges the imperative canvas world to Compose-observable state.
 * The split workspace simply hosts TWO PaneControllers.
 */
class PaneController(
    val docId: String,
    private val context: Context,
    val doc: DocumentModel,
    private val settings: SettingsStore,
    private val externalScope: CoroutineScope,
    private val density: Float,
    private val pdfFile: File?,
    private val imageResolver: (String) -> File?,
    private val pageCache: PageBitmapCache
) {

    companion object {
        private const val TAG = "PaneController"
        private const val RENDER_DEBOUNCE_MS = 120L
        private const val ZOOM_SETTLE_MS = 160L
    }

    val isPdf: Boolean = pdfFile != null
    val canvasState = CanvasState(doc.pages)
    val renderer = ElementRenderer(context)

    private val _textExtractor: PdfTextExtractor? = if (isPdf) PdfTextExtractor() else null
    val textExtractor: PdfTextExtractor? get() = _textExtractor

    private var pdfHandle: PdfDocumentHandle? = null
    private var renderManager: PageRenderManager? = null

    val toolConfig: StateFlow<ToolConfig> = settings.toolConfig

    // ---------------- UI state ----------------

    private val _activeTool = MutableStateFlow(ToolType.PEN)
    val activeTool: StateFlow<ToolType> = _activeTool

    private val _zoomPercent = MutableStateFlow(100)
    val zoomPercent: StateFlow<Int> = _zoomPercent

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage

    val pageCount: Int get() = doc.pageCount()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo

    private val _textMenu = MutableStateFlow<TextMenuState?>(null)
    val textMenu: StateFlow<TextMenuState?> = _textMenu

    private val _objectMenu = MutableStateFlow(false)
    val objectMenu: StateFlow<Boolean> = _objectMenu

    private val _insertTextRequest = MutableStateFlow<InsertTextRequest?>(null)
    val insertTextRequest: StateFlow<InsertTextRequest?> = _insertTextRequest

    private val _loading = MutableStateFlow(isPdf)
    val loading: StateFlow<Boolean> = _loading

    private val _textPreparing = MutableStateFlow(false)
    val textPreparing: StateFlow<Boolean> = _textPreparing

    val partiallyRecovered: Boolean get() = doc.partiallyRecovered

    private val _renderState = MutableStateFlow<Map<Int, PageRender>>(emptyMap())
    val renderState: StateFlow<Map<Int, PageRender>> = _renderState

    private var attachedView: CanvasView? = null

    // ---------------- tool controller ----------------

    /** Set right after [controller] is constructed (breaks the init cycle). */
    private lateinit var toolControllerRef: ToolController

    val controller = ToolController(
        doc = doc,
        state = canvasState,
        renderer = renderer,
        textExtractor = _textExtractor,
        imageResolver = imageResolver,
        scope = externalScope,
        listener = object : ToolController.Listener {
            override fun invalidate() { attachedView?.invalidate() }

            override fun commit(command: com.nanote.app.core.model.Command) {
                doc.apply(command)
                syncUndoRedo()
            }

            override fun onTextMenuVisibility(show: Boolean) {
                if (show) {
                    val sel = if (this@PaneController::toolControllerRef.isInitialized)
                        toolControllerRef.textSelection else null
                    val view = attachedView
                    if (view != null && sel != null && sel.isActive) {
                        val rects = sel.selectionRects()
                        if (rects.isNotEmpty()) {
                            val layout = canvasState.layout
                            val page = sel.pageIndex.coerceIn(0, layout.count - 1)
                            val sumCx = rects.sumOf { ((it[0] + it[2]) / 2.0) }
                            val cxWorld = (layout.pageLeft(page) + sumCx / rects.size).toFloat()
                            val topWorld = layout.pageTop(page) + rects.minOf { it[1] }
                            val sx = view.state.transform.worldToScreenX(cxWorld)
                            val sy = view.state.transform.worldToScreenY(topWorld)
                            _textMenu.value = TextMenuState(
                                anchorScreenX = sx,
                                anchorScreenY = sy,
                                text = sel.selectedText(),
                                wordCount = sel.selectedWordCount()
                            )
                            return
                        }
                    }
                    _textMenu.value = null
                } else {
                    _textMenu.value = null
                }
            }

            override fun onObjectMenuVisibility(show: Boolean) {
                _objectMenu.value = show
            }

            override fun onInsertTextRequest(page: Int, worldX: Float, worldY: Float, existing: TextElement?, sticky: Boolean) {
                _insertTextRequest.value = InsertTextRequest(page, worldX, worldY, existing, sticky)
            }

            override fun onTextPreparing() {
                _textPreparing.value = true
                externalScope.launch {
                    delay(600)
                    _textPreparing.value = false
                }
            }

            override fun onSelectionCountChanged(strokes: Int, texts: Int) {}
        }
    )

    init {
        toolControllerRef = controller
    }

    init {
        // live tool config wiring — highlighter opacity changes apply INSTANTLY
        // to the active tool + view redraw (the reported "must flip pages" bug fix).
        externalScope.launch {
            settings.toolConfig.collect { cfg ->
                controller.toolConfig = cfg
                attachedView?.invalidate()
            }
        }
        externalScope.launch {
            settings.pressureSensitivity.collect { controller.pressureEnabled = it }
        }
        externalScope.launch {
            settings.palmRejection.collect { controller.palmRejection = it }
        }
        externalScope.launch {
            doc.revision.collect {
                syncUndoRedo()
                _currentPage.value = canvasState.currentPage()
            }
        }
        externalScope.launch {
            doc.structureRevision.collect {
                canvasState.setPages(doc.pages)
                attachedView?.notifyVisibleChanged(force = true)
                attachedView?.invalidate()
            }
        }
        if (isPdf) {
            controller.pdfPageOf = { idx -> doc.pages.getOrNull(idx)?.pdfPage ?: -1 }
            openPdfAsync()
        } else {
            controller.pdfPageOf = { -1 }
        }
    }

    private fun syncUndoRedo() {
        _canUndo.value = doc.canUndo
        _canRedo.value = doc.canRedo
    }

    // ---------------- PDF open (fast first paint) ----------------

    private fun openPdfAsync() {
        val file = pdfFile ?: return
        externalScope.launch {
            try {
                val handle = PdfDocumentHandle.open(context, file)
                pdfHandle = handle
                renderManager = PageRenderManager(
                    docId = docId,
                    handle = handle,
                    cache = pageCache,
                    density = density,
                    scope = externalScope,
                    prefetchEnabled = { settings.prefetchPages.value }
                )
                externalScope.launch {
                    renderManager!!.renders.collect { _renderState.value = it }
                }
                _loading.value = false
                // first paint: fit width + render visible pages immediately
                val view = attachedView
                if (view != null) {
                    view.post {
                        fitWidth()
                        requestRenders(force = true)
                    }
                } else {
                    fitWidth()
                    requestRenders(force = true)
                }
                AppLog.i(TAG, "pane ready: ${handle.pageCount} pages")
            } catch (t: Throwable) {
                AppLog.e(TAG, "PDF open failed", t)
                _loading.value = false
                // document still usable as blank pages over the failed pdf
            }
        }
    }

    // ---------------- view wiring ----------------

    fun attachView(view: CanvasView) {
        attachedView = view
        view.state = canvasState
        view.controller = controller
        view.initialize()
        view.pageRenderProvider = { page ->
            val rm = renderManager
            val pdfPage = doc.pages.getOrNull(page)?.pdfPage ?: -1
            if (rm != null && pdfPage >= 0) rm.renders.value[pdfPage] else null
        }
        view.onVisiblePagesChanged = { pages ->
            _currentPage.value = canvasState.currentPage()
            scheduleRenderRequest()
        }
        view.onZoomSettled = {
            scheduleZoomSettleRender()
        }
        view.onTransformChanged = {
            _zoomPercent.value = ((canvasState.transform.scale / baseFitScale) * 100).toInt().coerceIn(10, 1200)
        }
        if (view.width > 0) {
            if (!didInitialFit) {
                didInitialFit = true
                fitWidth()
            }
            requestRenders(force = true)
        }
    }

    fun detachView() {
        attachedView = null
    }

    private var didInitialFit = false
    private var baseFitScale = 1f
    private var renderJob: Job? = null
    private var zoomSettleJob: Job? = null

    private fun scheduleRenderRequest() {
        renderJob?.cancel()
        renderJob = externalScope.launch {
            delay(RENDER_DEBOUNCE_MS)
            requestRenders(force = false)
        }
    }

    private fun scheduleZoomSettleRender() {
        zoomSettleJob?.cancel()
        zoomSettleJob = externalScope.launch {
            delay(ZOOM_SETTLE_MS)
            requestRenders(force = true)
        }
    }

    private fun requestRenders(force: Boolean) {
        val rm = renderManager ?: return
        val pages = canvasState.visiblePages()
        if (pages.isEmpty()) return
        val scale = canvasState.transform.scale
        val payload = pages.map { p ->
            val pdfPage = doc.pages.getOrNull(p)?.pdfPage ?: -1
            pdfPage to scale
        }.filter { it.first >= 0 }
        if (payload.isEmpty()) return
        rm.requestVisible(payload)
        rm.cancelOutside(
            (pages.min() - 2).coerceAtLeast(0)..(pages.max() + 2).coerceAtMost(doc.pageCount() - 1)
        )
    }

    // ---------------- public actions (toolbar / menus) ----------------

    fun setTool(tool: ToolType) {
        when (tool) {
            ToolType.UNDO -> { undo(); return }
            ToolType.REDO -> { redo(); return }
            else -> {}
        }
        // switching tools clears transient selections
        if (_textMenu.value != null) controller.clearTextSelection()
        if (controller.objectSelection.isActive) {
            controller.objectSelection.clear()
            _objectMenu.value = false
            attachedView?.invalidate()
        }
        _activeTool.value = tool
        controller.activeTool = tool
    }

    fun undo() { doc.undo(); syncUndoRedo(); attachedView?.invalidate() }
    fun redo() { doc.redo(); syncUndoRedo(); attachedView?.invalidate() }

    fun updateToolConfig(update: (ToolConfig) -> ToolConfig) {
        settings.updateToolConfig(update)
        // settings.toolConfig collector propagates to controller + redraw instantly
    }

    fun zoomIn() { zoomBy(1.25f) }
    fun zoomOut() { zoomBy(0.8f) }

    private fun zoomBy(factor: Float) {
        val view = attachedView
        val target = (canvasState.transform.scale * factor).coerceIn(0.5f, 10f)
        if (view != null) {
            view.animateZoom(target, view.width / 2f, view.height / 2f)
        } else {
            canvasState.applyZoomCentered(target, 0f, 0f)
        }
        scheduleZoomSettleRender()
    }

    fun fitWidth() {
        canvasState.fitWidthToPage(canvasState.currentPage())
        baseFitScale = if (baseFitScale <= 0f) canvasState.transform.scale else baseFitScale
        if (baseFitScale <= 0f) baseFitScale = canvasState.transform.scale
        _zoomPercent.value = 100
        attachedView?.invalidate()
        requestRenders(force = true)
    }

    fun fitPage() {
        canvasState.fitPage(canvasState.currentPage())
        _zoomPercent.value = ((canvasState.transform.scale / baseFitScale) * 100).toInt()
        attachedView?.invalidate()
        requestRenders(force = true)
    }

    fun actualSize() {
        canvasState.actualSize()
        _zoomPercent.value = ((canvasState.transform.scale / baseFitScale) * 100).toInt()
        attachedView?.invalidate()
        requestRenders(force = true)
    }

    fun nextPage() = goToPage(_currentPage.value + 1)
    fun prevPage() = goToPage(_currentPage.value - 1)

    fun goToPage(page: Int) {
        canvasState.scrollToPage(page.coerceIn(0, (doc.pageCount() - 1).coerceAtLeast(0)))
        _currentPage.value = page.coerceIn(0, (doc.pageCount() - 1).coerceAtLeast(0))
        attachedView?.invalidate()
        requestRenders(force = true)
    }

    fun addPage() {
        doc.apply(doc.addPageCmd())
        syncUndoRedo()
    }

    fun retryPage(page: Int) {
        renderManager?.retryPage(page, canvasState.transform.scale)
    }

    fun screenPointForPage(page: Int): PointF? {
        val view = attachedView ?: return null
        val layout = canvasState.layout
        if (page < 0 || page >= layout.count) return null
        return PointF(
            view.state.transform.worldToScreenX(layout.pageLeft(page) + layout.pageWidth(page) / 2f),
            view.state.transform.worldToScreenY(layout.pageTop(page) + layout.pageHeight(page) / 2f)
        )
    }

    fun failedVisiblePages(): List<Int> {
        val rm = renderManager ?: return emptyList()
        val out = ArrayList<Int>()
        for (p in canvasState.visiblePages()) {
            val pdfPage = doc.pages.getOrNull(p)?.pdfPage ?: -1
            if (pdfPage >= 0) {
                val r = rm.renders.value[pdfPage]
                if (r != null && r.failed) out.add(p)
            }
        }
        return out
    }

    // ---------------- text menu actions ----------------

    fun copySelectedText(): String = controller.copySelection()

    fun highlightSelection(): Boolean = controller.highlightSelection()

    fun underlineSelection(): Boolean = controller.underlineSelection()

    fun selectAllText() = controller.selectAllText()

    fun selectedText(): String = controller.selectedTextString()

    fun clearTextMenu() = controller.clearTextSelection()

    fun deleteSelection() = controller.deleteSelection()

    /** Duplicates the current object selection with a small offset (undoable). */
    fun duplicateSelection() {
        val sel = controller.objectSelection
        if (!sel.isActive) return
        val doc = this.doc
        // concrete duplication (kept simple & undoable per element type)
        val toAddStrokes = ArrayList<com.nanote.app.core.model.Stroke>()
        val toAddTexts = ArrayList<TextElement>()
        val toAddImages = ArrayList<com.nanote.app.core.model.ImageElement>()
        val toAddShapes = ArrayList<com.nanote.app.core.model.ShapeElement>()
        for (item in sel.items) {
            when (item) {
                is com.nanote.app.core.canvas.SelectedElement.SelStroke -> {
                    val s = item.stroke
                    toAddStrokes.add(s.copy(id = com.nanote.app.core.model.newId(), points = s.points.map { com.nanote.app.core.model.StrokePoint(it.x + 24f, it.y + 24f, it.p) }))
                }
                is com.nanote.app.core.canvas.SelectedElement.SelText -> {
                    val t = item.text
                    toAddTexts.add(t.copy(id = com.nanote.app.core.model.newId(), x = t.x + 24f, y = t.y + 24f))
                }
                is com.nanote.app.core.canvas.SelectedElement.SelImage -> {
                    val i = item.image
                    toAddImages.add(i.copy(id = com.nanote.app.core.model.newId(), x = i.x + 24f, y = i.y + 24f))
                }
                is com.nanote.app.core.canvas.SelectedElement.SelShape -> {
                    val sh = item.shape
                    toAddShapes.add(sh.copy(id = com.nanote.app.core.model.newId(), x1 = sh.x1 + 24f, y1 = sh.y1 + 24f, x2 = sh.x2 + 24f, y2 = sh.y2 + 24f))
                }
                else -> {}
            }
        }
        for (s in toAddStrokes) doc.apply(doc.addStrokeCmd(s))
        for (t in toAddTexts) doc.apply(doc.addTextCmd(t))
        for (i in toAddImages) doc.apply(doc.addImageCmd(i))
        for (sh in toAddShapes) doc.apply(doc.addShapeCmd(sh))
        if (toAddStrokes.isNotEmpty() || toAddTexts.isNotEmpty() || toAddImages.isNotEmpty() || toAddShapes.isNotEmpty()) {
            // shift selection to the duplicates
            sel.clear()
            _objectMenu.value = false
            attachedView?.invalidate()
        }
    }

    fun commitInsertText(request: InsertTextRequest, text: String, size: Float, color: Long) {
        controller.commitText(request.existing, request.page, request.worldX, request.worldY, text, request.sticky, size, color)
        _insertTextRequest.value = null
    }

    fun cancelInsertText() { _insertTextRequest.value = null }

    // ---------------- image insertion ----------------

    fun insertImage(file: File) {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return
        val page = canvasState.currentPage()
        val viewW = canvasState.viewportWidth.toFloat()
        val targetW = (viewW / canvasState.transform.scale * 0.5f).coerceIn(120f, 420f)
        val targetH = targetW * opts.outHeight / opts.outWidth
        val centerWorldX = canvasState.transform.screenToWorldX(canvasState.viewportWidth / 2f)
        val centerWorldY = canvasState.transform.screenToWorldY(canvasState.viewportHeight / 2f)
        val el = ImageElement(
            id = com.nanote.app.core.model.newId(),
            page = page,
            x = centerWorldX - targetW / 2f,
            y = centerWorldY - targetH / 2f,
            w = targetW, h = targetH,
            path = file.name
        )
        doc.apply(doc.addImageCmd(el))
        syncUndoRedo()
        attachedView?.invalidate()
    }

    // ---------------- lifecycle ----------------

    fun close() {
        renderManager?.close()
        renderManager = null
        _textExtractor?.close()
        renderer.clearCaches()
    }
}
