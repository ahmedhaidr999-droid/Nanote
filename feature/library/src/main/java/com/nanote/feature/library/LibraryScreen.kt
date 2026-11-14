package com.nanote.feature.library

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanote.core.design.R as CommonR
import com.nanote.core.design.components.ConfirmDialog
import com.nanote.core.design.components.DocumentCard
import com.nanote.core.design.components.DocumentListItem
import com.nanote.core.design.components.EmptyStateView
import com.nanote.core.design.components.FolderPickerDialog
import com.nanote.core.design.components.RenameDialog
import com.nanote.core.design.components.SearchField
import com.nanote.core.design.components.SortMenuButton
import com.nanote.core.design.components.ViewModeToggle
import com.nanote.core.design.components.documentKindLabel
import com.nanote.core.design.theme.Dimens
import com.nanote.core.model.DocumentMeta
import com.nanote.core.model.LibraryFilter
import com.nanote.core.model.NoteKind
import com.nanote.core.model.NotebookSettings
import com.nanote.core.model.SortOption
import com.nanote.core.model.ViewMode
import com.nanote.feature.notebooks.NotebookFormDialog
import com.nanote.feature.notebooks.NotebookFormState
import java.text.DateFormat
import java.util.Date

/**
 * The document library: search, filter chips, grid/list views, sorting,
 * multi-selection with batch actions, context menus, rename / duplicate /
 * move / delete, notebook creation with full presentation settings and
 * PDF / .nanote import.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenDocument: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFolders: () -> Unit,
    vm: LibraryViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val browser = vm.browser

    val documents by browser.documents.collectAsStateWithLifecycle()
    val query by browser.query.collectAsStateWithLifecycle()
    val sortOption by browser.sortOption.collectAsStateWithLifecycle()
    val viewMode by browser.viewMode.collectAsStateWithLifecycle()
    val selection by browser.selection.collectAsStateWithLifecycle()
    val folderTree by browser.folderTree.collectAsStateWithLifecycle()
    val message by browser.message.collectAsStateWithLifecycle()
    val defaults by vm.notebookDefaults.collectAsStateWithLifecycle()

    val filter by browser.filter.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var showSearch by remember { mutableStateOf(false) }
    var showCreateNotebook by remember { mutableStateOf(false) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<DocumentMeta?>(null) }
    var editTarget by remember { mutableStateOf<DocumentMeta?>(null) }
    var deleteTarget by remember { mutableStateOf<DocumentMeta?>(null) }
    var moveTargetIds by remember { mutableStateOf<Set<String>?>(null) }
    var actionsTarget by remember { mutableStateOf<DocumentMeta?>(null) }
    var detailsTarget by remember { mutableStateOf<DocumentMeta?>(null) }
    var confirmBatchDelete by remember { mutableStateOf(false) }

    val deletedItemsMsg = stringResource(R.string.deleted_items)
    val movedItemsMsg = stringResource(R.string.moved_items)
    LaunchedEffect(message) {
        when (message) {
            R.string.deleted_items -> snackbar.showSnackbar(deletedItemsMsg)
            R.string.moved_items -> snackbar.showSnackbar(movedItemsMsg)
        }
        browser.consumeMessage()
    }

    val untitledLabel = stringResource(CommonR.string.untitled_note)
    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileName = DocumentFile.fromSingleUri(context, uri)?.name
            val title = fileName?.substringBeforeLast('.')?.trim()?.ifBlank { null }
                ?: untitledLabel
            browser.importPdf(uri, title) { id ->
                if (id == null) Toast.makeText(context, R.string.import_failed, Toast.LENGTH_SHORT).show()
                else onOpenDocument(id)
            }
        }
    }

    val nanotePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            browser.importNanote(uri) { id ->
                if (id == null) Toast.makeText(context, R.string.import_failed, Toast.LENGTH_SHORT).show()
                else onOpenDocument(id)
            }
        }
    }

    val selectionMode = selection.isNotEmpty()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (selectionMode) {
                SelectionTopBar(
                    count = selection.size,
                    onClose = { browser.clearSelection() },
                    onSelectAll = { browser.selectAll() },
                    onMove = { moveTargetIds = selection },
                    onFavorite = { browser.setFavoritesForSelection(true) },
                    onDelete = { confirmBatchDelete = true }
                )
            } else {
                TopAppBar(
                    title = { Text(libraryTitle(filter)) },
                    navigationIcon = {
                        if (showSearch) {
                            IconButton(onClick = { showSearch = false; browser.setQuery("") }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back)
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSearch = !showSearch; if (!showSearch) browser.setQuery("") }) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = stringResource(R.string.search)
                            )
                        }
                        ViewModeToggle(current = viewMode, onViewModeChange = browser::setViewMode)
                        SortMenuButton(current = sortOption, onSortSelected = browser::setSort)
                        LibraryOverflowMenu(
                            onNewNotebook = { showCreateNotebook = true },
                            onNewFolder = { showCreateFolder = true },
                            onImportPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
                            onImportNanote = { nanotePicker.launch(arrayOf("application/octet-stream")) },
                            onOpenFolders = onOpenFolders,
                            onOpenSettings = onOpenSettings
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AnimatedVisibility(visible = showSearch) {
                SearchField(
                    value = query,
                    onValueChange = browser::setQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.paddingMd, vertical = Dimens.paddingSm)
                )
            }

            if (!browser.isFilterLocked && !showSearch) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.paddingMd, vertical = Dimens.paddingXs),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.paddingSm)
                ) {
                    FilterChip(
                        selected = filter == LibraryFilter.ALL,
                        onClick = { browser.setFilter(LibraryFilter.ALL) },
                        label = { Text(stringResource(R.string.filter_all)) }
                    )
                    FilterChip(
                        selected = filter == LibraryFilter.NOTEBOOKS,
                        onClick = { browser.setFilter(LibraryFilter.NOTEBOOKS) },
                        label = { Text(stringResource(R.string.filter_notebooks)) }
                    )
                    FilterChip(
                        selected = filter == LibraryFilter.IMPORTED,
                        onClick = { browser.setFilter(LibraryFilter.IMPORTED) },
                        label = { Text(stringResource(R.string.filter_imported)) }
                    )
                }
            }

            val empty = documents.isEmpty()
            when {
                empty && query.isNotBlank() -> EmptyStateView(
                    icon = Icons.Outlined.SearchOff,
                    title = stringResource(R.string.no_results),
                    subtitle = stringResource(CommonR.string.search_hint),
                    modifier = Modifier.weight(1f)
                )

                empty -> EmptyStateView(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    title = stringResource(R.string.library_empty_title),
                    subtitle = stringResource(R.string.library_empty_subtitle),
                    modifier = Modifier.weight(1f)
                )

                viewMode == ViewMode.GRID -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = Dimens.paddingMd),
                    contentPadding = PaddingValues(vertical = Dimens.paddingSm),
                    verticalArrangement = Arrangement.spacedBy(Dimens.paddingMd),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.paddingSm)
                ) {
                    items(documents.size, key = { documents[it].id }) { index ->
                        val meta = documents[index]
                        DocumentCard(
                            meta = meta,
                            selectionMode = selectionMode,
                            selected = meta.id in selection,
                            onClick = {
                                if (selectionMode) browser.toggleSelection(meta.id)
                                else onOpenDocument(meta.id)
                            },
                            onLongClick = { actionsTarget = meta },
                            onToggleFavorite = { browser.toggleFavorite(meta) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = Dimens.paddingMd),
                    contentPadding = PaddingValues(vertical = Dimens.paddingSm),
                    verticalArrangement = Arrangement.spacedBy(Dimens.paddingSm)
                ) {
                    items(documents.size, key = { documents[it].id }) { index ->
                        val meta = documents[index]
                        DocumentListItem(
                            meta = meta,
                            selectionMode = selectionMode,
                            selected = meta.id in selection,
                            onClick = {
                                if (selectionMode) browser.toggleSelection(meta.id)
                                else onOpenDocument(meta.id)
                            },
                            onLongClick = { actionsTarget = meta },
                            onToggleFavorite = { browser.toggleFavorite(meta) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }

    // ----- Dialogs & sheets -----

    if (showCreateNotebook) {
        var form by remember(defaults) {
            mutableStateOf(NotebookFormState.fromRequestDefaults(defaults))
        }
        NotebookFormDialog(
            state = form,
            onStateChange = { form = it },
            onDismiss = { showCreateNotebook = false },
            onConfirm = {
                showCreateNotebook = false
                browser.createNotebook(form.toRequest(null)) { onOpenDocument(it) }
            },
            dialogTitle = stringResource(R.string.new_notebook)
        )
    }

    if (showCreateFolder) {
        RenameDialog(
            initial = "",
            onDismiss = { showCreateFolder = false },
            onConfirm = { name ->
                showCreateFolder = false
                vm.createFolder(name)
            },
            title = stringResource(R.string.new_folder),
            confirmText = stringResource(CommonR.string.create),
            hint = stringResource(R.string.folder_name_hint)
        )
    }

    renameTarget?.let { target ->
        RenameDialog(
            initial = target.title,
            onDismiss = { renameTarget = null },
            onConfirm = { title ->
                renameTarget = null
                browser.rename(target.id, title)
            },
            title = stringResource(CommonR.string.rename)
        )
    }

    editTarget?.let { target ->
        var form by remember(target) {
            mutableStateOf(
                NotebookFormState(
                    title = target.title,
                    coverColor = target.coverColor,
                    icon = target.icon,
                    pageTemplate = target.pageTemplate,
                    pageOrientation = target.pageOrientation,
                    pageSize = target.pageSize
                )
            )
        }
        NotebookFormDialog(
            state = form,
            onStateChange = { form = it },
            onDismiss = { editTarget = null },
            onConfirm = {
                editTarget = null
                browser.updateNotebookSettings(
                    target.id,
                    NotebookSettings(
                        title = form.title.trim().ifBlank { null },
                        coverColor = form.coverColor,
                        icon = form.icon,
                        pageTemplate = form.pageTemplate,
                        pageOrientation = form.pageOrientation,
                        pageSize = form.pageSize
                    )
                )
            },
            dialogTitle = stringResource(R.string.notebook_settings),
            confirmLabel = stringResource(CommonR.string.confirm)
        )
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.delete_document_title),
            message = stringResource(R.string.delete_document_msg, target.title),
            confirmText = stringResource(CommonR.string.delete),
            dismissText = stringResource(CommonR.string.cancel),
            onConfirm = {
                deleteTarget = null
                browser.deleteIds(setOf(target.id))
            },
            onDismiss = { deleteTarget = null }
        )
    }

    if (confirmBatchDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_documents_title),
            message = stringResource(R.string.delete_documents_msg, selection.size),
            confirmText = stringResource(CommonR.string.delete),
            dismissText = stringResource(CommonR.string.cancel),
            onConfirm = { confirmBatchDelete = false; browser.deleteSelection() },
            onDismiss = { confirmBatchDelete = false }
        )
    }

    moveTargetIds?.let { ids ->
        FolderPickerDialog(
            title = stringResource(R.string.move_to_folder),
            nodes = folderTree,
            onDismiss = { moveTargetIds = null },
            onPick = { folderId ->
                moveTargetIds = null
                // Direct move for single or multi selection.
                if (ids == selection) {
                    browser.moveSelectionTo(folderId)
                }
            }
        )
    }

    actionsTarget?.let { meta ->
        DocumentActionsSheet(
            meta = meta,
            canReorder = sortOption == SortOption.CUSTOM,
            onDismiss = { actionsTarget = null },
            onOpen = { actionsTarget = null; onOpenDocument(meta.id) },
            onRename = { actionsTarget = null; renameTarget = meta },
            onDuplicate = { actionsTarget = null; browser.duplicate(meta.id) },
            onFavorite = { actionsTarget = null; browser.toggleFavorite(meta) },
            onMove = {
                actionsTarget = null
                browser.clearSelection()
                browser.toggleSelection(meta.id)
                moveTargetIds = setOf(meta.id)
            },
            onEditNotebook = {
                actionsTarget = null
                if (meta.kind == NoteKind.NOTE) editTarget = meta
            },
            onDetails = { actionsTarget = null; detailsTarget = meta },
            onDelete = { actionsTarget = null; deleteTarget = meta },
            onMoveUp = { browser.moveCustomOrder(meta.id, -1) },
            onMoveDown = { browser.moveCustomOrder(meta.id, +1) }
        )
    }

    detailsTarget?.let { meta ->
        DocumentDetailsSheet(meta = meta, onDismiss = { detailsTarget = null })
    }
}

@Composable
private fun libraryTitle(filter: LibraryFilter): String = when (filter) {
    LibraryFilter.ALL -> stringResource(R.string.library_title)
    LibraryFilter.NOTEBOOKS -> stringResource(R.string.filter_notebooks)
    LibraryFilter.IMPORTED -> stringResource(R.string.filter_imported)
    LibraryFilter.FAVORITES -> stringResource(CommonR.string.nav_favorites)
    LibraryFilter.RECENT -> stringResource(R.string.filter_recent)
    is LibraryFilter.InFolder -> stringResource(R.string.library_title)
    is LibraryFilter.ROOT -> stringResource(R.string.library_title)
}

@Composable
private fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onMove: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.paddingSm, vertical = Dimens.paddingXs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.clear_selection),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Text(
                text = stringResource(R.string.selected_count, count),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onSelectAll) {
                Icon(
                    Icons.Filled.SelectAll,
                    contentDescription = stringResource(R.string.select_all),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            IconButton(onClick = onMove) {
                Icon(
                    Icons.Filled.DriveFileMove,
                    contentDescription = stringResource(R.string.move_to_folder),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = stringResource(CommonR.string.favorite),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(CommonR.string.delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryOverflowMenu(
    onNewNotebook: () -> Unit,
    onNewFolder: () -> Unit,
    onImportPdf: () -> Unit,
    onImportNanote: () -> Unit,
    onOpenFolders: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.more_options)
            )
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.new_notebook)) },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = { expanded = false; onNewNotebook() }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.new_folder)) },
                leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                onClick = { expanded = false; onNewFolder() }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.import_pdf)) },
                leadingIcon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
                onClick = { expanded = false; onImportPdf() }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.import_nanote)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                onClick = { expanded = false; onImportNanote() }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(CommonR.string.nav_folders)) },
                leadingIcon = { Icon(Icons.Outlined.OpenInNew, contentDescription = null) },
                onClick = { expanded = false; onOpenFolders() }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(CommonR.string.nav_settings)) },
                leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                onClick = { expanded = false; onOpenSettings() }
            )
        }
    }
}

/** Contextual bottom sheet with all per-document actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentActionsSheet(
    meta: DocumentMeta,
    canReorder: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onFavorite: () -> Unit,
    onMove: () -> Unit,
    onEditNotebook: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = meta.title.ifBlank { stringResource(CommonR.string.untitled_note) },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Dimens.paddingLg, vertical = Dimens.paddingSm)
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.action_open)) },
            leadingContent = { Icon(Icons.Outlined.OpenInNew, contentDescription = null) },
            modifier = Modifier.clickableRow(onOpen)
        )
        ListItem(
            headlineContent = {
                Text(
                    stringResource(
                        if (meta.favorite) CommonR.string.unfavorite else CommonR.string.favorite
                    )
                )
            },
            leadingContent = {
                Icon(
                    if (meta.favorite) Icons.Outlined.StarBorder else Icons.Filled.Star,
                    contentDescription = null
                )
            },
            modifier = Modifier.clickableRow(onFavorite)
        )
        ListItem(
            headlineContent = { Text(stringResource(CommonR.string.rename)) },
            leadingContent = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
            modifier = Modifier.clickableRow(onRename)
        )
        ListItem(
            headlineContent = { Text(stringResource(CommonR.string.duplicate)) },
            leadingContent = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
            modifier = Modifier.clickableRow(onDuplicate)
        )
        if (meta.kind == NoteKind.NOTE) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.notebook_settings)) },
                leadingContent = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                modifier = Modifier.clickableRow(onEditNotebook)
            )
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.move_to_folder)) },
            leadingContent = { Icon(Icons.Filled.DriveFileMove, contentDescription = null) },
            modifier = Modifier.clickableRow(onMove)
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.details)) },
            leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
            modifier = Modifier.clickableRow(onDetails)
        )
        if (canReorder) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.move_up)) },
                leadingContent = { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = null) },
                modifier = Modifier.clickableRow { onDismiss(); onMoveUp() }
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.move_down)) },
                leadingContent = { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null) },
                modifier = Modifier.clickableRow { onDismiss(); onMoveDown() }
            )
        }
        ListItem(
            headlineContent = {
                Text(
                    stringResource(CommonR.string.delete),
                    color = MaterialTheme.colorScheme.error
                )
            },
            leadingContent = {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            modifier = Modifier.clickableRow { onDismiss(); onDelete() }
        )
        Spacer(Modifier.height(Dimens.paddingLg))
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

/** Metadata bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailsSheet(meta: DocumentMeta, onDismiss: () -> Unit) {
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.details),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = Dimens.paddingLg, vertical = Dimens.paddingSm)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.paddingLg)
                .padding(bottom = Dimens.paddingXl),
            verticalArrangement = Arrangement.spacedBy(Dimens.paddingSm)
        ) {
            DetailRow(stringResource(R.string.detail_title), meta.title)
            DetailRow(stringResource(R.string.detail_kind), documentKindLabel(meta))
            DetailRow(
                stringResource(R.string.detail_pages),
                meta.pageCount.toString()
            )
            DetailRow(
                stringResource(R.string.detail_created),
                dateFormat.format(Date(meta.createdAt))
            )
            DetailRow(
                stringResource(R.string.detail_modified),
                dateFormat.format(Date(meta.updatedAt))
            )
            if (meta.lastOpenedAt > 0) {
                DetailRow(
                    stringResource(R.string.detail_opened),
                    dateFormat.format(Date(meta.lastOpenedAt))
                )
            }
            if (meta.kind == NoteKind.NOTE) {
                DetailRow(
                    stringResource(R.string.detail_template),
                    templateNameOf(meta)
                )
                DetailRow(
                    stringResource(R.string.detail_size),
                    pageSizeNameOf(meta)
                )
            }
        }
    }
}

@Composable
private fun templateNameOf(meta: DocumentMeta): String =
    com.nanote.feature.notebooks.templateLabel(meta.pageTemplate)

@Composable
private fun pageSizeNameOf(meta: DocumentMeta): String = when (meta.pageSize) {
    com.nanote.core.model.PageSize.A4 -> stringResource(com.nanote.feature.notebooks.R.string.size_a4)
    com.nanote.core.model.PageSize.A5 -> stringResource(com.nanote.feature.notebooks.R.string.size_a5)
    com.nanote.core.model.PageSize.LETTER -> stringResource(com.nanote.feature.notebooks.R.string.size_letter)
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(Dimens.paddingMd))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2
        )
    }
}
