package com.nanote.app.ui.home

import android.content.Intent
import android.graphics.Bitmap
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanote.app.R
import com.nanote.app.core.data.repo.NoteMeta
import com.nanote.app.core.model.NoteKind
import com.nanote.app.ui.common.ConfirmDialog
import com.nanote.app.ui.common.EmptyStateView
import com.nanote.app.ui.common.NanoteCard
import com.nanote.app.ui.common.NanoteIconButton
import com.nanote.app.ui.common.NanotePrimaryButton
import com.nanote.app.ui.common.NanoteSecondaryButton
import com.nanote.app.ui.common.RenameDialog
import com.nanote.app.ui.common.SectionHeader
import com.nanote.app.ui.theme.Dimens
import com.nanote.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Library home: header, search, quick actions (new note / import PDF / import
 * .nanote), favorites + recent + all-notes grid with context menus, export
 * via share sheet, and empty states.
 */
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onOpenNote: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val notes by vm.notes.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()

    val searching = vm.query.value.isNotBlank()

    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<NoteMeta?>(null) }
    var deleteTarget by remember { mutableStateOf<NoteMeta?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileName = DocumentFile.fromSingleUri(context, uri)?.name
            val title = fileName
                ?.substringBeforeLast('.')
                ?.trim()
                ?.ifBlank { null }
                ?: context.getString(R.string.untitled_note)
            vm.importPdf(uri, title) { id ->
                if (id == null) toast(context.getString(R.string.import_failed))
                else onOpenNote(id)
            }
        }
    }

    val nanotePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.importNanote(uri) { id ->
                if (id == null) toast(context.getString(R.string.import_failed))
                else onOpenNote(id)
            }
        }
    }

    fun shareExported(meta: NoteMeta, asPdf: Boolean) {
        scope.launch {
            val file = vm.exportFile(meta, asPdf)
            if (file == null || !file.exists()) {
                toast(context.getString(R.string.export_failed))
                return@launch
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val mime = if (asPdf) "application/pdf" else "application/octet-stream"
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, file.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(chooser) }
                .onFailure {
                    AppLog.e("HomeScreen", "share export failed: ${it.message}", it)
                    toast(context.getString(R.string.export_failed))
                }
        }
    }

    // Local grid-section builder (titles are resolved in composition above the
    // grid lambda, which itself is not composable).
    fun LazyGridScope.noteSection(
        sectionTitle: String,
        list: List<NoteMeta>,
        keyPrefix: String
    ) {
        if (sectionTitle.isNotEmpty()) {
            item(key = "$keyPrefix-header", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(title = sectionTitle)
            }
        }
        items(list, key = { "$keyPrefix-${it.id}" }) { meta ->
            NoteCardItem(
                meta = meta,
                vm = vm,
                menuOpen = menuFor == meta.id,
                onOpenMenu = { open -> menuFor = if (open) meta.id else null },
                onOpenNote = onOpenNote,
                onRename = { renameTarget = it },
                onDelete = { deleteTarget = it },
                onExport = { m, asPdf -> shareExported(m, asPdf) }
            )
        }
    }

    val favoritesTitle = stringResource(R.string.favorites)
    val recentTitle = stringResource(R.string.recent)
    val allNotesTitle = stringResource(R.string.all_notes)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ----- Header -----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = Dimens.paddingMd,
                        end = Dimens.paddingMd,
                        top = Dimens.paddingSm,
                        bottom = Dimens.paddingSm
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(Dimens.radiusMd))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(Dimens.paddingMd))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.weight(1f))
                NanoteIconButton(
                    icon = Icons.Outlined.Settings,
                    contentDesc = stringResource(R.string.settings),
                    onClick = onOpenSettings
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

            // ----- Search -----
            OutlinedTextField(
                value = vm.query.value,
                onValueChange = vm::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = Dimens.paddingMd,
                        end = Dimens.paddingMd,
                        top = Dimens.paddingSm,
                        bottom = Dimens.paddingSm
                    ),
                shape = RoundedCornerShape(Dimens.radiusLg),
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                placeholder = {
                    Text(text = stringResource(R.string.search_notes))
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )

            // ----- Quick actions -----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = Dimens.paddingMd,
                        end = Dimens.paddingMd,
                        top = Dimens.paddingXs,
                        bottom = Dimens.paddingSm
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.paddingSm)
            ) {
                NanotePrimaryButton(
                    text = stringResource(R.string.new_note),
                    onClick = { showCreate = true },
                    leadingIcon = Icons.Outlined.Add,
                    modifier = Modifier.weight(1f)
                )
                NanoteSecondaryButton(
                    text = stringResource(R.string.import_pdf),
                    onClick = { pdfPicker.launch(arrayOf("application/pdf")) },
                    leadingIcon = Icons.Outlined.UploadFile,
                    modifier = Modifier.weight(1f)
                )
                NanoteIconButton(
                    icon = Icons.Outlined.FileUpload,
                    contentDesc = stringResource(R.string.import_nanote),
                    onClick = { nanotePicker.launch(arrayOf("application/octet-stream")) }
                )
            }

            // ----- Content -----
            val emptyShelf = !searching && notes.isEmpty()
            val noResults = searching && notes.isEmpty()

            when {
                emptyShelf -> EmptyStateView(
                    icon = Icons.Outlined.EditNote,
                    title = stringResource(R.string.empty_title),
                    subtitle = stringResource(R.string.empty_subtitle),
                    modifier = Modifier.weight(1f)
                )

                noResults -> EmptyStateView(
                    icon = Icons.Outlined.SearchOff,
                    title = stringResource(R.string.no_results),
                    subtitle = stringResource(R.string.search_notes),
                    modifier = Modifier.weight(1f)
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Dimens.paddingMd,
                        end = Dimens.paddingMd,
                        top = Dimens.paddingSm,
                        bottom = Dimens.paddingMd
                    ),
                    verticalArrangement = Arrangement.spacedBy(Dimens.paddingMd),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.paddingSm)
                ) {
                    if (!searching && favorites.isNotEmpty()) {
                        noteSection(favoritesTitle, favorites, "fav")
                    }
                    if (!searching && recent.isNotEmpty()) {
                        noteSection(recentTitle, recent, "rec")
                    }
                    noteSection(allNotesTitle, notes, "all")
                }
            }
        }
    }

    // ----- Dialogs -----

    if (showCreate) {
        RenameDialog(
            initial = "",
            onDismiss = { showCreate = false },
            onConfirm = { title ->
                showCreate = false
                vm.createNote(title) { id -> onOpenNote(id) }
            },
            title = stringResource(R.string.new_note),
            confirmText = stringResource(R.string.create_note),
            hint = stringResource(R.string.note_title_hint)
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
            title = stringResource(R.string.rename)
        )
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.delete_confirm_title),
            message = stringResource(R.string.delete_confirm_msg),
            confirmText = stringResource(R.string.delete),
            dismissText = stringResource(R.string.cancel),
            onConfirm = { vm.delete(target.id) },
            onDismiss = { deleteTarget = null }
        )
    }
}

