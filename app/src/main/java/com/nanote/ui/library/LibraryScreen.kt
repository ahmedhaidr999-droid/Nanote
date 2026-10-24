package com.nanote.ui.library

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nanote.R
import com.nanote.domain.model.Notebook
import com.nanote.domain.model.SortOrder
import java.text.DateFormat
import java.util.Date

/**
 * Library: grid/list with real first-page thumbnails, folder breadcrumbs,
 * search, sort, quick-switch recents strip, multi-select with a contextual
 * bar, pinned favorites (spec 8).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    folderId: String?,
    onOpenNotebook: (String) -> Unit,
    onOpenFolder: (String?) -> Unit,
    vm: LibraryViewModel = hiltViewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val importPdfLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            val name = queryDisplayName(context, uri)
            if (bytes != null) vm.importPdf(bytes, name) { id -> if (id != null) onOpenNotebook(id) }
        }
    }
    val importZipLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) vm.importArchive(bytes) { }
        }
    }
    val importImagesLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val list = uris.mapNotNull { u ->
                context.contentResolver.openInputStream(u)?.use { it.readBytes() }
            }
            if (list.isNotEmpty()) vm.importImages(list) { }
        }
    }
    val notebooks by vm.notebooks.collectAsState()
    val folders by vm.folders.collectAsState()
    val recents by vm.recents.collectAsState()
    val crumbs by vm.breadcrumbs.collectAsState()
    val sort by vm.sort.collectAsState()
    val grid by vm.viewModeGrid.collectAsState()
    val search by vm.search.collectAsState()
    val selected by vm.selectedIds.collectAsState()
    val tags by vm.tags.collectAsState()
    val tagFilter by vm.tagFilter.collectAsState()
    val tagged by vm.taggedNotebooks.collectAsState()
    var addTagTarget by remember { mutableStateOf<Notebook?>(null) }

    var fabOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Notebook?>(null) }
    var moveTarget by remember { mutableStateOf<Notebook?>(null) }
    var deleteTarget by remember { mutableStateOf<Notebook?>(null) }
    var searchActive by remember { mutableStateOf(false) }

    val folderVm: FolderListViewModel = hiltViewModel()

    Scaffold(
        topBar = {
            if (selected.isNotEmpty()) {
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.selected_count_pl, selected.size, selected.size)) },
                    navigationIcon = {
                        IconButton(onClick = { vm.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.trashSelected() }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        if (searchActive) {
                            OutlinedTextField(
                                value = search,
                                onValueChange = vm::setSearch,
                                placeholder = { Text(stringResource(R.string.search_hint)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else if (crumbs.isEmpty()) {
                            Text(stringResource(R.string.nav_library))
                        } else {
                            Text(crumbs.last().name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = {
                        if (crumbs.isNotEmpty()) {
                            IconButton(onClick = {
                                val parent = crumbs.last().parentId
                                onOpenFolder(parent)
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_to_library))
                            }
                        }
                    },
                    actions = {
                        if (!searchActive) {
                            IconButton(onClick = { searchActive = true }) {
                                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search))
                            }
                        } else {
                            IconButton(onClick = { searchActive = false; vm.setSearch("") }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                            }
                        }
                        SortMenu(sort, vm::setSort)
                        IconButton(onClick = { vm.setViewMode(!grid) }) {
                            Icon(
                                if (grid) Icons.Filled.ViewList else Icons.Filled.GridView,
                                contentDescription = stringResource(R.string.action_view)
                            )
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (selected.isEmpty()) {
                Column(horizontalAlignment = Alignment.End) {
                    if (fabOpen) {
                        SmallFab(label = stringResource(R.string.action_new_folder)) {
                            fabOpen = false
                            vm.createFolder { }
                        }
                        SmallFab(label = stringResource(R.string.action_import_pdf)) {
                            fabOpen = false
                            importPdfLauncher.launch(arrayOf("application/pdf"))
                        }
                        SmallFab(label = stringResource(R.string.action_import_images)) {
                            fabOpen = false
                            importImagesLauncher.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }
                        SmallFab(label = stringResource(R.string.action_import_nanote)) {
                            fabOpen = false
                            importZipLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                        }
                        SmallFab(label = stringResource(R.string.action_new_note)) {
                            fabOpen = false
                            vm.createNotebookAndOpen(onOpenNotebook)
                        }
                    }
                    FloatingActionButton(onClick = { fabOpen = !fabOpen }) {
                        Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = stringResource(R.string.action_new_note))
                    }
                }
            }
        }
    ) { padding ->
        val shownNotebooks = if (tagFilter != null) tagged else notebooks
        val showEmpty = shownNotebooks.isEmpty() && folders.isEmpty() && recents.isEmpty() && tagFilter == null
        if (showEmpty) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                com.nanote.ui.components.EmptyState(
                    icon = Icons.AutoMirrored.Outlined.Notes,
                    title = stringResource(R.string.library_empty_title),
                    hint = stringResource(R.string.library_empty_hint)
                )
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                // recents strip (quick switching between recent notes)
                if (recents.isNotEmpty() && search.isBlank()) {
                    Text(
                        stringResource(R.string.recent_notes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                    )
                    LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(recents, key = { it.id }) { nb ->
                            RecentChip(nb, onOpenNotebook)
                        }
                    }
                }

                // tag row (filter chips)
                if (tags.isNotEmpty()) {
                    LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(tags, key = { it.id }) { t ->
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (tagFilter == t.id) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.secondaryContainer,
                                onClick = { vm.setTagFilter(if (tagFilter == t.id) null else t.id) }
                            ) {
                                Text(
                                    t.name,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (tagFilter == t.id) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // folders row
                if (folders.isNotEmpty() && tagFilter == null) {
                    LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(folders, key = { it.id }) { f ->
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                onClick = { onOpenFolder(f.id) }
                            ) {
                                Text(
                                    stringResource(R.string.folder_chip) + " " + f.name,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // notebooks grid or list
                if (grid) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(shownNotebooks, key = { it.id }) { nb ->
                            NotebookCard(
                                nb = nb,
                                selected = nb.id in selected,
                                folderVm = folderVm,
                                onClick = { onOpenNotebook(nb.id) },
                                onLongClick = { vm.toggleSelect(nb.id) },
                                onRename = { renameTarget = nb },
                                onDelete = { deleteTarget = nb },
                                onDuplicate = { vm.duplicateNotebook(nb.id) },
                                onPin = { vm.togglePin(nb.id) },
                                onAddTag = { addTagTarget = nb }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(shownNotebooks, key = { it.id }) { nb ->
                            NotebookRow(
                                nb = nb,
                                selected = nb.id in selected,
                                folderVm = folderVm,
                                onClick = { onOpenNotebook(nb.id) },
                                onLongClick = { vm.toggleSelect(nb.id) },
                                onRename = { renameTarget = nb },
                                onDelete = { deleteTarget = nb },
                                onDuplicate = { vm.duplicateNotebook(nb.id) },
                                onPin = { vm.togglePin(nb.id) },
                                onAddTag = { addTagTarget = nb }
                            )
                        }
                    }
                }
            }
        }
    }

    // rename dialog
    renameTarget?.let { nb ->
        var name by remember(nb.id) { mutableStateOf(nb.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.action_rename)) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameNotebook(nb.id, name.trim().ifBlank { nb.title })
                    renameTarget = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    // add-tag dialog
    addTagTarget?.let { nb ->
        var tagName by remember(nb.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addTagTarget = null },
            title = { Text(stringResource(R.string.action_add_tag)) },
            text = {
                OutlinedTextField(value = tagName, onValueChange = { tagName = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    if (tagName.isNotBlank()) vm.addTagToNotebook(nb.id, tagName.trim())
                    addTagTarget = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { addTagTarget = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    // delete (soft, restorable in Trash)
    deleteTarget?.let { nb ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_notebook_title)) },
            text = { Text(stringResource(R.string.delete_notebook_text)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.trashOne(nb.id)
                    deleteTarget = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookCard(
    nb: Notebook,
    selected: Boolean,
    folderVm: FolderListViewModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onPin: () -> Unit,
    onAddTag: () -> Unit
) {
    val thumb by folderVm.thumbnailFlow(nb.id).collectAsState(initial = null)
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(16.dp)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(nb.coverArgb))
        ) {
            thumb?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = nb.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            if (nb.pinned) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = stringResource(R.string.nav_favorites),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                        .size(20.dp)
                        .padding(2.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(nb.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    pluralStringResource(R.plurals.page_count_pl, nb.pageCount, nb.pageCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            NotebookMenu(onRename = onRename, onDelete = onDelete, onDuplicate = onDuplicate, onPin = onPin, onAddTag = onAddTag)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookRow(
    nb: Notebook,
    selected: Boolean,
    folderVm: FolderListViewModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onPin: () -> Unit,
    onAddTag: () -> Unit
) {
    val thumb by folderVm.thumbnailFlow(nb.id).collectAsState(initial = null)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(nb.coverArgb))
        ) {
            thumb?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = nb.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(nb.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                pluralStringResource(R.plurals.page_count_pl, nb.pageCount, nb.pageCount) + " · " +
                    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(nb.updatedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (nb.pinned) {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        NotebookMenu(onRename = onRename, onDelete = onDelete, onDuplicate = onDuplicate, onPin = onPin, onAddTag = onAddTag)
    }
}

@Composable
private fun NotebookMenu(
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onPin: () -> Unit,
    onAddTag: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more), modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_rename)) },
                onClick = { open = false; onRename() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_duplicate)) },
                onClick = { open = false; onDuplicate() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_pin)) },
                onClick = { open = false; onPin() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_add_tag)) },
                onClick = { open = false; onAddTag() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) },
                onClick = { open = false; onDelete() }
            )
        }
    }
}

@Composable
private fun SortMenu(current: SortOrder, onPick: (SortOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = stringResource(R.string.action_sort))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.sort_name)) },
            onClick = { open = false; onPick(SortOrder.NAME) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.sort_created)) },
            onClick = { open = false; onPick(SortOrder.CREATED) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.sort_modified)) },
            onClick = { open = false; onPick(SortOrder.MODIFIED) }
        )
    }
}

@Composable
private fun SmallFab(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        onClick = onClick,
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun RecentChip(nb: Notebook, onOpen: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = { onOpen(nb.id) }
    ) {
        Row(
            Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(Color(nb.coverArgb)))
            Spacer(Modifier.width(6.dp))
            Text(nb.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}


private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String {
    var name = "PDF"
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) {
                c.getString(idx)?.let { name = it.removeSuffix(".pdf") }
            }
        }
    } catch (e: Exception) {
    }
    return name
}
