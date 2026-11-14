@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nanote.feature.home

import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanote.core.design.R as CommonR
import com.nanote.core.design.components.DocumentCard
import com.nanote.core.design.components.EmptyStateView
import com.nanote.core.design.components.FolderCard
import com.nanote.core.design.components.FolderPickerDialog
import com.nanote.core.design.components.RenameDialog
import com.nanote.core.design.components.SearchField
import com.nanote.core.design.components.SectionHeader
import com.nanote.core.design.components.composeColor
import com.nanote.core.design.components.imageVector
import com.nanote.core.design.theme.Dimens
import com.nanote.core.model.ActivityAction
import com.nanote.core.model.DocumentMeta
import com.nanote.core.model.NoteKind
import com.nanote.core.model.RecentActivityEntry
import com.nanote.feature.library.R as LibraryR
import com.nanote.feature.library.DocumentActionsSheet
import com.nanote.feature.notebooks.NotebookFormDialog
import com.nanote.feature.notebooks.NotebookFormState

/**
 * Home dashboard: branding + search, quick actions (new notebook / folder,
 * imports), recent documents, favorites, notebooks, folders and the
 * activity feed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDocument: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenNotebooks: () -> Unit,
    onOpenFolders: () -> Unit,
    onOpenFolder: (String) -> Unit,
    vm: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    val query by vm.query.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val searchFolders by vm.searchFolders.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val notebooks by vm.notebooks.collectAsStateWithLifecycle()
    val rootFolders by vm.rootFolders.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val folderTree by vm.folderTree.collectAsStateWithLifecycle()
    val defaults by vm.notebookDefaults.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()

    var showSearch by remember { mutableStateOf(false) }
    var showCreateNotebook by remember { mutableStateOf(false) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var actionsTarget by remember { mutableStateOf<DocumentMeta?>(null) }
    var renameTarget by remember { mutableStateOf<DocumentMeta?>(null) }
    var deleteTarget by remember { mutableStateOf<DocumentMeta?>(null) }
    var moveTargetIds by remember { mutableStateOf<Set<String>?>(null) }

    val searching = query.isNotBlank()

    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileName = DocumentFile.fromSingleUri(context, uri)?.name
            val title = fileName?.substringBeforeLast('.')?.trim()?.ifBlank { null }
                ?: stringResource(CommonR.string.untitled_note)
            vm.importPdf(uri, title) { id ->
                if (id == null) Toast.makeText(context, R.string.import_failed, Toast.LENGTH_SHORT).show()
                else onOpenDocument(id)
            }
        }
    }

    val nanotePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.importNanote(uri) { id ->
                if (id == null) Toast.makeText(context, R.string.import_failed, Toast.LENGTH_SHORT).show()
                else onOpenDocument(id)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(Dimens.radiusMd))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(Modifier.width(Dimens.paddingMd))
                        Text(
                            text = stringResource(CommonR.string.app_name),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch; if (!showSearch) vm.setQuery("") }) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = stringResource(LibraryR.string.search)
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(CommonR.string.nav_settings)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (showSearch) {
                SearchField(
                    value = query,
                    onValueChange = vm::setQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.paddingMd, vertical = Dimens.paddingXs)
                )
            }

            if (importing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.paddingMd),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            if (!searching) {
                // ---- Quick actions ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.paddingMd, vertical = Dimens.paddingSm),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.paddingSm)
                ) {
                    QuickAction(
                        icon = Icons.Filled.Add,
                        label = stringResource(LibraryR.string.new_notebook),
                        onClick = { showCreateNotebook = true },
                        modifier = Modifier.weight(1f)
                    )
                    QuickAction(
                        icon = Icons.Filled.CreateNewFolder,
                        label = stringResource(LibraryR.string.new_folder),
                        onClick = { showCreateFolder = true },
                        modifier = Modifier.weight(1f)
                    )
                    QuickAction(
                        icon = Icons.Outlined.UploadFile,
                        label = stringResource(LibraryR.string.import_pdf),
                        onClick = { pdfPicker.launch(arrayOf("application/pdf")) },
                        modifier = Modifier.weight(1f)
                    )
                    QuickAction(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        label = stringResource(LibraryR.string.import_nanote),
                        onClick = { nanotePicker.launch(arrayOf("application/octet-stream")) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            val totallyEmpty = !searching && recent.isEmpty() && notebooks.isEmpty() &&
                rootFolders.isEmpty() && activity.isEmpty()

            when {
                totallyEmpty -> EmptyStateView(
                    icon = Icons.Outlined.EditNote,
                    title = stringResource(R.string.home_empty_title),
                    subtitle = stringResource(R.string.home_empty_subtitle),
                    modifier = Modifier.weight(1f)
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Dimens.paddingMd,
                        end = Dimens.paddingMd,
                        bottom = Dimens.paddingLg
                    ),
                    verticalArrangement = Arrangement.spacedBy(Dimens.paddingSm)
                ) {
                    if (searching) {
                        item(key = "search-docs-header") {
                            SectionHeader(
                                title = stringResource(R.string.search_documents_section),
                                actionText = stringResource(R.string.see_all),
                                onAction = onOpenLibrary
                            )
                        }
                        val results = searchResults.orEmpty()
                        if (results.isEmpty() && searchFolders.isEmpty()) {
                            item(key = "search-empty") {
                                EmptyStateView(
                                    icon = Icons.Outlined.SearchOff,
                                    title = stringResource(LibraryR.string.no_results),
                                    subtitle = stringResource(CommonR.string.search_hint),
                                    modifier = Modifier.padding(vertical = Dimens.paddingXl)
                                )
                            }
                        }
                        items(results.size, key = { "doc-${results[it].id}" }) { index ->
                            val meta = results[index]
                            HomeDocumentRow(
                                meta = meta,
                                onClick = { onOpenDocument(meta.id) },
                                onLongClick = { actionsTarget = meta }
                            )
                        }
                        if (searchFolders.isNotEmpty()) {
                            item(key = "search-folders-header") {
                                SectionHeader(title = stringResource(R.string.search_folders_section))
                            }
                            items(searchFolders.size, key = { "folder-${searchFolders[it].id}" }) { index ->
                                val folder = searchFolders[index]
                                FolderCard(
                                    name = folder.name,
                                    count = folder.totalCount,
                                    onClick = { onOpenFolder(folder.id) },
                                    onLongClick = { }
                                )
                            }
                        }
                    } else {
                        if (recent.isNotEmpty()) {
                            item(key = "recent-header") {
                                SectionHeader(
                                    title = stringResource(R.string.home_recent),
                                    actionText = stringResource(R.string.see_all),
                                    onAction = onOpenLibrary
                                )
                            }
                            item(key = "recent-row") {
                                HorizontalCards(
                                    docs = recent,
                                    onOpen = onOpenDocument,
                                    onLongPress = { actionsTarget = it }
                                )
                            }
                        }
                        if (favorites.isNotEmpty()) {
                            item(key = "fav-header") {
                                SectionHeader(title = stringResource(CommonR.string.nav_favorites))
                            }
                            item(key = "fav-row") {
                                HorizontalCards(
                                    docs = favorites,
                                    onOpen = onOpenDocument,
                                    onLongPress = { actionsTarget = it }
                                )
                            }
                        }
                        if (notebooks.isNotEmpty()) {
                            item(key = "nb-header") {
                                SectionHeader(
                                    title = stringResource(CommonR.string.nav_notebooks),
                                    actionText = stringResource(R.string.see_all),
                                    onAction = onOpenNotebooks
                                )
                            }
                            item(key = "nb-row") {
                                HorizontalCards(
                                    docs = notebooks,
                                    onOpen = onOpenDocument,
                                    onLongPress = { actionsTarget = it }
                                )
                            }
                        }
                        if (rootFolders.isNotEmpty()) {
                            item(key = "folders-header") {
                                SectionHeader(
                                    title = stringResource(CommonR.string.nav_folders),
                                    actionText = stringResource(R.string.see_all),
                                    onAction = onOpenFolders
                                )
                            }
                            items(rootFolders.size, key = { rootFolders[it].id }) { index ->
                                val folder = rootFolders[index]
                                FolderCard(
                                    name = folder.name,
                                    count = folder.totalCount,
                                    onClick = { onOpenFolder(folder.id) },
                                    onLongClick = { }
                                )
                            }
                        }
                        if (activity.isNotEmpty()) {
                            item(key = "activity-header") {
                                SectionHeader(title = stringResource(R.string.home_activity))
                            }
                            items(activity.size, key = { "act-${activity[it].id}" }) { index ->
                                ActivityRow(entry = activity[index])
                            }
                        }
                    }
                }
            }
        }
    }

    // ----- Dialogs / sheets -----

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
                vm.createNotebook(form.toRequest(null)) { onOpenDocument(it) }
            },
            dialogTitle = stringResource(LibraryR.string.new_notebook)
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
            title = stringResource(LibraryR.string.new_folder),
            confirmText = stringResource(CommonR.string.create),
            hint = stringResource(LibraryR.string.folder_name_hint)
        )
    }

    renameTarget?.let { target ->
        RenameDialog(
            initial = target.title,
            onDismiss = { renameTarget = null },
            onConfirm = { title ->
                renameTarget = null
                vm.rename(target.id, title)
            },
            title = stringResource(CommonR.string.rename)
        )
    }

    deleteTarget?.let { target ->
        com.nanote.core.design.components.ConfirmDialog(
            title = stringResource(LibraryR.string.delete_document_title),
            message = stringResource(LibraryR.string.delete_document_msg, target.title),
            confirmText = stringResource(CommonR.string.delete),
            dismissText = stringResource(CommonR.string.cancel),
            onConfirm = {
                deleteTarget = null
                vm.deleteIds(setOf(target.id))
            },
            onDismiss = { deleteTarget = null }
        )
    }

    moveTargetIds?.let { ids ->
        FolderPickerDialog(
            title = stringResource(LibraryR.string.move_to_folder),
            nodes = folderTree,
            onDismiss = { moveTargetIds = null },
            onPick = { folderId ->
                moveTargetIds = null
                vm.moveIds(ids, folderId)
            }
        )
    }

    actionsTarget?.let { meta ->
        DocumentActionsSheet(
            meta = meta,
            canReorder = false,
            onDismiss = { actionsTarget = null },
            onOpen = { actionsTarget = null; onOpenDocument(meta.id) },
            onRename = { actionsTarget = null; renameTarget = meta },
            onDuplicate = { actionsTarget = null; vm.duplicate(meta.id) },
            onFavorite = { actionsTarget = null; vm.toggleFavorite(meta) },
            onMove = { actionsTarget = null; moveTargetIds = setOf(meta.id) },
            onEditNotebook = { actionsTarget = null },
            onDetails = { actionsTarget = null },
            onDelete = { actionsTarget = null; deleteTarget = meta },
            onMoveUp = { },
            onMoveDown = { }
        )
    }
}

@Composable
private fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Dimens.radiusLg),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Dimens.paddingMd, vertical = Dimens.paddingSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.paddingSm)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Horizontal strip of document cards (recent / favorites / notebooks). */
@Composable
private fun HorizontalCards(
    docs: List<DocumentMeta>,
    onOpen: (String) -> Unit,
    onLongPress: (DocumentMeta) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Dimens.paddingSm),
        contentPadding = PaddingValues(vertical = Dimens.paddingXs)
    ) {
        items(docs.size, key = { docs[it].id }) { index ->
            val meta = docs[index]
            Box(modifier = Modifier.width(148.dp)) {
                DocumentCard(
                    meta = meta,
                    selectionMode = false,
                    selected = false,
                    onClick = { onOpen(meta.id) },
                    onLongClick = { onLongPress(meta) },
                    onToggleFavorite = { }
                )
            }
        }
    }
}

