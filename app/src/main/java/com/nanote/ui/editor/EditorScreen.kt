package com.nanote.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nanote.R
import com.nanote.ink.engine.EditorTool
import com.nanote.ink.engine.PenType
import com.nanote.ink.render.InkCanvasView

/**
 * The handwriting editor: low-latency canvas + chrome (spec 8).
 * Tablets get a permanent page sidebar; phones open it as a bottom sheet.
 */
@Composable
fun EditorScreen(
    notebookId: String,
    initialPageId: String,
    onBack: () -> Unit,
    vm: EditorViewModel = hiltViewModel()
) {
    val title by vm.title.collectAsState()
    val pages by vm.pagesFlow.collectAsState()
    val pageIndex by vm.pageIndex.collectAsState()
    val tool by vm.tool.collectAsState()
    val fingersDraw by vm.fingersDraw.collectAsState()
    val undoDepth by vm.undoDepth.collectAsState()
    val redoDepth by vm.redoDepth.collectAsState()
    val session = vm.session
    val pageThumbs by vm.pageThumbs.collectAsState()
    val showTextComposer by vm.showTextComposer.collectAsState()
    val urlOpen by vm.urlOpenEvent.collectAsState()
    val viewRef = remember { mutableStateOf<InkCanvasView?>(null) }
    var showPages by remember { mutableStateOf(false) }
    var showPageSettings by remember { mutableStateOf(false) }
    var focusMode by remember { mutableStateOf(false) }
    var hasSelection by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val expanded = LocalConfiguration.current.screenWidthDp >= 600

    LaunchedEffect(Unit) { vm.openInitialPage() }

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
            imagePicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            vm.imagePickerRequest.value = 0
        }
    }

    // flush on ON_STOP (spec: save on pause/stop)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.saveNowAndClose()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler { vm.saveNowAndClose(); onBack() }

    // theme surface color for the canvas background
    vm.surfaceArgb = MaterialTheme.colorScheme.surfaceVariant.toArgb()

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.fillMaxSize()) {
            // ------------------------------------------------ main area
            Box(Modifier.weight(1f)) {
                if (session != null) {
                    AndroidView(
                        factory = { ctx ->
                            InkCanvasView(ctx).also { v ->
                                viewRef.value = v
                                vm.canvasView = v
                                v.attach(session)
                            }
                        },
                        update = { v ->
                            viewRef.value = v
                            vm.canvasView = v
                            if (v.host == null) v.attach(session)
                            v.refitViewportIfNeeded()
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
                    IconButton(onClick = { vm.saveNowAndClose(); onBack() }) {
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
                    if (!expanded && pages.isNotEmpty()) {
                        IconButton(onClick = { showPages = true }) {
                            Icon(Icons.Filled.AutoStories, contentDescription = stringResource(R.string.pages_title))
                        }
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

                // page pill
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 60.dp)
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

                // selection contextual bar
                if (hasSelection) {
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 100.dp)) {
                        SelectionBar(
                            onCopy = { vm.copySelection() },
                            onPaste = { vm.pasteClipboard() },
                            onDelete = { vm.deleteSelection() },
                            onRecolor = { vm.recolorSelection(tool.colorArgb) },
                            onDone = { vm.finishSelection() }
                        )
                    }
                }

                // bottom tool palette
                if (!focusMode) {
                    Column(
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
                            onShapeKind = vm::setShapeKind
                        )
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
            }

            // permanent page sidebar on tablets
            if (expanded && pages.isNotEmpty()) {
                Box(Modifier.width(140.dp)) {
                    PageSidebar(
                        expanded = true,
                        pages = pages,
                        currentIndex = pageIndex,
                        thumbs = pageThumbs,
                        onThumbRequest = vm::requestPageThumb,
                        onOpenPage = { vm.openPageAt(it) },
                        onAdd = { vm.addPageAndOpen() },
                        onDuplicate = { vm.duplicateCurrentPage() },
                        onDelete = { vm.deleteCurrentPage() },
                        onMove = { vm.moveCurrentPage(it) },
                        onOpenSettings = { showPageSettings = true },
                        onDismiss = { }
                    )
                }
            }
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

@Composable
private fun PenChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ColorDot(argb: Int, selected: Boolean, onClick: () -> Unit) {
    val color = androidx.compose.ui.graphics.Color(argb)
    Box(
        Modifier
            .size(if (selected) 26.dp else 22.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}
