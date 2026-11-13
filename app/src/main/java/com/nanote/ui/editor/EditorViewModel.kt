package com.nanote.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanote.core.DiagLog
import com.nanote.data.db.PageEntity
import com.nanote.data.files.PageStore
import com.nanote.data.files.SettingsStore
import com.nanote.data.repo.NotebookRepository
import com.nanote.di.IoDispatcher
import com.nanote.di.ThumbsDir
import com.nanote.domain.model.Notebook
import com.nanote.domain.model.Page
import com.nanote.domain.model.SortOrder
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
import com.nanote.ink.engine.PageStripLayout
import com.nanote.ink.engine.PenType
import com.nanote.ink.engine.Stroke
import com.nanote.ink.engine.ToolSpec
import com.nanote.ink.engine.ViewTransform
import com.nanote.ink.engine.buildLineStroke
import com.nanote.ink.render.InkCanvasView
import com.nanote.ink.render.InkTileCache
import com.nanote.ink.render.PageSnapshot
import com.nanote.ink.render.PdfWord
import com.nanote.ink.render.StripSlot
import com.nanote.ink.render.StripState
import com.nanote.ink.render.ThumbRenderer
import com.nanote.ui.library.ThumbLoader
import android.graphics.Bitmap
import android.graphics.RectF
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * One ViewModel per editor pane (spec 4 + v2.0 split workspace). Owns the
 * PageDoc + History + ViewTransform + TileCache of the edited page (they
 * survive config changes because they live here, not in the View) and drives
 * autosave.
 *
 * v2.0:
 *  - Created through an AssistedFactory so the split workspace can host TWO
 *    independent editors (own file, zoom, tool and undo — spec 7).
 *  - Autosave: 2 s after the last edit + every 30 s + ON_STOP + close
 *    (spec 8.1); the delay honors the Settings slider (default 2000 ms).
 *  - Notebook-level undo over page operations (add/delete/duplicate/reorder/
 *    paper/size) up to 200 steps (spec 9), in addition to the per-page ink
 *    history (300 steps).
 *  - PDF text selection actions: copy / translate / web search / highlight /
 *    underline (spec 5.3).
 *  - Infinite pages grow through [PageSession.onPageGrew].
 */
