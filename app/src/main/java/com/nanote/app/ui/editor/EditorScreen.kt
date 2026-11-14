package com.nanote.app.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanote.app.R
import com.nanote.app.core.canvas.CanvasView
import com.nanote.app.ui.editor.menus.AiSheet
import com.nanote.app.ui.editor.menus.MoreMenu
import com.nanote.app.ui.editor.menus.TextContextMenu
import com.nanote.app.ui.editor.menus.ZoomPopover
import com.nanote.app.ui.editor.toolbar.NanoteToolbar
import com.nanote.app.ui.editor.toolbar.ToolbarCustomizeSheet

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

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // ---------------- top bar ----------------
        EditorTopBar(
            title = title.ifBlank { stringResource(R.string.untitled_note) },
            pane = pane,
            splitEnabled = splitEnabled,
            onBack = onBack,
            onToggleSplit = { vm.toggleSplit() }
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
                        modifier = Modifier.weight((1f - splitRatio).coerceIn(0.25f, 0.75f)).fillMaxHeight()
                    )
                }
            } else {
                EditorPane(
                    vm = vm,
                    paneKey = EditorViewModel.KEY_MAIN,
                    pane = pane,
                    onOpenSettings = onOpenSettings,
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
    onBack: () -> Unit,
    onToggleSplit: () -> Unit
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
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    stringResource(R.string.page_indicator, currentPage + 1, pane.pageCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
    var aiState by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // (text, translateMode)
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
    val exportPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { vm.exportDocument(it, asPdf = true) { ok ->
            android.widget.Toast.makeText(context, context.getString(if (ok) R.string.export_done else R.string.export_failed), android.widget.Toast.LENGTH_SHORT).show()
        } }
    }
    val exportNanoteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { vm.exportDocument(it, asPdf = false) { ok ->
            android.widget.Toast.makeText(context, context.getString(if (ok) R.string.export_done else R.string.export_failed), android.widget.Toast.LENGTH_SHORT).show()
        } }
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

        // ---------------- object selection chip menu ----------------
        val objectMenu by pane.objectMenu.collectAsStateWithLifecycle()
        if (objectMenu) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
            ) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { pane.duplicateSelection() }) {
                        Icon(Icons.Outlined.ContentCopy, stringResource(R.string.duplicate))
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
            onTranslate = { text -> aiState = text to true },
            onSearchWeb = { text ->
                runCatching {
                    val url = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(text, "UTF-8")
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                }
            },
            onAskAi = { text -> aiState = text to false },
            onCopied = {
                android.widget.Toast.makeText(context, context.getString(R.string.copied), android.widget.Toast.LENGTH_SHORT).show()
            }
        )

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
            onCustomize = { showCustomize = true },
            onAskAi = { aiState = "" to false }
        )

        // ---------------- popovers / sheets ----------------
        if (showZoom) {
            ZoomPopover(pane = pane, onDismiss = { showZoom = false })
        }
        if (showMore) {
            MoreMenu(
                pane = pane,
                onDismiss = { showMore = false },
                onExportPdf = { showMore = false; exportPdfLauncher.launch(titleOf(pane, vm)) },
                onExportNanote = { showMore = false; exportNanoteLauncher.launch(titleOf(pane, vm) + ".nanote") },
                onSplitToggle = { showMore = false; vm.toggleSplit() },
                onCustomize = { showMore = false; showCustomize = true },
                onSettings = { showMore = false; onOpenSettings() }
            )
        }
        if (showCustomize) {
            ToolbarCustomizeSheet(settings = vm.settings, onDismiss = { showCustomize = false })
        }
        aiState?.let { (text, translateMode) ->
            AiSheet(
                initialText = text,
                translateMode = translateMode,
                aiService = vm.aiService,
                settings = vm.settings,
                onDismiss = { aiState = null }
            )
        }

        // ---------------- insert/edit text dialog ----------------
        val insertRequest by pane.insertTextRequest.collectAsStateWithLifecycle()
        insertRequest?.let { req ->
            InsertTextDialog(
                initial = req.existing?.text ?: "",
                sticky = req.sticky,
                onDismiss = { pane.cancelInsertText() },
                onConfirm = { txt, size, color ->
                    pane.commitInsertText(req, txt, size, color)
                }
            )
        }
    }
}

private fun titleOf(pane: PaneController, vm: EditorViewModel): String =
    vm.noteTitle.value.ifBlank { "nanote" }


@Composable
private fun InsertTextDialog(
    initial: String,
    sticky: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, Float, Long) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val settingsSize = 18f
    val settingsColor = 0xFF0F172A
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (sticky) stringResource(R.string.tool_sticky) else stringResource(R.string.tool_text)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.insert_text_hint)) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text, settingsSize, settingsColor) }) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
