package com.nanote.feature.folders

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanote.core.design.R as CommonR
import com.nanote.core.design.components.ConfirmDialog
import com.nanote.core.design.components.DocumentCard
import com.nanote.core.design.components.DocumentListItem
import com.nanote.core.design.components.EmptyStateView
import com.nanote.core.design.components.FolderCard
import com.nanote.core.design.components.FolderPickerDialog
import com.nanote.core.design.components.RenameDialog
import com.nanote.core.design.components.SectionHeader
import com.nanote.core.design.components.ViewModeToggle
import com.nanote.core.design.theme.Dimens
import com.nanote.core.model.Folder
import com.nanote.core.model.ViewMode
import com.nanote.feature.library.R as LibraryR

/**
 * Folder browser: breadcrumb navigation, nested subfolders, and the
 * folder's documents with full management (shared DocumentBrowser).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    onOpenFolder: (String) -> Unit,
    onOpenDocument: (String) -> Unit,
    onBackToRoot: () -> Unit,
    vm: FoldersViewModel = hiltViewModel()
) {
    val browser = vm.browser
    val currentFolder by vm.currentFolder.collectAsStateWithLifecycle()
    val path by vm.path.collectAsStateWithLifecycle()
    val subfolders by vm.subfolders.collectAsStateWithLifecycle()
    val documents by browser.documents.collectAsStateWithLifecycle()
    val viewMode by browser.viewMode.collectAsStateWithLifecycle()
    val selection by browser.selection.collectAsStateWithLifecycle()

    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Folder?>(null) }
    var deleteTarget by remember { mutableStateOf<Folder?>(null) }
    var moveFolderTarget by remember { mutableStateOf<Folder?>(null) }
    var disabledForMove by remember { mutableStateOf<Set<String>>(emptySet()) }
    var actionsTarget by remember { mutableStateOf<Folder?>(null) }

    val selectionMode = selection.isNotEmpty()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = currentFolder?.name
                                ?: stringResource(CommonR.string.nav_folders),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (path.size > 1) {
                            Text(
                                text = path.dropLast(1).joinToString(" / ") { it.name },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (vm.folderId != null) {
                        IconButton(onClick = {
                            val parent = currentFolder?.parentId
                            if (parent != null) onOpenFolder(parent) else onBackToRoot()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(LibraryR.string.back)
                            )
                        }
                    }
                },
                actions = {
                    ViewModeToggle(current = viewMode, onViewModeChange = browser::setViewMode)
                    IconButton(onClick = { showCreate = true }) {
                        Icon(
                            Icons.Filled.CreateNewFolder,
                            contentDescription = stringResource(R.string.new_folder)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = Dimens.paddingMd,
                end = Dimens.paddingMd,
                bottom = Dimens.paddingLg
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.paddingSm)
        ) {
            if (subfolders.isNotEmpty()) {
                item(key = "folders-header") {
                    SectionHeader(title = stringResource(R.string.subfolders))
                }
            }
            items(subfolders.size, key = { subfolders[it].id }) { index ->
                val folder = subfolders[index]
                FolderCard(
                    name = folder.name,
                    count = folder.totalCount,
                    onClick = { onOpenFolder(folder.id) },
                    onLongClick = { actionsTarget = folder }
                )
            }

            item(key = "docs-header") {
                SectionHeader(
                    title = stringResource(
                        if (vm.folderId == null) R.string.loose_documents
                        else R.string.documents_in_folder
                    )
                )
            }

            if (documents.isEmpty()) {
                item(key = "docs-empty") {
                    EmptyStateView(
                        icon = Icons.Filled.MenuBook,
                        title = stringResource(R.string.folder_empty_title),
                        subtitle = stringResource(R.string.folder_empty_subtitle),
                        modifier = Modifier.padding(vertical = Dimens.paddingLg)
                    )
                }
            } else {
                items(documents.size, key = { documents[it].id }) { index ->
                    val meta = documents[index]
                    if (viewMode == ViewMode.GRID) {
                        Row {
                            DocumentCard(
                                meta = meta,
                                selectionMode = selectionMode,
                                selected = meta.id in selection,
                                onClick = {
                                    if (selectionMode) browser.toggleSelection(meta.id)
                                    else onOpenDocument(meta.id)
                                },
                                onLongClick = {
                                    // Long-press inside folders falls back to selection.
                                    browser.toggleSelection(meta.id)
                                },
                                onToggleFavorite = { browser.toggleFavorite(meta) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        DocumentListItem(
                            meta = meta,
                            selectionMode = selectionMode,
                            selected = meta.id in selection,
                            onClick = {
                                if (selectionMode) browser.toggleSelection(meta.id)
                                else onOpenDocument(meta.id)
                            },
                            onLongClick = { browser.toggleSelection(meta.id) },
                            onToggleFavorite = { browser.toggleFavorite(meta) }
                        )
                    }
                }
            }
        }
    }

    // ----- Dialogs -----

    if (showCreate) {
        RenameDialog(
            initial = "",
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                showCreate = false
                vm.createFolder(name)
            },
            title = stringResource(R.string.new_folder),
            confirmText = stringResource(CommonR.string.create),
            hint = stringResource(R.string.folder_name_hint)
        )
    }

    renameTarget?.let { folder ->
        RenameDialog(
            initial = folder.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                vm.renameFolder(folder.id, name)
            },
            title = stringResource(CommonR.string.rename)
        )
    }

    deleteTarget?.let { folder ->
        ConfirmDialog(
            title = stringResource(R.string.delete_folder_title),
            message = stringResource(R.string.delete_folder_msg, folder.name),
            confirmText = stringResource(CommonR.string.delete),
            dismissText = stringResource(CommonR.string.cancel),
            onConfirm = {
                deleteTarget = null
                vm.deleteFolder(folder.id)
            },
            onDismiss = { deleteTarget = null }
        )
    }

    moveFolderTarget?.let { folder ->
        val tree by vm.folderTree.collectAsStateWithLifecycle()
        FolderPickerDialog(
            title = stringResource(R.string.move_folder_to),
            nodes = tree,
            onDismiss = { moveFolderTarget = null },
            onPick = { target ->
                moveFolderTarget = null
                vm.moveFolder(folder.id, target)
            },
            disabledIds = disabledForMove
        )
    }

    actionsTarget?.let { folder ->
        FolderActionsSheet(
            folder = folder,
            onDismiss = { actionsTarget = null },
            onRename = { actionsTarget = null; renameTarget = folder },
            onMove = {
                actionsTarget = null
                vm.subtreeOf(folder.id) { ids ->
                    disabledForMove = ids
                    moveFolderTarget = folder
                }
            },
            onDelete = { actionsTarget = null; deleteTarget = folder }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderActionsSheet(
    folder: Folder,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = folder.name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = Dimens.paddingLg, vertical = Dimens.paddingSm)
        )
        ListItem(
            headlineContent = { Text(stringResource(CommonR.string.rename)) },
            leadingContent = {
                Icon(
                    Icons.Outlined.DriveFileRenameOutline,
                    contentDescription = null
                )
            },
            modifier = Modifier.clickable { onDismiss(); onRename() }
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.move_folder_to)) },
            leadingContent = { Icon(Icons.Filled.DriveFileMove, contentDescription = null) },
            modifier = Modifier.clickable { onDismiss(); onMove() }
        )
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
            modifier = Modifier.clickable { onDismiss(); onDelete() }
        )
        Spacer(Modifier.height(Dimens.paddingLg))
    }
}