@HiltViewModel(assistedFactory = EditorViewModel.Factory::class)
class EditorViewModel @AssistedInject constructor(
    @Assisted("notebookId") val notebookId: String,
    @Assisted("pageId") initialPageId: String,
    private val repo: NotebookRepository,
    private val pageStore: PageStore,
    private val thumbs: ThumbRenderer,
    private val recent: SettingsStore,
    private val thumbLoader: ThumbLoader,
    @ThumbsDir private val thumbsDir: File,
    @com.nanote.di.ExportsDir private val exportsDir: File,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    @IoDispatcher private val io: CoroutineDispatcher
) : ViewModel() {

    private val requestedPageId: String = initialPageId.ifBlank { "first" }

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("notebookId") notebookId: String,
            @Assisted("pageId") pageId: String
        ): EditorViewModel
    }

    private companion object {
        /** v1.2: sessions kept live around the active page (±WINDOW). */
        const val WINDOW = 2

        /** v2.0 (spec 8.1): autosave debounce + periodic flush. */
        const val AUTOSAVE_DEBOUNCE_MS = 2000L
        const val AUTOSAVE_PERIOD_MS = 30_000L

        /** v2.0 (spec 9): notebook-level page-operation history cap. */
        const val PAGE_OPS_LIMIT = 200
    }

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

    /** v1.3: pen latency preset (ULTRA = raw input, ~zero delay by default). */
    val penResponse: StateFlow<com.nanote.ink.engine.PenResponse> = recent.penResponse
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.nanote.ink.engine.PenResponse.ULTRA)

    /** v1.1: barrel-button action consumed by the input router. */
    @Volatile private var barrelAction: com.nanote.ink.engine.StylusBarrelAction =
        com.nanote.ink.engine.StylusBarrelAction.ERASE

    // ------------------------------------------------- v2.0 UI event channel

    /** One-shot UI events (toasts, external intents, recovery notices). */
    sealed interface UiEvent {
        data object CopiedToClipboard : UiEvent
        data class OpenExternal(val url: String) : UiEvent
        data class PartialRecovery(val dropped: Int) : UiEvent
    }

    val uiEvents = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)

    /** v2.0: live PDF text selection info from the canvas (null = hidden). */
    val textSelection = MutableStateFlow<InkCanvasView.TextSelectionInfo?>(null)

    /** v2.0: notebooks pickable for the second split-workspace pane (spec 7). */
    val otherNotebooks: StateFlow<List<Notebook>> = repo.observeNotebooks(null, SortOrder.MODIFIED)
        .map { list -> list.filter { it.id != notebookId } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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
        // v2.0: default highlighter opacity from Settings (applied ONCE so
        // live slider edits during the session are never overwritten).
        viewModelScope.launch {
            val pct = recent.defaultHlOpacity.first()
            _tool.value = ToolSpec.withHlOpacity(_tool.value, pct)
        }
        // v2.0 (spec 8.1): periodic 30 s flush of everything dirty.
        viewModelScope.launch {
            while (true) {
                delay(AUTOSAVE_PERIOD_MS)
                if (hasUnsavedChanges) {
                    for (s in sessions.values) flushSave(s)
                    hasUnsavedChanges = false
                }
            }
        }
    }

    val undoDepth = MutableStateFlow(0)
    val redoDepth = MutableStateFlow(0)

    /** View invalidation signal after undo/redo/page switches. */
    val refreshTick = MutableStateFlow(0L)

    @Volatile var surfaceArgb: Int = Color.LTGRAY

    /** v2.0: any session has unsaved ink (drives the 30 s periodic flush). */
    @Volatile private var hasUnsavedChanges = false

    // ------------------------------------------------------------ session

    var session: PageSession? = null
        private set

    /**
     * v1.2: which page owns the pending text composer / image insert — the
     * composer is opened THROUGH a page session, so the tapped page (which
     * may be a strip neighbor, not the center page) receives the object.
     */
    private var textComposerSession: PageSession? = null
    private var imageSession: PageSession? = null

    /** v1.2: last page that received an edit (undo targets it). */
    @Volatile private var lastEdited: PageSession? = null

    /**
     * Editing state of one page; implements the canvas Host.
     *
     * v2.0: the tile cache is swappable ([liveTileCache]) so an infinite page
     * can grow without losing the page's doc/history.
     */
    inner class PageSession(
        @Volatile var page: Page,
        override val doc: PageDoc,
        override val history: History,
        override val transform: ViewTransform,
        tileCache: InkTileCache
    ) : InkCanvasView.Host {

        @Volatile var liveTileCache: InkTileCache = tileCache
        override val tileCache: InkTileCache get() = liveTileCache

        override val hiddenStrokeIds: MutableSet<Int> = HashSet()
        override val hiddenObjectIds: MutableSet<String> = HashSet()

        /** v2.0: cached PDF words of this page (text selection layer). */
        @Volatile var cachedWords: List<PdfWord>? = null

        /** v1.2: PDF pages open fit-to-PAGE (the whole page visible). */
        override val isPdfPage: Boolean get() = page.pdfPageIndex != null

        override val pdfPageIndex: Int get() = page.pdfPageIndex ?: -1

        override val isInfinitePage: Boolean get() = doc.infinite

        override fun onPageGrew(newW: Float, newH: Float) {
            onInfinitePageGrew(this, newW, newH)
        }

        override fun pdfWords(): List<PdfWord>? = cachedWords

        override fun requestPdfWords(onReady: () -> Unit) {
            val idx = page.pdfPageIndex ?: return
            viewModelScope.launch {
                val w = withContext(io) {
                    try {
                        sharedPdf?.words(idx) ?: emptyList()
                    } catch (t: Throwable) {
                        DiagLog.error("EditorVM", "words load failed page=$idx", t)
                        emptyList()
                    }
                }
                cachedWords = w
                onReady()
            }
        }

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
            markDirty(this)
        }

        override fun onViewportOrContentMayHaveChanged() {
            // live invalidation happens inside the view
        }

        override fun onTapForText(pageX: Float, pageY: Float) {
            textComposerSession = this
            this@EditorViewModel.textComposerX = pageX
            this@EditorViewModel.textComposerY = pageY
            showTextComposer.value = true
        }

        override fun onTapForImage(pageX: Float, pageY: Float) {
            imageSession = this
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

    // -------------------------------------------------- v1.2 page window

    /** Sessions of the loaded window (±WINDOW around the active page), by page id. */
    private val sessions = LinkedHashMap<String, PageSession>()

    /** v1.3: ONE PDF engine per notebook, shared by all page sessions. */
    @Volatile private var sharedPdf: com.nanote.ink.render.PdfPageProvider? = null

    private val _strip = MutableStateFlow<StripState?>(null)
    val strip: StateFlow<StripState?> = _strip.asStateFlow()

    private var windowCenter = -1
    private var fitToken = 0L
    private var windowJob: Job? = null
    private var openedInitial = false

    fun openInitialPage() {
        if (openedInitial) return
        openedInitial = true
        viewModelScope.launch {
            recent.touchNotebook(notebookId)
            val list = repo.pagesOf(notebookId)
            if (list.isEmpty()) return@launch
            val idx = list.indexOfFirst { it.id == requestedPageId }.takeIf { it >= 0 } ?: 0
            rebuildWindow(idx.coerceIn(0, list.size - 1), refit = true, centerOn = -1)
        }
    }

    /** Loads a page session (no-op when already in the window). */
    private suspend fun loadSession(page: Page): PageSession {
        sessions[page.id]?.let { return it }
        val loaded = withContext(io) { pageStore.load(notebookId, page.id) }
        if (loaded.droppedStrokes > 0) {
            // v2.0 (spec 8.2): partial recovery — inform, never reject.
            DiagLog.warn("EditorVM", "page ${page.id}: ${loaded.droppedStrokes} strokes salvaged around")
            uiEvents.tryEmit(UiEvent.PartialRecovery(loaded.droppedStrokes))
        }
        val doc = PageDoc(page.widthPt, page.heightPt, loaded.strokes, page.infinite)
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

        // v1.3 PDF engine: ONE shared, thread-safe provider per notebook —
        // swiping between PDF pages no longer reopens the file per page.
        if (page.pdfPageIndex != null) {
            val assetName = repo.pdfAssetName(notebookId)
            if (assetName != null) {
                val parts = assetName.split('.')
                val pdfFile = pageStore.assetFile(notebookId, parts[0], parts.getOrNull(1) ?: "pdf")
                val provider = sharedPdf ?: com.nanote.ink.render.PdfPageProvider(pdfFile)
                    .also { sharedPdf = it }
                s.liveTileCache.pdfPageIndex = page.pdfPageIndex
                s.liveTileCache.pdfTileDraw = { idx, ts, l, t, out ->
                    provider.renderTile(idx, ts, l, t, out)
                }
            }
        }
        sessions[page.id] = s
        return s
    }

    /** Full window rebuild: flush + close everything, reload ±WINDOW around [center]. */
    private fun rebuildWindow(center: Int, refit: Boolean, centerOn: Int) {
        windowJob?.cancel()
        windowJob = viewModelScope.launch {
            val list = repo.pagesOf(notebookId)
            if (list.isEmpty()) {
                _strip.value = null
                return@launch
            }
            val c = center.coerceIn(0, list.size - 1)
            val jobs = sessions.values.map { flushSave(it) }
            jobs.forEach { it?.join() }
            sessions.values.forEach { it.liveTileCache.close() }
            sessions.clear()
            for (i in (c - WINDOW).coerceAtLeast(0)..(c + WINDOW).coerceAtMost(list.size - 1)) {
                loadSession(list[i])
            }
            windowCenter = c
            if (refit) fitToken++
            activateSession(list[c].id, c)
            publishStrip(list, c, centerOn)
            refreshTick.value++
        }
    }

    /** Slides the window when scrolling made another page active. */
    private fun slideWindow(newActive: Int) {
        if (windowJob?.isActive == true) return
        windowJob = viewModelScope.launch {
            val list = repo.pagesOf(notebookId)
            if (list.isEmpty()) return@launch
            val c = newActive.coerceIn(0, list.size - 1)
            // close sessions 3+ pages away (invisible); flush first
            val keep = ((c - WINDOW - 1).coerceAtLeast(0)..(c + WINDOW + 1).coerceAtMost(list.size - 1))
                .map { list[it].id }.toSet()
            val stale = sessions.keys.filter { it !in keep }
            for (sid in stale) {
                flushSave(sessions[sid])
                sessions.remove(sid)?.liveTileCache?.close()
            }
            var changed = stale.isNotEmpty()
            for (i in (c - WINDOW).coerceAtLeast(0)..(c + WINDOW).coerceAtMost(list.size - 1)) {
                val id = list[i].id
                if (sessions[id] == null) {
                    loadSession(list[i])
                    changed = true
                }
            }
            windowCenter = c
            activateSession(list[c].id, c)
            if (changed) publishStrip(list, c, -1)
        }
    }

    private fun activateSession(pageId: String, idx: Int) {
        val s = sessions[pageId] ?: return
        session = s
        pageIndex.value = idx
    }

    private fun publishStrip(list: List<Page>, activeIdx: Int, centerOn: Int) {
        val layout = PageStripLayout.layout(list.map { it.widthPt to it.heightPt })
        val total = PageStripLayout.totalHeightPt(layout)
        val slots = layout.map { l ->
            val p = list[l.index]
            StripSlot(l.index, l.topPt, l.leftPt, l.widthPt, l.heightPt, p.infinite, sessions[p.id])
        }
        _strip.value = StripState(slots, activeIdx, total, fitToken, centerOn)
    }

    /**
     * v2.0 (spec 3): an infinite page grew — persist the new size and refresh
     * the strip so the page card covers the new extent. History and doc stay
     * intact; only the tile cache is rebuilt at the new dimensions.
     */
    private fun onInfinitePageGrew(s: PageSession, newW: Float, newH: Float) {
        if (sessions[s.page.id] == null) return
        DiagLog.log("EditorVM", "infinite page grew to ${newW.toInt()}x${newH.toInt()}")
        s.liveTileCache.close()
        s.liveTileCache = InkTileCache(newW, newH, s.page.paper, s.page.bgArgb).apply {
            snapshotProvider = { PageSnapshot(s.doc.strokesSnapshot(), s.doc.objectsSnapshot(), s.doc.revision) }
        }
        s.page = s.page.copy(widthPt = newW, heightPt = newH)
        val grownList = pagesFlow.value.map {
            if (it.id == s.page.id) it.copy(widthPt = newW, heightPt = newH) else it
        }
        if (grownList.isNotEmpty()) publishStrip(grownList, pageIndex.value, -1)
        hasUnsavedChanges = true
        viewModelScope.launch {
            try {
                repo.resizePage(s.page.id, newW, newH, infinite = true)
            } catch (e: Exception) {
                DiagLog.error("EditorVM", "persist grown size failed", e)
            }
        }
    }

    /** Viewport center moved to another page while scrolling (view callback). */
    fun onViewportActiveChanged(index: Int) {
        val list = pagesFlow.value
        if (list.isEmpty() || index !in list.indices) return
        if (index == pageIndex.value && index == windowCenter &&
            sessions[list[index].id] != null
        ) return
        activateSession(list[index].id, index)
        slideWindow(index)
    }

    /** Opens a page: in-window -> animate to it; otherwise rebuild the window. */
    fun openPageAt(index: Int) {
        viewModelScope.launch {
            val list = repo.pagesOf(notebookId)
            if (list.isEmpty()) return@launch
            val c = index.coerceIn(0, list.size - 1)
            val id = list[c].id
            if (sessions[id] != null) {
                windowCenter = c
                activateSession(id, c)
                publishStrip(list, c, centerOn = c)
            } else {
                rebuildWindow(c, refit = false, centerOn = c)
            }
        }
    }

    /**
     * v1.3 quick scrubber: jump straight to a page with NO animation so the
     * handle tracks the finger 1:1 while dragging. In-window pages center
     * instantly; out-of-window pages rebuild the session window centered.
     */
    fun jumpToPage(index: Int) {
        viewModelScope.launch {
            val list = repo.pagesOf(notebookId)
            if (list.isEmpty()) return@launch
            val c = index.coerceIn(0, list.size - 1)
            val id = list[c].id
            if (sessions[id] != null) {
                windowCenter = c
                activateSession(id, c)
                canvasView?.jumpToPage(c)
                slideWindow(c)
            } else {
                rebuildWindow(c, refit = false, centerOn = c)
            }
        }
    }

    // ---------------------------------------- v2.0 page ops + undo (spec 9)

    /** Notebook-level undoable page operation. */
    private class PageOp(val undo: suspend () -> Unit, val redo: suspend () -> Unit)

    private val pageOps = ArrayDeque<PageOp>()
    private val redoPageOps = ArrayDeque<PageOp>()

    private fun pushPageOp(op: PageOp) {
        pageOps.addLast(op)
        if (pageOps.size > PAGE_OPS_LIMIT) pageOps.removeFirst()
        redoPageOps.clear()
        syncUndoFlags()
    }

    private fun syncUndoFlags() {
        val s = lastEdited ?: session
        undoDepth.value = (s?.history?.depth ?: 0) + pageOps.size
        redoDepth.value = (if (s?.history?.canRedo == true) 1 else 0) + redoPageOps.size
    }

    fun addPageAndOpen() {
        viewModelScope.launch {
            val before = repo.pagesOf(notebookId).map { it.id }
            val added = repo.addPage(notebookId) ?: return@launch
            pushPageOp(
                PageOp(
                    undo = { repo.deletePage(notebookId, added.id); restoreOrder(before) },
                    redo = { repo.addPage(notebookId) }
                )
            )
            val list = repo.pagesOf(notebookId)
            if (list.isEmpty()) return@launch
            val last = list.size - 1
            rebuildWindow(last, refit = false, centerOn = last)
        }
    }

    fun duplicateCurrentPage() {
        viewModelScope.launch {
            val s = session ?: return@launch
            flushSave(s)
            val before = repo.pagesOf(notebookId).map { it.id }
            val copy = repo.duplicatePage(s.page.id) ?: return@launch
            pushPageOp(
                PageOp(
                    undo = { repo.deletePage(notebookId, copy.id); restoreOrder(before) },
                    redo = { repo.duplicatePage(s.page.id) }
                )
            )
            val list = repo.pagesOf(notebookId)
            val to = (pageIndex.value + 1).coerceAtMost(list.size - 1)
            rebuildWindow(to, refit = false, centerOn = to)
        }
    }

    fun deleteCurrentPage() {
        viewModelScope.launch {
            val s = session ?: return@launch
            val idx = pageIndex.value
            // v2.0: capture a full backup so the delete is undoable.
            val entity: PageEntity? = repo.pageEntityById(s.page.id)
            val inkBytes: ByteArray? = withContext(io) {
                val f = pageStore.pageInk(notebookId, s.page.id)
                if (f.exists()) try {
                    f.readBytes()
                } catch (e: Exception) {
                    null
                } else null
            }
            val jsonBytes: ByteArray? = withContext(io) {
                val f = pageStore.pageJson(notebookId, s.page.id)
                if (f.exists()) try {
                    f.readBytes()
                } catch (e: Exception) {
                    null
                } else null
            }
            val before = repo.pagesOf(notebookId).map { it.id }
            val ok = repo.deletePage(notebookId, s.page.id)
            if (ok) {
                val deletedId = s.page.id
                val captured = entity
                pushPageOp(
                    PageOp(
                        undo = {
                            if (captured != null) {
                                repo.insertPageEntity(captured)
                                withContext(io) {
                                    inkBytes?.let {
                                        com.nanote.data.files.writeAtomic(pageStore.pageInk(notebookId, deletedId), it)
                                    }
                                    jsonBytes?.let {
                                        com.nanote.data.files.writeAtomic(pageStore.pageJson(notebookId, deletedId), it)
                                    }
                                }
                            }
                            restoreOrder(before)
                        },
                        redo = { repo.deletePage(notebookId, deletedId) }
                    )
                )
                flushSave(s)
                sessions.remove(s.page.id)?.liveTileCache?.close()
                val list = repo.pagesOf(notebookId)
                if (list.isEmpty()) {
                    _strip.value = null
                    return@launch
                }
                val to = (idx - 1).coerceAtLeast(0).coerceAtMost(list.size - 1)
                rebuildWindow(to, refit = false, centerOn = to)
            }
        }
    }

    /** Restores the page order captured before an operation. */
    private suspend fun restoreOrder(ids: List<String>) {
        val current = repo.pagesOf(notebookId).map { it.id }
        val ordered = ids.filter { it in current } + current.filter { it !in ids }
        repo.reorderPages(notebookId, ordered)
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
            val before = list.map { it.id }
            val ids = before.toMutableList()
            ids.removeAt(idx)
            ids.add(to, s.page.id)
            repo.reorderPages(notebookId, ids)
            pushPageOp(
                PageOp(
                    undo = { repo.reorderPages(notebookId, before) },
                    redo = { repo.reorderPages(notebookId, ids) }
                )
            )
            rebuildWindow(to, refit = false, centerOn = to)
        }
    }

    fun setPagePaper(paper: String, bgArgb: Int) {
        viewModelScope.launch {
            val s = session ?: return@launch
            val oldPaper = s.page.paper
            val oldBg = s.page.bgArgb
            flushSave(s)
            repo.setPagePaper(s.page.id, paper, bgArgb)
            pushPageOp(
                PageOp(
                    undo = { repo.setPagePaper(s.page.id, oldPaper, oldBg) },
                    redo = { repo.setPagePaper(s.page.id, paper, bgArgb) }
                )
            )
            rebuildWindow(pageIndex.value, refit = true, centerOn = -1)
        }
    }

    fun resizeCurrentPage(size: String) {
        viewModelScope.launch {
            val s = session ?: return@launch
            flushSave(s)
            val (w, h) = PAGE_SIZES_PT.getValue(size)
            val infinite = size in com.nanote.ink.engine.INFINITE_SIZES
            val oldW = s.page.widthPt
            val oldH = s.page.heightPt
            val oldInfinite = s.page.infinite
            repo.resizePage(s.page.id, w, h, infinite)
            pushPageOp(
                PageOp(
                    undo = { repo.resizePage(s.page.id, oldW, oldH, oldInfinite) },
                    redo = { repo.resizePage(s.page.id, w, h, infinite) }
                )
            )
            rebuildWindow(pageIndex.value, refit = true, centerOn = -1)
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
                    thumbLoader.load(pageId, File(thumbsDir, "$pageId.webp"))
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

    /** v2.0 (spec 5.2): highlighter opacity 0..100% — new strokes only. */
    fun setHlOpacity(percent: Int) {
        _tool.value = ToolSpec.withHlOpacity(_tool.value, percent)
    }

    // ------------------------------------------- v2.0 text selection actions

    private val clipboardManager: ClipboardManager? =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    fun selectionCopyText(info: InkCanvasView.TextSelectionInfo) {
        clipboardManager?.setPrimaryClip(ClipData.newPlainText("Nanote", info.text))
        uiEvents.tryEmit(UiEvent.CopiedToClipboard)
    }

    fun selectionTranslate(info: InkCanvasView.TextSelectionInfo) {
        val url = "https://translate.google.com/?sl=auto&op=translate&text=" +
            java.net.URLEncoder.encode(info.text, "UTF-8")
        uiEvents.tryEmit(UiEvent.OpenExternal(url))
    }

    fun selectionSearchWeb(info: InkCanvasView.TextSelectionInfo) {
        val url = "https://www.google.com/search?q=" +
            java.net.URLEncoder.encode(info.text, "UTF-8")
        uiEvents.tryEmit(UiEvent.OpenExternal(url))
    }

    /** Highlight (spec 5.3 #4): one highlighter band per selected LINE. */
    fun selectionHighlight(info: InkCanvasView.TextSelectionInfo) {
        val s = info.host as? PageSession ?: return
        val tool = _tool.value
        val strokes = ArrayList<Stroke>()
        for (line in groupLines(info.rects)) {
            val l = line.minOf { it.left }
            val r = line.maxOf { it.right }
            val t = line.minOf { it.top }
            val b = line.maxOf { it.bottom }
            val h = (b - t).coerceAtLeast(6f)
            val raw = buildLineStroke(
                l, t + h / 2f, r, t + h / 2f,
                s.doc.nextStrokeId(), PenType.HIGHLIGHTER, tool.hlColorArgb, h, 0f
            )
            strokes.add(Stroke(raw.id, raw.pen, raw.argb, raw.baseWidth, raw.pts, tool.hlOpacityPercent))
        }
        if (strokes.isNotEmpty()) {
            s.history.push(AddStrokes(strokes), s.doc)
            markDirty(s)
            refreshTick.value++
            canvasView?.invalidate()
        }
    }

    /** Underline (spec 5.3 #5): one line stroke under each selected line. */
    fun selectionUnderline(info: InkCanvasView.TextSelectionInfo) {
        val s = info.host as? PageSession ?: return
        val tool = _tool.value
        val strokes = ArrayList<Stroke>()
        for (line in groupLines(info.rects)) {
            val l = line.minOf { it.left }
            val r = line.maxOf { it.right }
            val b = line.maxOf { it.bottom }
            val raw = buildLineStroke(
                l, b - 1.2f, r, b - 1.2f,
                s.doc.nextStrokeId(), PenType.BALLPOINT, tool.penColorArgb, 1.6f, 0f
            )
            strokes.add(Stroke(raw.id, raw.pen, raw.argb, raw.baseWidth, raw.pts, 0))
        }
        if (strokes.isNotEmpty()) {
            s.history.push(AddStrokes(strokes), s.doc)
            markDirty(s)
            refreshTick.value++
            canvasView?.invalidate()
        }
    }

    /** Groups word rects into visual lines (top within 4 pt = same line). */
    private fun groupLines(rects: List<RectF>): List<List<RectF>> {
        if (rects.isEmpty()) return emptyList()
        val sorted = rects.sortedBy { it.top }
        val lines = ArrayList<MutableList<RectF>>()
        var lastTop = Float.MIN_VALUE
        for (r in sorted) {
            if (lines.isEmpty() || abs(r.top - lastTop) > 4f) {
                lines.add(mutableListOf(r))
            } else {
                lines.last().add(r)
            }
            lastTop = r.top
        }
        return lines
    }

    /** The canvas reported a selection change (may be null = cleared). */
    fun onTextSelectionChanged(info: InkCanvasView.TextSelectionInfo?) {
        textSelection.value = info
    }

    fun clearTextSelection() {
        canvasView?.clearTextSelection()
        textSelection.value = null
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
        textComposerSession = null
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
        val s = textComposerSession ?: session ?: return
        textComposerSession = null
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
        markDirty(s)
    }

    /** Called by the screen after the photo picker returns a stream. */
    fun addImageAtTarget(bytes: ByteArray, ext: String) {
        val s = imageSession ?: session ?: return
        imageSession = null
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
            markDirty(s)
        }
    }

    fun assetFileFor(assetId: String, ext: String): File =
        pageStore.assetFile(notebookId, assetId, ext)

    // ------------------------------------------------------ selection ops

    val clipboard = ArrayList<Stroke>()

    fun copySelection() {
        val v = canvasView ?: return
        clipboard.clear()
        clipboard.addAll(v.lassoSelection())
    }

    fun pasteClipboard() {
        val v = canvasView ?: return
        // v1.2: paste lands on the page owning the selection (maybe a neighbor)
        val s = (v.selectionHost() as? PageSession) ?: session ?: return
        if (clipboard.isEmpty()) return
        val copies = clipboard.map {
            it.transformed(
                com.nanote.ink.engine.Mat3().also { m -> m.postTranslate(12f, 12f) },
                s.doc.nextStrokeId()
            )
        }
        s.history.push(AddStrokes(copies), s.doc)
        v.clearSelectionOnly()
        markDirty(s)
    }

    fun deleteSelection() {
        canvasView?.deleteSelection()
        markDirtySessionIfAny()
    }

    fun recolorSelection(argb: Int) {
        canvasView?.recolorSelection(argb)
        markDirtySessionIfAny()
    }

    private fun markDirtySessionIfAny() {
        val target = (canvasView?.selectionHost() as? PageSession) ?: lastEdited ?: session
        if (target != null) markDirty(target)
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

    private fun pdfBitmapProviderForExport(): ((Int, Int) -> Bitmap?)? {
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
                    DiagLog.error("EditorVM", "PDF export failed", e)
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
                    DiagLog.error("EditorVM", "PNG export failed", e)
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
                    DiagLog.error("EditorVM", ".nanote export failed", e)
                }
            }
        }
    }

    /** Writes the current page PNG into exports/ for sharing; returns the file. */
    suspend fun writeSharedPng(): File? {
        val s = session ?: return null
        flushSave(s)?.join()
        return withContext(io) {
            try {
                val snap = PageSnapshot(s.doc.strokesSnapshot(), s.doc.objectsSnapshot(), 0)
                val name = "nanote-share-${System.currentTimeMillis()}.png"
                val f = File(exportsDir, name)
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

    /** Undo targets the last edited page while it is still nearby. */
    private fun undoTarget(): PageSession? {
        val le = lastEdited
        val act = session
        if (le != null && le.history.depth > 0 && act != null) {
            val actPage = act.page.id
            val editedStillNear = sessions[le.page.id] != null
            if (editedStillNear || le.page.id == actPage) return le
        }
        if (le != null && act == null) return le
        return act
    }

    /**
     * v2.0 (spec 9): undo covers BOTH notebook-level page operations and
     * per-page ink history. Notebook ops (add/delete/duplicate/reorder page,
     * paper/size) undo first while their stack is non-empty; then the ink
     * history of the last-edited page.
     */
    fun undo() {
        val op = pageOps.removeLastOrNull()
        if (op != null) {
            redoPageOps.addLast(op)
            viewModelScope.launch {
                try {
                    op.undo()
                } catch (e: Exception) {
                    DiagLog.error("EditorVM", "page-op undo failed", e)
                }
                rebuildWindow(pageIndex.value, refit = false, centerOn = -1)
                syncUndoFlags()
            }
            syncUndoFlags()
            return
        }
        val s = undoTarget() ?: return
        s.history.undo(s.doc)
        syncUndoFlagsFor(s)
        flushSave(s)
        refreshTick.value++
        canvasView?.invalidate()
    }

    fun redo() {
        val op = redoPageOps.removeLastOrNull()
        if (op != null) {
            pageOps.addLast(op)
            viewModelScope.launch {
                try {
                    op.redo()
                } catch (e: Exception) {
                    DiagLog.error("EditorVM", "page-op redo failed", e)
                }
                rebuildWindow(pageIndex.value, refit = false, centerOn = -1)
                syncUndoFlags()
            }
            syncUndoFlags()
            return
        }
        val s = undoTarget() ?: return
        s.history.redo(s.doc)
        syncUndoFlagsFor(s)
        flushSave(s)
        refreshTick.value++
        canvasView?.invalidate()
    }

    private fun syncUndoFlagsFor(s: PageSession) {
        undoDepth.value = s.history.depth + pageOps.size
        redoDepth.value = (if (s.history.canRedo) 1 else 0) + redoPageOps.size
    }

    // ------------------------------------------------------------ autosave

    private var autosaveJob: Job? = null

    /** v2.0: the Settings autosave slider, honored live (default 2000 ms). */
    @Volatile private var autosaveDelayMs: Long = AUTOSAVE_DEBOUNCE_MS

    init {
        // (second init block kept separate for readability)
        viewModelScope.launch {
            recent.autosaveMs.collect { ms ->
                autosaveDelayMs = ms.coerceIn(500L, 10_000L)
            }
        }
    }

    private fun markDirty(s: PageSession) {
        lastEdited = s
        hasUnsavedChanges = true
        syncUndoFlagsFor(s)
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(autosaveDelayMs) // spec 8.1: 2 s (default) after the last change
            flushSave(s)
            hasUnsavedChanges = false
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
                        snapshot.objects.filterIsInstance<TextBox>().map { it.text }
                    )
                    // v2.0: thumbnails include the PDF background + highlighter.
                    val pdfBg = if (s.page.pdfPageIndex != null) {
                        try {
                            sharedPdf?.renderFull(s.page.pdfPageIndex!!, 300)
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                    thumbs.render(
                        s.page.id, s.page.widthPt, s.page.heightPt,
                        s.page.paper, s.page.bgArgb, snapshot, pdfBg
                    )
                    pdfBg?.recycle()
                } catch (e: Exception) {
                    // Saving must never crash the editor; the .bak keeps the last good state.
                    DiagLog.error("EditorVM", "save failed for ${s.page.id}", e)
                }
            }
        }
    }

    /** Full flush before leaving (also called from ON_STOP): all sessions. */
    fun saveNowAndClose() {
        for (s in sessions.values) flushSave(s)
        flushSave(session)
        hasUnsavedChanges = false
    }

    override fun onCleared() {
        saveNowAndClose()
        sessions.values.forEach { it.liveTileCache.close() }
        sharedPdf?.close()
        sharedPdf = null
        super.onCleared()
    }
}