/** Grid card for a single note: thumbnail, title, date, badges, favorite star. */
@Composable
private fun NoteCardItem(
    meta: NoteMeta,
    vm: HomeViewModel,
    menuOpen: Boolean,
    onOpenMenu: (Boolean) -> Unit,
    onOpenNote: (String) -> Unit,
    onRename: (NoteMeta) -> Unit,
    onDelete: (NoteMeta) -> Unit,
    onExport: (NoteMeta, Boolean) -> Unit
) {
    Box {
        NanoteCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onOpenNote(meta.id) },
            onLongClick = { onOpenMenu(true) }
        ) {
            NoteThumbnail(meta = meta, vm = vm)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dimens.paddingSm)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = meta.title.ifBlank { stringResource(R.string.untitled_note) },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = if (meta.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = stringResource(
                            if (meta.favorite) R.string.unfavorite else R.string.favorite
                        ),
                        tint = if (meta.favorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(Dimens.radiusSm))
                            .clickable { vm.toggleFavorite(meta) }
                            .padding(Dimens.paddingXs)
                    )
                }
                Spacer(Modifier.height(Dimens.paddingXs))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.paddingSm)
                ) {
                    Text(
                        text = DateUtils.getRelativeTimeSpanString(meta.updatedAt).toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(R.string.pages_count, meta.pageCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(50)
                            )
                            .padding(horizontal = Dimens.paddingSm, vertical = 2.dp)
                    )
                    if (meta.kind == NoteKind.PDF) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = stringResource(R.string.tool_pdf),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { onOpenMenu(false) }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename)) },
                leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                onClick = {
                    onOpenMenu(false)
                    onRename(meta)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.duplicate)) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                onClick = {
                    onOpenMenu(false)
                    vm.duplicate(meta.id)
                }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (meta.favorite) R.string.unfavorite else R.string.favorite
                        )
                    )
                },
                leadingIcon = {
                    Icon(
                        if (meta.favorite) Icons.Outlined.StarBorder else Icons.Filled.Star,
                        contentDescription = null
                    )
                },
                onClick = {
                    onOpenMenu(false)
                    vm.toggleFavorite(meta)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export_pdf)) },
                leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, contentDescription = null) },
                onClick = {
                    onOpenMenu(false)
                    onExport(meta, true)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export_nanote)) },
                leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                onClick = {
                    onOpenMenu(false)
                    onExport(meta, false)
                }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                onClick = {
                    onOpenMenu(false)
                    onDelete(meta)
                }
            )
        }
    }
}

/** Async first-page thumbnail with a soft placeholder while loading. */
@Composable
private fun NoteThumbnail(meta: NoteMeta, vm: HomeViewModel) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = meta.id) {
        value = withContext(Dispatchers.IO) {
            runCatching { vm.thumbnail(meta.id) }.getOrNull()
        }
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = meta.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(
                    RoundedCornerShape(
                        topStart = Dimens.radiusLg,
                        topEnd = Dimens.radiusLg
                    )
                )
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
