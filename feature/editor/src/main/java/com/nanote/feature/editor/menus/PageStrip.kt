package com.nanote.feature.editor.menus

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanote.feature.editor.PageThumb
import com.nanote.feature.editor.PaneController
import com.nanote.feature.editor.R
import com.nanote.core.model.PageSize
import com.nanote.core.model.PageTemplateKind
import com.nanote.core.design.theme.Dimens

/**
 * Vertical page rail with live thumbnails (§10):
 *  - tap a thumbnail → jump to that page,
 *  - long-press → page management menu (rename / duplicate / delete / reorder /
 *    template / size),
 *  - "+" at the end appends a page using the last page's template.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageStrip(pane: PaneController, modifier: Modifier = Modifier) {
    val thumbs by pane.pageThumbs.collectAsStateWithLifecycle()
    val currentPage by pane.currentPage.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<Int?>(null) }

    Surface(
        shape = RoundedCornerShape(Dimens.radiusLg),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 6.dp,
        modifier = modifier
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(thumbs.size, key = { thumbs[it].index }) { i ->
                val t = thumbs[i]
                PageThumbCell(
                    thumb = t,
                    selected = t.index == currentPage,
                    onClick = { pane.goToPage(t.index) },
                    onLongPress = { menuFor = t.index }
                )
            }
            item {
                IconButton(onClick = { pane.addPage() }) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = stringResource(R.string.add_page),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    menuFor?.let { page ->
        PageMenuPopup(
            pane = pane,
            page = page,
            onDismiss = { menuFor = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageThumbCell(
    thumb: PageThumb,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = 46.dp, height = 62.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(6.dp)
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongPress),
            contentAlignment = Alignment.Center
        ) {
            val bmp = thumb.bitmap
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.page_indicator, thumb.index + 1, 0)
                        .substringBefore(" /"),
                    modifier = Modifier.size(width = 44.dp, height = 60.dp)
                )
            } else {
                Text("${thumb.index + 1}", style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(
            text = thumb.title.ifBlank { "${thumb.index + 1}" },
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** Long-press page menu (§10): rename, duplicate, delete, reorder, template, size. */
@Composable
private fun PageMenuPopup(pane: PaneController, page: Int, onDismiss: () -> Unit) {
    var showRename by remember { mutableStateOf(false) }
    var showTemplate by remember { mutableStateOf(false) }
    var showSize by remember { mutableStateOf(false) }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            shape = RoundedCornerShape(Dimens.radiusLg),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 8.dp,
            modifier = Modifier.width(240.dp)
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(
                    stringResource(R.string.page_menu_title, page + 1),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                MenuRow(Icons.Outlined.DriveFileRenameOutline, stringResource(R.string.page_rename)) { showRename = true }
                MenuRow(Icons.Outlined.ContentCopy, stringResource(R.string.page_duplicate)) {
                    pane.duplicatePage(page); onDismiss()
                }
                MenuRow(Icons.Outlined.Wallpaper, stringResource(R.string.page_template)) { showTemplate = true }
                MenuRow(Icons.Outlined.Straighten, stringResource(R.string.page_size)) { showSize = true }
                MenuRow(Icons.Outlined.ArrowUpward, stringResource(R.string.page_move_up)) {
                    pane.movePageUp(page); onDismiss()
                }
                MenuRow(Icons.Outlined.ArrowDownward, stringResource(R.string.page_move_down)) {
                    pane.movePageDown(page); onDismiss()
                }
                MenuRow(Icons.Outlined.Delete, stringResource(R.string.page_delete)) {
                    pane.deletePage(page); onDismiss()
                }
            }
        }
    }

    if (showRename) {
        val current = pane.doc.pages.getOrNull(page)?.title ?: ""
        var name by remember { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.page_rename)) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    pane.renamePage(page, name.trim())
                    showRename = false
                    onDismiss()
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
    if (showTemplate) {
        PageTemplateDialog(
            onDismiss = { showTemplate = false },
            onPick = { kind ->
                pane.setPageTemplate(page, kind)
                showTemplate = false
                onDismiss()
            }
        )
    }
    if (showSize) {
        PageSizeDialog(
            onDismiss = { showSize = false },
            onPick = { size, landscape ->
                pane.setPageSize(page, if (landscape) size.heightPts else size.widthPts, if (landscape) size.widthPts else size.heightPts)
                showSize = false
                onDismiss()
            }
        )
    }
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .width(240.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** §10 — all 12 page templates. Changing never destroys handwriting. */
@Composable
fun PageTemplateDialog(onDismiss: () -> Unit, onPick: (PageTemplateKind) -> Unit) {
    val kinds = listOf(
        PageTemplateKind.BLANK to R.string.template_blank,
        PageTemplateKind.RULED to R.string.template_ruled,
        PageTemplateKind.COLLEGE_RULED to R.string.template_college_ruled,
        PageTemplateKind.WIDE_RULED to R.string.template_wide_ruled,
        PageTemplateKind.GRID to R.string.template_grid,
        PageTemplateKind.SMALL_GRID to R.string.template_small_grid,
        PageTemplateKind.DOTTED to R.string.template_dotted,
        PageTemplateKind.CORNELL to R.string.template_cornell,
        PageTemplateKind.MUSIC_STAFF to R.string.template_music,
        PageTemplateKind.PLANNER to R.string.template_planner,
        PageTemplateKind.TODO to R.string.template_todo,
        PageTemplateKind.CUSTOM to R.string.template_custom
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.page_template)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    stringResource(R.string.template_keep_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                kinds.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { (kind, res) ->
                            FilterChip(
                                selected = false,
                                onClick = { onPick(kind) },
                                label = { Text(stringResource(res), style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** §10 — page size + orientation. */
@Composable
fun PageSizeDialog(onDismiss: () -> Unit, onPick: (PageSize, Boolean) -> Unit) {
    var landscape by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.page_size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PageSize.entries.forEach { size ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(size, landscape) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = false, onClick = { onPick(size, landscape) })
                        Text(size.name, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${size.widthPts.toInt()}×${size.heightPts.toInt()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { landscape = !landscape },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = landscape, onClick = { landscape = true })
                    Text(stringResource(R.string.orientation_landscape))
                }
                Row(
                    Modifier.fillMaxWidth().clickable { landscape = !landscape },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = !landscape, onClick = { landscape = false })
                    Text(stringResource(R.string.orientation_portrait))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
