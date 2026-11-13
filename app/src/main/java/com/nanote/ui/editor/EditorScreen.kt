package com.nanote.ui.editor

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanote.R
import com.nanote.data.files.ToolbarPosition
import com.nanote.ink.render.InkCanvasView
import com.nanote.ui.editor.EditorViewModel.Factory
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Factory entry point so panes can create editor VMs with explicit args. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface EditorVmFactoryEntryPoint {
    fun editorViewModelFactory(): Factory
}

/**
 * The handwriting editor (spec 8 + v2.0 split workspace, spec 7):
 *  - ONE document by default; a toggle in the top bar opens a SECOND document
 *    side by side with a draggable divider. Each pane is a fully independent
 *    editor: own file, zoom, tool and undo stack. The split state survives
 *    rotation (rememberSaveable + configChanges in the manifest).
 *  - PDF text selection shows the 5-action contextual menu (spec 5.3),
 *    anchored to the selection and clamped to stay on screen and above the
 *    keyboard.
 *  - Tablets get a permanent page sidebar; phones open it as a bottom sheet.
 */
@Composable
fun EditorScreen(
    notebookId: String,
    initialPageId: String,
    onBack: () -> Unit,
    mainVm: EditorViewModel? = null
) {
    val context = LocalContext.current
    val main: EditorViewModel = mainVm ?: viewModel(
        key = "editor-$notebookId-$initialPageId"
    ) {
        EntryPointAccessors.fromApplication(
            context.applicationContext, EditorVmFactoryEntryPoint::class.java
        ).editorViewModelFactory().create(notebookId, initialPageId)
    }

    // ---- split workspace state (spec 7) ----
    var splitMode by rememberSaveable { mutableStateOf(false) }
    var splitNotebookId by rememberSaveable { mutableStateOf<String?>(null) }
    var splitFraction by rememberSaveable { mutableStateOf(0.5f) }
    var showSplitPicker by remember { mutableStateOf(false) }

    val others by main.otherNotebooks.collectAsState()

    fun exitSplit() {
        splitMode = false
        splitNotebookId = null
    }

    BackHandler(enabled = splitMode) { exitSplit() }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (splitMode && splitNotebookId != null) {
            val splitId = splitNotebookId!!
            Row(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .weight(splitFraction.coerceIn(0.25f, 0.75f))
                ) {
                    EditorPane(vm = main, onClose = onBack, isPrimary = true)
                }
                // draggable divider
                Box(
                    Modifier
                        .width(10.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val w = size.width.toFloat()
                                if (w > 0f) {
                                    splitFraction = (splitFraction + dragAmount.x / w)
                                        .coerceIn(0.25f, 0.75f)
                                }
                            }
                        }
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .weight((1f - splitFraction.coerceIn(0.25f, 0.75f)))
                ) {
                    SplitPaneHost(notebookId = splitId, onToggleSplit = { exitSplit() })
                }
            }
        } else {
            EditorPane(
                vm = main,
                onClose = onBack,
                isPrimary = true,
                onToggleSplit = { showSplitPicker = true }
            )
        }

        // split document picker (spec 7: choose a second document)
        if (showSplitPicker) {
            AlertDialog(
                onDismissRequest = { showSplitPicker = false },
                title = { Text(stringResource(R.string.pick_split_notebook)) },
                text = {
                    if (others.isEmpty()) {
                        Text(stringResource(R.string.empty_notebooks))
                    } else {
                        LazyColumn(Modifier.height(320.dp)) {
                            items(others, key = { it.id }) { nb ->
                                Text(
                                    text = nb.title.ifBlank { stringResource(R.string.action_new_note) },
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showSplitPicker = false
                                            splitNotebookId = nb.id
                                            splitMode = true
                                        }
                                        .padding(vertical = 12.dp, horizontal = 4.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSplitPicker = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }
}

/** Second split pane: builds its own independent editor ViewModel (spec 7). */
@Composable
private fun SplitPaneHost(notebookId: String, onToggleSplit: () -> Unit) {
    val context = LocalContext.current
    val vm: EditorViewModel = viewModel(key = "editor-split-$notebookId") {
        EntryPointAccessors.fromApplication(
            context.applicationContext, EditorVmFactoryEntryPoint::class.java
        ).editorViewModelFactory().create(notebookId, "first")
    }
    EditorPane(vm = vm, onClose = onToggleSplit, isPrimary = false, onToggleSplit = onToggleSplit)
}

/**
 * One editor pane: canvas + top bar + palette + scrubber + selection UI.
 * Extracted in v2.0 so the split workspace can host two of them.
 */
@Composable
fun EditorPane(
    vm: EditorViewModel,
    onClose: () -> Unit,
    isPrimary: Boolean,
    onToggleSplit: (() -> Unit)? = null
) {
    val title by vm.title.collectAsState()
    val pages by vm.pagesFlow.collectAsState()
    val pageIndex by vm.pageIndex.collectAsState()
    val tool by vm.tool.collectAsState()
    val fingersDraw by vm.fingersDraw.collectAsState()
    val stylusOnly by vm.stylusOnly.collectAsState()
    val toolbarPos by vm.toolbarPosition.collectAsState()
    val penResponse by vm.penResponse.collectAsState()
    val undoDepth by vm.undoDepth.collectAsState()
    val redoDepth by vm.redoDepth.collectAsState()
    val session = vm.session
    val stripState by vm.strip.collectAsState()
    val pageThumbs by vm.pageThumbs.collectAsState()
    val showTextComposer by vm.showTextComposer.collectAsState()
    val urlOpen by vm.urlOpenEvent.collectAsState()
    val textSel by vm.textSelection.collectAsState()
    val viewRef = remember { mutableStateOf<InkCanvasView?>(null) }
    var showPages by remember { mutableStateOf(false) }
    var showPageSettings by remember { mutableStateOf(false) }
    var focusMode by remember { mutableStateOf(false) }
    var hasSelection by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val expanded = LocalConfiguration.current.screenWidthDp >= 600
    val tileErrorLabel = stringResource(R.string.tile_render_failed)

    LaunchedEffect(Unit) { vm.openInitialPage() }

    // v2.0: UI events — clipboard toast, external intents, recovery notices
    LaunchedEffect(Unit) {
        vm.uiEvents.collect { ev ->
            when (ev) {
                is EditorViewModel.UiEvent.CopiedToClipboard ->
                    Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                is EditorViewModel.UiEvent.OpenExternal -> {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ev.url)))
                    } catch (e: Exception) {
                        // No browser installed: ignore silently (offline-first)
                    }
                }
                is EditorViewModel.UiEvent.PartialRecovery -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.recovered_partial_fmt, ev.dropped),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // selection state poll (cheap; view-driven)
    LaunchedEffect(session) {
        while (true) {
            hasSelection = vm.hasSelection()
            kotlinx.coroutines.delay(150)
        }
    }

    // URL open events from link objects
    LaunchedEffect(urlOpen) {
        urlOpen?.let { url ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                // No handler: ignore silently (offline-first)
            }
            vm.consumeUrlEvent()
        }
    }

    // image picker for the IMAGE tool
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null && bytes.isNotEmpty()) {
                vm.addImageAtTarget(bytes, "png")
            }
        }
    }

    // export launchers (SAF CreateDocument)
    val exportPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> vm.exportPdfTo(uri) }
    val exportPng = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri -> vm.exportPagePngTo(uri) }
    val exportNanote = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> vm.exportNanoteTo(uri) }

    var exportMenu by remember { mutableStateOf(false) }
    val shareScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(vm.imagePickerRequest) {
        if (vm.imagePickerRequest.value > 0) {
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            vm.imagePickerRequest.value = 0
        }
    }

    // flush on ON_STOP (spec 8.1: save on pause/stop)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.saveNowAndClose()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = textSel != null) { vm.clearTextSelection() }
    BackHandler(enabled = textSel == null) { vm.saveNowAndClose(); onClose() }

    // theme surface color for the canvas background
    vm.surfaceArgb = MaterialTheme.colorScheme.surfaceVariant.toArgb()

    Box(Modifier.fillMaxSize()) {
        // ------------------------------------------------ canvas
        if (session != null) {
            AndroidView(
                factory = { ctx ->
                    InkCanvasView(ctx).also { v ->
                        viewRef.value = v
                        vm.canvasView = v
                        v.onActivePageChanged = { idx -> vm.onViewportActiveChanged(idx) }
                        v.onPageNeedsLoad = { idx -> vm.onViewportActiveChanged(idx) }
                        v.onTextSelectionChanged = { info -> vm.onTextSelectionChanged(info) }
                    }
                },
                update = { v ->
                    viewRef.value = v
                    vm.canvasView = v
                    v.penResponse = penResponse
                    v.tileErrorText = tileErrorLabel
                    stripState?.let { v.setStrip(it) }
                },
                onRelease = { v ->
                    v.onTextSelectionChanged = null
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // top bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.saveNowAndClose(); onClose() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_to_library))
            }
            Text(
                text = title.ifBlank { stringResource(R.string.action_new_note) },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { vm.undo() }, enabled = undoDepth > 0) {
                Icon(Icons.Filled.Undo, contentDescription = stringResource(R.string.action_undo))
            }
            IconButton(onClick = { vm.redo() }, enabled = redoDepth > 0) {
                Icon(Icons.Filled.Redo, contentDescription = stringResource(R.string.action_redo))
            }
            // v2.0 (spec 7): split workspace toggle
            if (onToggleSplit != null) {
                IconButton(onClick = onToggleSplit) {
                    Icon(
                        Icons.Filled.VerticalSplit,
                        contentDescription = stringResource(R.string.split_view)
                    )
                }
            }
            if (!expanded && pages.isNotEmpty()) {
                IconButton(onClick = { showPages = true }) {
                    Icon(Icons.Filled.AutoStories, contentDescription = stringResource(R.string.pages_title))
                }
            }
            IconButton(onClick = { vm.toggleStylusOnly() }) {
                Icon(
                    androidx.compose.material.icons.Icons.Filled.FrontHand,
                    contentDescription = stringResource(R.string.stylus_only),
                    tint = if (stylusOnly) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { focusMode = !focusMode }) {
                Icon(Icons.Filled.VisibilityOff, contentDescription = stringResource(R.string.focus_mode))
            }
            Box {
                IconButton(onClick = { exportMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.export_pdf)) },
                        onClick = {
                            exportMenu = false
                            exportPdf.launch("${title.ifBlank { "notebook" }}.pdf")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.export_png)) },
                        onClick = {
                            exportMenu = false
                            exportPng.launch("${title.ifBlank { "page" }}.png")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.export_nanote)) },
                        onClick = {
                            exportMenu = false
                            exportNanote.launch("${title.ifBlank { "notebook" }}.nanote")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share_png)) },
                        onClick = {
                            exportMenu = false
                            shareScope.launch {
                                val f = vm.writeSharedPng()
                                if (f != null) {
                                    val shared = androidx.core.content.FileProvider.getUriForFile(
                                        context, context.packageName + ".fileprovider", f
                                    )
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/png"
                                        putExtra(Intent.EXTRA_STREAM, shared)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(send, null))
                                }
                            }
                        }
                    )
                }
            }
        }

        // page pill (shifted below the palette when the toolbar is on top)
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 2.dp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (toolbarPos == ToolbarPosition.TOP) 176.dp else 60.dp)
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${pageIndex + 1}/${pages.size.coerceAtLeast(1)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (pages.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = { vm.addPageAndOpen() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.action_add_page),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // v1.3 quick page scrubber
        if (session != null && pages.size > 1 && !focusMode) {
            PageScrubber(
                pageCount = pages.size,
                pageIndex = pageIndex,
                onJump = { vm.jumpToPage(it) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(
                        end = if (toolbarPos == ToolbarPosition.RIGHT) 64.dp else 0.dp,
                        top = 100.dp,
                        bottom = 24.dp
                    )
            )
        }

        // lasso selection contextual bar
        if (hasSelection) {
            Box(
                Modifier.align(Alignment.TopCenter)
                    .padding(top = if (toolbarPos == ToolbarPosition.TOP) 220.dp else 100.dp)
            ) {
                SelectionBar(
                    onCopy = { vm.copySelection() },
                    onPaste = { vm.pasteClipboard() },
                    onDelete = { vm.deleteSelection() },
                    onRecolor = { vm.recolorSelection(tool.colorArgb) },
                    onDone = { vm.finishSelection() }
                )
            }
        }

        // tool palette — position configurable in Settings (v1.1)
        if (!focusMode) {
            when (toolbarPos) {
                ToolbarPosition.TOP -> Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 64.dp)
                ) {
                    ToolPalette(
                        tool = tool,
                        fingersDraw = fingersDraw,
                        onTool = vm::selectTool,
                        onPen = vm::selectPen,
                        onColor = vm::selectColor,
                        onWidth = vm::selectWidth,
                        onFingersDraw = vm::toggleFingersDraw,
                        onShapeKind = vm::setShapeKind,
                        onHlOpacity = vm::setHlOpacity
                    )
                }
                ToolbarPosition.BOTTOM -> Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                ) {
                    ToolPalette(
                        tool = tool,
                        fingersDraw = fingersDraw,
                        onTool = vm::selectTool,
                        onPen = vm::selectPen,
                        onColor = vm::selectColor,
                        onWidth = vm::selectWidth,
                        onFingersDraw = vm::toggleFingersDraw,
                        onShapeKind = vm::setShapeKind,
                        onHlOpacity = vm::setHlOpacity
                    )
                }
                ToolbarPosition.LEFT -> Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 8.dp)
                ) {
                    ToolPalette(
                        tool = tool,
                        fingersDraw = fingersDraw,
                        onTool = vm::selectTool,
                        onPen = vm::selectPen,
                        onColor = vm::selectColor,
                        onWidth = vm::selectWidth,
                        onFingersDraw = vm::toggleFingersDraw,
                        onShapeKind = vm::setShapeKind,
                        onHlOpacity = vm::setHlOpacity,
                        vertical = true,
                        popoverOnEnd = true
                    )
                }
                ToolbarPosition.RIGHT -> Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp)
                ) {
                    ToolPalette(
                        tool = tool,
                        fingersDraw = fingersDraw,
                        onTool = vm::selectTool,
                        onPen = vm::selectPen,
                        onColor = vm::selectColor,
                        onWidth = vm::selectWidth,
                        onFingersDraw = vm::toggleFingersDraw,
                        onShapeKind = vm::setShapeKind,
                        onHlOpacity = vm::setHlOpacity,
                        vertical = true,
                        popoverOnEnd = false
                    )
                }
            }
        } else {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                androidx.compose.material3.SmallFloatingActionButton(onClick = { focusMode = false }) {
                    Icon(Icons.Filled.Visibility, contentDescription = stringResource(R.string.focus_mode))
                }
            }
        }

        // ------------------------------------------------ v2.0 (spec 5.3)
        // PDF text-selection 5-action contextual menu, anchored to the
        // selection and clamped so it never leaves the screen.
        val sel = textSel
        if (sel != null) {
            TextSelectionMenu(
                anchorX = sel.anchorX,
                anchorY = sel.anchorY,
                text = sel.text,
                onCopy = { vm.selectionCopyText(sel) },
                onTranslate = { vm.selectionTranslate(sel) },
                onSearchWeb = { vm.selectionSearchWeb(sel) },
                onHighlight = { vm.selectionHighlight(sel) },
                onUnderline = { vm.selectionUnderline(sel) },
                onDismiss = { vm.clearTextSelection() },
                modifier = Modifier.align(Alignment.TopStart)
            )
        }

        // phone: pages bottom sheet
        if (showPages && pages.isNotEmpty()) {
            PageSidebar(
                expanded = false,
                pages = pages,
                currentIndex = pageIndex,
                thumbs = pageThumbs,
                onThumbRequest = vm::requestPageThumb,
                onOpenPage = { vm.openPageAt(it); showPages = false },
                onAdd = { vm.addPageAndOpen() },
                onDuplicate = { vm.duplicateCurrentPage() },
                onDelete = { vm.deleteCurrentPage() },
                onMove = { vm.moveCurrentPage(it) },
                onOpenSettings = { showPageSettings = true },
                onDismiss = { showPages = false }
            )
        }

        if (showPageSettings && session != null) {
            PageSettingsSheet(
                page = session.page,
                onPaperPick = { paper, bg -> vm.setPagePaper(paper, bg) },
                onSizePick = { vm.resizeCurrentPage(it) },
                onDismiss = { showPageSettings = false }
            )
        }

        // text composer dialog
        if (showTextComposer) {
            var text by remember { mutableStateOf("") }
            var link by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { vm.dismissTextComposer() },
                title = { Text(stringResource(R.string.tool_text)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            placeholder = { Text(stringResource(R.string.text_hint)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = link,
                            onValueChange = { link = it },
                            placeholder = { Text(stringResource(R.string.link_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.commitText(text, 18f, tool.colorArgb, link)
                    }) { Text(stringResource(R.string.ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { vm.dismissTextComposer() }) { Text(stringResource(R.string.action_cancel)) }
                }
            )
        }
    }
}

/**
 * v2.0 (spec 5.3): the 5-action text-selection menu — Copy, Translate,
 * Search web, Highlight, Underline. Anchored to the selection and clamped
 * to stay inside the pane (never off-screen, never under the top bar).
 */
@Composable
fun TextSelectionMenu(
    anchorX: Float,
    anchorY: Float,
    text: String,
    onCopy: () -> Unit,
    onTranslate: () -> Unit,
    onSearchWeb: () -> Unit,
    onHighlight: () -> Unit,
    onUnderline: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuSize by remember { mutableStateOf(Size.Zero) }
    var paneSize by remember { mutableStateOf(Size.Zero) }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { c ->
                paneSize = Size(c.size.width.toFloat(), c.size.height.toFloat())
            }
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            tonalElevation = 4.dp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .onGloballyPositioned { c ->
                    menuSize = Size(c.size.width.toFloat(), c.size.height.toFloat())
                }
                .offset {
                    val w = menuSize.width
                    val h = menuSize.height
                    val pw = paneSize.width
                    val ph = paneSize.height
                    if (pw <= 0f || w <= 0f || h <= 0f) {
                        IntOffset.Zero
                    } else {
                        val x = (anchorX - w / 2f)
                            .coerceIn(8f, (pw - w - 8f).coerceAtLeast(8f))
                        var y = anchorY - h - 8f // above the selection
                        if (y < 64f) y = anchorY + 16f // no room -> below
                        y = y.coerceIn(64f, (ph - h - 16f).coerceAtLeast(64f))
                        IntOffset(x.roundToInt(), y.roundToInt())
                    }
                }
        ) {
            Row(
                Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MenuAction(Icons.Filled.ContentCopy, stringResource(R.string.action_copy), onCopy)
                MenuAction(Icons.Filled.Translate, stringResource(R.string.action_translate), onTranslate)
                MenuAction(Icons.Filled.Search, stringResource(R.string.action_web_search), onSearchWeb)
                MenuAction(Icons.Filled.Highlight, stringResource(R.string.action_highlight), onHighlight)
                MenuAction(Icons.Filled.FormatUnderlined, stringResource(R.string.action_underline), onUnderline)
                MenuAction(Icons.Filled.Done, stringResource(R.string.action_done), onDismiss)
            }
        }
    }
}

@Composable
private fun MenuAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .size(width = 52.dp, height = 52.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
