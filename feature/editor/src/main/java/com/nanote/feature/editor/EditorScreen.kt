package com.nanote.feature.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.automirrored.outlined.FormatAlignLeft
import androidx.compose.material.icons.automirrored.outlined.FormatAlignRight
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FormatAlignCenter
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LayersClear
import androidx.compose.material.icons.outlined.Splitscreen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanote.feature.editor.R
import com.nanote.core.graphics.CanvasView
import com.nanote.core.data.SaveState
import com.nanote.core.model.ExportOptions
import com.nanote.feature.editor.menus.ExportSheet
import com.nanote.feature.editor.menus.MoreMenu
import com.nanote.feature.editor.menus.PagePanel
import com.nanote.feature.editor.menus.PdfInfoSheet
import com.nanote.core.model.CanvasMode
import com.nanote.core.model.EditorFont
import com.nanote.core.model.TextAlignMode
import com.nanote.core.model.TextElement
import com.nanote.core.model.TextListStyle
import com.nanote.feature.editor.menus.PageStrip
import com.nanote.feature.editor.menus.TextContextMenu
import com.nanote.feature.editor.menus.ZoomPopover
import com.nanote.feature.editor.toolbar.NanoteToolbar
import com.nanote.feature.editor.toolbar.ToolbarCustomizeSheet