@Composable
private fun HomeDocumentRow(
    meta: DocumentMeta,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                meta.title.ifBlank { stringResource(CommonR.string.untitled_note) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = { Text(meta.kind.name) },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(Dimens.radiusSm))
                    .background(meta.coverColor.composeColor()),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = meta.icon.imageVector(),
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(19.dp)
                )
            }
        },
        modifier = Modifier
            .clip(RoundedCornerShape(Dimens.radiusMd))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickableRow(onClick, onLongClick)
    )
}

/** One activity-feed row: action icon, sentence and relative time. */
@Composable
fun ActivityRow(entry: RecentActivityEntry) {
    ListItem(
        headlineContent = {
            Text(
                text = activityText(entry),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = DateUtils.getRelativeTimeSpanString(entry.occurredAt).toString(),
                style = MaterialTheme.typography.labelSmall
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(Dimens.radiusSm))
                    .background(entry.coverColor.composeColor().copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = activityIcon(entry.action),
                    contentDescription = null,
                    tint = entry.coverColor.composeColor(),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    )
}

@Composable
private fun activityText(entry: RecentActivityEntry): String = when (entry.action) {
    ActivityAction.CREATED -> stringResource(R.string.activity_created, entry.documentTitle)
    ActivityAction.OPENED -> stringResource(R.string.activity_opened, entry.documentTitle)
    ActivityAction.RENAMED -> stringResource(R.string.activity_renamed, entry.documentTitle)
    ActivityAction.DUPLICATED -> stringResource(R.string.activity_duplicated, entry.documentTitle)
    ActivityAction.IMPORTED -> stringResource(R.string.activity_imported, entry.documentTitle)
    ActivityAction.MOVED -> stringResource(R.string.activity_moved, entry.documentTitle)
    ActivityAction.FAVORITED -> stringResource(R.string.activity_favorited, entry.documentTitle)
}

private fun activityIcon(action: ActivityAction): androidx.compose.ui.graphics.vector.ImageVector =
    when (action) {
        ActivityAction.CREATED -> Icons.Filled.Add
        ActivityAction.OPENED -> Icons.AutoMirrored.Filled.MenuBook
        ActivityAction.RENAMED -> Icons.Outlined.DriveFileRenameOutline
        ActivityAction.DUPLICATED -> Icons.Outlined.ContentCopy
        ActivityAction.IMPORTED -> Icons.Outlined.UploadFile
        ActivityAction.MOVED -> Icons.Filled.DriveFileMove
        ActivityAction.FAVORITED -> Icons.Filled.Star
    }

private fun Modifier.combinedClickableRow(
    onClick: () -> Unit,
    onLongClick: () -> Unit
): Modifier = this.combinedClickable(onClick = onClick, onLongClick = onLongClick)