/**
 * The editor: canvas + Notewise-style toolbar + overlays + split workspace.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: EditorViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val mainPane by vm.mainPane.collectAsStateWithLifecycle()
    val secondPane by vm.secondPane.collectAsStateWithLifecycle()
    val splitEnabled by vm.splitEnabled.collectAsStateWithLifecycle()
    val exporting by vm.exporting.collectAsStateWithLifecycle()
    val title by vm.noteTitle.collectAsStateWithLifecycle()
    val saveState by vm.saveState.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()

    // toast events
    LaunchedEffect(vm) {
        vm.toast.collect { key ->
            val id = runCatching {
                @Suppress("UNCHECKED_CAST")
                val res = R.string::class.java.getField(key).get(null) as Int
                res
            }.getOrNull()
            if (id != null) {
                android.widget.Toast.makeText(context, context.getString(id), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    val pane = mainPane
    if (pane == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    var showPagesPanel by remember { mutableStateOf(false) }
    var showPdfInfo by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ExportOptions?>(null) }

    fun exportResult(uri: Uri?) {
        val options = pendingExport
        pendingExport = null
        if (uri != null && options != null) {
            vm.exportWithOptions(uri, options) { ok ->
                android.widget.Toast.makeText(
                    context,
                    context.getString(if (ok) R.string.export_done else R.string.export_failed),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val exportPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { exportResult(it) }
    val exportPngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { exportResult(it) }
    val exportJpgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { exportResult(it) }
    val exportNanoteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { exportResult(it) }

    val exportTitle = vm.noteTitle.value.ifBlank { "nanote" }

    fun launchExport(options: ExportOptions, fileName: String) {
        pendingExport = options
        val name = fileName.ifBlank { "$exportTitle" }
        when (options.format) {
            com.nanote.core.model.ExportFormat.PDF -> exportPdfLauncher.launch(name)
            com.nanote.core.model.ExportFormat.PNG -> exportPngLauncher.launch(name)
            com.nanote.core.model.ExportFormat.JPEG -> exportJpgLauncher.launch(name)
            com.nanote.core.model.ExportFormat.NANOTE -> exportNanoteLauncher.launch(name)
        }
    }

    val insertPagesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.insertPdfPagesFrom(it, pane.currentPage.value + 1) }
    }

    // ---------------- document-level sheets ----------------
    if (showPagesPanel) {
        PagePanel(
            vm = vm,
            pane = pane,
            onDismiss = { showPagesPanel = false },
            onInsertFromPdf = {
                showPagesPanel = false
                insertPagesPicker.launch(arrayOf("application/pdf"))
            }
        )
    }
    if (showPdfInfo) {
        PdfInfoSheet(vm = vm, onDismiss = { showPdfInfo = false })
    }
    if (showExport) {
        ExportSheet(
            vm = vm,
            pane = pane,
            onDismiss = { showExport = false },
            onExportToFile = { options, name -> launchExport(options, name) }
        )
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // ---------------- top bar ----------------
        EditorTopBar(
            title = title.ifBlank { stringResource(R.string.untitled_note) },
            pane = pane,
            splitEnabled = splitEnabled,
            saveState = saveState,
            bookmarked = bookmarks.any { it.pageIndex == pane.currentPage.value },
            showBookmark = pane.isPdf,
            onBack = onBack,
            onToggleSplit = { vm.toggleSplit() },
            onToggleBookmark = { vm.toggleBookmarkOnCurrentPage() },
            onOpenPages = { showPagesPanel = true }
        )
        if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth())

        // ---------------- canvas area ----------------
        Box(Modifier.fillMaxSize()) {
            if (splitEnabled && secondPane != null) {
                var splitRatio by remember { mutableFloatStateOf(0.5f) }
                Row(Modifier.fillMaxSize()) {
                    EditorPane(
                        vm = vm,
                        paneKey = EditorViewModel.KEY_MAIN,
                        pane = pane,
                        onOpenSettings = onOpenSettings,
                        onOpenExport = { showExport = true },
                        onOpenPages = { showPagesPanel = true },
                        onOpenPdfInfo = { showPdfInfo = true },
                        modifier = Modifier.weight(splitRatio.coerceIn(0.25f, 0.75f)).fillMaxHeight()
                    )
                    // draggable divider
                    Box(
                        Modifier
                            .width(10.dp)
                            .fillMaxHeight()
                            .pointerInputDivider { delta ->
                                splitRatio = (splitRatio + delta).coerceIn(0.25f, 0.75f)
                            }
                    )
                    EditorPane(
                        vm = vm,
                        paneKey = EditorViewModel.KEY_SECOND,
                        pane = secondPane!!,
                        onOpenSettings = onOpenSettings,
                        onOpenExport = { showExport = true },
                        onOpenPages = { showPagesPanel = true },
                        onOpenPdfInfo = { showPdfInfo = true },
                        modifier = Modifier.weight((1f - splitRatio).coerceIn(0.25f, 0.75f)).fillMaxHeight()
                    )
                }
            } else {
                EditorPane(
                    vm = vm,
                    paneKey = EditorViewModel.KEY_MAIN,
                    pane = pane,
                    onOpenSettings = onOpenSettings,
                    onOpenExport = { showExport = true },
                    onOpenPages = { showPagesPanel = true },
                    onOpenPdfInfo = { showPdfInfo = true },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun EditorTopBar(
    title: String,
    pane: PaneController,
    splitEnabled: Boolean,
    saveState: SaveState,
    bookmarked: Boolean,
    showBookmark: Boolean,
    onBack: () -> Unit,
    onToggleSplit: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenPages: () -> Unit
) {
    val currentPage by pane.currentPage.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    SaveStateChip(saveState)
                }
                Text(
                    stringResource(R.string.page_indicator, currentPage + 1, pane.pageCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (showBookmark) {
                IconButton(onClick = onToggleBookmark) {
                    Icon(
                        if (bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = stringResource(R.string.bookmark_toggle),
                        tint = if (bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onOpenPages) {
                Icon(Icons.Outlined.AutoStories, contentDescription = stringResource(R.string.pages_panel_title))
            }
            IconButton(onClick = { pane.prevPage() }, enabled = currentPage > 0) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(R.string.zoom_out))
            }
            IconButton(onClick = { pane.nextPage() }, enabled = currentPage < pane.pageCount - 1) {
                Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.zoom_in))
            }
            IconButton(onClick = onToggleSplit) {
                Icon(
                    Icons.Outlined.Splitscreen,
                    contentDescription = if (splitEnabled) stringResource(R.string.exit_split) else stringResource(R.string.split_screen),
                    tint = if (splitEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Autosave status chip: Saving… / Saved. Never claims "cloud" anything. */
@Composable
private fun SaveStateChip(state: SaveState) {
    when (state) {
        is SaveState.Saving -> {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
                    Text(stringResource(R.string.save_state_saving), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        is SaveState.Saved -> {
            Text(
                stringResource(R.string.save_state_saved),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        is SaveState.Error -> {
            Text(
                stringResource(R.string.save_state_error),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        is SaveState.Idle -> Unit
    }
}

private fun Modifier.pointerInputDivider(onDelta: (Float) -> Unit): Modifier =
    this.pointerInput(Unit) {
        detectHorizontalDragGestures { _, dragAmount ->
            onDelta(dragAmount / 600f)
        }
    }

@Composable
private fun EditorPane(
    vm: EditorViewModel,
    paneKey: String,
    pane: PaneController,
    onOpenSettings: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenPages: () -> Unit,
    onOpenPdfInfo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val toolbarConfig by vm.settings.toolbarConfig.collectAsStateWithLifecycle()
    val loading by pane.loading.collectAsStateWithLifecycle()
    val textPreparing by pane.textPreparing.collectAsStateWithLifecycle()
    val renderState by pane.renderState.collectAsStateWithLifecycle()

    var showZoom by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showCustomize by remember { mutableStateOf(false) }
    // (offline build: translate / web-search / ask-AI actions were removed)
    var cameraTarget by remember { mutableStateOf<Uri?>(null) }

    // pickers
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.insertImage(it, paneKey) }
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importPdfToOtherPane(it) }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraTarget
        val file = vm.cameraTargetFile(paneKey)
        if (ok && uri != null && file != null && file.exists()) {
            vm.insertCameraImage(file, paneKey)
        }
    }

    Box(modifier.imePadding()) {

        // ---------------- canvas ----------------
        AndroidView(
            factory = { ctx -> CanvasView(ctx).also { pane.attachView(it) } },
            update = { },
            onRelease = { pane.detachView() },
            modifier = Modifier.fillMaxSize()
        )

        // ---------------- loading overlay ----------------
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(shape = MaterialTheme.shapes.large, shadowElevation = 4.dp) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Text(stringResource(R.string.loading_pdf), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // ---------------- text preparing chip ----------------
        if (textPreparing) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
            ) {
                Text(stringResource(R.string.text_layer_building), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
            }
        }

        // ---------------- failed page retry buttons ----------------
        for (page in pane.failedVisiblePages()) {
            val anchor = pane.screenPointForPage(page)
            if (anchor != null) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 3.dp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(
                            start = with(androidx.compose.ui.platform.LocalDensity.current) {
                                (anchor.x.coerceIn(60f, 2000f)).toDp() - 60.dp
                            },
                            top = with(androidx.compose.ui.platform.LocalDensity.current) {
                                (anchor.y.coerceIn(60f, 2000f)).toDp() - 60.dp
                            }
                        )
                ) {
                    Text(
                        stringResource(R.string.render_retry),
                        Modifier
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                val pdfPage = pane.doc.pages.getOrNull(page)?.pdfPage ?: -1
                                pane.retryPage(pdfPage)
                            },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        // ---------------- object selection contextual toolbar (§8 / §11) ----------------
        val objectMenu by pane.objectMenu.collectAsStateWithLifecycle()
        val canPaste by pane.canPaste.collectAsStateWithLifecycle()
        if (objectMenu) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
            ) {
                Row(
                    Modifier
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(onClick = { pane.copySelection() }) {
                        Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy))
                    }
                    IconButton(onClick = { pane.cutSelection() }) {
                        Icon(Icons.Outlined.ContentCut, stringResource(R.string.cut))
                    }
                    IconButton(
                        onClick = { pane.paste() },
                        enabled = canPaste
                    ) {
                        Icon(Icons.Outlined.ContentPaste, stringResource(R.string.paste), tint = if (canPaste) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline)
                    }
                    if (pane.canGroupSelection) {
                        IconButton(onClick = { pane.groupSelection() }) {
                            Icon(Icons.Outlined.Layers, stringResource(R.string.group_selection))
                        }
                    }
                    if (pane.canUngroupSelection) {
                        IconButton(onClick = { pane.ungroupSelection() }) {
                            Icon(Icons.Outlined.LayersClear, stringResource(R.string.ungroup_selection))
                        }
                    }
                    IconButton(onClick = { pane.duplicateSelection() }) {
                        Icon(Icons.Outlined.DoneAll, stringResource(R.string.duplicate))
                    }
                    IconButton(onClick = { pane.deleteSelection() }) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // ---------------- text selection context menu ----------------
        TextContextMenu(
            pane = pane,
            onCopied = {
                android.widget.Toast.makeText(context, context.getString(R.string.copied), android.widget.Toast.LENGTH_SHORT).show()
            }
        )

        // ---------------- page strip (§10, paged mode only) ----------------
        val canvasMode by pane.canvasMode.collectAsStateWithLifecycle()
        if (canvasMode == CanvasMode.PAGED) {
            PageStrip(
                pane = pane,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 56.dp, end = 8.dp)
                    .height(320.dp)
            )
        }

        // ---------------- toolbar (Notewise style) ----------------
        NanoteToolbar(
            pane = pane,
            position = toolbarConfig.position,
            onInsertImage = { imagePicker.launch(arrayOf("image/*")) },
            onInsertCamera = {
                val file = vm.cameraTargetFile(paneKey)
                if (file != null) {
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                    cameraTarget = uri
                    cameraLauncher.launch(uri)
                }
            },
            onInsertPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
            onZoomControls = { showZoom = true },
            onMore = { showMore = true },
            onCustomize = { showCustomize = true }
        )

        // ---------------- popovers / sheets ----------------
        if (showZoom) {
            ZoomPopover(pane = pane, onDismiss = { showZoom = false })
        }
        if (showMore) {
            MoreMenu(
                pane = pane,
                onDismiss = { showMore = false },
                onExport = { showMore = false; onOpenExport() },
                onPages = { showMore = false; onOpenPages() },
                onPdfInfo = if (pane.isPdf) {
                    { showMore = false; onOpenPdfInfo() }
                } else null,
                onSplitToggle = { showMore = false; vm.toggleSplit() },
                onCustomize = { showMore = false; showCustomize = true },
                onSettings = { showMore = false; onOpenSettings() }
            )
        }
        if (showCustomize) {
            ToolbarCustomizeSheet(settings = vm.settings, onDismiss = { showCustomize = false })
        }

        // ---------------- insert/edit text dialog (§12 rich text) ----------------
        val insertRequest by pane.insertTextRequest.collectAsStateWithLifecycle()
        insertRequest?.let { req ->
            RichTextDialog(
                request = req,
                onDismiss = { pane.cancelInsertText() },
                onConfirm = { edited -> pane.commitRichText(req, edited) }
            )
        }
    }
}

private fun titleOf(pane: PaneController, vm: EditorViewModel): String =
    vm.noteTitle.value.ifBlank { "nanote" }


/**
 * §12 — rich text editor: font family, size, bold/italic/underline/strikethrough,
 * alignment, bullet/numbered lists, text + background color. Arabic/English
 * mixed text works through Android's bidi-aware StaticLayout.
 */
@Composable
private fun RichTextDialog(
    request: InsertTextRequest,
    onDismiss: () -> Unit,
    onConfirm: (TextElement) -> Unit
) {
    val existing = request.existing
    var text by remember { mutableStateOf(existing?.text ?: "") }
    var size by remember { mutableFloatStateOf(existing?.size ?: 18f) }
    var bold by remember { mutableStateOf(existing?.bold ?: false) }
    var italic by remember { mutableStateOf(existing?.italic ?: false) }
    var underline by remember { mutableStateOf(existing?.underline ?: false) }
    var strikethrough by remember { mutableStateOf(existing?.strikethrough ?: false) }
    var align by remember { mutableStateOf(existing?.align ?: TextAlignMode.START) }
    var listStyle by remember { mutableStateOf(existing?.listStyle ?: TextListStyle.NONE) }
    var font by remember { mutableStateOf(existing?.font ?: EditorFont.DEFAULT) }
    var color by remember { mutableStateOf(existing?.color ?: 0xFF0F172A) }
    var bgColor by remember { mutableStateOf(existing?.bgColor ?: 0L) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (request.sticky) stringResource(R.string.tool_sticky) else stringResource(R.string.tool_text)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.insert_text_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                // font family
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    EditorFont.entries.forEach { f ->
                        androidx.compose.material3.FilterChip(
                            selected = font == f,
                            onClick = { font = f },
                            label = {
                                Text(
                                    stringResource(
                                        when (f) {
                                            EditorFont.DEFAULT -> R.string.font_default
                                            EditorFont.SERIF -> R.string.font_serif
                                            EditorFont.MONOSPACE -> R.string.font_mono
                                            EditorFont.CASUAL -> R.string.font_casual
                                            EditorFont.CURSIVE -> R.string.font_cursive
                                            EditorFont.SANS_SERIF_SMALL_CAPS -> R.string.font_sans
                                        }
                                    ),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }
                // style toggles
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(onClick = { bold = !bold }) {
                        Icon(
                            Icons.Outlined.FormatBold,
                            stringResource(R.string.bold),
                            tint = if (bold) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { italic = !italic }) {
                        Icon(
                            Icons.Outlined.FormatItalic,
                            stringResource(R.string.italic),
                            tint = if (italic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { underline = !underline }) {
                        Icon(
                            Icons.Outlined.FormatUnderlined,
                            stringResource(R.string.underline),
                            tint = if (underline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { strikethrough = !strikethrough }) {
                        Icon(
                            Icons.Outlined.FormatStrikethrough,
                            stringResource(R.string.strikethrough),
                            tint = if (strikethrough) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // alignment + lists
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(onClick = { align = TextAlignMode.START }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.FormatAlignLeft,
                            stringResource(R.string.align_start),
                            tint = if (align == TextAlignMode.START) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { align = TextAlignMode.CENTER }) {
                        Icon(
                            Icons.Outlined.FormatAlignCenter,
                            stringResource(R.string.align_center),
                            tint = if (align == TextAlignMode.CENTER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { align = TextAlignMode.END }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.FormatAlignRight,
                            stringResource(R.string.align_end),
                            tint = if (align == TextAlignMode.END) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { listStyle = if (listStyle == TextListStyle.BULLET) TextListStyle.NONE else TextListStyle.BULLET }) {
                        Icon(
                            Icons.Outlined.FormatListBulleted,
                            stringResource(R.string.list_bullet),
                            tint = if (listStyle == TextListStyle.BULLET) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { listStyle = if (listStyle == TextListStyle.NUMBERED) TextListStyle.NONE else TextListStyle.NUMBERED }) {
                        Icon(
                            Icons.Outlined.FormatListNumbered,
                            stringResource(R.string.list_numbered),
                            tint = if (listStyle == TextListStyle.NUMBERED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // size
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.font_size), style = MaterialTheme.typography.labelMedium)
                    androidx.compose.material3.Slider(
                        value = size,
                        onValueChange = { size = it },
                        valueRange = 8f..96f,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${size.toInt()}", style = MaterialTheme.typography.labelMedium)
                }
                // colors
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.text_color), style = MaterialTheme.typography.labelMedium)
                    listOf(0xFF0F172A, 0xFF2563EB, 0xFFDC2626, 0xFF16A34A, 0xFFD97706, 0xFF7C3AED).forEach { c ->
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(androidx.compose.ui.graphics.Color(c.toInt()))
                                .border(
                                    if (color == c) 3.dp else 1.dp,
                                    if (color == c) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    androidx.compose.foundation.shape.CircleShape
                                )
                                .clickable { color = c }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.background_color), style = MaterialTheme.typography.labelMedium)
                    listOf(0L, 0x33FDE047, 0x3386EFAC, 0x3393C5FD, 0x33FCA5A5, 0x33D8B4FE).forEach { c ->
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(
                                    if (c == 0L) MaterialTheme.colorScheme.surfaceVariant
                                    else androidx.compose.ui.graphics.Color(c.toInt())
                                )
                                .border(
                                    if (bgColor == c) 3.dp else 1.dp,
                                    if (bgColor == c) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    androidx.compose.foundation.shape.CircleShape
                                )
                                .clickable { bgColor = c }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val edited = if (existing != null) {
                    existing.copy(
                        text = text, size = size, bold = bold, italic = italic,
                        underline = underline, strikethrough = strikethrough,
                        align = align, listStyle = listStyle, font = font,
                        color = color, bgColor = bgColor, sticky = request.sticky
                    )
                } else {
                    TextElement(
                        id = com.nanote.core.model.newId(),
                        page = request.page,
                        text = text,
                        x = request.worldX,
                        y = request.worldY,
                        size = size,
                        color = color,
                        sticky = request.sticky,
                        width = if (request.sticky) 220f else 300f,
                        font = font,
                        bold = bold,
                        italic = italic,
                        underline = underline,
                        strikethrough = strikethrough,
                        align = align,
                        listStyle = listStyle,
                        bgColor = bgColor,
                        timestamp = System.currentTimeMillis()
                    )
                }
                onConfirm(edited)
            }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
